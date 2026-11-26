package com.github.downace.stormfox.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.actionsOnSave.ActionsOnSaveConfigurable
import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.ui.ErrorsConfigurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.Row
import com.intellij.util.ui.InlineIconButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.github.downace.stormfox.format.MagoValidation
import com.github.downace.stormfox.status.MagoStatusBarWidget
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ActionListener
import java.io.File
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

private const val NOT_VALIDATED_TEXT = "Not validated"
private const val CHECKING_TEXT = "Checking…"
private const val SECTION_TITLE = "Formatter"
private const val INSPECTIONS_CONFIGURABLE_ID = "Errors"
private const val COMMAND_HELP =
    """<html>Enter one of:
    <ul>
      <li>Binary name, for example <code>mago</code> (if it is in <code>PATH</code>)</li>
      <li>Full path, for example <code>/usr/local/bin/mago</code></li>
      <li>Command, for example:<br><code>docker run --rm -i -v .:/app -w /app ghcr.io/carthage-software/mago:1.60.1</code></li>
    </ul>
    Do not include a Mago subcommand.<br>For <code>docker run</code>, include <code>-i</code> and disable TTY allocation.</html>"""
private const val EXECUTION_ROOT_HELP =
    "If Mago runs in a container, enter the project path inside the container, for example <code>/app</code>."

private val SECONDARY_TEXT_COLOR: Color = UIUtil.getContextHelpForeground()
private val ERROR_TEXT_COLOR: Color = JBColor.namedColor("Label.errorForeground", UIUtil.getErrorForeground())

internal object MagoConfigurableHelp {
    const val LINT_MODE = """<ul>
        <li><b>Syntax and semantics only</b> (<code>--semantics</code>): Skip linter rules and only perform basic syntax and semantic validation. This mode only checks that your PHP code parses correctly and has valid semantic structure, without applying any style or quality rules. Useful for quick syntax validation.</li>
        <li><b>Normal</b> (no mode flag): Use the rules from the project configuration.</li>
        <li><b>All rules</b> (<code>--pedantic</code>): Enable every available linter rule for maximum thoroughness. This overrides your configuration and enables all rules, including those disabled by default. The output will be extremely verbose and is not recommended for regular use. Useful for comprehensive code audits.</li>
    </ul>"""
    const val TWIG_LINT_MODE = """<ul>
        <li><b>Syntax only</b> (<code>--semantics</code>): Skip linter rules and only check that templates parse.</li>
        <li><b>Normal</b> (no mode flag): Use the rules from the project configuration.</li>
        <li><b>All supported rules</b> (<code>--pedantic</code>): Enable every rule supported by the configured minimum Twig version.</li>
    </ul>"""
    const val GUARD_MODE = """<ul>
        <li><b>Both</b> (no mode flag): Run structural and perimeter checks.</li>
        <li><b>Structural</b> (<code>--structural</code>): Run only structural guard checks. When specified, only structural rules (naming conventions, modifiers, inheritance constraints) are checked. Perimeter rules are skipped.</li>
        <li><b>Perimeter</b> (<code>--perimeter</code>): Run only perimeter guard checks. When specified, only perimeter rules (dependency boundaries, layer restrictions) are checked. Structural rules are skipped.</li>
    </ul>"""

    data class Option(val title: String, val description: String)

    // HelpTooltip supports HTML in descriptions, but its title must remain plain text.
    fun option(flag: String, description: String) = Option(flag, "<p>$description</p>")
}

