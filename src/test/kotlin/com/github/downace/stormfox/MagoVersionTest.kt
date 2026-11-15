package com.github.downace.stormfox

import com.github.downace.stormfox.format.MagoValidation
import com.github.downace.stormfox.settings.MagoSettings
import com.github.downace.stormfox.lint.MagoLintInspection
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MagoVersionTest : BasePlatformTestCase() {
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
            service.refresh("different-path")
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
            if (original.isBlank()) service.refresh(original)
        }
    }
}
