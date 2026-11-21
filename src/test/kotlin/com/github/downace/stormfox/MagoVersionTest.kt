package com.github.downace.stormfox

import com.github.downace.stormfox.format.MagoValidation
import com.github.downace.stormfox.settings.MagoSettings
import com.github.downace.stormfox.lint.MagoLintInspection
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MagoVersionTest : BasePlatformTestCase() {
    fun testAbandonedManualValidationRespectsCooldownAndStaleTokens() {
        val state = MagoSettings.getInstance(project).state
        val oldPath = state.magoPath
        val oldRoot = state.executionRoot
        val job = SupervisorJob()
        val service = MagoVersionService(project, CoroutineScope(Dispatchers.Default + job))
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val finished = CountDownLatch(1)
        try {
            state.magoPath = "mago"
            state.executionRoot = ""
            service.nanoTimeForTest = { clock.get() }
            service.probeForTest = { _, _, _ ->
                calls.incrementAndGet()
                MagoValidation.Result.Success("mago 1.60.1")
            }
            service.probeFinishedForTest = { finished.countDown() }
            val old = service.beginValidation("mago", null, "")
            service.abandonValidation(old, "mago", null, "")
            service.ensureInitialized()
            assertEquals(0, calls.get())
            // Manual validation bypasses cooldown; an older cleanup must not release it.
            val current = service.beginValidation("mago", null, "")
            service.abandonValidation(old, "mago", null, "")
            clock.set(MagoVersionService.RETRY_COOLDOWN_NANOS)
            service.ensureInitialized()
            assertEquals(0, calls.get())
            service.abandonValidation(current, "mago", null, "")
            service.finish(current, "mago", MagoValidation.Result.Failure("late"), null, "")
            clock.addAndGet(MagoVersionService.RETRY_COOLDOWN_NANOS)
            service.ensureInitialized()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertEquals(MagoVersion(1, 60, 1), service.cachedVersion())
            service.finish(current, "mago", MagoValidation.Result.Success("mago 1.59.0"), null, "")
            assertEquals(MagoVersion(1, 60, 1), service.cachedVersion())
        } finally {
            job.cancel()
            state.magoPath = oldPath
            state.executionRoot = oldRoot
        }
    }

    fun testCancelledAndExceptionalProbesReleasePendingForRetry() {
        val state = MagoSettings.getInstance(project).state
        val oldPath = state.magoPath
        val oldRoot = state.executionRoot
        try {
            state.magoPath = "mago"
            state.executionRoot = ""
            for (cancel in listOf(true, false)) {
                val job = SupervisorJob()
                val service = MagoVersionService(
                    project, CoroutineScope(Dispatchers.Default + job + CoroutineExceptionHandler { _, _ -> }),
                )
                val clock = AtomicLong()
                val calls = AtomicInteger()
                val failed = CountDownLatch(1)
                val recovered = CountDownLatch(1)
                try {
                    service.nanoTimeForTest = { clock.get() }
                    service.probeFinishedForTest = {
                        if (calls.get() == 1) failed.countDown() else recovered.countDown()
                    }
                    service.probeForTest = { _, _, _ ->
                        if (calls.incrementAndGet() == 1) {
                            if (cancel) throw CancellationException("cancelled")
                            throw IllegalStateException("probe failed")
                        }
                        MagoValidation.Result.Success("mago 1.59.0")
                    }
                    service.ensureInitialized()
                    assertTrue(failed.await(5, TimeUnit.SECONDS))
                    service.ensureInitialized()
                    assertEquals(1, calls.get())
                    clock.set(MagoVersionService.RETRY_COOLDOWN_NANOS)
                    service.ensureInitialized()
                    assertTrue(recovered.await(5, TimeUnit.SECONDS))
                    assertEquals(MagoVersion(1, 59, 0), service.cachedVersion())
                    clock.addAndGet(MagoVersionService.RETRY_COOLDOWN_NANOS)
                    service.ensureInitialized()
                    assertEquals(2, calls.get())
                } finally {
                    job.cancel()
                }
            }
        } finally {
            state.magoPath = oldPath
            state.executionRoot = oldRoot
        }
    }

    fun testFailedProbeRetriesOnDemandAfterCooldownAndCoalescesConcurrentCalls() {
        val state = MagoSettings.getInstance(project).state
        val oldPath = state.magoPath
        val oldRoot = state.executionRoot
        val job = SupervisorJob()
        val service = MagoVersionService(project, CoroutineScope(Dispatchers.Default + job))
        val clock = AtomicLong(0)
        val calls = AtomicInteger()
        val firstFinished = CountDownLatch(1)
        val retryStarted = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val retryFinished = CountDownLatch(1)
        try {
            state.magoPath = "mago"
            state.executionRoot = ""
            service.nanoTimeForTest = { clock.get() }
            service.probeFinishedForTest = {
                if (calls.get() == 1) firstFinished.countDown() else retryFinished.countDown()
            }
            service.probeForTest = { _, _, _ ->
                if (calls.incrementAndGet() == 1) {
                    MagoValidation.Result.Failure("unavailable")
                } else {
                    retryStarted.countDown()
                    check(releaseRetry.await(5, TimeUnit.SECONDS))
                    MagoValidation.Result.Success("mago 1.60.1")
                }
            }
            service.ensureInitialized()
            assertTrue(firstFinished.await(5, TimeUnit.SECONDS))
            assertNull(service.cachedVersion())
            clock.set(MagoVersionService.RETRY_COOLDOWN_NANOS - 1)
            service.ensureInitialized()
            assertEquals(1, calls.get())
            clock.incrementAndGet()
            val threads = List(12) { Thread { service.ensureInitialized() } }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            assertTrue(retryStarted.await(5, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
            releaseRetry.countDown()
            assertTrue(retryFinished.await(5, TimeUnit.SECONDS))
            assertEquals(MagoVersion(1, 60, 1), service.cachedVersion())
            clock.addAndGet(MagoVersionService.RETRY_COOLDOWN_NANOS)
            service.ensureInitialized()
            assertEquals(2, calls.get())
        } finally {
            releaseRetry.countDown()
            job.cancel()
            state.magoPath = oldPath
            state.executionRoot = oldRoot
        }
    }

    fun testBlankDiagnosticCommandSharesDefaultMagoVersionContext() {
        val settings = MagoSettings.getInstance(project).state
        val previous = settings.magoPath
        val job = SupervisorJob()
        val service = MagoVersionService(project, CoroutineScope(Dispatchers.Default + job))
        try {
            settings.magoPath = ""
            val token = service.beginValidation("mago")
            assertNotNull(token)
            service.finish(token, "mago", MagoValidation.Result.Success("mago 1.60.0"))
            assertEquals(MagoVersion(1, 60, 0), service.cachedVersion())
            assertEquals(service.cachedVersion(), service.cachedVersion("mago", null, settings.executionRoot))
        } finally {
            settings.magoPath = previous
            job.cancel()
        }
    }

    fun testTwigCompatibilityStartsAt160WithoutChangingPhpSupport() {
        assertTrue(MagoVersion(1, 59, 9).isSupported)
        assertFalse(MagoVersion(1, 59, 9).supportsTwig)
        assertTrue(MagoVersion(1, 60, 0).supportsTwig)
        assertTrue(MagoVersion(1, 60, 1).supportsTwig)
        assertTrue(MagoVersion(2, 0, 0).supportsTwig)
    }

    fun testCacheNeverLooksUpSettingsWhileHoldingCacheMonitor() {
        val job = SupervisorJob()
        val service = MagoVersionService(project, CoroutineScope(Dispatchers.Default + job))
        val state = MagoSettings.getInstance(project).state
        val command = state.magoPath
        val root = state.executionRoot
        val thread = Thread.currentThread()
        try {
            MagoSettings.lookupGuard = {
                if (Thread.currentThread() === thread) {
                    check(!Thread.holdsLock(service)) { "Settings lookup while holding version cache monitor" }
                }
            }
            service.cachedVersion()
            val token = service.beginValidation(command, null, root)
            service.finish(token, command, MagoValidation.Result.Success("mago 1.60.1"), null, root)
        } finally {
            MagoSettings.lookupGuard = null
            job.cancel(null)
        }
    }

    fun testValidationClosesStdinForWrappers() {
        val root = Files.createTempDirectory("mago-eof").toFile()
        val source = root.resolve("WaitForEof.java")
        source.writeText("""
            public class WaitForEof {
                public static void main(String[] args) throws Exception {
                    System.in.readAllBytes();
                    System.out.println("mago 1.60.1");
                }
            }
        """.trimIndent())
        val java = java.io.File(System.getProperty("java.home"), "bin/java").absolutePath
        val command = "\"$java\" \"${source.absolutePath}\""
        try {
            val version = MagoValidation.checkBinary(command, root)
            assertTrue(version.toString(), version is MagoValidation.Result.Success)
            val config = MagoValidation.checkConfig(command, "mago.toml", root)
            assertTrue(config.toString(), config is MagoValidation.Result.Success)
        } finally {
            source.delete()
            root.delete()
        }
    }

    fun testBinaryValidationReportsCompatibilityForDetectedVersions() {
        val root = Files.createTempDirectory("mago-version-validation").toFile()
        val source = root.resolve("VersionStub.java")
        val java = java.io.File(System.getProperty("java.home"), "bin/java").absolutePath
        val command = "\"$java\" \"${source.absolutePath}\""
        try {
            val cases = listOf(
                "1.17.0" to false,
                "1.18.0" to true,
                "1.24.9" to true,
                "1.25.0" to false,
                "1.60.2" to false,
                "2.0.0" to false,
            )
            for ((version, expectsWarning) in cases) {
                val output = "mago $version"
                val stream = if (version == "1.18.0") "System.err" else "System.out"
                source.writeText(
                    """
                    public class VersionStub {
                        public static void main(String[] args) {
                            $stream.println("$output");
                        }
                    }
                    """.trimIndent(),
                )

                val result = MagoValidation.checkBinaryUncached(command, root)
                if (version == "1.17.0") {
                    assertTrue("$version should be rejected, got $result", result is MagoValidation.Result.Failure)
                    assertTrue((result as MagoValidation.Result.Failure).message.contains("1.18.0"))
                } else {
                    assertTrue("$version should succeed, got $result", result is MagoValidation.Result.Success)
                    assertEquals("$version warning mismatch", expectsWarning, (result as MagoValidation.Result.Success).warning != null)
                }
            }
        } finally {
            source.delete()
            root.delete()
        }
    }

    fun testRefreshOnlyProbesOpenDirectoriesAndCoalescesSettings() {
        val state = MagoSettings.getInstance(project).state
        val oldPath = state.magoPath
        val oldRoot = state.executionRoot
        val callback = state.onPathChanged
        val job = SupervisorJob()
        val service = MagoVersionService(project, CoroutineScope(Dispatchers.Default + job))
        val open = Files.createTempDirectory("mago-open").toFile()
        val closed = Files.createTempDirectory("mago-closed").toFile()
        val deleted = Files.createTempDirectory("mago-deleted").toFile()
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<java.io.File?, String>>())
        val called = CountDownLatch(1)
        try {
            state.onPathChanged = null
            state.magoPath = "docker compose exec app mago"
            state.executionRoot = ""
            for (directory in listOf(closed, deleted)) {
                val token = service.beginValidation(state.magoPath, directory, "")
                service.finish(token, state.magoPath, MagoValidation.Result.Success("mago 1.59.0"), directory, "")
            }
            assertTrue(deleted.delete())
            service.projectDirectoriesForTest = { listOf(open, deleted, open) }
            service.probeForTest = { _, directory, root ->
                calls.add(directory to root)
                called.countDown()
                MagoValidation.Result.Success("mago 1.60.1")
            }
            service.refresh(state.snapshot())
            state.executionRoot = "/app"
            service.refresh(state.snapshot())
            assertTrue("Expected one live-project probe", called.await(5, TimeUnit.SECONDS))
            Thread.sleep(600)
            assertEquals(listOf(open to "/app"), calls.toList())
            assertNull(service.cachedVersion(closed))
            assertNull(service.cachedVersion(deleted))
        } finally {
            job.cancel()
            state.executionRoot = oldRoot
            state.magoPath = oldPath
            state.onPathChanged = callback
            open.delete()
            closed.delete()
        }
    }

    fun testProjectContextsAndStaleRootValidation() {
        val state = MagoSettings.getInstance(project).state
        val originalPath = state.magoPath
        val originalRoot = state.executionRoot
        val job = SupervisorJob()
        val service = MagoVersionService(project, CoroutineScope(Dispatchers.Default + job))
        val first = java.io.File("project-one").absoluteFile
        val second = java.io.File("project-two").absoluteFile
        try {
            state.magoPath = "docker compose exec app mago"
            state.executionRoot = "/app"
            val firstToken = service.beginValidation(state.magoPath, first, "/app")
            val secondToken = service.beginValidation(state.magoPath, second, "/app")
            service.finish(firstToken, state.magoPath, MagoValidation.Result.Success("mago 1.59.0"), first, "/app")
            service.finish(secondToken, state.magoPath, MagoValidation.Result.Success("mago 1.60.1"), second, "/app")
            assertEquals(MagoVersion(1, 59, 0), service.cachedVersion(first))
            assertEquals(MagoVersion(1, 60, 1), service.cachedVersion(second))
            val stale = service.beginValidation(state.magoPath, first, "/app")
            state.executionRoot = "/new"
            service.finish(stale, state.magoPath, MagoValidation.Result.Success("mago 1.60.1"), first, "/app")
            assertNull(service.cachedVersion(first))
        } finally {
            state.executionRoot = originalRoot
            state.magoPath = originalPath
            job.cancel()
        }
    }

    fun testReleaseParsingAndCompatibilityBounds() {
        assertEquals(MagoVersion(1, 59, 0), MagoVersion.parse("mago 1.59.0"))
        assertFalse(MagoVersion(1, 17, 9).isSupported)
        assertTrue(MagoVersion(1, 18, 0).isSupported)
        assertTrue(MagoVersion(1, 24, 9).isSupported)
        assertFalse(MagoVersion(1, 24, 9).supportsFilepathAwareExcludes)
        assertTrue(MagoVersion(1, 25, 0).supportsFilepathAwareExcludes)
        assertTrue(MagoVersion.parse("Mago v1.60.1")!!.supportsFilepathAwareExcludes)
        assertTrue(MagoVersion.parse("mago 1.60.2")!!.isSupported)
        assertTrue(MagoVersion(2, 0, 0).isSupported)
        assertNull(MagoVersion.parse("mago 1.60.1-beta"))
        assertNull(MagoVersion.parse("unrelated 1.60.1"))
        assertNull(MagoVersion.parse("mago 999999999999999999999.1.0"))
    }

    fun testDocumentationFallbackAndReplacement() {
        val html = "<a href=\"https://mago.carthage.software/latest/en/tools/\">Mago</a>"
        assertEquals(html, versionedMagoDocumentation(html, null))
        assertTrue(versionedMagoDocumentation(html, MagoVersion(1, 60, 1)).contains("/1.60.1/en/"))
    }

    fun testStaleValidationCannotReplaceNewResultAndFailureClearsCache() {
        val settings = MagoSettings.getInstance(project)
        val original = settings.state.magoPath
        val job = SupervisorJob()
        val service = MagoVersionService(project, CoroutineScope(Dispatchers.Default + job))
        val connection = project.messageBus.connect(testRootDisposable)
        var notifications = 0
        connection.subscribe(MagoVersionListener.TOPIC, object : MagoVersionListener {
            override fun versionChanged() { notifications++ }
        })
        try {
            settings.state.magoPath = ""
            val old = service.beginValidation("")
            val current = service.beginValidation("")
            service.finish(current, "", MagoValidation.Result.Success("mago 1.60.1"))
            service.finish(old, "", MagoValidation.Result.Success("mago 1.59.0"))
            assertEquals(MagoVersion(1, 60, 1), service.cachedVersion())
            assertEquals(1, notifications)
            val same = service.beginValidation("")
            service.finish(same, "", MagoValidation.Result.Success("mago 1.60.1"))
            assertEquals(1, notifications)
            val stale = service.beginValidation("")
            job.cancel()
            service.refresh(com.github.downace.stormfox.settings.MagoSettingsSnapshot("different-path", settings.state.executionRoot))
            assertNull(service.cachedVersion())
            assertEquals(2, notifications)
            service.finish(stale, "", MagoValidation.Result.Success("mago 1.59.0"))
            assertNull(service.cachedVersion())
            assertEquals(2, notifications)
            val restored = service.beginValidation("")
            service.finish(restored, "", MagoValidation.Result.Success("mago 1.60.1"))
            assertEquals(3, notifications)
            val failed = service.beginValidation("")
            service.finish(failed, "", MagoValidation.Result.Failure("failed"))
            assertNull(service.cachedVersion())
            assertEquals(4, notifications)
        } finally {
            settings.state.magoPath = original
            job.cancel()
        }
    }

    fun testInspectionDescriptionUsesProjectIndependentFallback() {
        val settings = MagoSettings.getInstance(project)
        val original = settings.state.magoPath
        val service = MagoVersionService.getInstance(project)
        try {
            settings.state.magoPath = ""
            val token = service.beginValidation("")
            service.finish(token, "", MagoValidation.Result.Success("mago 1.60.1"))
            assertTrue(MagoLintInspection().loadDescription()!!.contains("/latest/en/tools/linter/"))
            val failure = service.beginValidation("")
            service.finish(failure, "", MagoValidation.Result.Failure("failed"))
            assertTrue(MagoLintInspection().loadDescription()!!.contains("/latest/en/tools/linter/"))
        } finally {
            settings.state.magoPath = original
            if (original.isBlank()) service.refresh(settings.state.snapshot())
        }
    }
}
