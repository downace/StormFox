package com.github.downace.stormfox.status

import com.github.downace.stormfox.lint.MagoDiagnosticActivity
import com.github.downace.stormfox.lint.MagoDiagnosticService
import com.github.downace.stormfox.lint.MagoTool
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.codeInspection.ex.Tools
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.profile.ProfileChangeAdapter
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.IconLabelButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.util.Locale
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants
import javax.swing.Timer

class MagoStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = MagoStatusBarWidget.ID
    override fun getDisplayName(): String = "Mago"
    override fun isAvailable(project: Project): Boolean = true
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = MagoStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
}

class MagoStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation, Disposable {
    companion object {
        const val ID = "com.github.downace.stormfox.MagoStatusBarWidget"
        private val RUNNING = JBColor(Color(0x4A, 0x9B, 0xF5), Color(0x69, 0xA8, 0xFF))
        private val WAITING = JBColor(Color(0xD6, 0x9A, 0x24), Color(0xE5, 0xB9, 0x58))
        private val ERROR = JBColor(Color(0xD9, 0x4A, 0x4A), Color(0xF0, 0x6A, 0x6A))

        fun refreshOpenProjects() {
            ProjectManager.getInstance().openProjects.forEach { p ->
                (WindowManager.getInstance().getStatusBar(p)?.getWidget(ID) as? MagoStatusBarWidget)?.refreshComponent()
            }
        }
    }

