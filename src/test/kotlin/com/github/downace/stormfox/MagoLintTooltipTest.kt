package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoLintReport
import com.github.downace.stormfox.lint.MagoLintTooltip
import com.github.downace.stormfox.lint.MagoRelatedNavigation
import com.github.downace.stormfox.lint.MagoShowRelatedLocationsAction
import com.intellij.codeInsight.hint.TooltipLinkHandlerEP
import com.intellij.codeInsight.hint.HintUtil
import com.intellij.ide.IdeTooltipManager
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
import com.intellij.modcommand.ModHighlight
import com.intellij.modcommand.ModNavigate
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MagoLintTooltipTest : BasePlatformTestCase() {
    fun testCompactTooltipUsesPrimaryMessageAndInlineRuleCode() {
        val diagnostic = diagnostic(
            message = "Issue from the rule.",
            primary = "This arrow function can be simplified to the `...` syntax.",
            code = "prefer-first-class-callable",
            help = "Use the callable syntax.",
        )
        val compact = MagoLintTooltip.compact(diagnostic)
        assertTrue(compact.startsWith("<html><b>Mago Linter</b>: This arrow function can be simplified to the <code>...</code> syntax."))
        assertTrue(compact.contains("<code>prefer-first-class-callable</code>"))
        assertTrue(compact.indexOf("<code>prefer-first-class-callable</code>") < compact.indexOf("Details"))
        assertFalse(compact.contains("Issue from the rule"))

        val details = expanded(compact)
        assertTrue(details.indexOf("Issue from the rule.") < details.indexOf("IntentionBulb"))
        assertFalse(details.contains("This arrow function can be simplified"))
        assertTrue(details.contains("AllIcons.Actions.IntentionBulb\" alt=\"Help\"/>&nbsp;&nbsp;Use the callable syntax."))
        assertFalse(details.contains("<b>Help</b>"))
        assertEquals(1, "Use the callable syntax.".toRegex().findAll(details).count())
        assertTrue(details.contains("Rule documentation"))
    }

    fun testTopLevelMessageIsFallbackAndNotRepeatedInDetails() {
        val diagnostic = diagnostic(message = "Fallback message", primary = null, code = null, help = "More detail")
        val compact = MagoLintTooltip.compact(diagnostic)
        assertTrue(compact.contains("<b>Mago Linter</b>: Fallback message"))
        val details = expanded(compact)
        assertFalse(details.contains("Fallback message"))
        assertTrue(details.contains("More detail"))
    }

    fun testIdenticalMessagesAreShownOnlyInCompactTooltip() {
        val diagnostic = diagnostic(message = "Same text", primary = " Same text ", code = null)
        val compact = MagoLintTooltip.compact(diagnostic)
        assertEquals(1, "Same text".toRegex().findAll(compact).count())
        assertNull(MagoLintTooltip.expanded(diagnostic))
        assertFalse(compact.contains("Details"))
    }

    fun testHelpAndNotesUseNativeIconsWithSpacing() {
        val html = MagoLintTooltip.expanded(diagnostic(
            help = "Use `strict_types`.", notes = listOf("This improves consistency.", "A second note."),
        ))!!
        val helpIcon = "<icon src=\"com.intellij.icons.AllIcons.Actions.IntentionBulb\" alt=\"Help\"/>&nbsp;&nbsp;"
        val notesIcon = "<icon src=\"com.intellij.icons.AllIcons.General.Information\" alt=\"Notes\"/>&nbsp;&nbsp;"
        assertTrue(html.contains("${helpIcon}Use <code>strict_types</code>."))
        assertTrue(html.contains("${notesIcon}This improves consistency.<br>A second note."))
        assertFalse(html.contains("<b>Help</b>"))
        assertFalse(html.contains("<b>Notes</b>"))
        assertEquals(1, "Use <code>strict_types</code>.".toRegex().findAll(html).count())
        assertEquals(1, "This improves consistency.".toRegex().findAll(html).count())
        assertTrue(html.indexOf(helpIcon) < html.indexOf(notesIcon))
        assertTrue(html.indexOf("Rule documentation") > html.indexOf(notesIcon))
        assertFalse(html.contains("Related locations"))
        val pane = IdeTooltipManager.initPane(html, HintUtil.getInformationHint(), null)
        assertTrue(pane.preferredSize.width > 0)
        assertTrue(pane.preferredSize.height > 0)
    }

    fun testTooltipLinkDispatchAndMalformedPayloads() {
        myFixture.configureByText("Sample.php", "<?php")
        val compact = MagoLintTooltip.compact(diagnostic(help = "Extra help"))
        val ref = firstHref(compact)
        assertTrue(ref.startsWith(MagoLintTooltip.PREFIX))
        assertEquals("Details", TooltipLinkHandlerEP.getDescriptionTitle(ref, myFixture.editor))
        assertTrue(TooltipLinkHandlerEP.getDescription(ref, myFixture.editor)!!.contains("Extra help"))
        assertNull(TooltipLinkHandlerEP.getDescription("stormfox-mago-details:%%%", myFixture.editor))
        assertNull(TooltipLinkHandlerEP.getDescription("https://example.com", myFixture.editor))
    }

    fun testTooltipFieldsAreEscapedAndRuleDocumentationIsLast() {
        val diagnostic = diagnostic(
            message = "Issue <script>", primary = "Primary <message>", code = "a\"b",
            help = "Help <unsafe>", notes = listOf("Note & detail"),
        )
        val compact = MagoLintTooltip.compact(diagnostic)
        assertTrue(compact.contains("Primary &lt;message&gt;"))
        assertFalse(compact.contains("<script>"))
        assertTrue(compact.contains("<code>a&quot;b</code>"))
        val details = expanded(compact)
        assertTrue(details.startsWith("<html><body><p>Issue &lt;script&gt;</p>"))
        assertTrue(details.contains("Help &lt;unsafe&gt;"))
        assertTrue(details.contains("Note &amp; detail"))
        assertTrue(details.contains("#a%22b"))
        assertTrue(details.indexOf("Rule documentation") > details.indexOf("Notes"))
    }

    fun testShowRelatedLocationsUsesNativeChooserAndRejectsStaleSnapshot() {
        val text = "<?php echo 1;"
        myFixture.configureByText("Sample.php", text)
        val hash = MagoRelatedNavigation.snapshotHash(text)
        val range = TextRange(6, 10)
        val location = MagoLintReport.RelatedLocation(
            "Related declaration", "Sample.php", range = range, filePath = myFixture.file.virtualFile.path,
        )
        val context = ActionContext.from(myFixture.editor, myFixture.file)
        val action = MagoShowRelatedLocationsAction(listOf(location), hash, myFixture.file.virtualFile)
        assertNotNull(action.getPresentation(context))
        val chooser = action.perform(context) as ModChooseAction
        val command = chooser.actions.single().perform(context)
        val navigate = command.unpack().filterIsInstance<ModNavigate>().single()
        val highlight = command.unpack().filterIsInstance<ModHighlight>().single()
        assertSame(myFixture.file.virtualFile, navigate.file)
        assertEquals(range.startOffset, navigate.selectionStart)
        assertEquals(range.endOffset, navigate.selectionEnd)
        assertEquals(range, highlight.highlights.single().range)

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(myFixture.editor.document.textLength, " ")
        }
        PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
        val staleContext = ActionContext.from(myFixture.editor, myFixture.file)
        assertNull(action.getPresentation(staleContext))
        assertTrue(action.perform(staleContext).isEmpty)
        assertTrue(chooser.actions.single().perform(staleContext).isEmpty)
    }

    private fun expanded(compact: String): String {
        myFixture.configureByText("Sample.php", "<?php")
        return TooltipLinkHandlerEP.getDescription(firstHref(compact), myFixture.editor)!!
    }

    private fun firstHref(html: String): String = Regex("<a href=\\\"([^\\\"]+)\\\"").find(html)!!.groupValues[1]

    private fun diagnostic(
        message: String = "Issue message",
        code: String? = "rule code",
        primary: String? = "Primary detail",
        notes: List<String> = emptyList(),
        help: String? = null,
    ) = MagoLintReport.Diagnostic(TextRange(0, 1), message, code, "Warning", primary, notes, help)
}
