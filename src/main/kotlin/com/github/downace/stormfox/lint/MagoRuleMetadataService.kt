package com.github.downace.stormfox.lint

import com.github.downace.stormfox.MagoExecutionContext
import com.github.downace.stormfox.MagoFailureLog
import com.github.downace.stormfox.MagoFailureLogService
import com.github.downace.stormfox.MagoVersion
import com.github.downace.stormfox.MagoVersionListener
import com.github.downace.stormfox.MagoVersionService
import com.github.downace.stormfox.settings.MagoSettings
import com.github.downace.stormfox.terminateMagoProcess
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
        var handler: OSProcessHandler? = null
        try {
            val args = arguments(key)
            if (log.isDebugEnabled) log.debug("Rule metadata command: $args")
            handler = OSProcessHandler(GeneralCommandLine(args).withWorkDirectory(File(key.directory)).withCharset(StandardCharsets.UTF_8))
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val exceeded = AtomicBoolean(false)
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    val target = when (outputType) {
                        ProcessOutputTypes.STDOUT -> stdout
                        ProcessOutputTypes.STDERR -> stderr
                        else -> return
                    }
                    synchronized(target) {
                        if (target.length + event.text.length > LIMIT) exceeded.set(true)
                        else target.append(event.text)
                    }
                }
            })
            handler.startNotify()
            handler.processInput?.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (!handler.waitFor(100)) {
                indicator.checkCanceled()
                if (exceeded.get()) return failed("output-limit")
                if (System.nanoTime() >= deadline) return failed("timeout")
            }
            indicator.checkCanceled()
            if (exceeded.get()) return failed("output-limit")
            val text = synchronized(stdout) { stdout.toString() }
            val errors = synchronized(stderr) { stderr.toString() }
            if (log.isDebugEnabled) log.debug("Rule metadata exit ${handler.exitCode}; stdout: $text; stderr: $errors")
            if (handler.exitCode != 0) return failed("exit")
            val parsed = try { MagoRuleMetadataParser.parse(text) }
            catch (e: RuntimeException) {
                log.debug("Invalid rule metadata JSON", e)
                return failed("invalid-report")
            }
            failures.success(context, "rule-metadata", "load")
            return parsed
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ProcessCanceledException()
        } catch (e: Exception) {
            log.debug("Rule metadata process failed", e)
            return failed("process")
        } finally {
            handler?.let {
                try { terminateMagoProcess(it) }
                catch (e: Exception) { log.debug("Could not terminate rule metadata process", e) }
            }
        }
    }
}
