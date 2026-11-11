package com.github.downace.stormfox.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.ui.ErrorsConfigurable
import com.intellij.profile.codeInspection.ui.ErrorsConfigurableProvider
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.jetbrains.php.lang.inspections.PhpInspectionsUtil
import com.intellij.util.ui.InlineIconButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.github.downace.stormfox.format.MagoValidation
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

private const val NOT_VALIDATED_TEXT = "Not validated"
private const val CHECKING_TEXT = "Checking…"
private const val SECTION_GAP = 8
private const val HINT_INDENT = 20
private const val MAX_STATUS_TEXT_LENGTH = 120
private const val MAX_STATUS_WIDTH = 400
private const val ELLIPSIS = "…"
private const val SECTION_TITLE = "Formatter"
private const val LINTER_SECTION_TITLE = "Linter"
private const val ANALYZER_SECTION_TITLE = "Analyzer"
private const val GUARD_SECTION_TITLE = "Guard"
private const val FORMATTER_HINT = "Mago replaces the IDE code style settings for PHP files."
private const val FORMATTER_ON_SAVE_HINT =
    "Formatting on save is controlled by the IDE: turn on Reformat code in Settings | Tools | Actions on Save."

private val SECONDARY_TEXT_COLOR: Color = UIUtil.getContextHelpForeground()
private val ERROR_TEXT_COLOR: Color = JBColor.namedColor("Label.errorForeground", UIUtil.getErrorForeground())
private val SEPARATOR_COLOR: Color = JBColor.namedColor("Group.separatorColor", JBColor.GRAY)

class MagoConfigurable(private val project: Project) : Configurable {

    private var ui: SettingsUi? = null

    override fun getDisplayName(): String = "Mago"

