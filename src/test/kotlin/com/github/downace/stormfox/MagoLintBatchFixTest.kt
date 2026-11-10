package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoLintBatchFixes
import com.github.downace.stormfox.lint.MagoLintReport
import com.github.downace.stormfox.lint.MagoLintReport.Edit
import com.github.downace.stormfox.lint.MagoLintReport.Safety
import com.intellij.openapi.util.TextRange
import org.junit.Assert.*
import org.junit.Test

class MagoLintBatchFixTest {
    private fun issue(text: String, code: String, vararg edits: Edit): MagoLintReport.Diagnostic =
        MagoLintReport.Diagnostic(TextRange(0, 0), "Issue", code, "Warning", fix = MagoLintReport.Fix(
            MagoLintBatchFixes.stitch(text, edits.toList()), edits.maxOf { it.safety }, edits.toList()))

    @Test fun thresholdsAreCumulativeAndWholeIssueSafetyWins() {
        val text = "abcd"
        val diagnostics = listOf(issue(text, "a", Edit(0, 1, "A")),
            issue(text, "a", Edit(1, 2, "B"), Edit(2, 3, "C", Safety.POTENTIALLY_UNSAFE)),
            issue(text, "b", Edit(3, 4, "D", Safety.UNSAFE)))
        assertEquals(listOf("Abcd", "ABCd", "ABCD"), MagoLintBatchFixes.variants(text, diagnostics).map { it.fix.text })
        assertEquals(listOf("Abcd", "ABCd"), MagoLintBatchFixes.variants(text, diagnostics.filter { it.code == "a" }).map { it.fix.text })
    }

    @Test fun skipsEntireConflictingIssueWithoutPoisoningSharedEdits() {
        val text = "abc"
        val diagnostics = listOf(issue(text, "a", Edit(0, 1, "A")),
            issue(text, "b", Edit(0, 2, "B"), Edit(3, 3, "!")),
            issue(text, "c", Edit(2, 3, "C"), Edit(3, 3, "!")))
        val batch = MagoLintBatchFixes.collect(text, diagnostics, Safety.UNSAFE)!!
        assertEquals("AbC!", batch.fix.text)
        assertEquals(2, batch.issues.size)
    }

    @Test fun repeatedPrimariesAndEquivalentDiagnosticsDoNotDuplicateInsertions() {
        val first = issue("ab", "a", Edit(0, 0, "use A;"), Edit(0, 1, "A"))
        val second = issue("ab", "b", Edit(0, 0, "use A;"), Edit(0, 0, "use B;"), Edit(1, 2, "B"))
        assertEquals("use A;use B;AB", MagoLintBatchFixes.collect("ab",
            listOf(first, first.copy(range = TextRange(1, 1)), first.copy(message = "Other"), second), Safety.SAFE)!!.fix.text)
    }

    @Test fun boundaryInsertionsAndUnicodeReplacementDeletionUseOriginalOffsets() {
        val text = "Я😀x"
        val diagnostics = listOf(issue(text, "a", Edit(1, 3, "😁"), Edit(3, 4, "")),
            issue(text, "b", Edit(1, 1, "1"), Edit(1, 1, "2"), Edit(3, 3, "!")))
        assertEquals("Я12😁!", MagoLintBatchFixes.collect(text, diagnostics, Safety.SAFE)!!.fix.text)
    }

    @Test fun noSafeFixStillHasReachableHigherVariantsAndRedundantOutputsAreRemoved() {
        val diagnostics = listOf(issue("ab", "a", Edit(0, 1, "A", Safety.POTENTIALLY_UNSAFE)),
            issue("ab", "b", Edit(0, 2, "B", Safety.UNSAFE)))
        assertNull(MagoLintBatchFixes.collect("ab", diagnostics, Safety.SAFE))
        val variants = MagoLintBatchFixes.variants("ab", diagnostics)
        assertEquals(1, variants.size)
        assertEquals(Safety.POTENTIALLY_UNSAFE, variants.single().fix.safety)
    }
}
