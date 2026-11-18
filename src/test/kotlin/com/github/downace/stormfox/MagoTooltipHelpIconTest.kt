package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoDiagnosticReport
import com.github.downace.stormfox.lint.MagoDiagnosticTooltip
import com.github.downace.stormfox.lint.MagoTool
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MagoTooltipHelpIconTest : BasePlatformTestCase() {
    fun testAllToolsUseTheSameHelpBulb() {
        for (source in MagoTool.entries) {
            val diagnostic = MagoDiagnosticReport.Diagnostic(
                TextRange(0, 1), "Issue", null, "Warning", help = "More help", source = source,
            )
            val html = MagoDiagnosticTooltip.expanded(diagnostic)!!
            assertTrue(source.name, html.contains(
                "<icon src=\"com.intellij.icons.AllIcons.Actions.IntentionBulb\" alt=\"Help\"/>&nbsp;&nbsp;More help",
            ))
            assertFalse(source.name, html.contains("AllIcons.General.ContextHelp"))
        }
    }
}
