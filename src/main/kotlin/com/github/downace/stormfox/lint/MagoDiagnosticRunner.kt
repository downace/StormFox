package com.github.downace.stormfox.lint

import com.github.downace.stormfox.MagoExecutionContext
import com.github.downace.stormfox.MagoFailureLog
import com.github.downace.stormfox.MagoFailureLogService
import com.github.downace.stormfox.terminateMagoProcess
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
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun <T> withMagoCancellation(action: (ProgressIndicator) -> T): T = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { continuation ->
        val indicator = EmptyProgressIndicator()
        continuation.invokeOnCancellation { indicator.cancel() }
        try { continuation.resume(action(indicator)) }
        catch (failure: Throwable) { continuation.resumeWithException(failure) }
    }
}

internal object MagoDiagnosticRunner {
    private val log = Logger.getInstance(MagoDiagnosticRunner::class.java)

    sealed interface Result {
        data class Success(val diagnostics: List<MagoDiagnosticReport.Diagnostic>, val warnings: List<String> = emptyList()) : Result
        data class Failure(val message: String) : Result
    }

    private sealed interface ProcessResult {
        data class Success(val stdout: String, val stderr: String, val exitCode: Int) : ProcessResult
        data class Failure(val message: String, val category: String) : ProcessResult
    }

    fun arguments(binary: String, configPath: String, logicalPath: String, workingDirectory: File, tool: MagoTool = MagoTool.LINT): List<String> {
        val relativePath = workingDirectory.toPath().toAbsolutePath().normalize()
            .relativize(File(logicalPath).toPath().toAbsolutePath().normalize()).toString().replace('\\', '/')
        return globalArguments(MagoExecutionContext(binary, workingDirectory), configPath) + listOf(tool.command, "--stdin-input", "--reporting-format", "json", "--", relativePath)
    }

    private fun globalArguments(context: MagoExecutionContext, configPath: String): List<String> {
        return buildList {
            addAll(context.command())
            if (configPath.isNotBlank()) addAll(listOf("--config", context.toExecution(configPath)))
        }
    }