    private val activity = project.getService(MagoDiagnosticService::class.java).activity
    @Volatile private var snapshot = activity.snapshot()
    @Volatile private var statusBar: StatusBar? = null
    @Volatile private var activePopup: JBPopup? = null
    @Volatile private var disposed = false
    @Volatile private var enabledToolsCache: Set<MagoTool> = emptySet()
    @Volatile private var profileLoaded = false
    private val lifetime = Disposer.newDisposable("Mago status widget")
    init {
        activity.addListener(lifetime) {
            snapshot = activity.snapshot()
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && !project.isDisposed) refreshComponent()
            }
        }
        project.messageBus.connect(lifetime).subscribe(ProfileChangeAdapter.TOPIC, object : ProfileChangeAdapter {
            override fun profileChanged(profile: com.intellij.codeInspection.InspectionProfile) = refreshLater()
            override fun profileActivated(
                oldProfile: com.intellij.codeInspection.InspectionProfile?,
                profile: com.intellij.codeInspection.InspectionProfile?,
            ) = refreshLater()
            override fun profilesInitialized() = refreshLater()
        })
    }

    override fun ID(): String = ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        refreshComponent()
    }
    override fun getSelectedValue(): String? =
        statusPresentationText(MagoSettings.getInstance().state.showStatusWidget, enabledToolsCache.isNotEmpty())
    override fun getTooltipText(): String = stateTooltip(statusState(filteredSnapshot(snapshot, enabledToolsCache)))
    override fun getIcon(): Icon = DotIcon(stateColor(statusState(filteredSnapshot(snapshot, enabledToolsCache))))
    override fun getPopup(): JBPopup? = createDetailsPopup()

    override fun dispose() {
        disposed = true
        activePopup?.cancel()
        activePopup = null
        popupTimers.values.forEach { it.stop() }
        popupTimers.clear()
        popupPanel.clear()
        Disposer.dispose(lifetime)
        statusBar = null
    }

    private fun refreshLater() {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && !project.isDisposed) {
                enabledToolsCache = readEnabledTools()
                profileLoaded = true
                refreshComponent()
            }
        }
    }

    private fun readEnabledTools(): Set<MagoTool> {
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        return enabledMagoTools(profile.getAllEnabledInspectionTools(project)
            .asSequence()
            .filter(Tools::isEnabled)
            .map { it.shortName }
            .toSet())
    }

    private fun refreshComponent() {
        if (disposed || project.isDisposed) return
        if (!profileLoaded) {
            enabledToolsCache = readEnabledTools()
            profileLoaded = true
        }
        statusBar?.updateWidget(ID)
        val visible = MagoSettings.getInstance().state.showStatusWidget && enabledToolsCache.isNotEmpty()
        if (!visible) {
            activePopup?.cancel()
            activePopup = null
        } else {
            activePopup?.let { updatePopup(it) }
        }
    }

    private fun createDetailsPopup(): JBPopup? {
        if (disposed || project.isDisposed || enabledToolsCache.isEmpty() || !MagoSettings.getInstance().state.showStatusWidget) return null
        activePopup?.cancel()
        val initial = filteredSnapshot(activity.snapshot(), enabledToolsCache)
        val view = detailsPanel(enabledToolsCache, initial)
        val panel = view.panel
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null)
            .setRequestFocus(true).setResizable(false).setMovable(false).createPopup()
        activePopup = popup
        val popupDisposable = Disposer.newDisposable("Mago status popup")
        Disposer.register(lifetime, popupDisposable)
        Disposer.register(popupDisposable, popup)
        popupPanel[popup] = view
        val ticker = Timer(200) { updatePopup(popup, elapsedOnly = true) }
        popupTimers[popup] = ticker
        val listenerDisposable = Disposer.newDisposable("Mago popup activity")
        Disposer.register(popupDisposable, listenerDisposable)
        activity.addListener(listenerDisposable) {
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && !project.isDisposed && activePopup === popup) updatePopup(popup)
            }
        }
        Disposer.register(popupDisposable, Disposable {
            ticker.stop()
            popupPanel.remove(popup)
            popupTimers.remove(popup)
            if (activePopup === popup) activePopup = null
        })
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                Disposer.dispose(popupDisposable)
            }
        })
        ticker.isRepeats = true
        ticker.startIfWorkActive()
        return popup
    }

    private data class PopupView(val panel: JPanel, var rows: Map<MagoTool, ToolRowView>)
    private val popupPanel = mutableMapOf<JBPopup, PopupView>()
    private val popupTimers = mutableMapOf<JBPopup, Timer>()

    private fun updatePopup(popup: JBPopup, elapsedOnly: Boolean = false) {
        if (disposed || project.isDisposed || popup.isDisposed || activePopup !== popup) return
        val view = popupPanel[popup] ?: return
        val latestEnabled = enabledToolsCache
        if (latestEnabled != view.rows.keys) {
            val replacement = detailsPanel(latestEnabled, filteredSnapshot(activity.snapshot(), latestEnabled))
            // Profile changes may alter the fixed set (at most three); elapsed ticks never rebuild.
            view.panel.removeAll()
            replacement.panel.components.forEach(view.panel::add)
            view.rows = replacement.rows
            view.panel.revalidate()
            view.panel.repaint()
        } else {
            val value = filteredSnapshot(activity.snapshot(), latestEnabled)
            view.rows.forEach { (tool, row) ->
                val state = aggregateTool(tool, value, System.nanoTime())
                if (!elapsedOnly || row.state != state.copy(elapsed = row.state.elapsed)) row.update(state)
                else row.updateElapsed(state.elapsed)
            }
        }
        popupTimers[popup]?.let { timer ->
            val current = filteredSnapshot(activity.snapshot(), latestEnabled)
            if (current.queued.isEmpty() && current.running.isEmpty()) timer.stop()
            else if (!timer.isRunning) timer.start()
        }
    }

    private fun Timer.startIfWorkActive() {
        // The timer only advances elapsed labels while there is work to display.
        if (activity.snapshot().queued.any { it.tool in enabledToolsCache } ||
            activity.snapshot().running.any { it.tool in enabledToolsCache }) start()
    }

    private fun detailsPanel(enabled: Set<MagoTool>, value: MagoDiagnosticActivity.Snapshot): PopupView {
        val rows = linkedMapOf<MagoTool, ToolRowView>()
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 12)
            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                val title = JBLabel("Mago").apply { font = JBUI.Fonts.label().deriveFont(Font.BOLD) }
                add(title, BorderLayout.WEST)
                add(IconLabelButton(AllIcons.General.Gear, { openPluginSettings() }).apply {
                    toolTipText = "Mago settings"
                    accessibleContext.accessibleName = "Mago settings"
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }, BorderLayout.EAST)
            }
            add(header)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(JSeparator(SwingConstants.HORIZONTAL))
            MagoTool.values().filter { it in enabled }.forEach { tool ->
                val row = toolRow(tool, aggregateTool(tool, value, System.nanoTime()))
                rows[tool] = row
                add(row.component)
            }
            val fontMetrics = getFontMetrics(JBUI.Fonts.label())
            val longestLabel = enabled.maxOfOrNull { tool -> reservedStatusLabelWidth(tool.label, fontMetrics, JBUI.scale(2)) } ?: 0
            val actionWidth = reservedElapsedColumnWidth(fontMetrics, JBUI.scale(32))
            preferredSize = Dimension(longestLabel + actionWidth + JBUI.scale(36), preferredSize.height)
        }
        return PopupView(panel, rows)
    }

    private inner class ToolRowView(
        val component: JPanel,
        private val dot: JBLabel,
        private val statusText: JBLabel,
        private val elapsedText: JBLabel,
        private val gear: JComponent,
        var state: ToolRowState,
    ) {
        fun update(next: ToolRowState) {
            state = next
            dot.icon = DotIcon(stateColor(next.status))
            dot.toolTipText = next.errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
                ?: "${component.name}: ${next.status.label}"
            statusText.text = "${component.name} - ${next.status.label}"
            elapsedText.text = next.elapsed.orEmpty()
        }
        fun updateElapsed(value: String?) {
            rowParts.updateElapsed(value)
            state = state.copy(elapsed = value)
        }
        private val rowParts = StableRowParts(statusText, dot, elapsedText, gear)
    }

    private fun toolRow(tool: MagoTool, state: ToolRowState): ToolRowView {
        val dot = JBLabel(DotIcon(stateColor(state.status))).apply {
            toolTipText = state.errors.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "${tool.label}: ${state.status.label}"
        }
        val statusText = JBLabel("${tool.label} - ${state.status.label}")
        val elapsedText = createElapsedLabel(state.elapsed.orEmpty())
        val gear = IconLabelButton(AllIcons.General.Gear, { openInspectionSettings(tool) }).apply {
            toolTipText = "${tool.label} inspection settings"
            accessibleContext.accessibleName = "${tool.label} inspection settings"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val row = JPanel(GridBagLayout()).apply {
        isOpaque = false
        name = tool.label
        border = JBUI.Borders.empty(8, 0, 2, 0)
        val status = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(dot, BorderLayout.WEST)
            add(statusText, BorderLayout.CENTER)
        }
        val statusWidth = reservedStatusLabelWidth(tool.label, status.getFontMetrics(statusText.font), JBUI.scale(2))
        status.preferredSize = Dimension(statusWidth, status.preferredSize.height)
        add(status, GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        })
        val actions = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            elapsedText.text = state.elapsed.orEmpty()
            add(elapsedText)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(gear)
        }
        add(actions, GridBagConstraints().apply {
            gridx = 1
            weightx = 0.0
            fill = GridBagConstraints.NONE
            anchor = GridBagConstraints.EAST
        })
        }
        return ToolRowView(row, dot, statusText, elapsedText, gear, state)
    }

    private fun openPluginSettings() {
        activePopup?.cancel()
        MagoStatusSettingsNavigation.openPlugin(project)
    }

    private fun openInspectionSettings(tool: MagoTool) {
        activePopup?.cancel()
        MagoStatusSettingsNavigation.openInspection(project, tool)
    }

    private fun stateColor(state: MagoStatusState): Color? = when (state) {
        MagoStatusState.IDLE -> null
        MagoStatusState.WAITING -> WAITING
        MagoStatusState.RUNNING -> RUNNING
        MagoStatusState.FAILED -> ERROR
    }

    private fun stateTooltip(state: MagoStatusState): String = when (state) {
        MagoStatusState.IDLE -> "Mago: no checks are active"
        MagoStatusState.WAITING -> "Mago: checks are waiting"
        MagoStatusState.RUNNING -> "Mago: checks are running"
        MagoStatusState.FAILED -> "Mago: a check failed"
    }

    private fun elapsed(nanos: Long): String {
        return formatElapsed(nanos, System.nanoTime())
    }

    private class DotIcon(private val color: Color?) : Icon {
        override fun getIconWidth() = JBUI.scale(8)
        override fun getIconHeight() = JBUI.scale(8)
        override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) {
            val copy = g.create() as Graphics2D
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                copy.color = color ?: JBUI.CurrentTheme.StatusBar.Widget.FOREGROUND
                copy.fillOval(x, y, iconWidth, iconHeight)
            } finally { copy.dispose() }
        }
    }
}

