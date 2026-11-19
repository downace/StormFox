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
import com.intellij.util.ui.InlineIconButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.github.downace.stormfox.format.MagoValidation
import com.github.downace.stormfox.status.MagoStatusBarWidget
import java.awt.Color
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
private const val INSPECTIONS_SECTION_TITLE = "Inspections"
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
            group(INSPECTIONS_SECTION_TITLE) {
                row { cell(ActionLink("Linter") { selectInspection("MagoLint") }) }
                row { cell(ActionLink("Analyzer") { selectInspection("MagoAnalyze") }) }
                row { cell(ActionLink("Guard") { selectInspection("MagoGuard") }) }
                row { cell(ActionLink("Twig Linter") { selectInspection("MagoTwigLint") }) }
            }
        }

        fun isModified(): Boolean {
            val state = MagoSettings.getInstance(project).state
            return phpFormatterCheckBox.isSelected != state.enabled ||
                twigFormatterCheckBox.isSelected != (state.twigEnabled ?: state.enabled) ||
                binaryField.text.trim() != state.magoPath.trim() ||
                configField.text.trim() != state.configPath.trim() ||
                executionRootField.text.trim() != state.executionRoot.trim() ||
                showStatusWidgetCheckBox.isSelected != state.showStatusWidget
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
        }

        fun reset() {
            val state = MagoSettings.getInstance(project).state
            phpFormatterCheckBox.isSelected = state.enabled
            twigFormatterCheckBox.isSelected = state.twigEnabled ?: state.enabled
            binaryField.text = state.magoPath
            configField.text = state.configPath
            executionRootField.text = state.executionRoot
            showStatusWidgetCheckBox.isSelected = state.showStatusWidget
            binaryRow.status.show(StatusLine.Kind.EMPTY, "")
            configRow.status.show(StatusLine.Kind.EMPTY, "")
        }

        private fun selectInspection(inspectionTool: String) {
            val settings = settingsForComponent(panel)
            val inspections = settings?.find(ErrorsConfigurable::class.java)
            if (inspections != null) {
                settings.select(inspections).doWhenDone {
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

            var checkRunning = false

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
                checkRunning = true
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
                    checkRunning = false
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
