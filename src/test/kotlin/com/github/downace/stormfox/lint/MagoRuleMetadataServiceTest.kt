package com.github.downace.stormfox.lint

import com.github.downace.stormfox.MagoVersionListener
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MagoRuleMetadataServiceTest : BasePlatformTestCase() {
    fun testSingleFlightNegativeCacheAndVersionRecovery() {
        val settings = MagoSettings.getInstance().state
        val oldCommand = settings.magoPath
        val oldRoot = settings.executionRoot
        val service = project.getService(MagoRuleMetadataService::class.java)
        val directory = File(project.basePath ?: ".")
        val started = LinkedBlockingQueue<Int>()
        val gate = CompletableDeferred<Map<String, MagoRuleMetadata>?>()
        var count = 0
        try {
            settings.magoPath = "metadata-test"
            settings.executionRoot = ""
            service.refreshForTest = {}
            service.loadForTest = {
                started.add(++count)
                gate.await()
            }
            repeat(10) { service.request("metadata-test", directory, "") }
            assertEquals(1, started.poll(5, TimeUnit.SECONDS))
            gate.complete(null)
            Thread.sleep(100)
            repeat(10) { service.request("metadata-test", directory, "") }
            assertNull(started.poll(200, TimeUnit.MILLISECONDS))
            val refreshed = LinkedBlockingQueue<Boolean>()
            service.refreshForTest = { refreshed.add(true) }
            service.loadForTest = {
                started.add(++count)
                mapOf("x" to MagoRuleMetadata("x", name = "Recovered"))
            }
            ApplicationManager.getApplication().messageBus.syncPublisher(MagoVersionListener.TOPIC).versionChanged()
            refreshed.poll(5, TimeUnit.SECONDS)
            service.request("metadata-test", directory, "")
            assertEquals(2, started.poll(5, TimeUnit.SECONDS))
            assertEquals(true, refreshed.poll(5, TimeUnit.SECONDS))
            assertEquals("Recovered", service.cached("x", "metadata-test", directory, "")?.name)
        } finally {
            service.loadForTest = null
            service.refreshForTest = null
            settings.magoPath = oldCommand
            settings.executionRoot = oldRoot
        }
    }

    fun testOldAResultCannotReplaceNewAAfterB() {
        val settings = MagoSettings.getInstance().state
        val oldCommand = settings.magoPath
        val oldRoot = settings.executionRoot
        val service = project.getService(MagoRuleMetadataService::class.java)
        val directory = File(project.basePath ?: ".")
        val started = LinkedBlockingQueue<String>()
        val first = CompletableDeferred<Map<String, MagoRuleMetadata>?>()
        val refreshed = LinkedBlockingQueue<Boolean>()
        var aCount = 0
        try {
            settings.executionRoot = ""
            service.refreshForTest = { refreshed.add(true) }
            service.loadForTest = { key ->
                started.add(key.command)
                if (key.command == "a" && ++aCount == 1) withContext(NonCancellable) { first.await() }
                else mapOf("x" to MagoRuleMetadata("x", name = key.command))
            }
            settings.magoPath = "a"
            service.request("a", directory, "")
            assertEquals("a", started.poll(5, TimeUnit.SECONDS))
            settings.magoPath = "b"
            service.request("b", directory, "")
            assertEquals("b", started.poll(5, TimeUnit.SECONDS))
            settings.magoPath = "a"
            service.request("a", directory, "")
            assertEquals("a", started.poll(5, TimeUnit.SECONDS))
            while (service.cached("x", "a", directory, "") == null) {
                assertNotNull("refresh timed out", refreshed.poll(5, TimeUnit.SECONDS))
            }
            first.complete(mapOf("x" to MagoRuleMetadata("x", name = "stale")))
            Thread.sleep(100)
            assertEquals("a", service.cached("x", "a", directory, "")?.name)
            assertNull(service.cached("x", "b", directory, ""))
        } finally {
            first.complete(null)
            service.loadForTest = null
            service.refreshForTest = null
            settings.magoPath = oldCommand
            settings.executionRoot = oldRoot
        }
    }
}
