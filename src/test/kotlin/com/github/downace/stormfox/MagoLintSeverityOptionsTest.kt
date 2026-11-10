package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoLintInspection
import com.intellij.codeInsight.daemon.impl.SeverityRegistrar
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element
import javax.swing.JComboBox
import javax.swing.JCheckBox
import java.awt.Container
import javax.swing.JPanel
import javax.swing.JList

class MagoLintSeverityOptionsTest : BasePlatformTestCase() {
    fun testDisabledOverrideKeepsProfileSeverityAndDefaultsArePersistable() {
        val inspection = MagoLintInspection()
        assertFalse(inspection.overrideSeverityLevels)
        val profileSeverity = HighlightSeverity.WARNING
        assertSame(profileSeverity, inspection.severityFor("Error", profileSeverity, project))
        assertSame(profileSeverity, inspection.severityFor("unrecognized", profileSeverity, project))

        inspection.overrideSeverityLevels = true
        inspection.noteSeverity = "WARNING"
        inspection.helpSeverity = "TYPO"
        inspection.warningSeverity = "ERROR"
        inspection.errorSeverity = "WEAK WARNING"
        val settings = Element("settings")
        inspection.writeSettings(settings)
        val restored = MagoLintInspection().apply { readSettings(settings) }
        assertTrue(restored.overrideSeverityLevels)
        assertEquals(inspection.noteSeverity, restored.noteSeverity)
        assertEquals(inspection.helpSeverity, restored.helpSeverity)
        assertEquals(inspection.warningSeverity, restored.warningSeverity)
        assertEquals(inspection.errorSeverity, restored.errorSeverity)
    }

    fun testOptionsStartDisabledAndControlsEditCanonicalNames() {
        val inspection = MagoLintInspection()
        val panel = inspection.createOptionsPanel()
        val controls = descendants(panel)
        val checkbox = controls.filterIsInstance<JCheckBox>().single()
        val combos = controls.filterIsInstance<JComboBox<*>>()
        assertEquals(4, combos.size)
        assertEquals(listOf("ERROR", "WARNING", "WEAK WARNING", "TYPO"),
            combos.map { (it.selectedItem as HighlightSeverity).name })
        assertTrue(combos.none { it.isEnabled })

        checkbox.doClick()
        assertTrue(inspection.overrideSeverityLevels)
        assertTrue(combos.all { it.isEnabled })
        val warningCombo = combos[1]
        warningCombo.selectedItem = HighlightSeverity.INFORMATION
        assertEquals(HighlightSeverity.INFORMATION.name, inspection.warningSeverity)

        checkbox.doClick()
        assertFalse(inspection.overrideSeverityLevels)
        assertTrue(combos.none { it.isEnabled })
    }

    fun testSeverityChoicesCapitalizeDisplayNames() {
        val panel = MagoLintInspection().createOptionsPanel()
        val combos = descendants(panel).filterIsInstance<JComboBox<*>>()
        val combo = combos.first()
        val renderer = combo.renderer as javax.swing.ListCellRenderer<Any>
        val rendered = renderer.getListCellRendererComponent(
            JList<Any>(), HighlightSeverity.WEAK_WARNING, -1, false, false,
        ) as javax.swing.JLabel
        assertEquals(HighlightSeverity.WEAK_WARNING.displayName.replaceFirstChar { it.titlecase() }, rendered.text)
        val typo = renderer.getListCellRendererComponent(
            JList<Any>(), (0 until combo.itemCount).map(combo::getItemAt)
                .filterIsInstance<HighlightSeverity>().first { it.name == "TYPO" }, -1, false, false,
        ) as javax.swing.JLabel
        assertEquals("Typo", typo.text)
    }

    @Suppress("DEPRECATION")
    fun testProjectRegisteredSeverityAppearsInChoicesWhenProjectContextIsAvailable() {
        val registrar = SeverityRegistrar.getSeverityRegistrar(project)
        val custom = HighlightSeverity("StormFox project choice", 250)
        registrar.registerSeverity(
            SeverityRegistrar.SeverityBasedTextAttributes(
                TextAttributes(),
                HighlightInfoType.HighlightInfoTypeImpl(custom, CodeInsightColors.WARNINGS_ATTRIBUTES),
            ), null,
        )
        try {
            val panel = MagoLintInspection().createOptionsPanel()
            DataManager.registerDataProvider(panel, DataProvider { dataId ->
                if (dataId == CommonDataKeys.PROJECT.name) project else null
            })
            JPanel().add(panel)
            val combos = descendants(panel).filterIsInstance<JComboBox<*>>()
            val model = combos.first().model
            assertTrue((0 until model.size).any { (model.getElementAt(it) as HighlightSeverity).name == custom.name })
        } finally {
            registrar.unregisterSeverity(custom)
        }
    }

    fun testConfiguredAndUnavailableSeveritiesResolveSafely() {
        val inspection = MagoLintInspection().apply { overrideSeverityLevels = true }
        val registrar = SeverityRegistrar.getSeverityRegistrar(project)
        val warning = registrar.getSeverity("WARNING")!!
        val error = registrar.getSeverity("ERROR")!!
        assertSame(warning, inspection.severityFor("Warning", HighlightSeverity.INFORMATION, project))
        assertSame(error, inspection.severityFor("Error", HighlightSeverity.INFORMATION, project))

        inspection.noteSeverity = "missing severity"
        val note = inspection.severityFor("Note", HighlightSeverity.ERROR, project)
        assertEquals(registrar.getSeverity("TYPO")?.name ?: registrar.getSeverity("WEAK WARNING")!!.name, note.name)
        inspection.warningSeverity = HighlightSeverity.INFORMATION.name
        assertSame(HighlightSeverity.INFORMATION, inspection.severityFor("Warning", HighlightSeverity.ERROR, project))
    }

    private fun descendants(container: Container): List<java.awt.Component> =
        container.components.flatMap { listOf(it) + (it as? Container)?.let(::descendants).orEmpty() }
}
