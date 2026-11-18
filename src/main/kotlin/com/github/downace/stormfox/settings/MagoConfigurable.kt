package com.github.downace.stormfox.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.actionsOnSave.ActionsOnSaveConfigurable
import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.ui.ErrorsConfigurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.InlineIconButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.github.downace.stormfox.format.MagoValidation
import com.github.downace.stormfox.status.MagoStatusBarWidget
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionListener
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

private const val NOT_VALIDATED_TEXT = "Not validated"
private const val CHECKING_TEXT = "Checking…"
private const val SECTION_GAP = 12
private const val OPTION_ROW_GAP = 8
private const val CONTENT_INDENT = 24
private const val HINT_INDENT = 20
private const val MAX_STATUS_TEXT_LENGTH = 120
private const val MAX_STATUS_WIDTH = 400
private const val ELLIPSIS = "…"
private const val SECTION_TITLE = "Formatter"
private const val INSPECTIONS_SECTION_TITLE = "Inspections"
private const val INSPECTIONS_CONFIGURABLE_ID = "Errors"
private const val FORMATTER_HINT = "Mago replaces the IDE code style settings for PHP files."
private const val TWIG_SUPPORT_HINT = "Twig formatting requires Mago 1.60 or newer."
private const val FORMATTER_ON_SAVE_PREFIX = "<html>Enable&nbsp;<b>Reformat code</b>&nbsp;in&nbsp;</html>"
private const val FORMATTER_ON_SAVE_LINK_SUFFIX = "\u00a0to format files on save."

