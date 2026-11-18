package com.github.downace.stormfox

import com.github.downace.stormfox.settings.MagoConfigurable
import com.github.downace.stormfox.settings.MagoSettings
import com.github.downace.stormfox.status.MagoStatusBarWidget
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.GridBagLayout
import java.awt.Point
import java.awt.Container
import javax.swing.SwingUtilities
import javax.swing.JCheckBox
import javax.swing.JTextField

class MagoConfigurableLifecycleTest : BasePlatformTestCase() {
    fun testFormatterAndInspectionRowsShareAnIndentedLeadingEdge() {
        val configurable = MagoConfigurable(project)
        try {
            val root = configurable.createComponent() as Container
            layoutTree(root)
            val ui = MagoConfigurable::class.java.getDeclaredField("ui").apply { isAccessible = true }.get(configurable)
            val formatter = ui.javaClass.getDeclaredField("formatterBlock").apply { isAccessible = true }.get(ui) as javax.swing.JPanel
            val inspections = ui.javaClass.getDeclaredField("inspectionsBlock").apply { isAccessible = true }.get(ui) as javax.swing.JPanel
            val formatterLayout = formatter.layout as GridBagLayout
            val inspectionsLayout = inspections.layout as GridBagLayout
            val formatterContent = formatterLayout.getConstraints(formatter.getComponent(1))
            val onSave = formatterLayout.getConstraints(formatter.getComponent(4))
            val inspectionContent = inspectionsLayout.getConstraints(inspections.getComponent(1))
            val selectorLabel = (formatter.getComponent(1) as Container).getComponent(0)
            val onSaveLabel = (formatter.getComponent(4) as Container).getComponent(0)
            val firstInspectionLink = (inspections.getComponent(1) as Container).getComponent(0)

            assertTrue(formatterContent.insets.left > 0)
            assertEquals(formatterContent.insets.left, onSave.insets.left)
            assertEquals(formatterContent.insets.left, inspectionContent.insets.left)
            assertEquals(0, formatterLayout.getConstraints(formatter.getComponent(0)).insets.left)
            assertEquals(0, inspectionsLayout.getConstraints(inspections.getComponent(0)).insets.left)
            assertEquals(0, formatterLayout.getConstraints(formatter.getComponent(2)).insets.top)
            assertEquals(2, formatterLayout.getConstraints(formatter.getComponent(3)).insets.top)
            assertEquals(
                SwingUtilities.convertPoint(selectorLabel, Point(0, 0), formatter).x,
                SwingUtilities.convertPoint(onSaveLabel, Point(0, 0), formatter).x,
            )
            assertEquals(
                SwingUtilities.convertPoint(selectorLabel, Point(0, 0), formatter).x,
                SwingUtilities.convertPoint(firstInspectionLink, Point(0, 0), inspections).x,
            )
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun layoutTree(container: Container) {
        container.setSize(container.preferredSize)
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutTree)
    }

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
        val state = MagoSettings.getInstance(project).state
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

    fun testStatusWidgetPreferenceParticipatesInResetApplyAndModifiedState() {
        val configurable = MagoConfigurable(project)
        val state = MagoSettings.getInstance(project).state
        val original = state.showStatusWidget
        try {
            state.showStatusWidget = true
            configurable.createComponent()
            val ui = MagoConfigurable::class.java.getDeclaredField("ui").apply { isAccessible = true }.get(configurable)
            val checkbox = ui.javaClass.getDeclaredField("showStatusWidgetCheckBox").apply { isAccessible = true }
                .get(ui) as JCheckBox

            assertTrue(checkbox.isSelected)
            assertFalse(configurable.isModified())
            checkbox.isSelected = false
            assertTrue(configurable.isModified())
            configurable.apply()
            assertFalse(state.showStatusWidget)
            assertFalse(configurable.isModified())

            checkbox.isSelected = true
            configurable.reset()
            assertFalse(checkbox.isSelected)
            assertFalse(configurable.isModified())
        } finally {
            state.showStatusWidget = original
            MagoStatusBarWidget.refreshOpenProjects()
            configurable.disposeUIResources()
        }
    }

    fun testFormatterCheckboxesAreIndependentAndMigrateLegacyValue() {
        val configurable = MagoConfigurable(project)
        val state = MagoSettings.getInstance(project).state
        val originalPhp = state.enabled
        val originalTwig = state.twigEnabled
        try {
            state.enabled = false
            state.twigEnabled = null
            configurable.createComponent()
            val ui = MagoConfigurable::class.java.getDeclaredField("ui").apply { isAccessible = true }.get(configurable)
            val php = ui.javaClass.getDeclaredField("phpFormatterCheckBox").apply { isAccessible = true }
                .get(ui) as JCheckBox
            val twig = ui.javaClass.getDeclaredField("twigFormatterCheckBox").apply { isAccessible = true }
                .get(ui) as JCheckBox

            assertFalse(php.isSelected)
            assertFalse(twig.isSelected)
            assertFalse(configurable.isModified())

            twig.isSelected = true
            assertTrue(configurable.isModified())
            configurable.apply()
            assertFalse(state.enabled)
            assertEquals(true, state.twigEnabled)
            assertFalse(configurable.isModified())

            php.isSelected = true
            twig.isSelected = false
            configurable.apply()
            assertTrue(state.enabled)
            assertEquals(false, state.twigEnabled)
            assertFalse(configurable.isModified())

            php.isSelected = false
            twig.isSelected = true
            configurable.reset()
            assertTrue(php.isSelected)
            assertFalse(twig.isSelected)
            assertFalse(configurable.isModified())
        } finally {
            state.enabled = originalPhp
            state.twigEnabled = originalTwig
            configurable.disposeUIResources()
        }
    }
}
