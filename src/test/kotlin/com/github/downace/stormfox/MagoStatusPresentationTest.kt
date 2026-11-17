package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoDiagnosticActivity
import com.github.downace.stormfox.lint.MagoTool
import com.github.downace.stormfox.status.MagoStatusState
import com.github.downace.stormfox.status.enabledMagoTools
import com.github.downace.stormfox.status.aggregateTool
import com.github.downace.stormfox.status.filteredSnapshot
import com.github.downace.stormfox.status.formatElapsed
import com.github.downace.stormfox.status.reservedElapsedColumnWidth
import com.github.downace.stormfox.status.reservedStatusLabelWidth
import com.github.downace.stormfox.status.createElapsedLabel
import com.github.downace.stormfox.status.StableRowParts
import com.github.downace.stormfox.status.statusPresentationText
import com.github.downace.stormfox.status.statusState
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import com.intellij.ui.components.JBLabel
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MagoStatusPresentationTest {
    private fun entry(error: String? = null) =
        MagoDiagnosticActivity.Entry(MagoTool.LINT, "src/Invoice.php", 1L, error)

    @Test
    fun `idle remains an explicit state when there is no activity or failure`() {
        assertEquals(MagoStatusState.IDLE, statusState(MagoDiagnosticActivity.Snapshot(emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun `only enabled project profile inspections produce rows`() {
        assertEquals(
            setOf(MagoTool.LINT, MagoTool.GUARD),
            enabledMagoTools(setOf("MagoLint", "MagoGuard", "SomeOtherInspection")),
        )
        assertTrue(enabledMagoTools(emptySet()).isEmpty())
    }

    @Test
    fun `tool aggregation prefers oldest running item and deduplicates failures`() {
        val runningLater = MagoDiagnosticActivity.Entry(MagoTool.LINT, "one.php", 50L, null)
        val runningEarlier = MagoDiagnosticActivity.Entry(MagoTool.LINT, "two.php", 20L, null)
        val waitingOld = MagoDiagnosticActivity.Entry(MagoTool.LINT, "queued.php", 1L, null)
        val failed1 = MagoDiagnosticActivity.Entry(MagoTool.LINT, "failed1.php", 0L, "could not launch")
        val failed2 = MagoDiagnosticActivity.Entry(MagoTool.LINT, "failed2.php", 0L, "could not launch")
        val state = aggregateTool(
            MagoTool.LINT,
            MagoDiagnosticActivity.Snapshot(listOf(waitingOld), listOf(runningLater, runningEarlier), listOf(failed1, failed2)),
            1_000_000_020L,
        )
        assertEquals(MagoStatusState.FAILED, state.status)
        assertEquals("could not launch", state.errors.single())
        assertEquals("1.0s", state.elapsed)
    }

    @Test
    fun `running elapsed uses oldest running entry rather than older queued entry`() {
        val snapshot = MagoDiagnosticActivity.Snapshot(
            listOf(MagoDiagnosticActivity.Entry(MagoTool.ANALYZE, "queued", 1L)),
            listOf(
                MagoDiagnosticActivity.Entry(MagoTool.ANALYZE, "running-old", 100_000_000L),
                MagoDiagnosticActivity.Entry(MagoTool.ANALYZE, "running-new", 300_000_000L),
            ),
            emptyList(),
        )
        assertEquals(
            "1.0s",
            aggregateTool(MagoTool.ANALYZE, snapshot, 1_100_000_000L).elapsed,
        )
    }

    @Test
    fun `disabled tools are removed from project-wide activity state`() {
        val lint = entry("lint failure")
        val guard = MagoDiagnosticActivity.Entry(MagoTool.GUARD, "guard.php", 1L)
        val filtered = filteredSnapshot(
            MagoDiagnosticActivity.Snapshot(emptyList(), listOf(guard), listOf(lint)),
            setOf(MagoTool.GUARD),
        )
        assertTrue(filtered.failures.isEmpty())
        assertEquals(MagoStatusState.RUNNING, statusState(filtered))
    }

    @Test
    fun `native status presentation hides when preference is off or all inspections are disabled`() {
        assertEquals("Mago", statusPresentationText(preferenceEnabled = true, hasEnabledTools = true))
        assertEquals(null, statusPresentationText(preferenceEnabled = false, hasEnabledTools = true))
        assertEquals(null, statusPresentationText(preferenceEnabled = true, hasEnabledTools = false))
    }

    @Test
    fun `elapsed updates are deterministic for ticker refresh`() {
        assertEquals("200ms", formatElapsed(10L, 200_000_010L))
    }

    @Test
    fun `elapsed ticker update preserves row status dot and gear components`() {
        val parts = StableRowParts(JBLabel("Linter - Running"), JBLabel("●"), JBLabel(""), JPanel())
        val status = parts.status
        val dot = parts.dot
        val gear = parts.gear
        parts.updateElapsed("400ms")
        assertEquals("400ms", parts.elapsed.text)
        assertTrue(status === parts.status)
        assertTrue(dot === parts.dot)
        assertTrue(gear === parts.gear)
    }

    @Test
    fun `idle elapsed column is blank while reserving width for a running time`() {
        val elapsed = createElapsedLabel("")
        val idleSize = elapsed.preferredSize
        assertEquals("", elapsed.text)
        assertTrue(idleSize.height > 0)
        assertEquals(javax.swing.SwingConstants.RIGHT, elapsed.horizontalAlignment)
        elapsed.text = "2.4s"
        assertEquals(idleSize, elapsed.preferredSize)
        elapsed.text = ""
        assertEquals("", elapsed.text)
        elapsed.text = "12.3s"
        assertEquals(idleSize, elapsed.preferredSize)
        val metrics = elapsed.getFontMetrics(elapsed.font)
        assertTrue(reservedElapsedColumnWidth(metrics, 0) >= metrics.stringWidth("12.3s"))
        assertTrue(reservedStatusLabelWidth("Analyzer", metrics, 0) >= metrics.stringWidth("Analyzer - Running"))
    }

    @Test
    fun `elapsed label keeps visible height from idle before activity begins`() {
        val elapsed = createElapsedLabel("")
        val gear = JPanel()
        val actions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(elapsed)
            add(Box.createHorizontalStrut(8))
            add(gear)
            size = preferredSize
            doLayout()
        }
        val firstBounds = elapsed.bounds
        elapsed.text = "4.2s"
        actions.doLayout()
        val runningBounds = elapsed.bounds
        elapsed.text = ""
        actions.doLayout()
        assertTrue(firstBounds.height > 0)
        assertEquals(firstBounds.height, runningBounds.height)
        assertEquals(firstBounds.width, runningBounds.width)
        assertTrue(gear.x >= elapsed.x + elapsed.width)
    }

    @Test
    fun `waiting is distinct from running`() {
        assertEquals(MagoStatusState.WAITING, statusState(MagoDiagnosticActivity.Snapshot(listOf(entry()), emptyList(), emptyList())))
        assertEquals(MagoStatusState.RUNNING, statusState(MagoDiagnosticActivity.Snapshot(emptyList(), listOf(entry()), emptyList())))
    }

    @Test
    fun `failure takes priority and remains visible alongside active work`() {
        assertEquals(MagoStatusState.FAILED, statusState(MagoDiagnosticActivity.Snapshot(emptyList(), emptyList(), listOf(entry("Unable to start mago")))))
        assertEquals(MagoStatusState.FAILED, statusState(MagoDiagnosticActivity.Snapshot(emptyList(), listOf(entry()), listOf(entry("Process failed")))))
    }
}