    fun run(binary: String, workingDirectory: File, configPath: String, logicalPath: String, text: String,
             tool: MagoTool = MagoTool.LINT, indicator: ProgressIndicator? = null, executionRoot: String = ""): Result {
        ProgressManager.checkCanceled()
        val failures = MagoFailureLogService.get()
        val key = MagoFailureLog.context(binary.trim(), workingDirectory.absoluteFile.normalize().path, configPath, executionRoot)
        val fileKey = MagoFailureLog.context(key, logicalPath)
        val operation = "diagnostics-${tool.command}"
        fun failed(stage: String, category: String, message: String): Result.Failure {
            failures.failure(if (category == "path") fileKey else key, operation, stage, category, workingDirectory.path)
            return Result.Failure(message)
        }
        val context = MagoExecutionContext(binary, workingDirectory, executionRoot)
        val globals = try { globalArguments(context, configPath) }
        catch (e: IllegalArgumentException) {
            log.debug("invalid diagnostic command", e)
            return failed("command", "invalid", "Invalid Mago command: ${e.message}")
        }
        val source = execute(globals + listOf("config", "--show", "source"), workingDirectory, "", 0..0, indicator, tool.timeoutMillis)
        if (source is ProcessResult.Failure) return failed("source", source.category, source.message)
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
            if (executionRoot.isNotBlank()) File(context.toHost(normalizedPath))
            else File(normalizedPath).let { if (it.isAbsolute) it else File(workingDirectory, normalizedPath) }
        } catch (e: com.google.gson.JsonParseException) {
            failure("Mago returned an invalid source configuration: ${e.message}", e, source.stdout + "\n" + source.stderr)
            return failed("source", "invalid-report", "Mago returned an invalid source configuration: ${e.message}")
        } catch (e: IllegalArgumentException) {
            failure("Mago returned an invalid source configuration: ${e.message}", e, source.stdout + "\n" + source.stderr)
            return failed("source", "invalid-report", "Mago returned an invalid source configuration: ${e.message}")
        }
        val arguments = try {
            if (executionRoot.isNotBlank()) context.toExecution(logicalPath)
            val relative = workspace.toPath().toAbsolutePath().normalize()
                .relativize(File(logicalPath).toPath().toAbsolutePath().normalize()).toString().replace('\\', '/')
            globals + listOf(tool.command, "--stdin-input", "--reporting-format", "json", "--", relative)
        } catch (e: IllegalArgumentException) {
            log.debug("cannot resolve diagnostic path", e)
            return failed("command", "path", "Cannot resolve Mago ${tool.command} path: ${e.message}")
        }
        val output = execute(arguments, workingDirectory, text, 0..1, indicator, tool.timeoutMillis)
        if (output is ProcessResult.Failure) return failed("process", output.category, output.message)
        output as ProcessResult.Success
        val result = parseOutput(output.stdout, output.stderr, output.exitCode, source.stderr, text, logicalPath, workspace, tool) {
            if (executionRoot.isBlank()) it else context.toHost(it, context.toExecution(workspace.path))
        }
        if (result is Result.Failure) failures.failure(fileKey, operation, "report", "invalid", workingDirectory.path)
        return result
    }

    internal fun parseOutput(stdout: String, stderr: String, exitCode: Int, sourceStderr: String, text: String,
                             logicalPath: String, workspace: File, tool: MagoTool,
                             mapPath: (String) -> String = { it }): Result {
        if (stdout.isBlank()) {
            return if (exitCode == 0) Result.Success(emptyList(), warningsFromStderr(sourceStderr, stderr))
            else failure("Mago ${tool.command} returned an empty JSON report (exit $exitCode)", details = stderr)
        }
        return try {
            val diagnostics = MagoDiagnosticReport.parse(stdout, text, logicalPath, workspace, tool, mapPath)
            Result.Success(diagnostics, warningsFromStderr(sourceStderr, stderr))
        } catch (e: com.google.gson.JsonParseException) {
            failure("Mago ${tool.command} returned an invalid JSON report: ${e.message}", e, stdout + "\n" + stderr)
        } catch (e: IOException) {
            failure("Mago ${tool.command} returned an invalid JSON report: ${e.message}", e, stdout + "\n" + stderr)
        } catch (e: IllegalArgumentException) {
            failure("Mago ${tool.command} returned an invalid JSON report: ${e.message}", e, stdout + "\n" + stderr)
        } catch (e: IllegalStateException) {
            failure("Mago ${tool.command} returned an invalid JSON report: ${e.message}", e, stdout + "\n" + stderr)
        }
    }

    private val ansiEscape = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val warningLine = Regex(
        """^\s*(?:(?:\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?)\s+)?WARN(?:\s+|:\s*)(.*)$"""
    )
    private val logTarget = Regex("""^mago(?:::[A-Za-z_][A-Za-z0-9_]*)+:\s+""")

    internal fun warningsFromStderr(vararg streams: String): List<String> =
        streams.asSequence().flatMap { ansiEscape.replace(it, "").lineSequence() }
            .mapNotNull { line ->
                warningLine.matchEntire(line)?.groupValues?.get(1)
                    ?.replaceFirst(logTarget, "")?.trim()?.takeIf { it.isNotEmpty() }
            }.distinct().toList()

    private fun execute(arguments: List<String>, workingDirectory: File, text: String, validExitCodes: IntRange,
                        workerIndicator: ProgressIndicator?, timeoutMillis: Int): ProcessResult {
        workerIndicator?.checkCanceled()
        ProgressManager.checkCanceled()
        val command = try {
            GeneralCommandLine(arguments)
                .withCharset(StandardCharsets.UTF_8).withWorkDirectory(workingDirectory)
        } catch (e: IllegalArgumentException) {
            return processFailure("Cannot resolve Mago path: ${e.message}", "path", e)
        }
        val handler = try {
            CapturingProcessHandler(command)
        } catch (e: ExecutionException) {
            return processFailure("Cannot start Mago: ${e.message}", "start", e)
        }
        val writer = AppExecutorUtil.getAppExecutorService().submit {
            try {
                handler.processInput.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
            } catch (e: IOException) {
                log.debug("cannot write diagnostic stdin", e)
            }
        }
        try {
            val indicator = workerIndicator ?: ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
            val output = handler.runProcessWithProgressIndicator(indicator, timeoutMillis, true)
            indicator.checkCanceled()
            ProgressManager.checkCanceled()
            if (log.isDebugEnabled) log.debug("${command.commandLineString}; exit ${output.exitCode}; stdout: ${output.stdout}; stderr: ${output.stderr}")
            if (output.isTimeout) return processFailure("Mago did not finish within ${timeoutMillis / 1000} seconds", "timeout")
            if (output.exitCode !in validExitCodes) {
                return processFailure("Mago failed (exit ${output.exitCode}): ${output.stderr.trim().ifEmpty { "No diagnostics returned" }}", "exit-${output.exitCode}")
            }
            return ProcessResult.Success(output.stdout, output.stderr, output.exitCode)
        } finally {
            terminateMagoProcess(handler)
            writer.cancel(true)
        }
    }

    private fun processFailure(message: String, category: String, cause: Throwable? = null): ProcessResult.Failure {
        if (log.isDebugEnabled) log.debug("diagnostic process failed: $message", cause)
        return ProcessResult.Failure(message, category)
    }

    private fun failure(message: String, cause: Throwable? = null, details: String = ""): Result.Failure {
        if (log.isDebugEnabled) log.debug("diagnostic processing failed: $message\n$details", cause)
        return Result.Failure(message)
    }
}
