package com.github.downace.stormfox

import com.github.downace.stormfox.settings.MagoConfigurable
import com.github.downace.stormfox.settings.MagoInspectionProfileActionProvider
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.profile.codeInspection.ui.SingleInspectionProfilePanel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.github.downace.stormfox.lint.MagoLintInspection
import com.github.downace.stormfox.lint.MagoTwigLintInspection
import com.github.downace.stormfox.lint.MagoAnalyzeInspection
import com.github.downace.stormfox.lint.MagoGuardInspection
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableGroup
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.extensions.ProjectExtensionPointName
import com.intellij.openapi.options.ConfigurableEP
import com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.ide.actionsOnSave.ActionsOnSaveConfigurable
import com.intellij.profile.codeInspection.ui.ErrorsConfigurable
import com.intellij.profile.codeInspection.ui.ErrorsConfigurableProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.awt.Component
import java.awt.Container
import java.awt.event.HierarchyEvent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

class MagoSettingsNavigationTest : BasePlatformTestCase() {
    fun testInspectionDescriptionActionsSelectInCurrentProfile() {
        val configurable = ConfigurableExtensionPointUtil.createProjectConfigurableForProvider(
            project, ErrorsConfigurableProvider::class.java,
        ) as ErrorsConfigurable
        try {
            val component = requireNotNull(configurable.createComponent())
            configurable.reset()
            val sourcePanel = descendants(component).filterIsInstance<SingleInspectionProfilePanel>().first()
            var selected: String? = null
            val panel = object : SingleInspectionProfilePanel(ProjectInspectionProfileManager.getInstance(project), sourcePanel.profile) {
                override fun selectInspectionTool(name: String) { selected = name }
                override fun getSelectedTool(): InspectionToolWrapper<*, *>? =
                    when (selected) {
                        "MagoLint" -> LocalInspectionToolWrapper(MagoLintInspection())
                        "MagoTwigLint" -> LocalInspectionToolWrapper(MagoTwigLintInspection())
                        else -> null
                    }
            }
            val profile = panel.profile
            val actions = MagoInspectionProfileActionProvider().getProfilePanelActions(panel).associateBy { it.actionId() }
            for ((id, target) in listOf("stormfox.magoTwigLint" to "MagoTwigLint", "stormfox.magoLint" to "MagoLint")) {
                val action = actions.getValue(id).action()
                action.actionPerformed(AnActionEvent.createFromAnAction(action, null, "test", DataContext.EMPTY_CONTEXT))
                assertEquals(target, selected)
                assertSame(profile, panel.profile)
                val description = javaClass.classLoader.getResource("inspectionDescriptions/${if (target == "MagoLint") "MagoTwigLint" else "MagoLint"}.html")!!.readText()
                assertTrue(description.contains("action://$id"))
            }
        } finally {
            configurable.disposeUIResources()
        }
    }
    fun testRegisteredProjectConfigurableUsesPhpInspectionNavigationLink() {
        val extension = ProjectExtensionPointName<ConfigurableEP<*>>("com.intellij.projectConfigurable")
            .getPoint(project).extensionList.single { it.instanceClass == MagoConfigurable::class.java.name }
        val configurable = extension.createConfigurable() as MagoConfigurable
        try {
            val component = configurable.createComponent()
            val links = inspectionLinks(component)
            assertEquals(4, links.count())
            assertTrue(links.all { it.document.getText(0, it.document.length).contains("Configure inspection") })
            val headings = descendants(component).filterIsInstance<com.intellij.ui.components.JBLabel>()
                .mapNotNull { it.text }.filter { it in listOf("Linter", "Analyzer", "Guard", "Twig Linter") }.toList()
            assertEquals(listOf("Linter", "Analyzer", "Guard", "Twig Linter"), headings)
            assertFalse(descendants(component).filterIsInstance<com.intellij.ui.components.JBLabel>().any { it.text == "Inspections" })
            assertTrue(links.none { it.document.getText(0, it.document.length).contains("Mago") })
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testInspectionStatusesUseSavedProjectProfile() {
        myFixture.enableInspections(MagoLintInspection(), MagoAnalyzeInspection(), MagoGuardInspection(), MagoTwigLintInspection())
        val profile = ProjectInspectionProfileManager.getInstance(project).currentProfile
        val configurable = MagoConfigurable(project)
        try {
            val component = configurable.createComponent()
            val statuses = inspectionLinks(component)
            val names = listOf("MagoLint", "MagoAnalyze", "MagoGuard", "MagoTwigLint")
            assertEquals(4, statuses.size)
            val originals = names.associateWith { name ->
                profile.isToolEnabled(requireNotNull(HighlightDisplayKey.find(name)), null)
            }
            try {
                names.forEachIndexed { index, name ->
                    profile.setToolEnabled(name, false)
                    configurable.reset()
                    assertTrue(statuses[index].text.contains("<b>disabled</b>"))
                    profile.setToolEnabled(name, true)
                    configurable.reset()
                    assertTrue(statuses[index].text.contains("<b>enabled</b>"))
                }
            } finally {
                names.forEach { name ->
                    profile.setToolEnabled(name, originals.getValue(name))
                }
            }
            assertEquals(4, inspectionLinks(component).size)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testHidingSettingsPageDoesNotInitializeInspectionUi() {
        val configurable = MagoConfigurable(project) {
            error("Hiding the Mago settings page must not query Settings")
        }
        try {
            val component = configurable.createComponent()
            val event = HierarchyEvent(
                component as Container, HierarchyEvent.HIERARCHY_CHANGED, component, component,
                HierarchyEvent.SHOWING_CHANGED.toLong(),
            )
            component.hierarchyListeners.forEach { it.hierarchyChanged(event) }
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
            val links = inspectionLinks(component)
            assertSame(inspections, settings.find(ErrorsConfigurable::class.java))
            val expected = listOf("MagoLint", "MagoAnalyze", "MagoGuard", "MagoTwigLint")
            assertEquals(expected.size, links.size)
            links.forEachIndexed { index, link ->
                selection = AsyncPromise()
                activateInspectionLink(link, expected[index])
                assertEquals(expected.take(index), selected)
                selection.setResult(Any())
                assertEquals(expected.take(index + 1), selected)
            }
        } finally {
            configurable.disposeUIResources()
            delegate.disposeUIResources()
        }
    }

    fun testFormatterProseLinkSelectsActionsOnSaveInTheCurrentSettingsWindow() {
        val actionsOnSave = object : SearchableConfigurable {
            override fun getId(): String = ActionsOnSaveConfigurable.CONFIGURABLE_ID
            override fun getDisplayName(): String = "Actions on Save"
            override fun createComponent(): javax.swing.JComponent = JPanel()
            override fun isModified(): Boolean = false
            override fun apply() {}
            override fun reset() {}
        }
        val group = object : ConfigurableGroup {
            override fun getConfigurables(): Array<Configurable> = arrayOf(actionsOnSave)
            override fun getDisplayName(): String = "Test"
        }
        var selected: Configurable? = null
        val settings = object : Settings(listOf(group)) {
            override fun selectImpl(configurable: Configurable): Promise<in Any> {
                selected = configurable
                return AsyncPromise<Any>().apply { setResult(Any()) }
            }
            override fun getConfigurableWithInitializedUiComponentImpl(configurable: Configurable, initializeUiComponentIfNotYet: Boolean): Configurable = configurable
            override fun checkModifiedImpl(configurable: Configurable) {}
            override fun setSearchText(option: String) {}
        }
        val configurable = MagoConfigurable(project) { settings }
        try {
            val root = configurable.createComponent()
            val prose = descendants(root).filterIsInstance<JEditorPane>()
                .single { it.text.contains("Mago Formatter replaces") }
            val event = HyperlinkEvent(prose, HyperlinkEvent.EventType.ACTIVATED, null, "actionsOnSave")
            prose.hyperlinkListeners.forEach { it.hyperlinkUpdate(event) }
            assertSame(actionsOnSave, selected)
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

    private fun inspectionLinks(component: Component): List<JEditorPane> =
        descendants(component).filterIsInstance<JEditorPane>()
            .filter { it.text.contains("href=\"inspection:") }.toList()

    private fun activateInspectionLink(editor: JEditorPane, shortName: String) {
        val event = HyperlinkEvent(editor, HyperlinkEvent.EventType.ACTIVATED, null, "inspection:$shortName")
        editor.hyperlinkListeners.forEach { it.hyperlinkUpdate(event) }
    }
}
