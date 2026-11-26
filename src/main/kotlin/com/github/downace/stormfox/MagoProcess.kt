package com.github.downace.stormfox

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Key
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Terminates the local CLI process tree, not independently managed remote processes. */
internal fun terminateMagoProcess(handler: OSProcessHandler) {
    val process = handler.process
    val children = if (process.isAlive) process.descendants().use { it.toList() } else emptyList()
    children.forEach { it.destroyForcibly() }
    if (process.isAlive) process.destroyForcibly()
    handler.destroyProcess()
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)
    for (child in children) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) break
        try { child.onExit().get(remaining, TimeUnit.NANOSECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        catch (_: Exception) { }
    }
}

internal fun terminateMagoProcess(process: Process) {
    if (process.isAlive) {
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }
}

/** Only local processes are owned here; a Docker CLI cannot guarantee container termination. */
internal object MagoProcess {
    data class Output(val exitCode: Int, val stdout: String, val stderr: String)
    class TimedOut : Exception()
    class OutputLimit : Exception()

    fun run(
        command: GeneralCommandLine,
        input: String?,
        timeoutMillis: Int,
        limit: Int? = null,
        checkCanceled: () -> Unit = {},
        attach: (Process) -> Unit = {},
        // The writer latch signals its finally block; a cancelled Future can complete before that.
        observe: (OSProcessHandler, CountDownLatch) -> Unit = { _, _ -> },
    ): Output {
        checkCanceled()
        val handler = OSProcessHandler(command)
        var writer: java.util.concurrent.Future<*>? = null
        val writerDone = CountDownLatch(1)
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val exceeded = AtomicBoolean()
        try {
            observe(handler, writerDone)
            attach(handler.process)
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val target = when (outputType) {
                        ProcessOutputTypes.STDOUT -> stdout
                        ProcessOutputTypes.STDERR -> stderr
                        else -> return
                    }
                    synchronized(target) {
                        if (limit != null && event.text.length > limit - target.length) exceeded.set(true)
                        else target.append(event.text)
                    }
                }
            })
            handler.startNotify()
            // The child can fill either output pipe before it reads stdin.
            writer = AppExecutorUtil.getAppExecutorService().submit {
                try {
                    handler.processInput.use { stream ->
                        if (input != null) stream.write(input.toByteArray(StandardCharsets.UTF_8))
                    }
                } catch (_: IOException) {
                    // Early child exit is reported by its exit code and stderr, not a broken pipe.
                } finally {
                    writerDone.countDown()
                }
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
            while (true) {
                checkCanceled()
                if (exceeded.get()) throw OutputLimit()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw TimedOut()
                if (handler.waitFor(minOf(100L, TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)))) break
            }
            checkCanceled()
            if (exceeded.get()) throw OutputLimit()
            return Output(handler.exitCode ?: -1, synchronized(stdout) { stdout.toString() }, synchronized(stderr) { stderr.toString() })
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ProcessCanceledException()
        } finally {
            // Cleanup is bounded and must not replace the process failure or cancellation.
            try { terminateMagoProcess(handler) } catch (_: Exception) { }
            try { handler.process.waitFor(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            writer?.cancel(true)
            if (writer != null) {
                try { writerDone.await(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
    }
}
