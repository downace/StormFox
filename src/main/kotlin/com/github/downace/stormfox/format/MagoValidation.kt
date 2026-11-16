package com.github.downace.stormfox.format

import com.github.downace.stormfox.MagoExecutionContext
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

    fun checkBinary(binaryPath: String, projectDirectory: File? = null, executionRoot: String = ""): Result {
        val service = MagoVersionService.getInstance()
        val token = service.beginValidation(binaryPath, projectDirectory, executionRoot)
        val result = checkBinaryUncached(binaryPath, projectDirectory, executionRoot)
        service.finish(token, binaryPath, result, projectDirectory, executionRoot)
        return result
    }

    internal fun checkBinaryUncached(binaryPath: String, projectDirectory: File? = null, executionRoot: String = ""): Result {
        if (binaryPath.isBlank()) {
            return Result.Failure("The Mago command is not set")
        }
        val arguments = try { MagoExecutionContext(binaryPath, projectDirectory, executionRoot).command() + "--version" }
        catch (e: IllegalArgumentException) { return Result.Failure(e.message ?: "Invalid Mago command") }
        return when (val outcome = run(arguments, projectDirectory)) {
            is Result.Success -> {
                val message = outcome.message.trim().ifEmpty { "mago is available" }
                val version = MagoVersion.parse(message)
                if (version != null && !version.isSupported) {
                    return Result.Failure("Mago $version is not supported. Use Mago 1.18.0 or newer.")
                }
                val warning = if (version != null && !version.supportsFilepathAwareExcludes)
                    "Mago $version may format files excluded by source.excludes or formatter.excludes. Use Mago 1.25.0 or newer to honor these exclusions."
                else null
                Result.Success(message, warning)
            }
            is Result.Failure -> outcome
        }
    }

    fun checkConfig(binaryPath: String, configPath: String, projectDirectory: File? = null, executionRoot: String = ""): Result {
        if (binaryPath.isBlank()) {
            return Result.Failure("The Mago command is not set")
        }
        if (configPath.isBlank()) {
            return Result.Success("Not set - mago uses the configuration of the project")
        }
        val arguments = try {
            val context = MagoExecutionContext(binaryPath, projectDirectory, executionRoot)
            context.command() + listOf("--config", context.toExecution(configPath), "config")
        } catch (e: IllegalArgumentException) { return Result.Failure(e.message ?: "Invalid Mago command") }
        return when (val outcome = run(arguments, projectDirectory)) {
            is Result.Success -> Result.Success("Configuration is valid")
            is Result.Failure -> outcome
        }
    }

    private fun run(arguments: List<String>, workingDirectory: File?): Result {
        ProgressManager.checkCanceled()
        val commandLine = GeneralCommandLine(arguments).withCharset(StandardCharsets.UTF_8)
        if (workingDirectory != null) commandLine.withWorkDirectory(workingDirectory)

        val handler = try {
            CapturingProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            LOG.warn("Cannot start mago", e)
            return Result.Failure("Cannot start mago: ${e.message}")
        }

        return try {
            // Validation commands never send input; wrappers may wait for EOF before exiting.
            handler.processInput.close()
            val indicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
            indicator.checkCanceled()
            val output = handler.runProcessWithProgressIndicator(indicator, TIMEOUT_MS, true)
            indicator.checkCanceled()
            if (output.isTimeout) {
                return Result.Failure("mago did not answer within ${TIMEOUT_MS / 1000} seconds")
            }
            if (output.exitCode != 0) {
                val message = output.stderr.trim().ifEmpty { output.stdout.trim() }
                return Result.Failure(message.ifEmpty { "mago exited with code ${output.exitCode}" })
            }
            // Some Mago builds emit informational output (including --version) on stderr.
            val message = listOf(output.stdout.trim(), output.stderr.trim())
                .filter(String::isNotEmpty)
                .joinToString("\n")
            return Result.Success(message)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("mago validation failed", e)
            return Result.Failure(e.message ?: e.toString())
        } finally {
            terminateMagoProcess(handler)
        }
    }

}
