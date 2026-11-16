package com.github.downace.stormfox

import com.github.downace.stormfox.format.MagoValidation
import com.github.downace.stormfox.settings.MagoSettings
import com.github.downace.stormfox.lint.MagoLintInspection
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MagoVersionTest : BasePlatformTestCase() {
    fun testCacheNeverLooksUpSettingsWhileHoldingCacheMonitor() {
        val job = SupervisorJob()
        val service = MagoVersionService(CoroutineScope(Dispatchers.Default + job))
        val state = MagoSettings.getInstance().state
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

    fun testRefreshOnlyProbesOpenDirectoriesAndCoalescesSettings() {
        val state = MagoSettings.getInstance().state
        val oldPath = state.magoPath
        val oldRoot = state.executionRoot
        val callback = state.onPathChanged
        val job = SupervisorJob()
        val service = MagoVersionService(CoroutineScope(Dispatchers.Default + job))
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
        val state = MagoSettings.getInstance().state
        val originalPath = state.magoPath
        val originalRoot = state.executionRoot
        val job = SupervisorJob()
        val service = MagoVersionService(CoroutineScope(Dispatchers.Default + job))
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

    fun testReleaseParsingAndTestedBounds() {
        assertEquals(MagoVersion(1, 59, 0), MagoVersion.parse("mago 1.59.0"))
        assertTrue(MagoVersion.parse("Mago v1.60.1")!!.isTested)
        assertTrue(MagoVersion.parse("mago 1.59.0")!!.isTested)
        assertFalse(MagoVersion.parse("mago 1.58.9")!!.isTested)
        assertFalse(MagoVersion.parse("mago 1.60.2")!!.isTested)
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
        val settings = MagoSettings.getInstance()
        val original = settings.state.magoPath
        val job = SupervisorJob()
        val service = MagoVersionService(CoroutineScope(Dispatchers.Default + job))
        val connection = ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
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

    fun testInspectionDescriptionUsesCachedVersionAndFallback() {
        val settings = MagoSettings.getInstance()
        val original = settings.state.magoPath
        val service = MagoVersionService.getInstance()
        try {
            settings.state.magoPath = ""
            val token = service.beginValidation("")
            service.finish(token, "", MagoValidation.Result.Success("mago 1.60.1"))
            assertTrue(MagoLintInspection().loadDescription()!!.contains("/1.60.1/en/tools/linter/"))
            val failure = service.beginValidation("")
            service.finish(failure, "", MagoValidation.Result.Failure("failed"))
            assertTrue(MagoLintInspection().loadDescription()!!.contains("/latest/en/tools/linter/"))
        } finally {
            settings.state.magoPath = original
            if (original.isBlank()) service.refresh(settings.state.snapshot())
        }
    }
}
