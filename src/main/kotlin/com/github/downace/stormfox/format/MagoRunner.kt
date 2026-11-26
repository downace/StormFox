package com.github.downace.stormfox.format

import com.github.downace.stormfox.MagoExecutionContext
import com.github.downace.stormfox.MagoFailureLog
import com.github.downace.stormfox.MagoFailureLogService
import com.github.downace.stormfox.MagoVersion
import com.github.downace.stormfox.MagoProcess
import com.github.downace.stormfox.terminateMagoProcess
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.charset.StandardCharsets

internal object MagoRunner {

    private val LOG = Logger.getInstance(MagoRunner::class.java)

    const val TIMEOUT_MS = 30_000

    // Attachment and delivery share the cancellation lock: cancellation can race both process
    // startup and completion, but a late process or result must not outlive it.
    internal class Cancellation {
        private var cancelled = false
        private var process: Process? = null

        @Synchronized
        fun cancel() {
            cancelled = true
            process?.let(::terminateMagoProcess)
        }

        @Synchronized
        fun attach(process: Process) {
            this.process = process
            if (cancelled) {
                terminateMagoProcess(process)
                throw ProcessCanceledException()
            }
        }

        @Synchronized
        fun checkCanceled() {
            if (cancelled) throw ProcessCanceledException()
        }

        @Synchronized
        fun deliver(action: () -> Unit) {
            if (!cancelled) action()
        }
    }

    sealed interface Result {
        data class Success(val text: String) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Formats [text] with mago. The buffer is piped through stdin, so the file on disk is never touched.
     *
     * @param configPath value for mago's global `--config` flag, empty to let mago discover the configuration itself
     */
    fun format(
        binary: String,
        workingDirectory: File?,
        configPath: String,
        logicalPath: String,
        text: String,
        cancellation: Cancellation = Cancellation(),
        executionRoot: String = "",
        cachedVersion: MagoVersion? = null,
        twig: Boolean = logicalPath.endsWith(".twig", ignoreCase = true),
        timeoutMillis: Int = TIMEOUT_MS,
        onVersionProbed: (MagoValidation.Result) -> Unit = {},
    ): Result {
        cancellation.checkCanceled()
        val log = MagoFailureLogService.get()
        val key = MagoFailureLog.context(binary.trim(), workingDirectory?.absoluteFile?.normalize()?.path.orEmpty(), configPath, executionRoot)
        fun failed(stage: String, category: String, message: String): Result.Failure {
            log.failure(key, "format", stage, category, workingDirectory?.path)
            if (LOG.isDebugEnabled) LOG.debug("format $stage failure: $message")
            return Result.Failure(message)
        }
        val context = try {
            MagoExecutionContext(binary, workingDirectory, executionRoot).also { it.command() }
        } catch (e: IllegalArgumentException) {
            val message = e.message ?: "Invalid Mago command"
            if (cachedVersion == null) {
                cancellation.deliver { onVersionProbed(MagoValidation.Result.Failure(message)) }
                cancellation.checkCanceled()
            }
            return failed("command", "invalid", message)
        }
        val version = cachedVersion ?: when (val probe = probeVersion(context, workingDirectory, cancellation, timeoutMillis)) {
            is VersionResult.Known -> {
                cancellation.deliver {
                    onVersionProbed(if (probe.version.isSupported) MagoValidation.Result.Success("mago ${probe.version}")
                        else MagoValidation.Result.Failure("Mago ${probe.version} is not supported. Use Mago 1.18.0 or newer."))
                }
                cancellation.checkCanceled()
                probe.version
            }
            is VersionResult.Failure -> {
                cancellation.deliver { onVersionProbed(MagoValidation.Result.Failure(probe.message)) }
                cancellation.checkCanceled()
                return failed("version", probe.category, probe.message)
            }
        }
        log.success(key, "format", "version")
        if (twig && !version.supportsTwig) {
            return failed("version", "unsupported-twig", "Twig formatting requires Mago 1.60.0 or newer.")
        }
        val arguments = try {
            buildArguments(context, configPath, logicalPath, version, twig)
        } catch (e: IllegalArgumentException) {
            return failed("command", "invalid", e.message ?: "Invalid Mago command")
        }
        log.success(key, "format", "command")
        val commandLine = GeneralCommandLine(arguments)
            .withCharset(StandardCharsets.UTF_8)

        if (workingDirectory != null) {
            commandLine.withWorkDirectory(workingDirectory)
        }

        return try {
            val started = System.currentTimeMillis()
            val output = MagoProcess.run(commandLine, text, timeoutMillis, checkCanceled = cancellation::checkCanceled, attach = cancellation::attach)
            if (LOG.isDebugEnabled) LOG.debug("format finished in ${System.currentTimeMillis() - started}ms, exit code ${output.exitCode}, command: ${commandLine.commandLineString}; stdout: ${output.stdout}; stderr: ${output.stderr}")
            when {
                output.exitCode != 0 -> failed("process", "exit-${output.exitCode}", errorMessage(output.stderr, output.exitCode))
                text.isNotEmpty() && output.stdout.isBlank() -> failed("process", "empty-output", "mago returned no formatted content for non-empty input; verify stdin forwarding (for docker run, use -i). The original text was preserved.")
                else -> { log.success(key, "format", "process"); Result.Success(output.stdout) }
            }
        } catch (e: ProcessCanceledException) { throw e
        } catch (e: CancellationException) { throw e
        } catch (e: MagoProcess.TimedOut) {
            failed("process", "timeout", "mago did not finish within ${timeoutMillis / 1000} seconds")
        } catch (e: ExecutionException) {
            LOG.debug("formatter start failed", e)
            failed("process", "start", "Cannot start mago: ${e.message}")
        } catch (e: Exception) {
            LOG.debug("formatter execution failed", e)
            failed("process", "exception", "mago failed: ${e.message ?: e.toString()}")
        }
    }

