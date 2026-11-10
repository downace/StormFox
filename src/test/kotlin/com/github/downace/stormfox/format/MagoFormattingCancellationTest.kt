package com.github.downace.stormfox.format

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MagoFormattingCancellationTest {
    @Test fun cancellationTerminatesActiveProcess() {
        val cancellation = MagoRunner.Cancellation()
        val handler = waitingProcess()
        try {
            cancellation.attach(handler.process)
            cancellation.cancel()
            assertTrue(handler.process.waitFor(5, TimeUnit.SECONDS))
        } finally {
            handler.process.destroyForcibly()
        }
    }

    @Test fun cancellationDuringLaunchTerminatesNewlyAttachedProcess() {
        val cancellation = MagoRunner.Cancellation()
        cancellation.cancel()
        val handler = waitingProcess()
        try {
            assertThrows(ProcessCanceledException::class.java) { cancellation.attach(handler.process) }
            assertTrue(handler.process.waitFor(5, TimeUnit.SECONDS))
        } finally {
            handler.process.destroyForcibly()
        }
    }

    @Test fun cancellationBeforeRunDoesNotLaunchFormatter() {
        val cancellation = MagoRunner.Cancellation()
        cancellation.cancel()
        assertThrows(ProcessCanceledException::class.java) {
            MagoRunner.format("nonexistent-formatter", null, "", "test.php", "", cancellation)
        }
    }

    @Test fun cancellationSuppressesLateSuccess() = assertLateResultSuppressed(MagoRunner.Result.Success("new text"))

    @Test fun cancellationSuppressesLateFailure() = assertLateResultSuppressed(MagoRunner.Result.Failure("error"))

    private fun assertLateResultSuppressed(result: MagoRunner.Result) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val request = Proxy.newProxyInstance(
            AsyncFormattingRequest::class.java.classLoader, arrayOf(AsyncFormattingRequest::class.java),
        ) { _, method, _ -> throw AssertionError("Unexpected request access: ${method.name}") } as AsyncFormattingRequest
        val task = MagoFormattingService.MagoFormattingTask(request, "", "", null, "test.php", "old text") {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            result
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val running = executor.submit { task.run() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(task.cancel())
            release.countDown()
            running.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun waitingProcess(): CapturingProcessHandler {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val resource = WaitingFormatter::class.java.getResource("WaitingFormatter.class")!!
        val classpath = File(resource.toURI()).absolutePath.removeSuffix(
            WaitingFormatter::class.java.name.replace('.', File.separatorChar) + ".class",
        )
        val handler = CapturingProcessHandler(GeneralCommandLine(java, "-cp", classpath, WaitingFormatter::class.java.name))
        assertEquals("ready", handler.process.inputStream.bufferedReader().readLine())
        return handler
    }
}
