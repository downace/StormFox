package com.github.downace.stormfox

import com.github.downace.stormfox.settings.MagoConfigurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase

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
}
