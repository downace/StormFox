package com.github.downace.stormfox

import com.github.downace.stormfox.settings.MagoConfigurable
import com.github.downace.stormfox.settings.MagoSettings
import com.github.downace.stormfox.status.MagoStatusBarWidget
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ContextHelpLabel
import java.awt.Container
import java.awt.Component
import java.awt.Point
import javax.swing.JLabel
import javax.swing.JEditorPane
import javax.swing.SwingUtilities
import javax.swing.JCheckBox
import javax.swing.JTextField

class MagoConfigurableLifecycleTest : BasePlatformTestCase() {
    fun testFormatterAndInspectionContentShareIndentedLeadingEdge() {
        val configurable = MagoConfigurable(project)
        try {
            val root = configurable.createComponent() as Container
            layoutAt(root, 800)
            val labels = descendants(root).filterIsInstance<JLabel>()
            val selector = labels.single { it.text == "Use Mago Formatter for:" }
            val prose = descendants(root).filterIsInstance<JEditorPane>()
                .single { it.text.contains("Mago Formatter replaces") }
            val inspection = descendants(root).single { it is com.intellij.ui.components.ActionLink && it.text == "Linter" }
            assertTrue("Formatter options should precede their explanation",
                SwingUtilities.convertPoint(selector, Point(0, 0), root).y <
                    SwingUtilities.convertPoint(prose, Point(0, 0), root).y)
            val selectorX = SwingUtilities.convertPoint(selector, Point(0, 0), root).x
            assertEquals(selectorX, SwingUtilities.convertPoint(inspection, Point(0, 0), root).x)
            val sectionHeadings = labels.filter { it.text in listOf("Formatter", "Inspections") }
            assertEquals(listOf("Formatter", "Inspections"), sectionHeadings.map { it.text }.toList())
            sectionHeadings.forEach { heading ->
                assertTrue("Section body should be indented from ${heading.text}",
                    selectorX > SwingUtilities.convertPoint(heading, Point(0, 0), root).x)
            }
        } finally {
            configurable.disposeUIResources()
        }
    }
    fun testGeneralFieldsExpandWithTheSettingsPage() {
        val configurable = MagoConfigurable(project)
        try {
            val root = configurable.createComponent() as Container
            val ui = MagoConfigurable::class.java.getDeclaredField("ui").apply { isAccessible = true }.get(configurable)
            val fields = listOf("binaryField", "executionRootField", "configField").map { name ->
                ui.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(ui) as Component
            }
            layoutAt(root, 640)
            val narrow = fields.map { it.width }
            layoutAt(root, 960)
            val wide = fields.map { it.width }
            assertEquals(3, wide.size)
            wide.zip(narrow).forEach { (larger, smaller) ->
                assertTrue("Field should grow with the settings page ($smaller -> $larger)", larger > smaller)
            }
        } finally {
            configurable.disposeUIResources()
        }
    }
    fun testGeneralHintsAndStatusUseTheirFieldColumnWithoutExtraCheckboxIndent() {
        val configurable = MagoConfigurable(project)
        try {
            val root = configurable.createComponent() as Container
            layoutAt(root, 800)
            val ui = MagoConfigurable::class.java.getDeclaredField("ui").apply { isAccessible = true }.get(configurable)
            fun field(name: String): Component =
                ui.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(ui) as Component
            fun x(component: Component) = SwingUtilities.convertPoint(component, Point(0, 0), root).x
            fun y(component: Component) = SwingUtilities.convertPoint(component, Point(0, 0), root).y

            val binary = field("binaryField")
            val executionRoot = field("executionRootField")
            val config = field("configField")
            val helpLabels = descendants(root).filterIsInstance<ContextHelpLabel>().toList()
            val status = ui.javaClass.getDeclaredField("binaryRow").apply { isAccessible = true }.get(ui)
                .let { it.javaClass.getDeclaredField("status").apply { isAccessible = true }.get(it) }
                .let { it.javaClass.getDeclaredMethod("getComponent").apply { isAccessible = true }.invoke(it) as Component }
            val checkbox = descendants(root).filterIsInstance<JCheckBox>()
                .single { it.text == "Show Mago status bar widget" }

            assertEquals(2, helpLabels.size)
            assertEquals(1, descendants(root).filterIsInstance<JLabel>().count { it.text == "Mago command:" })
            assertEquals(1, descendants(root).filterIsInstance<JLabel>().count { it.text == "Execution project root:" })
            assertFalse(descendants(root).filterIsInstance<JLabel>().any { it.text?.contains("Enter one of:") == true })
            assertFalse(descendants(root).filterIsInstance<JLabel>().any { it.text?.contains("If Mago runs in a container") == true })
            assertTrue("Validation status should sit under its input: ${x(binary)} vs ${x(status)}",
                kotlin.math.abs(x(binary) - x(status)) <= 4)
            assertTrue("Status line should remain directly below the config field", y(checkbox) - (y(config) + config.height) < 50)
            assertTrue("Status widget toggle should align with the page content, not the input column", x(checkbox) < x(config))
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testFormatterHelpAndNavigationLinksStayCompact() {
        val configurable = MagoConfigurable(project)
        try {
            val root = configurable.createComponent() as Container
            layoutAt(root, 800)
            val labels = descendants(root).filterIsInstance<JLabel>()
            val text = descendants(root).filterIsInstance<JEditorPane>()
                .single { it.text.contains("Mago Formatter replaces") }
            val php = descendants(root).filterIsInstance<JCheckBox>().single { it.text == "PHP" }
            val twig = descendants(root).filterIsInstance<JCheckBox>().single { it.text == "Twig" }
            val requirement = labels.single { it.text?.contains("requires Mago 1.60+") == true }
            assertEquals(php.font.size, text.font.size)
            assertEquals(twig.font.size, requirement.font.size)
            assertEquals(twig.foreground, php.foreground)
            assertEquals(com.intellij.util.ui.UIUtil.getContextHelpForeground(), requirement.foreground)
            assertTrue(text.text.replace(Regex("\\s+"), " ").contains("Reformat code"))

            val prose = descendants(root).filterIsInstance<JEditorPane>()
                .single { it.text.contains("Mago Formatter replaces") }
            val proseMarkup = prose.text.replace(Regex("\\s+"), " ")
            assertTrue(proseMarkup.contains("Enable <b>Reformat code</b> in <a href=\"actionsOnSave\">Actions on Save</a>"))
            assertTrue(proseMarkup.contains("to format files on save."))
            val freshConfigurable = MagoConfigurable(project)
            try {
                val freshRoot = freshConfigurable.createComponent() as Container
                repeat(3) { layoutAt(freshRoot, 440) }
                val wrapped = descendants(freshRoot).filterIsInstance<JEditorPane>()
                    .single { it.text.contains("Mago Formatter replaces") }
                val renderedText = wrapped.document.getText(0, wrapped.document.length)
                assertTrue(renderedText.replace(Regex("\\s+"), " ").contains("to format files on save."))
                // Two widths can legitimately occupy the same number of lines; inspect the actual layout.
                val glyphs = renderedText.indices.filter { !renderedText[it].isWhitespace() }
                    .map { wrapped.modelToView2D(it).bounds2D }
                assertTrue("Formatter paragraph should occupy multiple visual lines",
                    glyphs.map { it.y }.distinct().size > 1)
                val insets = wrapped.insets
                glyphs.forEach { glyph ->
                    assertTrue("Text must fit inside the paragraph: $glyph in ${wrapped.size}",
                        glyph.x >= insets.left - 1 && glyph.maxX <= wrapped.width - insets.right + 1 &&
                            glyph.y >= insets.top - 1 && glyph.maxY <= wrapped.height - insets.bottom + 1)
                }
            } finally {
                freshConfigurable.disposeUIResources()
            }

            val inspectionLinks = descendants(root).filterIsInstance<com.intellij.ui.components.ActionLink>()
                .filter { it.text in listOf("Linter", "Analyzer", "Guard", "Twig Linter") }.toList()
            assertEquals(listOf("Linter", "Analyzer", "Guard", "Twig Linter"), inspectionLinks.map { it.text })
            inspectionLinks.zipWithNext().forEach { (before, after) ->
                assertTrue("Inspection links should form a vertical list",
                    SwingUtilities.convertPoint(after, Point(0, 0), root).y >
                        SwingUtilities.convertPoint(before, Point(0, 0), root).y)
            }
        } finally {
            configurable.disposeUIResources()
        }
    }
    fun testValidationStatusUsesAvailableWidthWithoutWideningThePage() {
        val configurable = MagoConfigurable(project)
        try {
            val root = configurable.createComponent() as Container
            val ui = MagoConfigurable::class.java.getDeclaredField("ui").apply { isAccessible = true }.get(configurable)
            val binaryRow = ui.javaClass.getDeclaredField("binaryRow").apply { isAccessible = true }.get(ui)
            val status = binaryRow.javaClass.getDeclaredField("status").apply { isAccessible = true }.get(binaryRow)
            layoutAt(root, 640)
            val preferredWidthBeforeMessage = root.preferredSize.width
            val kind = status.javaClass.declaredClasses.single { it.isEnum }
            val errorKind = kind.enumConstants.single { it.toString() == "ERROR" }
            status.javaClass.getDeclaredMethod("show", kind, String::class.java).apply { isAccessible = true }
                .invoke(status, errorKind, "Mago returned a very long validation response ".repeat(20))
            val label = status.javaClass.getDeclaredField("label").apply { isAccessible = true }.get(status) as JLabel
            val fullMessage = "Mago returned a very long validation response ".repeat(20)
            assertEquals(fullMessage, label.text)
            assertEquals(fullMessage, label.toolTipText)

            layoutAt(root, 640)
            assertEquals("A long process response must not widen the settings page",
                preferredWidthBeforeMessage, root.preferredSize.width)
            val narrowStatusWidth = label.width
            layoutAt(root, 960)
            val wideStatusWidth = label.width
            assertTrue("Status should use the available field width ($narrowStatusWidth -> $wideStatusWidth)",
                wideStatusWidth > narrowStatusWidth)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testVersionWarningIconStaysBesideShortStatusTextAndKeepsTooltip() {
        val configurable = MagoConfigurable(project)
        try {
            val root = configurable.createComponent() as Container
            val ui = MagoConfigurable::class.java.getDeclaredField("ui").apply { isAccessible = true }.get(configurable)
            val binaryRow = ui.javaClass.getDeclaredField("binaryRow").apply { isAccessible = true }.get(ui)
            val status = binaryRow.javaClass.getDeclaredField("status").apply { isAccessible = true }.get(binaryRow)
            val message = "mago 1.24.0"
            val warning = "This version does not support filepath-aware exclusions."
            status.javaClass.getDeclaredMethod("showSuccess", String::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(status, message, warning)
            layoutAt(root, 800)

            val label = status.javaClass.getDeclaredField("label").apply { isAccessible = true }.get(status) as JLabel
            assertEquals(message, label.text)
            assertTrue(label.toolTipText.contains(message))
            assertTrue(label.toolTipText.contains(warning))
            assertTrue(label.accessibleContext.accessibleDescription.contains(warning))
            assertNotNull(label.icon)
            assertEquals(javax.swing.SwingConstants.LEFT, label.horizontalTextPosition)

            val iconBounds = java.awt.Rectangle()
            val textBounds = java.awt.Rectangle()
            javax.swing.SwingUtilities.layoutCompoundLabel(
                label,
                label.getFontMetrics(label.font),
                label.text,
                label.icon,
                label.verticalAlignment,
                label.horizontalAlignment,
                label.verticalTextPosition,
                label.horizontalTextPosition,
                java.awt.Rectangle(0, 0, label.width, label.height),
                iconBounds,
                textBounds,
                label.iconTextGap,
            )
            assertEquals("Warning should follow the version with native label spacing",
                textBounds.x + textBounds.width + label.iconTextGap, iconBounds.x)
            assertTrue("Warning should remain beside the version, not at the far edge",
                iconBounds.x + iconBounds.width < label.width - 30)
        } finally {
            configurable.disposeUIResources()
        }
    }
    private fun layoutAt(root: Container, width: Int) {
        root.setSize(width, root.preferredSize.height)
        layoutTree(root)
    }
    private fun layoutTree(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutTree)
    }
    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }

    fun testConstructorDoesNotAllocateSettingsUi() {
        val configurable = MagoConfigurable(project)
        assertFalse(configurable.isModified())
        val ui = MagoConfigurable::class.java.declaredFields.single { it.type.simpleName == "SettingsUi" }
        ui.isAccessible = true
        assertNull(ui.get(configurable))
        configurable.apply()
        configurable.reset()
        configurable.disposeUIResources()
    }

    fun testUiResourcesCanBeDisposedAndRecreated() {
        val configurable = MagoConfigurable(project)
        val first = configurable.createComponent()
        assertSame(first, configurable.createComponent())

        configurable.disposeUIResources()
        assertFalse(configurable.isModified())

        val recreated = configurable.createComponent()
        assertNotSame(first, recreated)
        configurable.reset()
        configurable.disposeUIResources()
    }

    fun testExecutionRootParticipatesInResetApplyAndModifiedState() {
        val configurable = MagoConfigurable(project)
        val state = MagoSettings.getInstance(project).state
        val originalRoot = state.executionRoot
        try {
            state.executionRoot = "/stored"
            configurable.createComponent()
            val ui = MagoConfigurable::class.java.getDeclaredField("ui").apply { isAccessible = true }.get(configurable)
            val rootField = ui.javaClass.getDeclaredField("executionRootField").apply { isAccessible = true }
                .get(ui) as JTextField

            assertEquals("/stored", rootField.text)
            assertFalse(configurable.isModified())
            rootField.text = "/app"
            assertTrue(configurable.isModified())
            configurable.apply()
            assertEquals("/app", state.executionRoot)
            assertFalse(configurable.isModified())

            rootField.text = "/changed"
            configurable.reset()
            assertEquals("/app", rootField.text)
            assertFalse(configurable.isModified())
        } finally {
            state.executionRoot = originalRoot
            configurable.disposeUIResources()
        }
    }

    fun testStatusWidgetPreferenceParticipatesInResetApplyAndModifiedState() {
        val configurable = MagoConfigurable(project)
        val state = MagoSettings.getInstance(project).state
        val original = state.showStatusWidget
        try {
            state.showStatusWidget = true
            configurable.createComponent()
            val ui = MagoConfigurable::class.java.getDeclaredField("ui").apply { isAccessible = true }.get(configurable)
            val checkbox = ui.javaClass.getDeclaredField("showStatusWidgetCheckBox").apply { isAccessible = true }
                .get(ui) as JCheckBox

            assertTrue(checkbox.isSelected)
            assertFalse(configurable.isModified())
            checkbox.isSelected = false
            assertTrue(configurable.isModified())
            configurable.apply()
            assertFalse(state.showStatusWidget)
            assertFalse(configurable.isModified())

            checkbox.isSelected = true
            configurable.reset()
            assertFalse(checkbox.isSelected)
            assertFalse(configurable.isModified())
        } finally {
            state.showStatusWidget = original
            MagoStatusBarWidget.refreshOpenProjects()
            configurable.disposeUIResources()
        }
    }

    fun testFormatterCheckboxesAreIndependentAndMigrateLegacyValue() {
        val configurable = MagoConfigurable(project)
        val state = MagoSettings.getInstance(project).state
        val originalPhp = state.enabled
        val originalTwig = state.twigEnabled
        try {
            state.enabled = false
            state.twigEnabled = null
            configurable.createComponent()
            val ui = MagoConfigurable::class.java.getDeclaredField("ui").apply { isAccessible = true }.get(configurable)
            val php = ui.javaClass.getDeclaredField("phpFormatterCheckBox").apply { isAccessible = true }
                .get(ui) as JCheckBox
            val twig = ui.javaClass.getDeclaredField("twigFormatterCheckBox").apply { isAccessible = true }
                .get(ui) as JCheckBox

            assertFalse(php.isSelected)
            assertFalse(twig.isSelected)
            assertFalse(configurable.isModified())

            twig.isSelected = true
            assertTrue(configurable.isModified())
            configurable.apply()
            assertFalse(state.enabled)
            assertEquals(true, state.twigEnabled)
            assertFalse(configurable.isModified())

            php.isSelected = true
            twig.isSelected = false
            configurable.apply()
            assertTrue(state.enabled)
            assertEquals(false, state.twigEnabled)
            assertFalse(configurable.isModified())

            php.isSelected = false
            twig.isSelected = true
            configurable.reset()
            assertTrue(php.isSelected)
            assertFalse(twig.isSelected)
            assertFalse(configurable.isModified())
        } finally {
            state.enabled = originalPhp
            state.twigEnabled = originalTwig
            configurable.disposeUIResources()
        }
    }
}
