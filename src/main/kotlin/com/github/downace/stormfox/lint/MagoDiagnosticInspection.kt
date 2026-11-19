package com.github.downace.stormfox.lint

import com.github.downace.stormfox.versionedMagoDocumentation
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ExternalAnnotatorInspectionVisitor
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.codeInspection.ex.GlobalInspectionContextBase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.codeInsight.daemon.impl.SeverityRegistrar
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.DefaultComboBoxModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener

abstract class MagoDiagnosticInspection(private val source: MagoTool) : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
    @JvmField var overrideSeverityLevels: Boolean = false
    @JvmField var noteSeverity: String = "TYPO"
    @JvmField var helpSeverity: String = "WEAK WARNING"
    @JvmField var warningSeverity: String = "WARNING"
    @JvmField var errorSeverity: String = "ERROR"

    init {
        ApplicationManager.getApplication().getService(MagoInspectionResultsLifetime::class.java)
    }

    override fun getShortName(): String = source.shortName
    override fun getDisplayName(): String = source.displayName
    override fun getGroupDisplayName(): String = "Quality tools"
    override fun getGroupPath(): Array<String> = arrayOf(if (source == MagoTool.TWIG_LINT) "Twig" else "PHP", groupDisplayName)
    override fun isEnabledByDefault(): Boolean = false
    override fun loadDescription(): String? =
        javaClass.classLoader.getResourceAsStream("inspectionDescriptions/${source.shortName}.html")
            ?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                versionedMagoDocumentation(reader.readText(), null)
            }

    /** No-editor collection also serves highlighting passes, so only this explicit entrypoint may wait for batch results. */
    override fun checkFile(file: PsiFile, context: GlobalInspectionContext, manager: InspectionManager): Array<ProblemDescriptor> =
        ExternalAnnotatorInspectionVisitor.checkFileWithExternalAnnotator(
            file, manager, false, MagoDiagnosticAnnotator(source, true, (context as? GlobalInspectionContextBase)?.currentProfile))

    override fun createOptionsPanel(): JPanel {
        val combos = mutableListOf<Pair<JComboBox<HighlightSeverity>, () -> String>>()
        lateinit var optionsPanel: JPanel
        val override = JBCheckBox("Override severity levels", overrideSeverityLevels)
        override.addItemListener { overrideSeverityLevels = override.isSelected }
        fun registeredSeverities(project: Project?): List<HighlightSeverity> {
            val registrar = SeverityRegistrar.getSeverityRegistrar(project)
            val severities = registrar.allSeverities.toMutableList()
            if (severities.none { it.name == HighlightSeverity.INFORMATION.name }) severities.add(HighlightSeverity.INFORMATION)
            severities.sortWith { left, right -> -registrar.compare(left, right) }
            return severities
        }
        fun addChoice(current: () -> String, update: (String) -> Unit): JComboBox<HighlightSeverity> {
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
            override.addItemListener { combo.isEnabled = override.isSelected }
            return combo
        }
        fun refreshChoices() {
            val project = DataManager.getInstance().getDataContext(optionsPanel).getData(CommonDataKeys.PROJECT)
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
        optionsPanel = panel {
            row { cell(override) }
            row("Error:") {
                val combo = addChoice({ errorSeverity }) { errorSeverity = it }
                combos.add(combo to { errorSeverity })
                cell(combo).align(Align.FILL).resizableColumn()
            }
            row("Warning:") {
                val combo = addChoice({ warningSeverity }) { warningSeverity = it }
                combos.add(combo to { warningSeverity })
                cell(combo).align(Align.FILL).resizableColumn()
            }
            row("Help:") {
                val combo = addChoice({ helpSeverity }) { helpSeverity = it }
                combos.add(combo to { helpSeverity })
                cell(combo).align(Align.FILL).resizableColumn()
            }
            row("Note:") {
                val combo = addChoice({ noteSeverity }) { noteSeverity = it }
                combos.add(combo to { noteSeverity })
                cell(combo).align(Align.FILL).resizableColumn()
            }
        }
        optionsPanel.addHierarchyListener(HierarchyListener { event ->
            if (event.changeFlags and (HierarchyEvent.PARENT_CHANGED or HierarchyEvent.SHOWING_CHANGED).toLong() != 0L) refreshChoices()
        })
        return optionsPanel
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

}
