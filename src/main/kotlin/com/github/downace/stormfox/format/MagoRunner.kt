package com.github.downace.stormfox.format

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

internal object MagoRunner {

    private val LOG = Logger.getInstance(MagoRunner::class.java)

    const val TIMEOUT_MS = 30_000

    sealed interface Result {
        data class Success(val text: String) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Formats [text] with mago. The buffer is piped through stdin, so the file on disk is never touched.
     *
     * @param configPath value for mago's global `--config` flag, empty to let mago discover the configuration itself
     */
    fun format(binary: String, workingDirectory: File?, configPath: String, logicalPath: String, text: String): Result {
        val commandLine = GeneralCommandLine(buildArguments(binary, configPath, logicalPath))
            .withCharset(StandardCharsets.UTF_8)

        if (workingDirectory != null) {
            commandLine.withWorkDirectory(workingDirectory)
        }

        val handler = try {
            CapturingProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            LOG.warn("Cannot start mago", e)
            return Result.Failure("Cannot start mago: ${e.message}")
        }

        return try {
            writeStdin(handler, text)

            val started = System.currentTimeMillis()
            val output = handler.runProcess(TIMEOUT_MS, true)
            val duration = System.currentTimeMillis() - started

            LOG.info(
                "mago finished in ${duration}ms, exit code ${output.exitCode}, command: ${commandLine.commandLineString}",
            )
            if (output.stdout.isNotBlank()) {
                LOG.debug("mago stdout: ${output.stdout}")
            }
            if (output.stderr.isNotBlank()) {
                LOG.info("mago stderr: ${output.stderr}")
            }

            when {
                output.isTimeout -> Result.Failure("mago did not finish within ${TIMEOUT_MS / 1000} seconds")
                output.exitCode != 0 -> Result.Failure(errorMessage(output.stderr, output.exitCode))
                else -> Result.Success(output.stdout)
            }
        } catch (e: Exception) {
            LOG.warn("mago failed", e)
            Result.Failure("mago failed: ${e.message ?: e.toString()}")
        } finally {
            handler.destroyProcess()
        }
    }

    private fun buildArguments(binary: String, configPath: String, logicalPath: String): List<String> {
        val arguments = mutableListOf<String>()
        if (configPath.isNotBlank()) {
            // Global flags have to come before the subcommand.
            arguments += "--config"
            arguments += configPath
        }
        arguments += listOf("format", "--stdin-input", "--stdin-filepath", logicalPath)
        return listOf(resolveExecutable(binary)) + arguments
    }

    /**
     * Writing to stdin fails when mago has already exited, which is exactly what happens with an invalid
     * configuration. Its diagnostics on stderr are more useful than that exception, so the failure is
     * only logged and the process output is still collected.
     */
    private fun writeStdin(handler: CapturingProcessHandler, text: String) {
        try {
            handler.processInput.write(text.toByteArray(StandardCharsets.UTF_8))
            handler.processInput.flush()
            handler.processInput.close()
        } catch (e: IOException) {
            LOG.debug("Cannot write to mago stdin, the process probably exited already", e)
        }
    }

    private fun errorMessage(stderr: String, exitCode: Int): String {
        val message = stderr.trim()
        if (message.isEmpty()) {
            return "mago exited with code $exitCode"
        }
        return message
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
