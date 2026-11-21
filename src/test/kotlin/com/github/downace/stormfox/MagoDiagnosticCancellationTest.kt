package com.github.downace.stormfox

import com.github.downace.stormfox.lint.withMagoCancellation
import com.github.downace.stormfox.terminateMagoProcess
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files

class MagoDiagnosticCancellationTest : BasePlatformTestCase() {
    fun testWorkerCancellationTerminatesChildDuringStartup() = cancellationScenario(false)
    fun testWorkerCancellationTerminatesRunningChildProcess() = cancellationScenario(true)

    fun testCoroutineCancellationDuringBlockedRunnerInput() = runBlocking {
        val directory = Files.createTempDirectory("stormfox-coroutine-input")
        val readyFile = directory.resolve("ready")
        val ready = CompletableDeferred<Process>()
        val resource = ProcessFixture::class.java.getResource("ProcessFixture.class")!!
        val classpath = File(resource.toURI()).absolutePath.removeSuffix(
            ProcessFixture::class.java.name.replace('.', File.separatorChar) + ".class")
        val executable = File(System.getProperty("java.home"), "bin/java").absolutePath
        var child: Process? = null
        val worker = launch(Dispatchers.Default) {
            withMagoCancellation { indicator ->
                MagoProcess.run(
                    GeneralCommandLine(executable, "-cp", classpath, ProcessFixture::class.java.name,
                        "blocked-write", readyFile.toString()), "x".repeat(16 * 1024 * 1024), 10_000,
                    attach = { child = it },
                    checkCanceled = {
                        if (Files.exists(readyFile)) child?.let { ready.complete(it) }
                        indicator.checkCanceled()
                    })
            }
        }
        try {
            val process = withTimeout(10_000) { ready.await() }
            withTimeout(10_000) { worker.cancelAndJoin() }
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(process.isAlive)
            assertTrue(worker.isCancelled)
        } finally {
            child?.destroyForcibly()
            worker.cancel()
            Files.deleteIfExists(readyFile)
            Files.deleteIfExists(directory)
        }
    }

    private fun cancellationScenario(waitForOutput: Boolean) = runBlocking {
        val directory = Files.createTempDirectory("stormfox-cancel-")
        val source = directory.resolve("Sleeper.java")
        Files.writeString(source, "class Sleeper { public static void main(String[] args) throws Exception { System.out.println(\"READY\"); Thread.sleep(60000); } }")
        val started = CompletableDeferred<Process>()
        val worker = launch(Dispatchers.Default) {
            withMagoCancellation { indicator ->
                val java = java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                    if (com.intellij.openapi.util.SystemInfo.isWindows) "java.exe" else "java").toString()
                val handler = CapturingProcessHandler(GeneralCommandLine(java, source.toString()))
                if (!waitForOutput) started.complete(handler.process)
                else handler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
                    override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                        if (event.text.contains("READY")) started.complete(handler.process)
                    }
                })
                try { handler.runProcessWithProgressIndicator(indicator, 60_000, true) }
                finally { terminateMagoProcess(handler) }
            }
        }
        try {
            val child = withTimeout(10_000) { started.await() }
            assertTrue(child.isAlive)
            worker.cancel(null)
            withTimeout(10_000) { while (child.isAlive) delay(20) }
            worker.join()
            assertFalse(child.isAlive)
        } finally {
            worker.cancel(null)
            Files.deleteIfExists(source)
            Files.deleteIfExists(directory)
        }
    }
}
