package com.github.downace.stormfox

import com.github.downace.stormfox.settings.MagoConfigurable
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JTextField

class MagoConfigurableLifecycleTest : BasePlatformTestCase() {
    fun testConstructorDoesNotAllocateSettingsUi() {
        val configurable = MagoConfigurable(project)
        assertFalse(configurable.isModified())
        val ui = MagoConfigurable::class.java.declaredFields.single { it.type.simpleName == "SettingsUi" }
        ui.isAccessible = true
        assertNull(ui.get(configurable))
        configurable.apply()
        configurable.reset()
        configurable.disposeUIResources()
    }

    fun testUiResourcesCanBeDisposedAndRecreated() {
        val configurable = MagoConfigurable(project)
        val first = configurable.createComponent()
        assertSame(first, configurable.createComponent())

        configurable.disposeUIResources()
        assertFalse(configurable.isModified())

        val recreated = configurable.createComponent()
        assertNotSame(first, recreated)
        configurable.reset()
        configurable.disposeUIResources()
    }

    fun testExecutionRootParticipatesInResetApplyAndModifiedState() {
        val configurable = MagoConfigurable(project)
        val state = MagoSettings.getInstance().state
        val originalRoot = state.executionRoot
        try {
            state.executionRoot = "/stored"
            configurable.createComponent()
            val ui = MagoConfigurable::class.java.getDeclaredField("ui").apply { isAccessible = true }.get(configurable)
            val rootField = ui.javaClass.getDeclaredField("executionRootField").apply { isAccessible = true }
                .get(ui) as JTextField

            assertEquals("/stored", rootField.text)
            assertFalse(configurable.isModified())
            rootField.text = "/app"
            assertTrue(configurable.isModified())
            configurable.apply()
            assertEquals("/app", state.executionRoot)
            assertFalse(configurable.isModified())

            rootField.text = "/changed"
            configurable.reset()
            assertEquals("/app", rootField.text)
            assertFalse(configurable.isModified())
        } finally {
            state.executionRoot = originalRoot
            configurable.disposeUIResources()
        }
    }
}
