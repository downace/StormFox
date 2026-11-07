package com.github.downace.stormfox

import com.intellij.formatting.service.FormattingService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationDisplayType
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.ProjectExtensionPointName
import com.intellij.openapi.options.ConfigurableEP
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.github.downace.stormfox.format.MagoFormattingService
import com.github.downace.stormfox.settings.MagoSettings

/**
 * Runs against a real IDE instance with the plugin loaded, so it proves that the extension points
 * are registered and that the service behaves as expected, not just that the classes exist.
 */
class MagoPluginRegistrationTest : BasePlatformTestCase() {

    fun testFormattingServiceIsRegistered() {
        assertTrue(
            "MagoFormattingService is not registered",
            FormattingService.EP_NAME.extensionList.any { it is MagoFormattingService },
        )
    }

    fun testNotificationGroupIsRegistered() {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Mago")
        assertNotNull("Mago notification group is not registered", group)
        assertEquals(NotificationDisplayType.BALLOON, group.displayType)
    }

    fun testPluginDoesNotRegisterItsOwnActionsOnSaveEntry() {
        val extensionPoints = listOf(
            "com.intellij.actionOnSave",
            "com.intellij.actionOnSaveInfoProvider",
        )

        for (name in extensionPoints) {
            val implementations = ExtensionPointName<Any>(name).extensionList.map { it.javaClass.name }
            val ours = implementations.filter { it.startsWith(PLUGIN_PACKAGE) }
            assertTrue(
                "The plugin must not register an Actions on Save entry, but '$name' registers $ours",
                ours.isEmpty(),
            )
        }
    }

    fun testConfigurableIsRegisteredUnderThePhpQualityToolsPage() {
        val configurables = ProjectExtensionPointName<ConfigurableEP<*>>("com.intellij.projectConfigurable").getPoint(project)
        val ours = configurables.extensionList.filter { it.instanceClass == "com.github.downace.stormfox.settings.MagoConfigurable" }

        assertEquals(1, ours.size)
        assertEquals("settings.php.quality.tools", ours.single().parentId)
        assertEquals("settings.php.quality.tools.php.mago.formatter", ours.single().id)
    }

    fun testServiceOnlyClaimsPhpFilesWhenEnabled() {
        val service = MagoFormattingService()
        val settings = MagoSettings.getInstance().state
        val original = settings.enabled

        try {
            settings.enabled = false
            val phpFile = myFixture.configureByText("Sample.php", "<?php\nfunction  foo( \$a ) {}\n")
            assertFalse("The service must not claim PHP files while it is turned off", service.canFormat(phpFile))

            settings.enabled = true
            assertTrue("The service must claim PHP files while it is turned on", service.canFormat(phpFile))
        } finally {
            settings.enabled = original
        }
    }

    private companion object {
        const val PLUGIN_PACKAGE = "com.github.downace.stormfox"
    }
}