internal enum class MagoStatusState(val label: String) { IDLE("Idle"), WAITING("Waiting"), RUNNING("Running"), FAILED("Error") }
internal data class ToolRowState(val status: MagoStatusState, val elapsed: String?, val errors: List<String>)
internal class StableRowParts(
    val status: JBLabel,
    val dot: JBLabel,
    val elapsed: JBLabel,
    val gear: JComponent,
) {
    fun updateElapsed(value: String?) { elapsed.text = value.orEmpty() }
}
internal fun statusPresentationText(preferenceEnabled: Boolean, hasEnabledTools: Boolean): String? =
    "Mago".takeIf { preferenceEnabled && hasEnabledTools }
internal fun statusState(snapshot: MagoDiagnosticActivity.Snapshot): MagoStatusState = when {
    snapshot.failures.isNotEmpty() -> MagoStatusState.FAILED
    snapshot.running.isNotEmpty() -> MagoStatusState.RUNNING
    snapshot.queued.isNotEmpty() -> MagoStatusState.WAITING
    else -> MagoStatusState.IDLE
}
internal fun enabledMagoTools(enabledShortNames: Set<String>): Set<MagoTool> =
    MagoTool.values().filter { it.inspectionShortName in enabledShortNames }.toSet()
internal fun filteredSnapshot(snapshot: MagoDiagnosticActivity.Snapshot, enabled: Set<MagoTool>) =
    MagoDiagnosticActivity.Snapshot(
        snapshot.queued.filter { it.tool in enabled },
        snapshot.running.filter { it.tool in enabled },
        snapshot.failures.filter { it.tool in enabled },
    )
