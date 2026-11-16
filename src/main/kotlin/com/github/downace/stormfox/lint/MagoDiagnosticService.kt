package com.github.downace.stormfox.lint

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.util.SystemInfo
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.github.downace.stormfox.settings.MagoSettings
import java.io.File
import java.util.Locale
import java.nio.file.InvalidPathException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Computable

internal class MagoDependencyPaths(workingDirectory: File, configPath: String, binaryPath: String) {
    private val root = workingDirectory.toPath().toAbsolutePath().normalize()
    private val config = configPath.takeIf { it.isNotBlank() }?.let(::normalize)
    private val binary = binaryPath.ifBlank { "mago" }
    private val binaryLocation = normalize(binary)
    private val binaryName = binary.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)

    private fun normalize(path: String): String? = try {
        val normalized = root.resolve(path.replace('\\', '/')).normalize().toString().replace('\\', '/')
        if (SystemInfo.isFileSystemCaseSensitive) normalized else normalized.lowercase(Locale.ROOT)
    } catch (_: InvalidPathException) { null }

    fun affectsDependencies(path: String, includePhp: Boolean = false): Boolean {
        val name = path.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
        val extension = name.substringAfterLast('.', "")
        val location = normalize(path)
        return extension in setOf("toml", "yaml", "yml", "json") || name == "composer.lock" ||
            location != null && (location == config || location == binaryLocation) || name.substringBeforeLast('.') == binaryName ||
            includePhp && extension in setOf("php", "phtml")
    }
}

@Service(Service.Level.PROJECT)
class MagoDiagnosticService(private val project: Project, scope: CoroutineScope) : Disposable {
    @org.jetbrains.annotations.TestOnly
    @Volatile internal var computeForTest: (suspend (MagoTool, MagoSnapshot) -> MagoDiagnosticRunner.Result)? = null
    private val generation = AtomicLong()
    private val log = Logger.getInstance(MagoDiagnosticService::class.java)
    internal fun trace(message: String) { log.info("Mago: diagnostics project=${project.name} $message") }
    private val workers = MagoTool.entries.associateWith { Semaphore(1) }
    @Volatile private var disposed = false
    internal val epoch: Long get() = generation.get()
    internal val state: MagoDiagnosticState<VirtualFile> = MagoDiagnosticState(scope, { tool, snapshot ->
        val testCompute = computeForTest
        if (testCompute != null) testCompute(tool, snapshot) else workers.getValue(tool).withPermit {
            withMagoCancellation { indicator ->
                MagoDiagnosticRunner.run(snapshot.binary, snapshot.workingDirectory, snapshot.configPath,
                    snapshot.logicalPath, snapshot.text, tool, indicator, snapshot.executionRoot)
            }
        }
    }, { file, tool, snapshot ->
        ApplicationManager.getApplication().invokeLater {
            val details = "file=${snapshot.logicalPath} tool=$tool revision=${snapshot.revision} generation=${snapshot.generation}"
            fun skip(reason: String) { trace("restart-skip $details reason=$reason") }
            if (disposed || project.isDisposed) { skip("disposed"); return@invokeLater }
            if (!file.isValid || !state.isCurrent(file, tool, snapshot) || epoch != snapshot.generation) {
                skip("invalid-file-or-stale-slot-or-generation"); return@invokeLater
            }
            val psi = PsiManager.getInstance(project).findFile(file) ?: run { skip("no-psi"); return@invokeLater }
            val document = PsiDocumentManager.getInstance(project).getDocument(psi) ?: run { skip("no-document"); return@invokeLater }
            if (document.modificationStamp != snapshot.revision) { skip("revision"); return@invokeLater }
            if (MagoDiagnosticAnnotator.inspection(psi, tool) == null) { skip("inspection-disabled"); return@invokeLater }
            trace("restart $details")
            DaemonCodeAnalyzer.getInstance(project).restart(psi)
        }
    }, trace = { trace(it) })