    override fun createComponent(): JComponent {
        val settingsUi = ui ?: SettingsUi(project.getService(MagoSettingsValidationService::class.java).createSession()).also { ui = it }
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

    private class SettingsUi(private val validation: MagoSettingsValidationService.Session) {

        fun dispose() = validation.dispose()

        private val enabledCheckBox = JBCheckBox("Use Mago formatter")

        private val binaryField = TextFieldWithBrowseButton()

        private val configField = TextFieldWithBrowseButton().apply {
            toolTipText =
                "Passed to mago as the --config flag. It overrides the mago.toml configuration of the project."
        }

        private val binaryRow = PathRow(
            field = binaryField,
            tooltip = "Check that the mago executable can be started",
            check = { binary, _ -> MagoValidation.checkBinary(binary) },
        )

        private val configRow = PathRow(
            field = configField,
            tooltip = "Check that mago accepts this configuration file",
            check = { binary, config -> MagoValidation.checkConfig(binary, config) },
        )

        /**
         * The general settings: where mago lives and which configuration file it should use.
         *
         * Labels and fields share a row and are anchored to the top of it, so that a label lines up
         * with the field it belongs to instead of with the middle of the row.
         */
        private val generalBlock: JComponent = JPanel(GridBagLayout()).apply {
            add(JBLabel("Mago executable:"), cell(gridx = 0, gridy = 0, anchor = GridBagConstraints.WEST))
            add(binaryRow.rowComponent, cell(gridx = 1, gridy = 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, anchor = GridBagConstraints.NORTH))
            add(binaryRow.status.label, cell(gridx = 1, gridy = 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, anchor = GridBagConstraints.NORTH, insets = JBUI.insets(2, 0, 0, 0)))

            add(
                JBLabel("Default configuration file:"),
                cell(gridx = 0, gridy = 2, anchor = GridBagConstraints.WEST, insets = JBUI.insets(JBUI.scale(SECTION_GAP), 0, 0, 0)),
            )
            add(
                configRow.rowComponent,
                cell(gridx = 1, gridy = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, anchor = GridBagConstraints.NORTH, insets = JBUI.insets(JBUI.scale(SECTION_GAP), 0, 0, 0)),
            )
            add(
                configRow.status.label,
                cell(gridx = 1, gridy = 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, anchor = GridBagConstraints.NORTH, insets = JBUI.insets(2, 0, 0, 0)),
            )
        }

        /**
         * The per feature settings, separated from the general ones by a titled rule.
         */
        private val formatterBlock: JComponent = JPanel(GridBagLayout()).apply {
            add(
                sectionRule(SECTION_TITLE),
                cell(gridx = 0, gridy = 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(JBUI.scale(SECTION_GAP), 0, JBUI.scale(SECTION_GAP), 0)),
            )
            add(enabledCheckBox, cell(gridx = 0, gridy = 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
            add(
                hint(FORMATTER_HINT),
                cell(gridx = 0, gridy = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(0, JBUI.scale(HINT_INDENT), 0, 0)),
            )
            add(
                hint(FORMATTER_ON_SAVE_HINT),
                cell(gridx = 0, gridy = 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(2, JBUI.scale(HINT_INDENT), 0, 0)),
            )
        }

        private val linterBlock = inspectionBlock(LINTER_SECTION_TITLE, "MagoLint")

        private val analyzerBlock = inspectionBlock(ANALYZER_SECTION_TITLE, "MagoAnalyze")

        private val guardBlock = inspectionBlock(GUARD_SECTION_TITLE, "MagoGuard")

        /**
         * The content is put into a BorderLayout.NORTH: a GridBagLayout would share the extra height
         * between its rows and the page would end up vertically centred in the dialog.
         */
        val panel: JComponent = JPanel(BorderLayout()).apply {
            add(
                JPanel(GridBagLayout()).apply {
                    add(generalBlock, cell(gridx = 0, gridy = 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
                    add(formatterBlock, cell(gridx = 0, gridy = 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
                    add(linterBlock, cell(gridx = 0, gridy = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
                    add(analyzerBlock, cell(gridx = 0, gridy = 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
                    add(guardBlock, cell(gridx = 0, gridy = 4, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
                },
                BorderLayout.NORTH,
            )
        }

        fun isModified(): Boolean {
            val state = MagoSettings.getInstance().state
            return enabledCheckBox.isSelected != state.enabled ||
                binaryField.text.trim() != state.magoPath.trim() ||
                configField.text.trim() != state.configPath.trim()
        }

        fun apply() {
            val state = MagoSettings.getInstance().state
            state.enabled = enabledCheckBox.isSelected
            state.magoPath = binaryField.text.trim()
            state.configPath = configField.text.trim()
        }

        fun reset() {
            val state = MagoSettings.getInstance().state
            enabledCheckBox.isSelected = state.enabled
            binaryField.text = state.magoPath
            configField.text = state.configPath
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

        private fun inspectionBlock(title: String, inspectionTool: String): JComponent = JPanel(GridBagLayout()).apply {
            add(
                sectionRule(title),
                cell(gridx = 0, gridy = 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL, insets = JBUI.insets(JBUI.scale(SECTION_GAP), 0, JBUI.scale(SECTION_GAP), 0)),
            )
            add(
                PhpInspectionsUtil.createPanelWithSettingsLink(
                    "Mago $title inspection",
                    ErrorsConfigurable::class.java,
                    { targetProject ->
                        ConfigurableExtensionPointUtil.createProjectConfigurableForProvider(
                            targetProject,
                            ErrorsConfigurableProvider::class.java,
                        ) as ErrorsConfigurable
                    },
                    { it.selectInspectionTool(inspectionTool) },
                ),
                cell(gridx = 0, gridy = 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL),
            )
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
            val check: (binaryPath: String, configPath: String) -> MagoValidation.Result,
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
                checkRunning = true
                validateButton.isEnabled = false
                status.show(StatusLine.Kind.CHECKING, CHECKING_TEXT)

                validation.validate(check = {
                    try {
                        check(binaryPath, ownPath)
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
                    if (binaryField.text.trim() != binaryPath || field.text.trim() != ownPath) {
                        status.show(StatusLine.Kind.STALE, NOT_VALIDATED_TEXT)
                    } else {
                        when (result) {
                            is MagoValidation.Result.Success -> status.show(StatusLine.Kind.SUCCESS, result.message)
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
            label.setMessage(message)
            label.foreground = if (kind == Kind.ERROR) ERROR_TEXT_COLOR else SECONDARY_TEXT_COLOR
        }

        fun markStale() {
            if (kind == Kind.SUCCESS || kind == Kind.ERROR) {
                show(Kind.STALE, NOT_VALIDATED_TEXT)
            }
        }
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
