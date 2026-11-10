package com.github.downace.stormfox.format

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

internal object MagoValidation {

    private val LOG = Logger.getInstance(MagoValidation::class.java)

    private const val TIMEOUT_MS = 15_000

    sealed interface Result {
        data class Success(val message: String) : Result
        data class Failure(val message: String) : Result
    }

    fun checkBinary(binaryPath: String): Result {
        if (binaryPath.isBlank()) {
            return Result.Failure("The path to the mago binary is not set")
        }
        return when (val outcome = run(listOf(resolveExecutable(binaryPath), "--version"))) {
            is Result.Success -> Result.Success(outcome.message.trim().ifEmpty { "mago is available" })
            is Result.Failure -> outcome
        }
    }

    fun checkConfig(binaryPath: String, configPath: String): Result {
        if (binaryPath.isBlank()) {
            return Result.Failure("The path to the mago binary is not set")
        }
        if (configPath.isBlank()) {
            return Result.Success("Not set - mago uses the configuration of the project")
        }
        return when (val outcome = run(listOf(resolveExecutable(binaryPath), "--config", configPath, "config"))) {
            is Result.Success -> Result.Success("Configuration is valid")
            is Result.Failure -> outcome
        }
    }

    private fun run(arguments: List<String>): Result {
        ProgressManager.checkCanceled()
        val commandLine = GeneralCommandLine(arguments).withCharset(StandardCharsets.UTF_8)

        val handler = try {
            CapturingProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            LOG.warn("Cannot start mago", e)
            return Result.Failure("Cannot start mago: ${e.message}")
        }

        return try {
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
            return Result.Success(output.stdout)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("mago validation failed", e)
            return Result.Failure(e.message ?: e.toString())
        } finally {
            handler.destroyProcess()
        }
    }

    private fun resolveExecutable(path: String): String {
        val file = File(path)
        if (file.isFile) {
            return file.absolutePath
        }
        if (SystemInfo.isWindows && !path.endsWith(".exe", ignoreCase = true)) {
            val executable = File("$path.exe")
            if (executable.isFile) {
                return executable.absolutePath
            }
        }
        return path
    }
}
