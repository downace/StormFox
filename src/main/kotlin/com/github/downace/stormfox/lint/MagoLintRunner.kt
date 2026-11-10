package com.github.downace.stormfox.lint

import com.github.downace.stormfox.resolveMagoExecutable
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

internal object MagoLintRunner {
    private val log = Logger.getInstance(MagoLintRunner::class.java)
    const val TIMEOUT_MS = 30_000

    sealed interface Result {
        data class Success(val diagnostics: List<MagoLintReport.Diagnostic>) : Result
        data class Failure(val message: String) : Result
    }

    private sealed interface ProcessResult {
        data class Success(val stdout: String, val stderr: String) : ProcessResult
        data class Failure(val message: String) : ProcessResult
    }

    fun arguments(binary: String, configPath: String, logicalPath: String, workingDirectory: File): List<String> {
        val relativePath = workingDirectory.toPath().toAbsolutePath().normalize()
            .relativize(File(logicalPath).toPath().toAbsolutePath().normalize()).toString().replace('\\', '/')
        return globalArguments(binary, configPath) + listOf("lint", "--stdin-input", "--reporting-format", "json", "--", relativePath)
    }

    private fun globalArguments(binary: String, configPath: String): List<String> {
        return buildList {
            add(resolveMagoExecutable(binary))
            if (configPath.isNotBlank()) addAll(listOf("--config", configPath))
        }
    }

    fun lint(binary: String, workingDirectory: File, configPath: String, logicalPath: String, text: String): Result {
        ProgressManager.checkCanceled()
        val source = execute(globalArguments(binary, configPath) + listOf("config", "--show", "source"), workingDirectory, "", 0..0)
        if (source is ProcessResult.Failure) return Result.Failure(source.message)
        source as ProcessResult.Success
        val workspace = try {
            val root = JsonParser.parseString(source.stdout)
            require(root.isJsonObject) { "Expected source configuration object" }
            val value = root.asJsonObject.get("workspace")
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Missing source workspace" }
            val configuredPath = value.asString
            require(configuredPath.isNotBlank()) { "Empty source workspace" }
            val normalizedPath = when {
                configuredPath.startsWith("\\\\?\\UNC\\", true) -> "\\\\" + configuredPath.substring(8)
                configuredPath.startsWith("\\\\?\\") -> configuredPath.substring(4)
                else -> configuredPath
            }
            File(normalizedPath).let { if (it.isAbsolute) it else File(workingDirectory, normalizedPath) }
        } catch (e: com.google.gson.JsonParseException) {
            return failure("Mago returned an invalid source configuration: ${e.message}", e, source.stdout + "\n" + source.stderr)
        } catch (e: IllegalArgumentException) {
            return failure("Mago returned an invalid source configuration: ${e.message}", e, source.stdout + "\n" + source.stderr)
        }
        val arguments = try {
            arguments(binary, configPath, logicalPath, workspace)
        } catch (e: IllegalArgumentException) {
            return failure("Cannot resolve Mago lint path: ${e.message}", e)
        }
        val output = execute(arguments, workingDirectory, text, 0..1)
        if (output is ProcessResult.Failure) return Result.Failure(output.message)
        output as ProcessResult.Success
        return try {
            Result.Success(MagoLintReport.parse(output.stdout, text, logicalPath, workspace))
        } catch (e: com.google.gson.JsonParseException) {
            failure("Mago lint returned an invalid JSON report: ${e.message}", e, output.stdout + "\n" + output.stderr)
        } catch (e: IllegalArgumentException) {
            failure("Mago lint returned an invalid JSON report: ${e.message}", e, output.stdout + "\n" + output.stderr)
        } catch (e: IllegalStateException) {
            failure("Mago lint returned an invalid JSON report: ${e.message}", e, output.stdout + "\n" + output.stderr)
        }
    }

    private fun execute(arguments: List<String>, workingDirectory: File, text: String, validExitCodes: IntRange): ProcessResult {
        ProgressManager.checkCanceled()
        val command = try {
            GeneralCommandLine(arguments)
                .withCharset(StandardCharsets.UTF_8).withWorkDirectory(workingDirectory)
        } catch (e: IllegalArgumentException) {
            return processFailure("Cannot resolve Mago lint path: ${e.message}", e)
        }
        val handler = try {
            CapturingProcessHandler(command)
        } catch (e: ExecutionException) {
            return processFailure("Cannot start Mago: ${e.message}", e)
        }
        val writer = AppExecutorUtil.getAppExecutorService().submit {
            try {
                handler.processInput.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
            } catch (e: IOException) {
                log.debug("Cannot write Mago lint stdin", e)
            }
        }
        try {
            val indicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
            val output = handler.runProcessWithProgressIndicator(indicator, TIMEOUT_MS, true)
            indicator.checkCanceled()
            ProgressManager.checkCanceled()
            log.debug("Mago lint: ${command.commandLineString}; exit ${output.exitCode}; stderr: ${output.stderr}")
            if (output.isTimeout) return processFailure("Mago did not finish within 30 seconds")
            if (output.exitCode !in validExitCodes) {
                return processFailure("Mago failed (exit ${output.exitCode}): ${output.stderr.trim().ifEmpty { "No diagnostics returned" }}")
            }
            return ProcessResult.Success(output.stdout, output.stderr)
        } finally {
            handler.destroyProcess()
            writer.cancel(true)
        }
    }

    private fun processFailure(message: String, cause: Throwable? = null): ProcessResult.Failure {
        log.warn(message, cause)
        return ProcessResult.Failure(message)
    }

    private fun failure(message: String, cause: Throwable? = null, details: String = ""): Result.Failure {
        log.warn("$message\n$details", cause)
        return Result.Failure(message)
    }
}
