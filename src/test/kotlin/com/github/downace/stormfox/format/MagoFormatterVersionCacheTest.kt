package com.github.downace.stormfox.format

import com.github.downace.stormfox.MagoVersion
import com.github.downace.stormfox.MagoVersionService
import com.github.downace.stormfox.MagoFailureLog
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class MagoFormatterVersionCacheTest : BasePlatformTestCase() {
    fun testOnlyCurrentValidVersionRecoversValidationFailureEvenWhenUnchanged() {
        val state = MagoSettings.getInstance(project).state
        val oldCommand = state.magoPath
        val oldRoot = state.executionRoot
        val job = SupervisorJob()
        val service = MagoVersionService(project, CoroutineScope(Dispatchers.Default + job))
        val events = mutableListOf<MagoFailureLog.Level>()
        val failureLog = MagoFailureLog({ level, _ -> events.add(level) })
        service.failureLogForTest = failureLog
        val directory = File("formatter-project/../formatter-project").absoluteFile
        try {
            state.magoPath = "mago"
            state.executionRoot = "/app"
            val initial = service.beginValidation("mago", directory, "/app")
            service.finish(initial, "mago", MagoValidation.Result.Success("mago 1.25.0"), directory, "/app")
            val key = MagoFailureLog.context("mago", directory.absoluteFile.normalize().path, "/app")
            failureLog.failure(key, "validation", "version", "failed")
            val stale = service.beginValidation("mago", directory, "/app")
            val current = service.beginValidation("mago", directory, "/app")
            service.finish(stale, "mago", MagoValidation.Result.Success("mago 1.25.0"), directory, "/app")
            assertEquals(listOf(MagoFailureLog.Level.WARN), events)
            service.finish(current, "mago", MagoValidation.Result.Success("mago 1.25.0"), directory, "/app")
            assertEquals(listOf(MagoFailureLog.Level.WARN, MagoFailureLog.Level.INFO), events)
        } finally {
            state.magoPath = oldCommand
            state.executionRoot = oldRoot
            job.cancel()
        }
    }

    fun testOnlyAcceptedChangedVersionsAreAnnounced() {
        val state = MagoSettings.getInstance(project).state
        val oldCommand = state.magoPath
        val oldRoot = state.executionRoot
        val job = SupervisorJob()
        val service = MagoVersionService(project, CoroutineScope(Dispatchers.Default + job))
        val accepted = mutableListOf<MagoVersion>()
        service.acceptedVersionForTest = { accepted.add(it) }
        try {
            state.magoPath = "mago"
            state.executionRoot = ""
            val stale = service.beginValidation("mago")
            val current = service.beginValidation("mago")
            service.finish(stale, "mago", MagoValidation.Result.Success("mago 1.18.0"))
            assertTrue(accepted.isEmpty())
            service.finish(current, "mago", MagoValidation.Result.Success("mago 1.25.0"))
            service.cachedVersion()
            val same = service.beginValidation("mago")
            service.finish(same, "mago", MagoValidation.Result.Success("mago 1.25.0"))
            val changed = service.beginValidation("mago")
            service.finish(changed, "mago", MagoValidation.Result.Success("mago 1.26.0"))
            assertEquals(listOf(MagoVersion(1, 25, 0), MagoVersion(1, 26, 0)), accepted)
        } finally {
            state.magoPath = oldCommand
            state.executionRoot = oldRoot
            job.cancel()
        }
    }

    fun testExplicitLookupRequiresMatchingCommandDirectoryAndRoot() {
        val state = MagoSettings.getInstance(project).state
        val oldCommand = state.magoPath
        val oldRoot = state.executionRoot
        val job = SupervisorJob()
        val service = MagoVersionService(project, CoroutineScope(Dispatchers.Default + job))
        val directory = File("formatter-project").absoluteFile
        try {
            state.magoPath = "docker compose exec app mago"
            state.executionRoot = "/app"
            val token = service.beginValidation(state.magoPath, directory, "/app")
            service.finish(token, state.magoPath, MagoValidation.Result.Success("mago 1.18.0"), directory, "/app")

            assertEquals(MagoVersion(1, 18, 0), service.cachedVersion(state.magoPath, directory, "/app"))
            assertNull(service.cachedVersion("other mago", directory, "/app"))
            assertNull(service.cachedVersion(state.magoPath, File("other-project"), "/app"))
            assertNull(service.cachedVersion(state.magoPath, directory, "/other"))
        } finally {
            state.executionRoot = oldRoot
            state.magoPath = oldCommand
            job.cancel()
        }
    }
}