class MagoConfigurable @JvmOverloads constructor(
    private val project: Project,
    private val settingsForComponent: (JComponent) -> Settings? = {
        Settings.KEY.getData(DataManager.getInstance().getDataContext(it))
    },
) : Configurable {

    private var ui: SettingsUi? = null

    override fun getDisplayName(): String = "Mago"

    override fun createComponent(): JComponent {
        val settingsUi = ui ?: SettingsUi(project, project.getService(MagoSettingsValidationService::class.java).createSession(), settingsForComponent).also { ui = it }
        settingsUi.reset()
        return settingsUi.panel
    }

    override fun isModified(): Boolean = ui?.isModified() ?: false

    override fun apply() {
        ui?.apply()
    }

    override fun reset() {
        ui?.reset()
    }

    override fun disposeUIResources() {
        ui?.dispose()
        ui = null
    }

    private class SettingsUi(private val project: Project, private val validation: MagoSettingsValidationService.Session, private val settingsForComponent: (JComponent) -> Settings?) {

        fun dispose() = validation.dispose()

        private val phpFormatterCheckBox = JBCheckBox("PHP")
        private val twigFormatterCheckBox = JBCheckBox("Twig")
        private val showStatusWidgetCheckBox = JBCheckBox("Show Mago status bar widget")
        private val lintModeBox = javax.swing.JComboBox(
            arrayOf(MagoLintMode.SEMANTICS, MagoLintMode.NORMAL, MagoLintMode.PEDANTIC),
        )
        private val twigLintModeBox = javax.swing.JComboBox(
            arrayOf(MagoLintMode.SEMANTICS, MagoLintMode.NORMAL, MagoLintMode.PEDANTIC),
        )
        private val guardModeBox = javax.swing.JComboBox(MagoGuardMode.values())
        private val lintIgnoreBaseline = JBCheckBox("Ignore baseline")
        private val lintFixableOnly = JBCheckBox("Show only fixable issues")
        private val twigLintIgnoreBaseline = JBCheckBox("Ignore baseline")
        private val twigLintFixableOnly = JBCheckBox("Show only fixable issues")
        private val analyzeIgnoreBaseline = JBCheckBox("Ignore baseline")
        private val analyzeNoStubs = JBCheckBox("Disable built-in and library stubs")
        private val analyzeSkipIgnores = JBCheckBox("Report issues hidden by configured ignores")
        private val analyzeFixableOnly = JBCheckBox("Show only fixable issues")
        private val guardIgnoreBaseline = JBCheckBox("Ignore baseline")
        private val guardNoStubs = JBCheckBox("Disable built-in and library stubs")
        private val guardFixableOnly = JBCheckBox("Show only fixable issues")

        private val lintModeLabels = mapOf(
            MagoLintMode.SEMANTICS to "Syntax and semantics only",
            MagoLintMode.NORMAL to "Normal",
            MagoLintMode.PEDANTIC to "All rules",
        )
        private val guardModeLabels = mapOf(
            MagoGuardMode.BOTH to "Both",
            MagoGuardMode.STRUCTURAL to "Structural",
            MagoGuardMode.PERIMETER to "Perimeter",
        )

        private fun <T> javax.swing.JComboBox<T>.useLabels(labels: Map<T, String>) {
            setRenderer(object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
                ): Component {
                    return super.getListCellRendererComponent(
                        list, value?.let { candidate -> labels.entries.firstOrNull { it.key == candidate }?.value ?: candidate.toString() },
                        index, isSelected, cellHasFocus,
                    )
                }
            })
        }

        private val binaryField = TextFieldWithBrowseButton().apply {
            toolTipText = "Enter a binary name, a full path, or a command that starts Mago. Do not include a Mago subcommand."
        }

        private val executionRootField = JTextField().apply {
            toolTipText = "If Mago runs in a container, enter the project path inside the container, for example /app."
        }

        private val configField = TextFieldWithBrowseButton().apply {
            toolTipText =
                "Passed to mago as the --config flag. It overrides the mago.toml configuration of the project."
        }

        private val binaryRow = PathRow(
            field = binaryField,
            tooltip = "Check that the Mago command can be started",
            check = { binary, _, projectDirectory, executionRoot ->
                MagoValidation.checkBinary(binary, projectDirectory, executionRoot, project)
            },
        )

        private val configRow = PathRow(
            field = configField,
            tooltip = "Check that the Mago command accepts this configuration file",
            check = { binary, config, projectDirectory, executionRoot ->
                MagoValidation.checkConfig(binary, config, projectDirectory, executionRoot)
            },
        )

        init {
            lintModeBox.useLabels(lintModeLabels)
            twigLintModeBox.useLabels(mapOf(
                MagoLintMode.SEMANTICS to "Syntax only",
                MagoLintMode.NORMAL to "Normal",
                MagoLintMode.PEDANTIC to "All supported rules",
            ))
            guardModeBox.useLabels(guardModeLabels)
            val markValidationStale = object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    binaryRow.status.markStale()
                    configRow.status.markStale()
                }
            }
            binaryField.textField.document.addDocumentListener(markValidationStale)
            executionRootField.document.addDocumentListener(markValidationStale)
        }

        val panel: JComponent = panel {
            row("Mago command:") {
                cell(binaryRow.rowComponent).align(Align.FILL).resizableColumn()
                contextHelp(COMMAND_HELP, "Mago command")
            }
            row("") { cell(binaryRow.status.component).align(Align.FILL).resizableColumn() }
            row("Execution project root:") {
                cell(executionRootField).align(Align.FILL).resizableColumn()
                contextHelp(EXECUTION_ROOT_HELP, "Execution project root")
            }
            row("Default configuration file:") { cell(configRow.rowComponent).align(Align.FILL).resizableColumn() }
            row("") { cell(configRow.status.component).align(Align.FILL).resizableColumn() }
            row { cell(showStatusWidgetCheckBox) }
            group(SECTION_TITLE) {
                row {
                    label("Use Mago Formatter for:")
                    cell(phpFormatterCheckBox)
                    cell(twigFormatterCheckBox).gap(RightGap.SMALL)
                    cell(JBLabel("(requires Mago 1.60+)").apply {
                        font = twigFormatterCheckBox.font
                        foreground = SECONDARY_TEXT_COLOR
                    })
                }
                row {
                    text(
                        """Mago Formatter replaces the IDE code style settings for PHP files. Enable <b>Reformat code</b> in <a href="actionsOnSave">Actions on Save</a> to format files on save.""",
                        action = { event ->
                            if (event.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                                openActionsOnSave()
                            }
                        },
                    ).resizableColumn()
                }
            }
            collapsibleGroup("Linter") {
                    row {
                        label("Lint mode:")
                        cell(lintModeBox)
                        contextHelp(
                            MagoConfigurableHelp.LINT_MODE,
                            "Lint mode",
                        )
                    }
                    row { option(lintIgnoreBaseline, "--ignore-baseline", "Ignore the baseline file, reporting all issues. When this flag is set, any configured or specified baseline file is ignored, and all issues are reported as if no baseline exists. This is useful for fixing issues that are currently suppressed by the baseline.") }
                    row { option(lintFixableOnly, "--fixable-only", "Filter the output to only show issues that can be automatically fixed. When enabled, only issues that have available automatic fixes will be displayed. This is useful when you want to focus on issues that can be resolved immediately.") }
                    row { cell(ActionLink("Configure inspection") { selectInspection("MagoLint") }) }
            }
            collapsibleGroup("Analyzer") {
                    row { option(analyzeIgnoreBaseline, "--ignore-baseline", "Ignore the baseline file, reporting all issues. When this flag is set, any configured or specified baseline file is ignored, and all issues are reported as if no baseline exists. This is useful for fixing issues that are currently suppressed by the baseline.") }
                    row { option(analyzeNoStubs, "--no-stubs", "Disable built-in PHP and library stubs for analysis. By default, the analyzer uses stubs for built-in PHP functions and popular libraries to provide accurate type information. Disabling this may result in more reported issues when external symbols can't be resolved.") }
                    row { option(analyzeSkipIgnores, "--skip-ignores", "Ignore the <code>ignore</code> list from the analyzer configuration. Reports every issue the analyzer finds, including codes suppressed by the configured <code>ignore</code> entries. Useful for one-off runs that audit what is currently being hidden. Inline suppressions in the source are unaffected.") }
                    row { option(analyzeFixableOnly, "--fixable-only", "Filter the output to only show issues that can be automatically fixed. When enabled, only issues that have available automatic fixes will be displayed. This is useful when you want to focus on issues that can be resolved immediately.") }
                    row { cell(ActionLink("Configure inspection") { selectInspection("MagoAnalyze") }) }
            }
            collapsibleGroup("Guard") {
                    row {
                        label("Guard mode:")
                        cell(guardModeBox)
                        contextHelp(
                            MagoConfigurableHelp.GUARD_MODE,
                            "Guard mode",
                        )
                    }
                    row { option(guardIgnoreBaseline, "--ignore-baseline", "Ignore the baseline file, reporting all issues. When this flag is set, any configured or specified baseline file is ignored, and all issues are reported as if no baseline exists. This is useful for fixing issues that are currently suppressed by the baseline.") }
                    row { option(guardNoStubs, "--no-stubs", "Disable built-in PHP and library stubs for checking. By default, guard uses stubs for built-in PHP functions and popular libraries to provide accurate symbol information. Disabling this may result in more warnings when external symbols can't be resolved.") }
                    row { option(guardFixableOnly, "--fixable-only", "Filter the output to only show issues that can be automatically fixed. When enabled, only issues that have available automatic fixes will be displayed. This is useful when you want to focus on issues that can be resolved immediately.") }
                    row { cell(ActionLink("Configure inspection") { selectInspection("MagoGuard") }) }
            }
            collapsibleGroup("Twig Linter") {
                    row {
                        label("Lint mode:")
                        cell(twigLintModeBox)
                        contextHelp(
                            MagoConfigurableHelp.TWIG_LINT_MODE,
                            "Twig lint mode",
                        )
                    }
                    row { option(twigLintIgnoreBaseline, "--ignore-baseline", "Ignore the baseline file, reporting all issues. When this flag is set, any configured or specified baseline file is ignored, and all issues are reported as if no baseline exists. This is useful for fixing issues that are currently suppressed by the baseline.") }
                    row { option(twigLintFixableOnly, "--fixable-only", "Filter the output to only show issues that can be automatically fixed. When enabled, only issues that have available automatic fixes will be displayed. This is useful when you want to focus on issues that can be resolved immediately.") }
                    row { cell(ActionLink("Configure inspection") { selectInspection("MagoTwigLint") }) }
            }
        }

        private fun Row.option(checkBox: JBCheckBox, flag: String, description: String) {
            cell(checkBox)
            val help = MagoConfigurableHelp.option(flag, description)
            contextHelp(help.description, help.title)
        }

        fun isModified(): Boolean {
            val state = MagoSettings.getInstance(project).state
            return phpFormatterCheckBox.isSelected != state.enabled ||
                twigFormatterCheckBox.isSelected != (state.twigEnabled ?: state.enabled) ||
                binaryField.text.trim() != state.magoPath.trim() ||
                configField.text.trim() != state.configPath.trim() ||
                executionRootField.text.trim() != state.executionRoot.trim() ||
                showStatusWidgetCheckBox.isSelected != state.showStatusWidget ||
                lintModeBox.selectedItem != state.lintMode ||
                lintIgnoreBaseline.isSelected != state.lintIgnoreBaseline ||
                lintFixableOnly.isSelected != state.lintFixableOnly ||
                twigLintModeBox.selectedItem != state.twigLintMode ||
                twigLintIgnoreBaseline.isSelected != state.twigLintIgnoreBaseline ||
                twigLintFixableOnly.isSelected != state.twigLintFixableOnly ||
                analyzeIgnoreBaseline.isSelected != state.analyzeIgnoreBaseline ||
                analyzeNoStubs.isSelected != state.analyzeNoStubs ||
                analyzeSkipIgnores.isSelected != state.analyzeSkipIgnores ||
                analyzeFixableOnly.isSelected != state.analyzeFixableOnly ||
                guardModeBox.selectedItem != state.guardMode ||
                guardIgnoreBaseline.isSelected != state.guardIgnoreBaseline ||
                guardNoStubs.isSelected != state.guardNoStubs ||
                guardFixableOnly.isSelected != state.guardFixableOnly
        }

        fun apply() {
            val state = MagoSettings.getInstance(project).state
            state.enabled = phpFormatterCheckBox.isSelected
            state.twigEnabled = twigFormatterCheckBox.isSelected
            state.magoPath = binaryField.text.trim()
            state.configPath = configField.text.trim()
            state.executionRoot = executionRootField.text.trim()
            if (state.showStatusWidget != showStatusWidgetCheckBox.isSelected) {
                state.showStatusWidget = showStatusWidgetCheckBox.isSelected
                MagoStatusBarWidget.refreshOpenProjects()
            }
            state.lintMode = lintModeBox.selectedItem as MagoLintMode
            state.lintIgnoreBaseline = lintIgnoreBaseline.isSelected
            state.lintFixableOnly = lintFixableOnly.isSelected
            state.twigLintMode = twigLintModeBox.selectedItem as MagoLintMode
            state.twigLintIgnoreBaseline = twigLintIgnoreBaseline.isSelected
            state.twigLintFixableOnly = twigLintFixableOnly.isSelected
            state.analyzeIgnoreBaseline = analyzeIgnoreBaseline.isSelected
            state.analyzeNoStubs = analyzeNoStubs.isSelected
            state.analyzeSkipIgnores = analyzeSkipIgnores.isSelected
            state.analyzeFixableOnly = analyzeFixableOnly.isSelected
            state.guardMode = guardModeBox.selectedItem as MagoGuardMode
            state.guardIgnoreBaseline = guardIgnoreBaseline.isSelected
            state.guardNoStubs = guardNoStubs.isSelected
            state.guardFixableOnly = guardFixableOnly.isSelected
        }

        fun reset() {
            val state = MagoSettings.getInstance(project).state
            phpFormatterCheckBox.isSelected = state.enabled
            twigFormatterCheckBox.isSelected = state.twigEnabled ?: state.enabled
            binaryField.text = state.magoPath
            configField.text = state.configPath
            executionRootField.text = state.executionRoot
            showStatusWidgetCheckBox.isSelected = state.showStatusWidget
            lintModeBox.selectedItem = state.lintMode
            lintIgnoreBaseline.isSelected = state.lintIgnoreBaseline
            lintFixableOnly.isSelected = state.lintFixableOnly
            twigLintModeBox.selectedItem = state.twigLintMode
            twigLintIgnoreBaseline.isSelected = state.twigLintIgnoreBaseline
            twigLintFixableOnly.isSelected = state.twigLintFixableOnly
            analyzeIgnoreBaseline.isSelected = state.analyzeIgnoreBaseline
            analyzeNoStubs.isSelected = state.analyzeNoStubs
            analyzeSkipIgnores.isSelected = state.analyzeSkipIgnores
            analyzeFixableOnly.isSelected = state.analyzeFixableOnly
            guardModeBox.selectedItem = state.guardMode
            guardIgnoreBaseline.isSelected = state.guardIgnoreBaseline
            guardNoStubs.isSelected = state.guardNoStubs
            guardFixableOnly.isSelected = state.guardFixableOnly
            binaryRow.status.show(StatusLine.Kind.EMPTY, "")
            configRow.status.show(StatusLine.Kind.EMPTY, "")
        }

        private fun selectInspection(inspectionTool: String) {
            val settings = settingsForComponent(panel)
            val inspections = settings?.find(ErrorsConfigurable::class.java)
            if (inspections != null) {
                settings.select(inspections).doWhenDone {
                    // Selecting the Settings node does not guarantee its UI is initialized yet.
                    if (settings.getConfigurableWithInitializedUiComponent(INSPECTIONS_CONFIGURABLE_ID, true) != null) {
                        inspections.selectInspectionTool(inspectionTool)
                    }
                }
            }
        }

        private fun openActionsOnSave() {
            val settings = settingsForComponent(panel)
            val configurable = settings?.find(ActionsOnSaveConfigurable.CONFIGURABLE_ID)
            if (settings != null && configurable != null) {
                settings.select(configurable)
            } else {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ActionsOnSaveConfigurable::class.java)
            }
        }

        /**
         * A path field with a validate button and a status line below it.
         *
         * The check runs an external process, so it happens off the EDT and the result is applied back on it.
         */
        private inner class PathRow(
            val field: TextFieldWithBrowseButton,
            val tooltip: String,
            val check: (binaryPath: String, configPath: String, projectDirectory: File?, executionRoot: String) -> MagoValidation.Result,
        ) {

            val validateButton = InlineIconButton(AllIcons.Actions.Refresh)

            val status = StatusLine()

            val rowComponent: JComponent = panel {
                row {
                    cell(field).align(Align.FILL).resizableColumn()
                    cell(validateButton)
                }
            }

            init {
                field.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor())
                validateButton.withBackgroundHover = true
                validateButton.toolTipText = tooltip
                validateButton.actionListener = ActionListener { validate() }
                field.textField.document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) = status.markStale()
                })
            }

            fun validate() {
                val binaryPath = binaryField.text.trim()
                val ownPath = field.text.trim()
                val executionRoot = executionRootField.text.trim()
                val projectDirectory = project.basePath
                validateButton.isEnabled = false
                status.show(StatusLine.Kind.CHECKING, CHECKING_TEXT)

                validation.validate(check = {
                    try {
                        check(binaryPath, ownPath, projectDirectory?.let(::File), executionRoot)
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        MagoValidation.Result.Failure(e.message ?: e.toString())
                    }
                }, update = { result ->
                    validateButton.isEnabled = true
                    if (binaryField.text.trim() != binaryPath ||
                        field.text.trim() != ownPath ||
                        executionRootField.text.trim() != executionRoot ||
                        project.basePath != projectDirectory
                    ) {
                        status.show(StatusLine.Kind.STALE, NOT_VALIDATED_TEXT)
                    } else {
                        when (result) {
                            is MagoValidation.Result.Success -> status.showSuccess(result.message, result.warning)
                            is MagoValidation.Result.Failure -> status.show(StatusLine.Kind.ERROR, result.message)
                        }
                    }
                })
            }
        }

        }

    private class StatusLine {

        enum class Kind { EMPTY, CHECKING, SUCCESS, ERROR, STALE }

        val label = StatusLabel()
        var kind: Kind = Kind.EMPTY
            private set

        init {
            label.font = JBUI.Fonts.smallFont()
            label.foreground = SECONDARY_TEXT_COLOR
        }

        fun show(kind: Kind, message: String) {
            this.kind = kind
            label.setMessage(message, null)
            label.foreground = if (kind == Kind.ERROR) ERROR_TEXT_COLOR else SECONDARY_TEXT_COLOR
        }

        fun showSuccess(message: String, warning: String?) {
            this.kind = Kind.SUCCESS
            label.setMessage(message, warning)
            label.foreground = SECONDARY_TEXT_COLOR
        }

        fun markStale() {
            if (kind == Kind.SUCCESS || kind == Kind.ERROR) {
                show(Kind.STALE, NOT_VALIDATED_TEXT)
            }
        }

        val component: JComponent get() = label
    }

    /**
     * JLabel keeps the full response for its native clipping and tooltip; the status row supplies its width.
     */
    private class StatusLabel : JBLabel() {

        fun setMessage(message: String, warning: String?) {
            icon = if (warning.isNullOrBlank()) null else AllIcons.General.BalloonWarning
            horizontalTextPosition = SwingConstants.LEFT
            toolTipText = when {
                message.isEmpty() && warning.isNullOrBlank() -> null
                warning.isNullOrBlank() -> message
                message.isEmpty() -> warning
                else -> "$message\n\nVersion compatibility warning: $warning"
            }
            accessibleContext?.accessibleDescription = warning
            text = message
        }

        override fun getPreferredSize(): Dimension {
            return Dimension(0, super.getPreferredSize().height)
        }

        override fun getMinimumSize(): Dimension {
            return Dimension(0, super.getMinimumSize().height)
        }

    }
}
