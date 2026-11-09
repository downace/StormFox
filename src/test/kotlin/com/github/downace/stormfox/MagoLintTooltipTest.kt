package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoLintReport
import com.github.downace.stormfox.lint.MagoLintTooltip
import com.intellij.codeInsight.hint.TooltipLinkHandlerEP
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MagoLintTooltipTest : BasePlatformTestCase() {
    fun testCompactAndPlatformExpansionKeepDiagnosticOnlyOnce() {
        myFixture.configureByText("Sample.php", "<?php")
        val diagnostic = diagnostic(message = "Class has high complexity.", primary = "Complexity is 27; threshold is 15.")
        val compact = MagoLintTooltip.compact(diagnostic)
        val ref = firstHref(compact)
        assertTrue(ref.startsWith(MagoLintTooltip.PREFIX))
        assertTrue(compact.contains(diagnostic.message))
        assertTrue(compact.contains(diagnostic.primaryMessage!!))
        val expanded = TooltipLinkHandlerEP.getDescription(ref, myFixture.editor)!!
        assertEquals("Details", TooltipLinkHandlerEP.getDescriptionTitle(ref, myFixture.editor))
        assertFalse(expanded.contains(diagnostic.message))
        assertFalse(expanded.contains(diagnostic.primaryMessage!!))
        assertTrue(expanded.contains("Rule documentation"))
    }

    fun testHelpPrecedesNotesAndDocumentationComesLast() {
        val html = MagoLintTooltip.expanded(diagnostic(
            help = "Use `strict_types`.", notes = listOf("This improves consistency."),
        ))!!
        assertTrue(html.indexOf("<b>Help</b>") < html.indexOf("<b>Notes</b>"))
        assertTrue(html.indexOf("Rule documentation") > html.indexOf("<b>Notes</b>"))
        assertTrue(html.contains("<code>strict_types</code>"))
        assertTrue(html.contains("https://mago.carthage.software/latest/en/tools/linter/rules/#rule%20code"))
    }

    fun testOptionalContentSuppressesEmptyDetailsLink() {
        val diagnostic = diagnostic(code = null, primary = null)
        assertNull(MagoLintTooltip.expanded(diagnostic))
        assertFalse(MagoLintTooltip.compact(diagnostic).contains("Details"))
    }

    fun testPlatformDispatchRejectsMalformedAndUnregisteredLinks() {
        myFixture.configureByText("Sample.php", "<?php")
        assertNull(TooltipLinkHandlerEP.getDescription("stormfox-mago-details:%%%", myFixture.editor))
        assertNull(TooltipLinkHandlerEP.getDescription("https://example.com", myFixture.editor))
    }

    fun testRelatedLocationsOmitUnconfirmedLineNumber() {
        val html = MagoLintTooltip.expanded(diagnostic(
            relatedLocations = listOf(MagoLintReport.RelatedLocation("Related declaration", "src/File.php", 12)),
        ))!!
        assertTrue(html.contains("Related declaration — src/File.php"))
        assertFalse(html.contains(":12"))
    }

    fun testPayloadsAreIsolatedAndDiagnosticFieldsAreEscaped() {
        myFixture.configureByText("Sample.php", "<?php")
        val first = MagoLintTooltip.compact(diagnostic(
            message = "First <message>", code = "a\"b", primary = "First primary",
            help = "Help <unsafe>", notes = listOf("Note & detail"),
            relatedLocations = listOf(MagoLintReport.RelatedLocation("Related \"note\"", "<path>")),
        ))
        val second = MagoLintTooltip.compact(diagnostic(message = "Second", code = "other", help = "Other help"))
        val firstRef = firstHref(first)
        val secondRef = firstHref(second)
        assertTrue(firstRef != secondRef)
        val expanded = TooltipLinkHandlerEP.getDescription(firstRef, myFixture.editor)!!
        assertTrue(expanded.contains("Help &lt;unsafe&gt;"))
        assertTrue(expanded.contains("Note &amp; detail"))
        assertTrue(expanded.contains("Related &quot;note&quot; — &lt;path&gt;"))
        assertTrue(expanded.contains("#a%22b"))
        assertFalse(first.contains("<message>"))
        assertTrue(first.contains("First primary"))
        assertFalse(expanded.contains("First primary"))
    }

    fun testExactPrimaryDuplicateAppearsOnce() {
        val diagnostic = diagnostic(message = "Same text", primary = " Same text ")
        val compact = MagoLintTooltip.compact(diagnostic)
        assertEquals(1, "Same text".toRegex().findAll(compact).count())
    }

    private fun firstHref(html: String): String = Regex("<a href=\\\"([^\\\"]+)\\\"").find(html)!!.groupValues[1]

    private fun diagnostic(
        message: String = "Issue message",
        code: String? = "rule code",
        primary: String? = "Primary detail",
        notes: List<String> = emptyList(),
        help: String? = null,
        relatedLocations: List<MagoLintReport.RelatedLocation> = emptyList(),
    ) = MagoLintReport.Diagnostic(TextRange(0, 1), message, code, "Warning", primary, notes, help, relatedLocations)
}