internal fun aggregateTool(
    tool: MagoTool,
    snapshot: MagoDiagnosticActivity.Snapshot,
    nowNanos: Long,
): ToolRowState {
    val running = snapshot.running.filter { it.tool == tool }
    val waiting = snapshot.queued.filter { it.tool == tool }
    val failures = snapshot.failures.filter { it.tool == tool }
    val status = when {
        failures.isNotEmpty() -> MagoStatusState.FAILED
        running.isNotEmpty() -> MagoStatusState.RUNNING
        waiting.isNotEmpty() -> MagoStatusState.WAITING
        else -> MagoStatusState.IDLE
    }
    val timedEntries = if (running.isNotEmpty()) running else waiting
    return ToolRowState(
        status,
        timedEntries.minOfOrNull { it.startedNanos }?.let { formatElapsed(it, nowNanos) },
        failures.mapNotNull { it.error }.distinct(),
    )
}
internal fun formatElapsed(startedNanos: Long, nowNanos: Long): String {
    val ms = ((nowNanos - startedNanos).coerceAtLeast(0L)) / 1_000_000L
    return if (ms < 1000) "${ms}ms" else "%.1fs".format(Locale.ROOT, ms / 1000.0)
}
internal fun reservedElapsedColumnWidth(metrics: java.awt.FontMetrics, extra: Int): Int =
    metrics.stringWidth("999.9s") + extra
internal fun createElapsedLabel(initial: String): JBLabel = JBLabel(initial, SwingConstants.RIGHT).apply {
    foreground = JBColor.GRAY
    val metrics = getFontMetrics(font)
    preferredSize = Dimension(reservedElapsedColumnWidth(metrics, 0), metrics.height)
    minimumSize = preferredSize
    maximumSize = preferredSize
}
internal fun reservedStatusLabelWidth(toolLabel: String, metrics: java.awt.FontMetrics, extra: Int): Int =
    listOf("Waiting", "Running", "Error", "Idle")
        .maxOf { metrics.stringWidth("$toolLabel - $it") } + extra

private val MagoTool.label: String get() = when (this) {
    MagoTool.LINT -> "Linter"
    MagoTool.ANALYZE -> "Analyzer"
    MagoTool.GUARD -> "Guard"
}
private val MagoTool.inspectionShortName: String get() = when (this) {
    MagoTool.LINT -> "MagoLint"
    MagoTool.ANALYZE -> "MagoAnalyze"
    MagoTool.GUARD -> "MagoGuard"
}
