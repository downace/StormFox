package com.github.downace.stormfox.format

import com.github.downace.stormfox.MagoVersion
import com.github.downace.stormfox.MagoVersionService
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class MagoFormatterVersionCacheTest : BasePlatformTestCase() {
    fun testExplicitLookupRequiresMatchingCommandDirectoryAndRoot() {
        val state = MagoSettings.getInstance().state
        val oldCommand = state.magoPath
        val oldRoot = state.executionRoot
        val job = SupervisorJob()
        val service = MagoVersionService(CoroutineScope(Dispatchers.Default + job))
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
