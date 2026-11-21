package com.github.downace.stormfox.format

import com.github.downace.stormfox.MagoExecutionContext
import com.github.downace.stormfox.MagoFailureLog
import com.github.downace.stormfox.MagoFailureLogService
import com.github.downace.stormfox.terminateMagoProcess
import com.github.downace.stormfox.MagoVersion
import com.github.downace.stormfox.MagoVersionService
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.io.File
import java.util.concurrent.CancellationException

internal object MagoValidation {

    private val LOG = Logger.getInstance(MagoValidation::class.java)

    private const val TIMEOUT_MS = 15_000

    sealed interface Result {
        data class Success(val message: String, val warning: String? = null) : Result
        data class Failure(val message: String) : Result
    }

    fun checkBinary(binaryPath: String, projectDirectory: File? = null, executionRoot: String = "", project: Project? = null): Result {
        val service = project?.let { MagoVersionService.getInstance(it) }
        val token = service?.beginValidation(binaryPath, projectDirectory, executionRoot)
        try {
            val result = checkBinaryUncached(binaryPath, projectDirectory, executionRoot)
            service?.finish(token, binaryPath, result, projectDirectory, executionRoot)
            return result
        } finally {
            // A cancelled or throwing validation has no result to finish; never strand its token.
            service?.abandonValidation(token, binaryPath, projectDirectory, executionRoot)
        }
    }

    internal fun checkBinaryUncached(binaryPath: String, projectDirectory: File? = null, executionRoot: String = "",
                                     failureLog: MagoFailureLog = MagoFailureLogService.get()): Result {
        val key = MagoFailureLog.context(binaryPath.trim(), projectDirectory?.absoluteFile?.normalize()?.path.orEmpty(), executionRoot)
        if (binaryPath.isBlank()) {
            failureLog.failure(key, "validation", "version", "missing-command", projectDirectory?.path)
            return Result.Failure("The Mago command is not set")
        }
        val arguments = try { MagoExecutionContext(binaryPath, projectDirectory, executionRoot).command() + "--version" }
        catch (e: IllegalArgumentException) {
            failureLog.failure(key, "validation", "version", "invalid-command", projectDirectory?.path)
            failureLog.debug { "invalid validation command: ${e.message}" }
            return Result.Failure(e.message ?: "Invalid Mago command")
        }
        return when (val outcome = run(arguments, projectDirectory, key, "version", false, failureLog)) {
            is Result.Success -> {
                val message = outcome.message.trim().ifEmpty { "mago is available" }
                val version = MagoVersion.parse(message)
                if (version != null && !version.isSupported) {
                    failureLog.failure(key, "validation", "version", "unsupported", projectDirectory?.path)
                    return Result.Failure("Mago $version is not supported. Use Mago 1.18.0 or newer.")
                }
                if (version == null) failureLog.failure(key, "validation", "version", "unrecognized-output", projectDirectory?.path)
                val warning = if (version != null && !version.supportsFilepathAwareExcludes)
                    "Mago $version may format files excluded by source.excludes or formatter.excludes. Use Mago 1.25.0 or newer to honor these exclusions."
                else null
                Result.Success(message, warning)
            }
            is Result.Failure -> outcome
        }
    }

    fun checkConfig(binaryPath: String, configPath: String, projectDirectory: File? = null, executionRoot: String = ""): Result {
        val key = MagoFailureLog.context(binaryPath.trim(), projectDirectory?.absoluteFile?.normalize()?.path.orEmpty(), executionRoot, configPath)
        if (binaryPath.isBlank()) {
            MagoFailureLogService.get().failure(key, "validation", "config", "missing-command", projectDirectory?.path)
            return Result.Failure("The Mago command is not set")
        }
        if (configPath.isBlank()) {
            return Result.Success("Not set - mago uses the configuration of the project")
        }
        val arguments = try {
            val context = MagoExecutionContext(binaryPath, projectDirectory, executionRoot)
            context.command() + listOf("--config", context.toExecution(configPath), "config")
        } catch (e: IllegalArgumentException) {
            MagoFailureLogService.get().failure(key, "validation", "config", "invalid-command", projectDirectory?.path)
            LOG.debug("invalid config validation command", e)
            return Result.Failure(e.message ?: "Invalid Mago command")
        }
        return when (val outcome = run(arguments, projectDirectory, key, "config", true)) {
            is Result.Success -> Result.Success("Configuration is valid")
            is Result.Failure -> outcome
        }
    }

    private fun run(arguments: List<String>, workingDirectory: File?, key: String, stage: String, recover: Boolean,
                    failures: MagoFailureLog = MagoFailureLogService.get()): Result {
        fun failed(category: String, message: String): Result.Failure {
            failures.failure(key, "validation", stage, category, workingDirectory?.path)
            return Result.Failure(message)
        }
        ProgressManager.checkCanceled()
        val commandLine = GeneralCommandLine(arguments).withCharset(StandardCharsets.UTF_8)
        if (workingDirectory != null) commandLine.withWorkDirectory(workingDirectory)

        val handler = try {
            CapturingProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            failures.debug { "validation start failed: ${e.message}" }
            return failed("start", "Cannot start mago: ${e.message}")
        }

        return try {
            // Validation commands never send input; wrappers may wait for EOF before exiting.
            handler.processInput.close()
            val indicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
            indicator.checkCanceled()
            val output = handler.runProcessWithProgressIndicator(indicator, TIMEOUT_MS, true)
            indicator.checkCanceled()
            failures.debug { "validation command=${commandLine.commandLineString}; exit=${output.exitCode}; stdout=${output.stdout}; stderr=${output.stderr}" }
            if (output.isTimeout) {
                return failed("timeout", "mago did not answer within ${TIMEOUT_MS / 1000} seconds")
            }
            if (output.exitCode != 0) {
                val message = output.stderr.trim().ifEmpty { output.stdout.trim() }
                return failed("exit-${output.exitCode}", message.ifEmpty { "mago exited with code ${output.exitCode}" })
            }
            // Some Mago builds emit informational output (including --version) on stderr.
            val message = listOf(output.stdout.trim(), output.stderr.trim())
                .filter(String::isNotEmpty)
                .joinToString("\n")
            if (recover) failures.success(key, "validation", stage)
            return Result.Success(message)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.debug("validation execution failed", e)
            return failed("exception", e.message ?: e.toString())
        } finally {
            terminateMagoProcess(handler)
        }
    }

}
