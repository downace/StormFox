package com.github.downace.stormfox.lint

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
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
    private val generation = AtomicLong()
    private val workers = MagoTool.entries.associateWith { Semaphore(1) }
    @Volatile private var disposed = false
    internal val epoch: Long get() = generation.get()
    internal val state: MagoDiagnosticState<VirtualFile> = MagoDiagnosticState(scope, { tool, snapshot ->
        workers.getValue(tool).withPermit {
            withMagoCancellation { indicator ->
                MagoDiagnosticRunner.run(snapshot.binary, snapshot.workingDirectory, snapshot.configPath,
                    snapshot.logicalPath, snapshot.text, tool, indicator)
            }
        }
    }, { file, tool, snapshot ->
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && !project.isDisposed) ReadAction.run<RuntimeException> {
                if (!file.isValid || !state.isCurrent(file, tool, snapshot) || epoch != snapshot.generation) return@run
                val psi = PsiManager.getInstance(project).findFile(file) ?: return@run
                val document = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return@run
                if (document.modificationStamp != snapshot.revision || MagoDiagnosticAnnotator.inspection(psi, tool) == null) return@run
                DaemonCodeAnalyzer.getInstance(project).restart(psi)
            }
        }
    })

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                FileDocumentManager.getInstance().getFile(event.document)?.let { file ->
                    if (dependencyPaths().affectsDependencies(file.path)) invalidateDependencies()
                    else state.invalidate(file)
                }
            }
        }, this)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val paths = dependencyPaths()
                if (events.any { event ->
                    val affectedPaths = when (event) {
                        is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) listOf(event.oldPath, event.newPath) else listOf(event.path)
                        is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
                        else -> listOf(event.path)
                    }
                    event.file?.fileType?.name == "PHP" || event.file?.isDirectory == true ||
                        affectedPaths.any { paths.affectsDependencies(it, includePhp = true) }
                }) invalidateDependencies()
            }
        })
        scope.launch {
            fun settingsKey() = MagoSettings.getInstance().state.let { it.magoPath to it.configPath }
            var previous = settingsKey()
            while (isActive) {
                delay(1000)
                val current = settingsKey()
                if (current != previous) { previous = current; invalidateDependencies() }
            }
        }
    }

    private fun dependencyPaths(): MagoDependencyPaths {
        val settings = MagoSettings.getInstance().state
        val workingDirectory = File(project.basePath ?: state.keys().firstOrNull()?.parent?.path ?: ".")
        return MagoDependencyPaths(workingDirectory, settings.configPath, settings.magoPath)
    }

    private fun invalidateDependencies() {
        val files = state.keys()
        generation.incrementAndGet()
        state.invalidateAll()
        if (files.isEmpty()) return
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && !project.isDisposed) ReadAction.run<RuntimeException> {
                files.filter { it.isValid }.forEach { file ->
                    PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
                }
            }
        }
    }

    override fun dispose() { disposed = true; state.dispose() }
}
