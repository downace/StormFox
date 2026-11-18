package com.github.downace.stormfox

import com.github.downace.stormfox.settings.MagoConfigurable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableGroup
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.extensions.ProjectExtensionPointName
import com.intellij.openapi.options.ConfigurableEP
import com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.profile.codeInspection.ui.ErrorsConfigurable
import com.intellij.profile.codeInspection.ui.ErrorsConfigurableProvider
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.awt.Component
import java.awt.Container

class MagoSettingsNavigationTest : BasePlatformTestCase() {
    fun testRegisteredProjectConfigurableUsesPhpInspectionNavigationLink() {
        val extension = ProjectExtensionPointName<ConfigurableEP<*>>("com.intellij.projectConfigurable")
            .getPoint(project).extensionList.single { it.instanceClass == MagoConfigurable::class.java.name }
        val configurable = extension.createConfigurable() as MagoConfigurable
        try {
            val component = configurable.createComponent()
            val links = descendants(component).filterIsInstance<LinkLabel<*>>()
            assertEquals(listOf("Linter", "Analyzer", "Guard", "Twig Linter"), links.map { it.text }.toList())
            assertEquals(
                1,
                descendants(component).filterIsInstance<com.intellij.ui.components.JBLabel>()
                    .count { it.text == "Inspections" },
            )
            assertFalse(links.any { it.text.contains("Mago") || it.text.contains("inspection", ignoreCase = true) })
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
            configurable.selectInspectionTool("MagoAnalyze")
            configurable.selectInspectionTool("MagoGuard")
            configurable.selectInspectionTool("MagoTwigLint")
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testInspectionLinksSelectTheirToolAfterCurrentSettingsPageIsReady() {
        val delegate = ConfigurableExtensionPointUtil.createProjectConfigurableForProvider(
            project, ErrorsConfigurableProvider::class.java,
        ) as ErrorsConfigurable
        assertEquals("Errors", (delegate as SearchableConfigurable).id)
        val selected = mutableListOf<String>()
        var initialized = false
        val inspections = object : ErrorsConfigurable by delegate, SearchableConfigurable {
            override fun getId(): String = "Errors"
            override fun selectInspectionTool(selectedToolShortName: String) {
                assertTrue("Inspection UI must be initialized before selecting a tool", initialized)
                delegate.selectInspectionTool(selectedToolShortName)
                selected.add(selectedToolShortName)
            }
        }
        val group = object : ConfigurableGroup {
            override fun getConfigurables(): Array<Configurable> = arrayOf(inspections)
            override fun getDisplayName(): String = "Test"
        }
        var selection = AsyncPromise<Any>()
        val settings = object : Settings(listOf(group)) {
            override fun selectImpl(configurable: Configurable): Promise<in Any> {
                assertSame(inspections, configurable)
                return selection
            }
            override fun getConfigurableWithInitializedUiComponentImpl(configurable: Configurable, initializeUiComponentIfNotYet: Boolean): Configurable {
                assertSame(inspections, configurable)
                assertTrue(initializeUiComponentIfNotYet)
                if (!initialized) {
                    delegate.createComponent()
                    delegate.reset()
                    initialized = true
                }
                return configurable
            }
            override fun checkModifiedImpl(configurable: Configurable) {}
            override fun setSearchText(option: String) {}
        }
        val configurable = MagoConfigurable(project) { settings }
        try {
            val component = configurable.createComponent()
            val links = descendants(component).filterIsInstance<LinkLabel<*>>().toList()
            assertSame(inspections, settings.find(ErrorsConfigurable::class.java))
            val expected = listOf("MagoLint", "MagoAnalyze", "MagoGuard", "MagoTwigLint")
            links.forEachIndexed { index, link ->
                selection = AsyncPromise()
                link.doClick()
                assertEquals(expected.take(index), selected)
                selection.setResult(Any())
                assertEquals(expected.take(index + 1), selected)
            }
        } finally {
            configurable.disposeUIResources()
            delegate.disposeUIResources()
        }
    }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) {
            for (child in component.components) yieldAll(descendants(child))
        }
    }
}