private val SECONDARY_TEXT_COLOR: Color = UIUtil.getContextHelpForeground()
private val ERROR_TEXT_COLOR: Color = JBColor.namedColor("Label.errorForeground", UIUtil.getErrorForeground())
private val SEPARATOR_COLOR: Color = JBColor.namedColor("Group.separatorColor", JBColor.GRAY)

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
                MagoValidation.checkBinary(binary, projectDirectory, executionRoot)
            },
        )

        private val configRow = PathRow(
            field = configField,
            tooltip = "Check that the Mago command accepts this configuration file",
            check = { binary, config, projectDirectory, executionRoot ->
                MagoValidation.checkConfig(binary, config, projectDirectory, executionRoot)
            },
        )

        /**
         * The command used to run Mago and the configuration file it should use.
         *
         * Labels and fields share a row and are anchored to the top of it, so that a label lines up
         * with the field it belongs to instead of with the middle of the row.
         */
        private val generalBlock: JComponent = JPanel(GridBagLayout()).apply {
            add(JBLabel("Mago command:"), cell(gridx = 0, gridy = 0, anchor = GridBagConstraints.WEST))
            add(binaryRow.rowComponent, cell(gridx = 1, gridy = 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, anchor = GridBagConstraints.NORTH))
            add(binaryRow.status.component, cell(gridx = 1, gridy = 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, anchor = GridBagConstraints.NORTH, insets = JBUI.insets(2, 0, 0, 0)))

            add(
                hint(
                    "<html>Enter one of:<ul style='margin-top:2px;margin-bottom:2px'>" +
                        "<li>Binary name, for example <code>mago</code> (if it is in <code>PATH</code>)</li>" +
                        "<li>Full path, for example <code>/usr/local/bin/mago</code></li>" +
                        "<li>Command, for example:<br><code>docker run --rm -i -v .:/app<br>-w /app ghcr.io/carthage-software/mago:1.60.1</code></li>" +
                        "</ul>Do not include a Mago subcommand.<br>For <code>docker run</code>, include <code>-i</code> and disable TTY allocation.</html>",
                ),
                cell(gridx = 1, gridy = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(2, 0, 0, 0)),
            )
            add(JBLabel("Execution project root:"), cell(gridx = 0, gridy = 3, anchor = GridBagConstraints.WEST, insets = JBUI.insets(JBUI.scale(SECTION_GAP), 0, 0, 0)))
            add(executionRootField, cell(gridx = 1, gridy = 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(JBUI.scale(SECTION_GAP), 0, 0, 0)))
            add(hint("<html>If Mago runs in a container, enter the project path inside the container,<br>for example <code>/app</code>.</html>"), cell(gridx = 1, gridy = 4, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(2, 0, 0, 0)))
            add(JBLabel("Default configuration file:"), cell(gridx = 0, gridy = 5, anchor = GridBagConstraints.WEST, insets = JBUI.insets(JBUI.scale(SECTION_GAP), 0, 0, 0)))
            add(configRow.rowComponent, cell(gridx = 1, gridy = 5, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, anchor = GridBagConstraints.NORTH, insets = JBUI.insets(JBUI.scale(SECTION_GAP), 0, 0, 0)))
            add(configRow.status.component, cell(gridx = 1, gridy = 6, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, anchor = GridBagConstraints.NORTH, insets = JBUI.insets(2, 0, 0, 0)))
            add(showStatusWidgetCheckBox, cell(gridx = 1, gridy = 7, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(6, 0, 0, 0)))
        }

        /**
         * The per feature settings, separated from the general ones by a titled rule.
         */
        private val formatterBlock: JComponent = JPanel(GridBagLayout()).apply {
            add(
                sectionRule(SECTION_TITLE),
                cell(gridx = 0, gridy = 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(SECTION_GAP, 0, SECTION_GAP, 0)),
            )
            add(
                JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                    add(JBLabel("Use Mago formatter for:"))
                    add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
                    add(phpFormatterCheckBox)
                    add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
                    add(twigFormatterCheckBox)
                },
                cell(gridx = 0, gridy = 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(0, CONTENT_INDENT, 0, 0)),
            )
            add(
                hint(FORMATTER_HINT),
                cell(gridx = 0, gridy = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(0, CONTENT_INDENT + HINT_INDENT, 0, 0)),
            )
            add(
                hint(TWIG_SUPPORT_HINT),
                cell(gridx = 0, gridy = 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(2, CONTENT_INDENT + HINT_INDENT, 0, 0)),
            )
            add(
                JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                    add(JBLabel(FORMATTER_ON_SAVE_PREFIX))
                    add(
                        ActionsOnSaveConfigurable.createGoToPageInSettingsLink(
                            "Actions on Save",
                            ActionsOnSaveConfigurable.CONFIGURABLE_ID,
                        ),
                    )
                    add(JBLabel(FORMATTER_ON_SAVE_LINK_SUFFIX))
                },
                cell(gridx = 0, gridy = 4, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(OPTION_ROW_GAP, CONTENT_INDENT, 0, 0)),
            )
        }

        private val inspectionsBlock: JComponent = JPanel(GridBagLayout()).apply {
            add(
                sectionRule(INSPECTIONS_SECTION_TITLE),
                cell(gridx = 0, gridy = 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(SECTION_GAP, 0, SECTION_GAP, 0)),
            )
            add(
                JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                    inspectionLink("Linter", "MagoLint")
                    add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
                    add(JBLabel("·"))
                    add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
                    inspectionLink("Analyzer", "MagoAnalyze")
                    add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
                    add(JBLabel("·"))
                    add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
                    inspectionLink("Guard", "MagoGuard")
                    add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
                    add(JBLabel("·"))
                    add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
                    inspectionLink("Twig Linter", "MagoTwigLint")
                },
                cell(gridx = 0, gridy = 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(0, CONTENT_INDENT, 0, 0)),
            )
        }

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

        /**
         * The content is put into a BorderLayout.NORTH: a GridBagLayout would share the extra height
         * between its rows and the page would end up vertically centred in the dialog.
         */
        val panel: JComponent = JPanel(BorderLayout()).apply {
            add(
                JPanel(GridBagLayout()).apply {
                    add(generalBlock, cell(gridx = 0, gridy = 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
                    add(formatterBlock, cell(gridx = 0, gridy = 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
                    add(inspectionsBlock, cell(gridx = 0, gridy = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
                },
                BorderLayout.NORTH,
            )
        }

        fun isModified(): Boolean {
            val state = MagoSettings.getInstance().state
            return phpFormatterCheckBox.isSelected != state.enabled ||
                twigFormatterCheckBox.isSelected != (state.twigEnabled ?: state.enabled) ||
                binaryField.text.trim() != state.magoPath.trim() ||
                configField.text.trim() != state.configPath.trim() ||
                executionRootField.text.trim() != state.executionRoot.trim() ||
                showStatusWidgetCheckBox.isSelected != state.showStatusWidget
        }

        fun apply() {
            val state = MagoSettings.getInstance().state
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
            val state = MagoSettings.getInstance().state
            phpFormatterCheckBox.isSelected = state.enabled
            twigFormatterCheckBox.isSelected = state.twigEnabled ?: state.enabled
            binaryField.text = state.magoPath
            configField.text = state.configPath
            executionRootField.text = state.executionRoot
            showStatusWidgetCheckBox.isSelected = state.showStatusWidget
            binaryRow.status.show(StatusLine.Kind.EMPTY, "")
            configRow.status.show(StatusLine.Kind.EMPTY, "")
        }

        /**
         * A hint line: small secondary text that explains the setting above it.
         */
        private fun hint(text: String): JBLabel = JBLabel(text).apply {
            font = JBUI.Fonts.smallFont()
            foreground = SECONDARY_TEXT_COLOR
        }

        /**
         * A titled rule: the section title on the left, a thin line running to the right edge.
         */
        private fun sectionRule(title: String): JComponent {
            val titleLabel = JBLabel(title).apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                foreground = UIUtil.getLabelForeground()
            }
            val line = JSeparator(SwingConstants.HORIZONTAL).apply {
                foreground = SEPARATOR_COLOR
            }
            return JPanel(GridBagLayout()).apply {
                isOpaque = false
                add(titleLabel, cell(gridx = 0, gridy = 0, anchor = GridBagConstraints.WEST, insets = JBUI.insets(0, 0, 0, JBUI.scale(8))))
                add(line, cell(gridx = 1, gridy = 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, anchor = GridBagConstraints.CENTER))
            }
        }

        private fun JPanel.inspectionLink(label: String, inspectionTool: String) {
            add(LinkLabel.create(label) {
                val settings = settingsForComponent(this)
                val inspections = settings?.find(ErrorsConfigurable::class.java)
                if (inspections != null) {
                    settings.select(inspections).doWhenDone {
                        if (settings.getConfigurableWithInitializedUiComponent(INSPECTIONS_CONFIGURABLE_ID, true) != null) {
                            inspections.selectInspectionTool(inspectionTool)
                        }
                    }
                }
            })
        }

        private fun cell(
            gridx: Int,
            gridy: Int,
            weightx: Double = 0.0,
            fill: Int = GridBagConstraints.NONE,
            anchor: Int = GridBagConstraints.NORTHWEST,
            insets: java.awt.Insets = JBUI.insets(0, 0, 0, 0),
        ): GridBagConstraints = GridBagConstraints().apply {
            this.gridx = gridx
            this.gridy = gridy
            this.weightx = weightx
            this.fill = fill
            this.anchor = anchor
            this.insets = insets
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

            val rowComponent: JComponent = JPanel(GridBagLayout()).apply {
                add(
                    field,
                    cell(gridx = 0, gridy = 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, anchor = GridBagConstraints.NORTH, insets = JBUI.insets(0, 0, 0, JBUI.scale(4))),
                )
                add(validateButton, cell(gridx = 1, gridy = 0, anchor = GridBagConstraints.CENTER))
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
        private val warningIcon = JBLabel(AllIcons.General.BalloonWarning).apply {
            isVisible = false
            toolTipText = null
            accessibleContext.accessibleName = "Version compatibility warning"
        }
        private val content = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(label)
            add(warningIcon)
        }

        var kind: Kind = Kind.EMPTY
            private set

        init {
            label.font = JBUI.Fonts.smallFont()
            label.foreground = SECONDARY_TEXT_COLOR
        }

        fun show(kind: Kind, message: String) {
            this.kind = kind
            label.setMessage(message)
            setWarning(null)
            label.foreground = if (kind == Kind.ERROR) ERROR_TEXT_COLOR else SECONDARY_TEXT_COLOR
        }

        fun showSuccess(message: String, warning: String?) {
            this.kind = Kind.SUCCESS
            label.setMessage(message)
            label.foreground = SECONDARY_TEXT_COLOR
            setWarning(warning)
        }

        private fun setWarning(warning: String?) {
            warningIcon.isVisible = !warning.isNullOrBlank()
            warningIcon.toolTipText = warning
            warningIcon.accessibleContext.accessibleDescription = warning
            content.revalidate()
        }

        fun markStale() {
            if (kind == Kind.SUCCESS || kind == Kind.ERROR) {
                show(Kind.STALE, NOT_VALIDATED_TEXT)
            }
        }

        val component: JComponent get() = content
    }

    /**
     * A status label that a long mago message cannot use to widen the settings page.
     *
     * The message is elided to a fixed width and the full text goes into the tooltip.
     */
    private class StatusLabel : JBLabel() {

        fun setMessage(message: String) {
            toolTipText = if (message.isEmpty()) null else message
            text = elide(message)
        }

        override fun getPreferredSize(): Dimension {
            val preferred = super.getPreferredSize()
            val maxWidth = JBUI.scale(MAX_STATUS_WIDTH)
            return if (preferred.width <= maxWidth) preferred else Dimension(maxWidth, preferred.height)
        }

        override fun getMinimumSize(): Dimension {
            return Dimension(0, super.getMinimumSize().height)
        }

        private fun elide(message: String): String {
            if (message.isEmpty()) return message
            val singleLine = message.replace(Regex("\\s+"), " ").trim()
            val metrics = getFontMetrics(font)
            val maxWidth = JBUI.scale(MAX_STATUS_WIDTH)
            if (singleLine.length <= MAX_STATUS_TEXT_LENGTH && metrics.stringWidth(singleLine) <= maxWidth) {
                return singleLine
            }
            val characters = singleLine.toCharArray()
            val limit = minOf(MAX_STATUS_TEXT_LENGTH, characters.size)
            val ellipsisWidth = metrics.stringWidth(ELLIPSIS)
            var end = 0
            while (end < limit && metrics.charsWidth(characters, 0, end + 1) + ellipsisWidth <= maxWidth) {
                end++
            }
            var elided = singleLine.substring(0, end)
            val lastSpace = elided.lastIndexOf(' ')
            if (lastSpace > elided.length / 2) {
                elided = elided.substring(0, lastSpace)
            }
            return elided.trimEnd() + ELLIPSIS
        }
    }
}
