package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoDiagnosticReport as MagoLintReport
import com.github.downace.stormfox.lint.MagoDiagnosticTooltip as MagoLintTooltip
import com.github.downace.stormfox.lint.MagoRuleMetadata
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
import javax.swing.text.Element

class MagoLintTooltipTest : BasePlatformTestCase() {
    fun testAllToolsShareSummaryFallbackAndPreserveDistinctIssueInDetails() {
        for (source in com.github.downace.stormfox.lint.MagoTool.entries) {
            for (primary in listOf("This must be declared as `final`", null, " \t", "Structural flaw in App\\Models\\Shipment")) {
                val message = "Structural flaw in App\\Models\\Shipment"
                val diagnostic = diagnostic(message = message, primary = primary, code = null).copy(source = source)
                val compact = MagoLintTooltip.compact(diagnostic)
                if (primary != null && primary.isNotBlank() && primary != message) {
                    assertTrue(compact.contains("This must be declared as <code>final</code>"))
                    assertFalse(compact.contains(message))
                    val details = expanded(compact)
                    assertEquals(1, Regex(Regex.escape(message)).findAll(details).count())
                    assertFalse(details.contains("This must be declared"))
                } else {
                    assertEquals(1, Regex(Regex.escape(message)).findAll(compact).count())
                    assertNull(MagoLintTooltip.expanded(diagnostic))
                    assertFalse(compact.contains("Details"))
                    assertTrue(compact.contains("Open Mago config"))
                }
            }
        }
    }

    fun testCompactTooltipUsesPrimaryMessageAndInlineRuleCode() {
        val diagnostic = diagnostic(
            message = "Issue from the rule.",
            primary = "This arrow function can be simplified to the `...` syntax.",
            code = "prefer-first-class-callable",
            help = "Use the callable syntax.",
        )
        val compact = MagoLintTooltip.compact(diagnostic)
        assertTrue(compact.startsWith("<html><b>Mago</b>: This arrow function can be simplified to the <code>...</code> syntax."))
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
        assertTrue(details.contains("Open Mago config"))
    }

    fun testMetadataIsIgnoredForNonLintOrMismatchedRuleCode() {
        val metadata = MagoRuleMetadata(
            code = "known-rule", name = "A rule", category = "Security", description = "Details",
            level = "Error", goodExample = "good", badExample = "bad",
        )
        for (source in com.github.downace.stormfox.lint.MagoTool.entries.filter {
            it != com.github.downace.stormfox.lint.MagoTool.LINT
        }) {
            val details = MagoLintTooltip.expanded(
                diagnostic(code = metadata.code, help = "More").copy(source = source), metadata,
            )!!
            assertFalse(details.contains("A rule"))
            assertFalse(details.contains("Bad example"))
            assertTrue(details.contains("AllIcons.Actions.IntentionBulb"))
        }
        val wrongCode = MagoLintTooltip.expanded(diagnostic(code = "other-rule", help = "More"), metadata)!!
        assertFalse(wrongCode.contains("A rule"))
    }

    fun testMetadataOmitsMissingLevelAndExamples() {
        val metadata = MagoRuleMetadata(code = "rule code", name = "A rule", level = "")
        val details = MagoLintTooltip.expanded(diagnostic(code = metadata.code, help = "More"), metadata)!!
        assertTrue(details.contains("A rule"))
        assertFalse(details.contains("(default)"))
        assertFalse(details.contains("Bad example"))
        assertFalse(details.contains("Good example"))
    }

