package com.github.downace.stormfox

import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MagoSettingsStorageTest {
    @Test
    fun localExecutableAndConfigPathsDoNotRoam() {
        val storage = MagoSettings::class.java.getAnnotation(State::class.java).storages.single()
        assertEquals("mago.xml", storage.value)
        assertEquals(RoamingType.DISABLED, storage.roamingType)
    }
}

class MagoSettingsSerializationTest : BasePlatformTestCase() {
    fun testPersistedColdLoadDoesNotLookUpSettingsWhileLoading() {
        // Ensure loadState takes the branch used when the version service already exists.
        val service = MagoVersionService.getInstance()
        val original = MagoSettings.getInstance().state.snapshot()
        val xml = JDOMUtil.load("""<State><option name="magoPath" value="mago" /><option name="executionRoot" value="/app" /></State>""")
        val loaded = XmlSerializer.deserialize(xml, MagoSettings.State::class.java)
        val component = MagoSettings()
        val executor = Executors.newSingleThreadExecutor { action -> Thread(action, "mago-cold-load-test").apply { isDaemon = true } }
        try {
            MagoSettings.lookupGuard = {
                check(Thread.currentThread().name != "mago-cold-load-test") {
                    "Settings service lookup during persisted loadState"
                }
            }
            executor.submit { component.loadState(loaded) }.get(5, TimeUnit.SECONDS)
            assertEquals("mago", component.state.magoPath)
            assertEquals("/app", component.state.executionRoot)
        } finally {
            MagoSettings.lookupGuard = null
            executor.shutdownNow()
            service.refresh(original)
        }
    }

    fun testAttachedStateRoundTrip() {
        val state = MagoSettings.getInstance().getState()
        assertNotNull(state.onPathChanged)
        val xml = XmlSerializer.serialize(state)
        val text = JDOMUtil.writeElement(xml)
        val restored = try {
            XmlSerializer.deserialize(xml, MagoSettings.State::class.java)
        } catch (e: Exception) {
            throw AssertionError("Cannot deserialize attached settings: $text", e)
        }
        assertFalse("Callback must not be persisted: $text", text.contains("onPathChanged"))
        assertEquals(state.magoPath, restored.magoPath)
        assertEquals(state.configPath, restored.configPath)
        assertEquals(state.executionRoot, restored.executionRoot)
        assertEquals(state.enabled, restored.enabled)
        assertEquals(state.showStatusWidget, restored.showStatusWidget)
    }

    fun testPreviouslySavedSettingsLoad() {
        assertPersistedSettingsLoad("")
    }

    fun testPreviouslySavedCallbackIsIgnored() {
        assertPersistedSettingsLoad("""<option name="onPathChanged"><Function1 /></option>""")
    }

    private fun assertPersistedSettingsLoad(callback: String) {
        val xml = JDOMUtil.load(
            """
            <State>
              <option name="magoPath" value="/tools/mago" />
              <option name="configPath" value="/project/mago.toml" />
              <option name="executionRoot" value="/project" />
              <option name="enabled" value="false" />
              <option name="showStatusWidget" value="false" />
              $callback
            </State>
            """.trimIndent(),
        )
        val state = XmlSerializer.deserialize(xml, MagoSettings.State::class.java)
        assertEquals("/tools/mago", state.magoPath)
        assertEquals("/project/mago.toml", state.configPath)
        assertEquals("/project", state.executionRoot)
        assertFalse(state.enabled)
        assertFalse(state.showStatusWidget)
        assertNull(state.onPathChanged)
    }
}
