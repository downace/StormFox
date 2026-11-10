package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoApplyLintFixAction
import com.github.downace.stormfox.lint.MagoLintFixIntention
import com.github.downace.stormfox.lint.MagoLintReport
import com.github.downace.stormfox.lint.MagoRelatedNavigation
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionActionWithOptions
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Iconable

class MagoLintFixIntentionTest : BasePlatformTestCase() {
    private fun action(
        safety: MagoLintReport.Safety = MagoLintReport.Safety.SAFE,
        options: List<IntentionAction> = emptyList(),
    ) = MagoApplyLintFixAction(
        MagoLintReport.Fix("<?php echo 2;", safety),
        MagoRelatedNavigation.snapshotHash(myFixture.editor.document.text),
        myFixture.file.virtualFile,
        "prefer-first-class-callable",
        "Issue",
        options,
    )

    fun testAdapterKeepsModCommandAvailabilityPreviewAndExecution() {
        myFixture.configureByText("Sample.php", "<?php echo 1;")
        val commandAction = action()
        val intention = commandAction.asIntention() as MagoLintFixIntention

        assertSame(commandAction, intention.asModCommandAction())
        assertEquals("Mago Linter: Fix prefer-first-class-callable", intention.getText())
        assertEquals("Fix prefer-first-class-callable", intention.getTooltipText())
        assertTrue(intention.isAvailable(project, myFixture.editor, myFixture.file))
        assertFalse(intention.startInWriteAction())
        assertFalse(intention.generatePreview(project, myFixture.editor, myFixture.file) == IntentionPreviewInfo.FALLBACK_DIFF)
        assertEquals("<?php echo 2;", myFixture.getIntentionPreviewText(intention))

        myFixture.launchAction(intention)

        assertEquals("<?php echo 2;", myFixture.editor.document.text)
    }

    fun testTooltipRemovesPrefixButKeepsRiskSuffixAndLeavesChildLabelAlone() {
        myFixture.configureByText("Sample.php", "<?php echo 1;")
        val risky = action(MagoLintReport.Safety.UNSAFE).asIntention() as MagoLintFixIntention
        assertEquals("Mago Linter: Fix prefer-first-class-callable (unsafe)", risky.getText())
        assertEquals("Fix prefer-first-class-callable (unsafe)", risky.getTooltipText())

        val child = MagoApplyLintFixAction(
            MagoLintReport.Fix("<?php echo 2;", MagoLintReport.Safety.SAFE),
            MagoRelatedNavigation.snapshotHash(myFixture.editor.document.text), myFixture.file.virtualFile,
            null, "", overrideLabel = "Fix all problems in file (safe only)",
        ).asIntention() as MagoLintFixIntention
        assertEquals("Fix all problems in file (safe only)", child.getTooltipText())
    }

    fun testCurrentFixHasHighPriority() {
        myFixture.configureByText("Sample.php", "<?php echo 1;")
        val intentions = MagoLintReport.Safety.entries.map { safety -> action(safety).asIntention() as MagoLintFixIntention }

        assertTrue(intentions.all { it.getPriority() == PriorityAction.Priority.HIGH })
    }

    fun testIntentionAndNestedOptionUseStandardBulb() {
        myFixture.configureByText("Sample.php", "<?php echo 1;")
        val child = action().asIntention() as MagoLintFixIntention
        val intention = action(options = listOf(child)).asIntention() as MagoLintFixIntention

        assertEquals(AllIcons.Actions.IntentionBulb, (intention as Iconable).getIcon(0))
        assertEquals(AllIcons.Actions.IntentionBulb, (child as Iconable).getIcon(0))
    }

    fun testProvidedOptionsAreExposedAsNativeSubmenu() {
        myFixture.configureByText("Sample.php", "<?php echo 1;")
        val child = action(MagoLintReport.Safety.UNSAFE).asIntention()
        val intention = action(options = listOf(child)).asIntention() as MagoLintFixIntention

        assertEquals(listOf(child), intention.getOptions())
        assertEquals(
            IntentionActionWithOptions.CombiningPolicy.IntentionOptionsOnly,
            intention.getCombiningPolicy(),
        )
    }

    fun testWithoutProvidedOptionsKeepsInspectionOptionPolicy() {
        myFixture.configureByText("Sample.php", "<?php echo 1;")
        val intention = action().asIntention() as MagoLintFixIntention

        assertTrue(intention.getOptions().isEmpty())
        assertEquals(
            IntentionActionWithOptions.CombiningPolicy.InspectionOptionsOnly,
            intention.getCombiningPolicy(),
        )
    }

    fun testBatchPreviewIsEmptyWhenDisabledAndCurrentPreviewRemainsDelegateGenerated() {
        myFixture.configureByText("Sample.php", "<?php echo 1;")
        val batch = MagoApplyLintFixAction(
            MagoLintReport.Fix("<?php echo 2;", MagoLintReport.Safety.SAFE),
            MagoRelatedNavigation.snapshotHash(myFixture.editor.document.text), myFixture.file.virtualFile,
            null, "", showPreview = false,
        ).asIntention()
        assertSame(IntentionPreviewInfo.EMPTY, batch.generatePreview(project, myFixture.editor, myFixture.file))
        val current = action().asIntention()
        assertNotSame(IntentionPreviewInfo.EMPTY, current.generatePreview(project, myFixture.editor, myFixture.file))
    }
}
