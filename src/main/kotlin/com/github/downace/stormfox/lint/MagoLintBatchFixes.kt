package com.github.downace.stormfox.lint

internal object MagoLintBatchFixes {
    data class Batch(val fix: MagoLintReport.Fix, val issues: List<MagoLintReport.Fix>)

    fun stitch(text: String, edits: List<MagoLintReport.Edit>): String {
        var cursor = 0
        return buildString {
            for (edit in edits.sortedWith(compareBy<MagoLintReport.Edit> { it.start }.thenBy { it.end })) {
                require(edit.start >= cursor && edit.end >= edit.start && edit.end <= text.length)
                append(text, cursor, edit.start)
                append(edit.text)
                cursor = edit.end
            }
            append(text, cursor, text.length)
        }
    }

    private fun overlaps(a: MagoLintReport.Edit, b: MagoLintReport.Edit): Boolean = when {
        a.start == a.end -> a.start > b.start && a.start < b.end
        b.start == b.end -> b.start > a.start && b.start < a.end
        else -> a.start < b.end && b.start < a.end
    }

    fun collect(text: String, diagnostics: List<MagoLintReport.Diagnostic>, threshold: MagoLintReport.Safety): Batch? {
        val accepted = mutableListOf<MagoLintReport.Edit>()
        val issues = mutableListOf<MagoLintReport.Fix>()
        for (fix in diagnostics.mapNotNull { it.fix }.distinct()) {
            if (fix.safety > threshold || fix.edits.isEmpty()) continue
            val fresh = fix.edits.filterNot { it in accepted }
            if (fresh.isEmpty() || fresh.any { edit -> accepted.any { overlaps(edit, it) } }) continue
            accepted.addAll(fresh)
            issues.add(fix)
        }
        if (accepted.isEmpty()) return null
        val result = stitch(text, accepted)
        if (result == text) return null
        return Batch(MagoLintReport.Fix(result, issues.maxOf { it.safety }, accepted), issues)
    }

    fun variants(text: String, diagnostics: List<MagoLintReport.Diagnostic>): List<Batch> =
        MagoLintReport.Safety.entries.mapNotNull { collect(text, diagnostics, it) }.distinctBy { it.fix.text }

    fun globalLabel(safety: MagoLintReport.Safety): String = when (safety) {
        MagoLintReport.Safety.SAFE -> "Mago Linter: Fix all problems in file (safe only)"
        MagoLintReport.Safety.POTENTIALLY_UNSAFE -> "Mago Linter: Fix all problems in file (including potentially unsafe)"
        MagoLintReport.Safety.UNSAFE -> "Mago Linter: Fix all problems in file (including unsafe)"
    }
}