    fun testTopLevelMessageIsFallbackAndNotRepeatedInDetails() {
        val diagnostic = diagnostic(message = "Fallback message", primary = null, code = null, help = "More detail")
        val compact = MagoLintTooltip.compact(diagnostic)
        assertTrue(compact.contains("<b>Mago</b>: Fallback message"))
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

    fun testRuleMetadataRendersSpacedOutlinedBadgesInNativePane() {
        val metadata = MagoRuleMetadata(
            code = "prefer-strict-types",
            name = "Prefer strict types",
            category = "Clarity",
            description = "Use **explicit** types where possible.",
            level = "Warning",
        )
        val html = MagoLintTooltip.expanded(diagnostic(code = metadata.code, help = "Rule help"), metadata)!!

        assertTrue(html.contains("<b><font size=\"+1\">Prefer strict types</font></b>"))
        assertTrue(html.contains("<hr>"))
        assertTrue(html.contains("Clarity"))
        assertTrue(html.contains("Warning"))
        assertFalse(html.contains("(default)"))
        assertTrue(html.contains("<b>explicit</b> types"))
        assertTrue(html.contains("com.intellij.icons.AllIcons.General.InspectionsEye"))
        assertTrue(html.contains("com.intellij.icons.AllIcons.General.Warning"))
        assertTrue(html.contains("<td><b><font size=\"+1\">Prefer strict types</font></b></td>"))
        assertTrue(html.contains("<td style=\"border:1px solid #"))
        assertTrue(html.contains("cellspacing=\"4\""))
        assertTrue(html.contains("cellpadding=\"3\""))
        assertFalse(html.contains("bgcolor"))
        assertFalse(html.contains("<font color"))
        assertFalse(html.contains("(default)"))

        val pane = IdeTooltipManager.initPane(html, HintUtil.getInformationHint(), null)
        assertTrue(pane.preferredSize.width > 0)
        assertTrue(pane.preferredSize.height > 0)
        val elementNames = mutableListOf<String>()
        fun collect(element: Element) {
            elementNames += element.name.lowercase()
            for (index in 0 until element.elementCount) collect(element.getElement(index))
        }
        collect(pane.document.defaultRootElement)
        assertTrue(elementNames.contains("hr"))
        assertTrue(elementNames.contains("table"))
        val document = pane.document as javax.swing.text.StyledDocument
        assertTrue(document.getText(0, document.length).contains("Clarity"))
        pane.setSize(pane.preferredSize)
        pane.doLayout()
        val rootView = pane.ui.getRootView(pane)
        val viewAttributes = mutableListOf<String>()
        fun collectViews(view: javax.swing.text.View) {
            val attrs = view.attributes
            if (attrs != null) {
                val names = attrs.attributeNames
                while (names.hasMoreElements()) {
                    val key = names.nextElement()
                    viewAttributes += "$key=${attrs.getAttribute(key)}"
                }
            }
            for (index in 0 until view.viewCount) collectViews(view.getView(index))
        }
        collectViews(rootView)
        val resolvedStyles = viewAttributes.joinToString("\n")
        assertTrue(resolvedStyles, viewAttributes.any { it == "border-top-style=solid" })
        assertTrue(resolvedStyles, viewAttributes.any { it == "border-top-width=1px" })
    }

    fun testRuleMetadataTextIsEscaped() {
        val metadata = MagoRuleMetadata(
            code = "safe", name = "Rule <unsafe>", category = "Safety & security",
            level = "Warning <unsafe>",
        )
        val html = MagoLintTooltip.expanded(diagnostic(code = metadata.code, help = "Help"), metadata)!!
        assertTrue(html.contains("Rule &lt;unsafe&gt;"))
        assertTrue(html.contains("Safety &amp; security"))
        assertTrue(html.contains("Warning &lt;unsafe&gt;"))
        assertFalse(html.contains("(default)"))
        assertFalse(html.contains("<unsafe>"))
    }

    fun testLargeRuleExamplesAreNotIncludedInCompactOrExpandedTooltip() {
        val badExample = "<?php\n" + "BAD_SAMPLE_SENTINEL\n".repeat(20_000)
        val goodExample = "<?php\n" + "GOOD_SAMPLE_SENTINEL\n".repeat(20_000)
        val metadata = MagoRuleMetadata(
            code = "large-example-rule", name = "Large example rule",
            badExample = badExample, goodExample = goodExample,
        )
        val diagnostic = diagnostic(code = metadata.code, help = "Rule help")
        val compact = MagoLintTooltip.compact(diagnostic, metadata)
        val details = expanded(compact)

        assertFalse(compact.contains("BAD_SAMPLE_SENTINEL"))
        assertFalse(compact.contains("GOOD_SAMPLE_SENTINEL"))
        assertFalse(details.contains("BAD_SAMPLE_SENTINEL"))
        assertFalse(details.contains("GOOD_SAMPLE_SENTINEL"))
        val detailsLink = Regex("<a href=\\\"(stormfox-mago-details:[^\\\"]+)\\\">Details</a>")
            .find(compact)!!.groupValues[1]
        val encodedDetails = detailsLink.removePrefix(MagoLintTooltip.PREFIX)
        assertEquals(details, MagoLintTooltip.decode(encodedDetails))
        assertTrue(compact.length < 4096)
        assertFalse(details.contains("Bad example"))
        assertFalse(details.contains("Good example"))
    }

    fun testExamplesAloneDoNotCreateAnEmptyRuleSection() {
        val metadata = MagoRuleMetadata(
            code = "example-only-rule", badExample = "bad sample", goodExample = "good sample",
        )
        val diagnostic = diagnostic(code = metadata.code, primary = null)
        assertEquals(MagoLintTooltip.expanded(diagnostic), MagoLintTooltip.expanded(diagnostic, metadata))
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

    fun testConfigLinkPayloadIsSafeAndStrictlyDecoded() {
        for (source in com.github.downace.stormfox.lint.MagoTool.entries) {
            val diagnostic = diagnostic(code = "odd rule/ code", help = "Details").copy(source = source)
            val details = expanded(MagoLintTooltip.compact(diagnostic))
            val ref = Regex("<a href=\\\"(stormfox-mago-config:[^\\\"]+)\\\">Open Mago config</a>")
                .find(details)!!.groupValues[1]
            assertEquals(source.command to "odd rule/ code", MagoLintTooltip.decodeConfig(ref.removePrefix(MagoLintTooltip.CONFIG_PREFIX)))
        }
        assertNull(MagoLintTooltip.decodeConfig("%%%"))
        assertNull(MagoLintTooltip.decodeConfig(java.util.Base64.getUrlEncoder().encodeToString("unknown\u0000rule".toByteArray())))
        assertTrue(expanded(MagoLintTooltip.compact(diagnostic(code = "any rule", help = "Details"))).contains("Rule documentation"))
    }

    fun testNoExtraDetailsStillOffersConfigLinkWithNoRuleCode() {
        val noDetails = diagnostic(code = null, primary = "Issue message")
        val compact = MagoLintTooltip.compact(noDetails)
        assertNull(MagoLintTooltip.expanded(noDetails))
        val ref = Regex("<a href=\\\"(stormfox-mago-config:[^\\\"]+)\\\">Open Mago config</a>")
            .find(compact)!!.groupValues[1]
        assertEquals("lint" to null, MagoLintTooltip.decodeConfig(ref.removePrefix(MagoLintTooltip.CONFIG_PREFIX)))
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