    private sealed interface VersionResult {
        data class Known(val version: MagoVersion) : VersionResult
        data class Failure(val message: String, val category: String) : VersionResult
    }

    private fun probeVersion(context: MagoExecutionContext, directory: File?, cancellation: Cancellation, timeoutMillis: Int): VersionResult {
        cancellation.checkCanceled()
        val commandLine = GeneralCommandLine(context.command() + "--version").withCharset(StandardCharsets.UTF_8)
        if (directory != null) commandLine.withWorkDirectory(directory)
        return try {
            val output = MagoProcess.run(commandLine, null, timeoutMillis, checkCanceled = cancellation::checkCanceled, attach = cancellation::attach)
            if (LOG.isDebugEnabled) LOG.debug("formatter version command=${commandLine.commandLineString}; exit=${output.exitCode}; stdout=${output.stdout}; stderr=${output.stderr}")
            when {
                output.exitCode != 0 -> VersionResult.Failure(errorMessage(output.stderr, output.exitCode), "exit-${output.exitCode}")
                else -> MagoVersion.parse(output.stdout + "\n" + output.stderr)?.let { VersionResult.Known(it) }
                    ?: VersionResult.Failure("Cannot determine Mago version from --version output", "unrecognized-output")
            }
        } catch (e: ProcessCanceledException) { throw e
        } catch (e: CancellationException) { throw e
        } catch (e: MagoProcess.TimedOut) {
            VersionResult.Failure("mago did not finish within ${timeoutMillis / 1000} seconds", "timeout")
        } catch (e: ExecutionException) {
            VersionResult.Failure("Cannot start mago: ${e.message}", "start")
        } catch (e: Exception) {
            LOG.debug("formatter version probe failed", e)
            VersionResult.Failure("mago failed: ${e.message ?: e.toString()}", "exception")
        }
    }

    private fun buildArguments(context: MagoExecutionContext, configPath: String, logicalPath: String, version: MagoVersion, twig: Boolean): List<String> {
        val arguments = mutableListOf<String>()
        if (configPath.isNotBlank()) {
            // Global flags have to come before the subcommand.
            arguments += "--config"
            arguments += context.toExecution(configPath)
        }
        arguments += listOf(if (twig) "twig:fmt" else "format", "--stdin-input")
        if (twig || version.supportsFilepathAwareExcludes) {
            arguments += listOf("--stdin-filepath", context.toExecution(logicalPath))
        }
        return context.command() + arguments
    }

    private fun errorMessage(stderr: String, exitCode: Int): String {
        val message = stderr.trim()
        if (message.isEmpty()) {
            return "mago exited with code $exitCode"
        }
        return message
    }

}