    internal fun <T> withCurrentReport(input: MagoDiagnosticAnnotator.Snapshot, tool: MagoTool,
                                       action: (MagoDiagnosticAnnotator.Report) -> T): T? =
        ApplicationManager.getApplication().runReadAction(Computable<T?> {
            fun drop(reason: String): T? {
                trace("resolve-drop reason=$reason file=${input.logicalPath} tool=$tool revision=${input.revision} generation=${input.generation}")
                return null
            }
            val file = input.file ?: return@Computable drop("no-file")
            if (disposed || project.isDisposed || input.project !== project || !file.isValid)
                return@Computable drop("disposed-or-invalid-file")
            val document = FileDocumentManager.getInstance().getCachedDocument(file)
                ?: return@Computable drop("no-cached-document")
            val text = document.text
            if (text != input.text) return@Computable drop("text-not-current")
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@Computable drop("no-psi")
            if (MagoDiagnosticAnnotator.inspection(psi, tool) == null) return@Computable drop("inspection-disabled")
            val revision = document.modificationStamp
            synchronized(this) {
                if (disposed || project.isDisposed || !file.isValid) return@synchronized drop("disposed-or-invalid-file")
                val settings = MagoSettings.getInstance().state
                val path = file.path
                val directory = project.basePath?.let(::File) ?: File(path).parentFile
                    ?: return@synchronized drop("no-working-directory")
                val current = MagoDiagnosticAnnotator.Snapshot(text, path, directory,
                    settings.magoPath.ifBlank { "mago" }, settings.configPath, revision, project, file, epoch, System.getenv(), settings.executionRoot)
                if (current != input) {
                    trace("resolve-current file=$path tool=$tool inputRevision=${input.revision} revision=$revision inputGeneration=${input.generation} generation=${current.generation} metadataChanged=true")
                }
                val worker = MagoSnapshot(current.revision, current.text, current.logicalPath, current.workingDirectory,
                    current.binary, current.configPath, current.environment, current.generation, current.executionRoot)
                val view = state.requestView(file, tool, worker, verifiedCurrent = true)
                    ?: return@synchronized null
                action(MagoDiagnosticAnnotator.Report(current, view.result, view.retained, view))
            }
        })

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                FileDocumentManager.getInstance().getFile(event.document)?.let { file ->
                    if (dependencyPaths().affectsDependencies(file.path)) invalidateDependencies(reason = "dependency-document")
                    else state.edited(file, event.offset, event.oldLength, event.newLength,
                        event.document.text, event.document.modificationStamp)
                }
            }
        }, this)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val paths = dependencyPaths()
                val affected = events.filter { event ->
                    val affectedPaths = when (event) {
                        is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) listOf(event.oldPath, event.newPath) else listOf(event.path)
                        is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
                        else -> listOf(event.path)
                    }
                    event.file?.fileType?.name == "PHP" || event.file?.isDirectory == true ||
                        affectedPaths.any { paths.affectsDependencies(it, includePhp = true) }
                }
                if (affected.isNotEmpty()) {
                    val save = affected.singleOrNull() as? VFileContentChangeEvent
                    val file = save?.file
                    val document = if (save?.isFromSave == true && file != null &&
                        !paths.affectsDependencies(file.path)) FileDocumentManager.getInstance().getCachedDocument(file) else null
                    if (file != null && document != null) {
                        invalidateDependencies(file, document.text, document.modificationStamp, "vfs-save")
                    } else invalidateDependencies(reason = "vfs-external-or-mixed")
                }
            }
        })
        scope.launch {
            fun settingsKey() = MagoSettings.getInstance().state.let { Triple(it.magoPath, it.configPath, it.executionRoot) }
            var previous = settingsKey()
            while (isActive) {
                delay(1000)
                val current = settingsKey()
                if (current != previous) { previous = current; invalidateDependencies(reason = "settings") }
            }
        }
    }

    private fun dependencyPaths(): MagoDependencyPaths {
        val settings = MagoSettings.getInstance().state
        val workingDirectory = File(project.basePath ?: state.keys().firstOrNull()?.parent?.path ?: ".")
        return MagoDependencyPaths(workingDirectory, settings.configPath, settings.magoPath)
    }

    @Synchronized
    private fun invalidateDependencies(savedFile: VirtualFile? = null, text: String = "", revision: Long = -1,
                                       reason: String) {
        val files = state.keys()
        val oldGeneration = generation.getAndIncrement()
        trace("dependency-invalidate reason=$reason file=${savedFile?.path ?: "-"} revision=$revision generation=$oldGeneration nextGeneration=${oldGeneration + 1} files=${files.size}")
        if (savedFile == null) state.invalidateAll()
        else {
            val settings = MagoSettings.getInstance().state
            state.invalidateForSave(savedFile, text, revision, oldGeneration, oldGeneration + 1,
                settings.magoPath.ifBlank { "mago" }, settings.configPath, System.getenv())
        }
        if (files.isEmpty()) return
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
            files.filter { it.isValid }.forEach { file ->
                PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
            }
        }
    }

    override fun dispose() { disposed = true; state.dispose() }
}
