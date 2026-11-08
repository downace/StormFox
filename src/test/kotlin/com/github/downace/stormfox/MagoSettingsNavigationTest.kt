package com.github.downace.stormfox

import com.github.downace.stormfox.settings.MagoConfigurable
import com.intellij.openapi.extensions.ProjectExtensionPointName
import com.intellij.openapi.options.ConfigurableEP
import com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil
import com.intellij.profile.codeInspection.ui.ErrorsConfigurable
import com.intellij.profile.codeInspection.ui.ErrorsConfigurableProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.HyperlinkLabel
import java.awt.Component
import java.awt.Container

class MagoSettingsNavigationTest : BasePlatformTestCase() {
    fun testRegisteredProjectConfigurableUsesPhpInspectionNavigationLink() {
        val extension = ProjectExtensionPointName<ConfigurableEP<*>>("com.intellij.projectConfigurable")
            .getPoint(project).extensionList.single { it.instanceClass == MagoConfigurable::class.java.name }
        val configurable = extension.createConfigurable() as MagoConfigurable
        try {
            val component = configurable.createComponent()
            val links = descendants(component).filterIsInstance<HyperlinkLabel>()
                .filter { it.text.contains("Mago inspection") }.toList()
            assertEquals(1, links.size)
            val listeners = HyperlinkLabel::class.java.getDeclaredField("myListeners").apply {
                isAccessible = true
            }.get(links.single()) as List<*>
            assertTrue(
                "The inspection link must use PHP's settings navigation helper",
                listeners.any {
                    it?.javaClass?.name?.startsWith("com.jetbrains.php.lang.inspections.PhpInspectionsUtil$") == true
                },
            )
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testInspectionConfigurableCanBeCreatedFromProjectProviderBeforeOpeningSettings() {
        val configurable = ConfigurableExtensionPointUtil.createProjectConfigurableForProvider(
            project,
            ErrorsConfigurableProvider::class.java,
        ) as ErrorsConfigurable
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset()
            configurable.selectInspectionTool("MagoLint")
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) {
            for (child in component.components) yieldAll(descendants(child))
        }
    }
}
