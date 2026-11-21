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
import com.github.downace.stormfox.status.MagoStatusBarWidget
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JPanel
import com.intellij.ui.components.JBLabel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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

class MagoStatusPopupGeometryTest : BasePlatformTestCase() {
    fun testPopupClosesWhenEnabledToolsChange() {
        val widget = MagoStatusBarWidget(project)
        com.intellij.openapi.util.Disposer.register(testRootDisposable, widget)
        val createPanel = MagoStatusBarWidget::class.java.getDeclaredMethod(
            "detailsPanel", Set::class.java, MagoDiagnosticActivity.Snapshot::class.java,
        ).apply { isAccessible = true }
        val view = createPanel.invoke(
            widget, MagoTool.entries.toSet(),
            MagoDiagnosticActivity.Snapshot(emptyList(), emptyList(), emptyList()),
        )
        var cancelled = false
        val popup = java.lang.reflect.Proxy.newProxyInstance(
            com.intellij.openapi.ui.popup.JBPopup::class.java.classLoader,
            arrayOf(com.intellij.openapi.ui.popup.JBPopup::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "isDisposed" -> false
                "cancel" -> { cancelled = true; null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> error("Unexpected popup call: ${method.name}")
            }
        } as com.intellij.openapi.ui.popup.JBPopup
        fun widgetField(name: String) = MagoStatusBarWidget::class.java.getDeclaredField(name).apply { isAccessible = true }
        widgetField("activePopup").set(widget, popup)
        widgetField("enabledToolsCache").set(widget, setOf(MagoTool.entries.first()))
        @Suppress("UNCHECKED_CAST")
        val panels = widgetField("popupPanel").get(widget) as MutableMap<Any, Any>
        panels[popup] = view
        MagoStatusBarWidget::class.java.getDeclaredMethod(
            "updatePopup", com.intellij.openapi.ui.popup.JBPopup::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(widget, popup, false)
        assertTrue("Changing enabled tools should close the popup", cancelled)
    }

    fun testPopupDslRowsFitAndReserveIdleElapsedGeometry() {
        val widget = MagoStatusBarWidget(project)
        com.intellij.openapi.util.Disposer.register(testRootDisposable, widget)
        val createPanel = MagoStatusBarWidget::class.java.getDeclaredMethod(
            "detailsPanel", Set::class.java, MagoDiagnosticActivity.Snapshot::class.java,
        ).apply { isAccessible = true }
        val view = createPanel.invoke(
            widget, MagoTool.entries.toSet(),
            MagoDiagnosticActivity.Snapshot(emptyList(), emptyList(), emptyList()),
        ) ?: error("Expected popup view")
        val root = view.javaClass.getDeclaredField("panel").apply { isAccessible = true }.get(view) as JPanel
        val rows = view.javaClass.getDeclaredField("rows").apply { isAccessible = true }
            .get(view) as Map<*, *>
        assertEquals(com.intellij.util.ui.JBUI.insets(10, 12), root.insets)
        root.setSize(root.preferredSize)
        layoutRecursively(root)
        val initialPreferredSize = root.preferredSize
        val geometry = rows.values.filterNotNull().map { row ->
            val component = row.field<JPanel>("component")
            assertEquals(java.awt.Insets(0, 0, 0, 0), component.insets)
            val dot = row.field<JComponent>("dot")
            val status = row.field<JComponent>("statusText")
            val elapsed = row.field<JBLabel>("elapsedText")
            val gear = row.field<JComponent>("gear")
            val dotBounds = javax.swing.SwingUtilities.convertRectangle(dot.parent, dot.bounds, root)
            val statusBounds = javax.swing.SwingUtilities.convertRectangle(status.parent, status.bounds, root)
            val elapsedBounds = javax.swing.SwingUtilities.convertRectangle(elapsed.parent, elapsed.bounds, root)
            val gearBounds = javax.swing.SwingUtilities.convertRectangle(gear.parent, gear.bounds, root)
            listOf(dotBounds, statusBounds, elapsedBounds, gearBounds).forEach { bounds ->
                assertTrue("Control lies outside popup: $bounds / ${root.size}", root.bounds.contains(bounds))
            }
            assertTrue("Status and elapsed columns overlap", statusBounds.x + statusBounds.width <= elapsedBounds.x)
            assertTrue("Gear should sit to the right of elapsed text", gearBounds.x >= elapsedBounds.x + elapsedBounds.width)
            assertTrue("Elapsed column should reserve visible space", elapsedBounds.width > 0 && elapsedBounds.height > 0)
            Triple(component, elapsed, gearBounds)
        }
        assertEquals("All gear actions should align on the right", 1, geometry.map { it.third.x }.distinct().size)
        geometry.forEach { (_, elapsed, _) -> elapsed.text = "999.9s" }
        layoutRecursively(root)
        assertEquals(initialPreferredSize, root.preferredSize)
        geometry.forEach { (component, elapsed, initialGearBounds) ->
            val currentElapsedBounds = javax.swing.SwingUtilities.convertRectangle(elapsed.parent, elapsed.bounds, root)
            val currentGear = rows.values.filterNotNull().first { it.field<JComponent>("component") === component }
                .field<JComponent>("gear")
            val currentGearBounds = javax.swing.SwingUtilities.convertRectangle(currentGear.parent, currentGear.bounds, root)
            assertEquals(initialGearBounds, currentGearBounds)
            assertEquals(reservedElapsedColumnWidth(elapsed.getFontMetrics(elapsed.font), 0), currentElapsedBounds.width)
        }
    }

    private fun layoutRecursively(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutRecursively)
    }

    private inline fun <reified T> Any.field(name: String): T =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
}
