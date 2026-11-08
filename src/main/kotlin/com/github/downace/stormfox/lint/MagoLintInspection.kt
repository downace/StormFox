package com.github.downace.stormfox.lint

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ui.InspectionOptionsPanel
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.codeInsight.daemon.impl.SeverityRegistrar
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.DefaultComboBoxModel
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener

class MagoLintInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
    @JvmField var overrideSeverityLevels: Boolean = false
    @JvmField var noteSeverity: String = "TYPO"
    @JvmField var helpSeverity: String = "WEAK WARNING"
    @JvmField var warningSeverity: String = "WARNING"
    @JvmField var errorSeverity: String = "ERROR"

    init {
        ApplicationManager.getApplication().getService(MagoInspectionResultsLifetime::class.java)
    }

    override fun getShortName(): String = SHORT_NAME
    override fun getDisplayName(): String = "Mago validation"
    override fun getGroupDisplayName(): String = "Quality tools"
    override fun getGroupPath(): Array<String> = arrayOf("PHP", groupDisplayName)
    override fun isEnabledByDefault(): Boolean = false

    @Suppress("DEPRECATION")
    override fun createOptionsPanel(): JPanel {
        val panel = InspectionOptionsPanel(this)
        val override = panel.addCheckboxEx("Override severity levels", "overrideSeverityLevels")
        val combos = mutableListOf<Pair<JComboBox<HighlightSeverity>, () -> String>>()
        fun registeredSeverities(project: Project?): List<HighlightSeverity> {
            val registrar = SeverityRegistrar.getSeverityRegistrar(project)
            val severities = registrar.allSeverities.toMutableList()
            if (severities.none { it.name == HighlightSeverity.INFORMATION.name }) severities.add(HighlightSeverity.INFORMATION)
            severities.sortWith { left, right -> -registrar.compare(left, right) }
            return severities
        }
        fun addChoice(label: String, current: () -> String, update: (String) -> Unit) {
            val severities = registeredSeverities(null)
            val model = DefaultComboBoxModel(severities.toTypedArray())
            val combo = JComboBox(model)
            combo.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: javax.swing.JList<*>?, value: Any?, index: Int,
                                                         isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
                    val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val severity = value as? HighlightSeverity
                    text = severity?.displayName?.replaceFirstChar { it.titlecase() }.orEmpty()
                    icon = severity?.let { HighlightDisplayLevel.find(it)?.icon }
                    return component
                }
            }
            combo.selectedItem = severities.firstOrNull { it.name == current() }
            combo.isEnabled = overrideSeverityLevels
            combo.addActionListener { (combo.selectedItem as? HighlightSeverity)?.let { update(it.name) } }
            panel.addLabeledRow(label, combo)
            override.addItemListener { combo.isEnabled = override.isSelected }
            combos.add(combo to current)
        }
        addChoice("Error", { errorSeverity }) { errorSeverity = it }
        addChoice("Warning", { warningSeverity }) { warningSeverity = it }
        addChoice("Help", { helpSeverity }) { helpSeverity = it }
        addChoice("Note", { noteSeverity }) { noteSeverity = it }
        fun refreshChoices() {
            val project = DataManager.getInstance().getDataContext(panel).getData(CommonDataKeys.PROJECT)
            if (project == null) return
            val severities = registeredSeverities(project)
            combos.forEach { (combo, current) ->
                val selected = current()
                val model = combo.model as DefaultComboBoxModel<HighlightSeverity>
                model.removeAllElements()
                severities.forEach(model::addElement)
                combo.selectedItem = severities.firstOrNull { it.name == selected }
            }
        }
        panel.addHierarchyListener(HierarchyListener { event ->
            if (event.changeFlags and (HierarchyEvent.PARENT_CHANGED or HierarchyEvent.SHOWING_CHANGED).toLong() != 0L) refreshChoices()
        })
        return panel
    }

    fun severityFor(level: String, defaultSeverity: HighlightSeverity, project: Project): HighlightSeverity {
        if (!overrideSeverityLevels) return defaultSeverity
        val (configured, fallback) = when (level) {
            "Note" -> noteSeverity to "TYPO"
            "Help" -> helpSeverity to "WEAK WARNING"
            "Warning" -> warningSeverity to "WARNING"
            "Error" -> errorSeverity to "ERROR"
            else -> return defaultSeverity
        }
        val registrar = SeverityRegistrar.getSeverityRegistrar(project)
        fun resolve(name: String): HighlightSeverity? =
            if (name == HighlightSeverity.INFORMATION.name) HighlightSeverity.INFORMATION else registrar.getSeverity(name)
        return resolve(configured) ?: resolve(fallback)
            ?: (if (level == "Note") resolve("WEAK WARNING") else null)
            ?: defaultSeverity
    }

    companion object {
        const val SHORT_NAME = "MagoLint"
    }
}
