package com.github.downace.stormfox.lint

import com.github.downace.stormfox.MagoExecutionContext
import com.github.downace.stormfox.MagoFailureLog
import com.github.downace.stormfox.MagoFailureLogService
import com.github.downace.stormfox.MagoVersion
import com.github.downace.stormfox.MagoVersionListener
import com.github.downace.stormfox.MagoVersionService
import com.github.downace.stormfox.settings.MagoSettings
import com.github.downace.stormfox.MagoProcess
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.StandardCharsets

internal data class MagoRuleMetadataKey(val command: String, val directory: String, val root: String, val version: MagoVersion?)

@Service(Service.Level.PROJECT)
internal class MagoRuleMetadataService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val log = Logger.getInstance(MagoRuleMetadataService::class.java)
    private var key: MagoRuleMetadataKey? = null
    private var generation = 0L
    private var job: Job? = null
    private var catalogue: Map<String, MagoRuleMetadata>? = null
    private var attempted = false
    @org.jetbrains.annotations.TestOnly
    @Volatile internal var loadForTest: (suspend (MagoRuleMetadataKey) -> Map<String, MagoRuleMetadata>?)? = null
    @org.jetbrains.annotations.TestOnly
    @Volatile internal var refreshForTest: (() -> Unit)? = null

    init {
        project.messageBus.connect(this).subscribe(MagoVersionListener.TOPIC, object : MagoVersionListener {
            override fun versionChanged() {
                synchronized(this@MagoRuleMetadataService) {
                    generation++
                    job?.cancel(null)
                    job = null
                    key = null
                    catalogue = null
                    attempted = false
                }
                refreshOpenFiles()
            }
        })
    }

    fun request(command: String, directory: File, root: String) {
        val settings = MagoSettings.getInstance(project).state
        if (command.trim() != settings.magoPath.ifBlank { "mago" }.trim() || root.trim() != settings.executionRoot.trim()) return
        val next = MagoRuleMetadataKey(command.trim(), directory.absoluteFile.normalize().path, root.trim(),
            MagoVersionService.getInstance(project).cachedVersion(command, directory, root))
        synchronized(this) {
            if (key != next) {
                generation++
                job?.cancel(null)
                job = null
                catalogue = null
                attempted = false
                key = next
            }
            // Cache failures too: annotator passes should not repeatedly spawn a failing process.
            if (attempted) return
            attempted = true
            val token = generation
            job = scope.launch(Dispatchers.IO) {
                val result = try {
                    val testLoader = loadForTest
                    if (testLoader != null) testLoader(next)
                    else withMagoCancellation { indicator -> MagoRuleMetadataRunner.load(next, indicator) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Unexpected rule metadata failure", e)
                    null
                }
                synchronized(this@MagoRuleMetadataService) {
                    // The key can cycle back to the same value while an old load is still finishing.
                    if (token != generation || key != next || project.isDisposed) return@launch
                    catalogue = result
                    job = null
                }
                if (result != null) refreshOpenFiles(token)
            }
        }
    }

    @Synchronized fun cached(code: String?, command: String, directory: File, root: String): MagoRuleMetadata? {
        val current = key ?: return null
        if (current.command != command.trim() || current.directory != directory.absoluteFile.normalize().path || current.root != root.trim() ||
            current.version != MagoVersionService.getInstance(project).cachedVersion(command, directory, root)) return null
        return code?.let { catalogue?.get(it) }
    }

    private fun refreshOpenFiles(token: Long? = null) {
        refreshForTest?.let { it(); return }
        ApplicationManager.getApplication().invokeLater {
            synchronized(this) {
                if (project.isDisposed || token != null && token != generation) return@invokeLater
            }
            project.getService(FileEditorManager::class.java).openFiles.filter { it.isValid && it.extension.equals("php", true) }
                .forEach { file -> PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) } }
        }
    }

    override fun dispose() {
        synchronized(this) {
            generation++
            job?.cancel(null)
            job = null
            catalogue = null
            key = null
        }
    }
}

internal object MagoRuleMetadataRunner {
    private const val LIMIT = 4 * 1024 * 1024
    private const val TIMEOUT_SECONDS = 20L
    private val log = Logger.getInstance(MagoRuleMetadataRunner::class.java)

    // --list-rules lists enabled rules; --pedantic enables all rules, including those disabled
    // by configuration or by default, so diagnostic tooltips can look up their metadata.
    internal fun arguments(key: MagoRuleMetadataKey): List<String> =
        MagoExecutionContext(key.command, File(key.directory), key.root).command() +
            listOf("lint", "--list-rules", "--json", "--pedantic")

    fun load(key: MagoRuleMetadataKey, indicator: com.intellij.openapi.progress.ProgressIndicator): Map<String, MagoRuleMetadata>? {
        val failures = MagoFailureLogService.get()
        val context = MagoFailureLog.context(key.command, key.directory, key.root)
        fun failed(category: String): Map<String, MagoRuleMetadata>? {
            failures.failure(context, "rule-metadata", "load", category, key.directory)
            return null
        }
        try {
            val args = arguments(key)
            if (log.isDebugEnabled) log.debug("Rule metadata command: $args")
            val command = GeneralCommandLine(args).withWorkDirectory(File(key.directory)).withCharset(StandardCharsets.UTF_8)
            val output = MagoProcess.run(command, null, (TIMEOUT_SECONDS * 1000).toInt(), LIMIT, checkCanceled = indicator::checkCanceled)
            val text = output.stdout
            if (log.isDebugEnabled) log.debug("Rule metadata exit ${output.exitCode}; stdout: $text; stderr: ${output.stderr}")
            if (output.exitCode != 0) return failed("exit")
            val parsed = try { MagoRuleMetadataParser.parse(text) }
            catch (e: RuntimeException) {
                log.debug("Invalid rule metadata JSON", e)
                return failed("invalid-report")
            }
            failures.success(context, "rule-metadata", "load")
            return parsed
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: MagoProcess.OutputLimit) {
            return failed("output-limit")
        } catch (e: MagoProcess.TimedOut) {
            return failed("timeout")
        } catch (e: Exception) {
            log.debug("Rule metadata process failed", e)
            return failed("process")
        }
    }
}
