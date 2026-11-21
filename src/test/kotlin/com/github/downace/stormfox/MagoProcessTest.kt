package com.github.downace.stormfox

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class MagoProcessTest {
    private fun command(vararg args: String): GeneralCommandLine {
        val resource = ProcessFixture::class.java.getResource("ProcessFixture.class")!!
        val classpath = File(resource.toURI()).absolutePath.removeSuffix(
            ProcessFixture::class.java.name.replace('.', File.separatorChar) + ".class",
        )
        return GeneralCommandLine(listOf(File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp", classpath, ProcessFixture::class.java.name) + args).withCharset(StandardCharsets.UTF_8)
    }

    @Test fun eofAndSeparateUtf8Streams() {
        val result = MagoProcess.run(command("eof"), null, 10_000)
        assertEquals(0, result.exitCode)
        assertEquals("out-é", result.stdout)
        assertEquals("err-狐", result.stderr)
    }

    @Test fun fullPipesAndLargeInputDoNotDeadlock() {
        val result = MagoProcess.run(command("pipes"), "狐".repeat(300_000), 10_000)
        assertEquals("é".repeat(100_000) + "done", result.stdout)
        assertEquals("é".repeat(100_000), result.stderr)
    }

    @Test fun earlyExitRetainsStderr() {
        val result = MagoProcess.run(command("early"), "a".repeat(1_000_000), 10_000)
        assertEquals(7, result.exitCode)
        assertEquals("useful error", result.stderr)
    }

    @Test fun characterLimitAppliesToEachStream() {
        val limit = 4 * 1024 * 1024
        for (stream in listOf("out", "err")) {
            val result = MagoProcess.run(command("limit", stream, limit.toString()), null, 20_000, limit)
            assertEquals(limit, if (stream == "out") result.stdout.length else result.stderr.length)
            assertThrows(MagoProcess.OutputLimit::class.java) {
                MagoProcess.run(command("limit", stream, (limit + 1).toString()), null, 20_000, limit)
            }
        }
    }

    @Test fun cancellationBeforeStart() {
        assertThrows(ProcessCanceledException::class.java) {
            MagoProcess.run(command("eof"), null, 10_000, checkCanceled = { throw ProcessCanceledException() })
        }
    }

    @Test fun timeoutTerminatesLocalProcess() {
        var process: Process? = null
        assertThrows(MagoProcess.TimedOut::class.java) {
            MagoProcess.run(command("wait"), null, 500, attach = { process = it })
        }
        assertTrue(process!!.waitFor(5, TimeUnit.SECONDS))
        assertFalse(process!!.isAlive)
    }

    @Test fun cancellationAfterAttachTerminatesLocalProcess() {
        var process: Process? = null
        assertThrows(ProcessCanceledException::class.java) {
            MagoProcess.run(command("wait"), null, 10_000,
                attach = { process = it },
                checkCanceled = { if (process != null) throw ProcessCanceledException() })
        }
        assertTrue(process!!.waitFor(5, TimeUnit.SECONDS))
        assertFalse(process!!.isAlive)
    }

    @Test fun cancellationWhileInputWriterIsBlocked() {
        val directory = Files.createTempDirectory("stormfox-blocked-writer")
        val ready = directory.resolve("ready")
        var process: Process? = null
        try {
            assertThrows(ProcessCanceledException::class.java) {
                MagoProcess.run(command("blocked-write", ready.toString()), "x".repeat(16 * 1024 * 1024), 10_000,
                    attach = { process = it },
                    checkCanceled = { if (Files.exists(ready)) throw ProcessCanceledException() })
            }
            assertTrue(process!!.waitFor(5, TimeUnit.SECONDS))
            assertFalse(process!!.isAlive)
        } finally {
            process?.destroyForcibly()
            Files.deleteIfExists(ready)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun cancellationTerminatesLocalDescendant() {
        val directory = Files.createTempDirectory("stormfox-process-test")
        val ready = directory.resolve("child.pid")
        var process: Process? = null
        var child: ProcessHandle? = null
        try {
            assertThrows(ProcessCanceledException::class.java) {
                MagoProcess.run(command("tree", ready.toString()), null, 10_000,
                    attach = { process = it },
                    checkCanceled = {
                        if (Files.exists(ready)) {
                            val pid = Files.readString(ready).toLongOrNull()
                            if (pid != null) {
                                child = ProcessHandle.of(pid).orElseThrow()
                                throw ProcessCanceledException()
                            }
                        }
                    })
            }
            assertTrue(process!!.waitFor(5, TimeUnit.SECONDS))
            child!!.onExit().get(5, TimeUnit.SECONDS)
            assertFalse(child!!.isAlive)
        } finally {
            child?.destroyForcibly()
            process?.destroyForcibly()
            Files.deleteIfExists(ready)
            Files.deleteIfExists(directory.resolve("child.tmp"))
            Files.deleteIfExists(directory)
        }
    }

    @Test fun timeoutTerminatesLocalDescendant() {
        val directory = Files.createTempDirectory("stormfox-timeout-tree")
        val ready = directory.resolve("child.pid")
        var process: Process? = null
        var child: ProcessHandle? = null
        try {
            assertThrows(MagoProcess.TimedOut::class.java) {
                MagoProcess.run(command("tree", ready.toString()), null, 2_000,
                    attach = { process = it },
                    checkCanceled = {
                        if (child == null && Files.exists(ready)) {
                            child = ProcessHandle.of(Files.readString(ready).toLong()).orElseThrow()
                        }
                    })
            }
            assertNotNull("child must report its PID before timeout", child)
            assertTrue(process!!.waitFor(5, TimeUnit.SECONDS))
            child!!.onExit().get(5, TimeUnit.SECONDS)
            assertFalse(child!!.isAlive)
        } finally {
            child?.destroyForcibly()
            process?.destroyForcibly()
            Files.deleteIfExists(ready)
            Files.deleteIfExists(directory.resolve("child.tmp"))
            Files.deleteIfExists(directory)
        }
    }
}
