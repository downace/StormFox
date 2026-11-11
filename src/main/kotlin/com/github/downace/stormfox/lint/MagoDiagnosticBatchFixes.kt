package com.github.downace.stormfox.lint

internal object MagoDiagnosticBatchFixes {
    data class Batch(val fix: MagoDiagnosticReport.Fix, val issues: List<MagoDiagnosticReport.Fix>)

    fun stitch(text: String, edits: List<MagoDiagnosticReport.Edit>): String {
        var cursor = 0
        return buildString {
            for (edit in edits.sortedWith(compareBy<MagoDiagnosticReport.Edit> { it.start }.thenBy { it.end })) {
                require(edit.start >= cursor && edit.end >= edit.start && edit.end <= text.length)
                append(text, cursor, edit.start)
                append(edit.text)
                cursor = edit.end
            }
            append(text, cursor, text.length)
        }
    }

    private fun overlaps(a: MagoDiagnosticReport.Edit, b: MagoDiagnosticReport.Edit): Boolean = when {
        a.start == a.end -> a.start > b.start && a.start < b.end
        b.start == b.end -> b.start > a.start && b.start < a.end
        else -> a.start < b.end && b.start < a.end
    }

    fun collect(text: String, diagnostics: List<MagoDiagnosticReport.Diagnostic>, threshold: MagoDiagnosticReport.Safety): Batch? {
        val accepted = mutableListOf<MagoDiagnosticReport.Edit>()
        val issues = mutableListOf<MagoDiagnosticReport.Fix>()
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
        return Batch(MagoDiagnosticReport.Fix(result, issues.maxOf { it.safety }, accepted), issues)
    }

    fun variants(text: String, diagnostics: List<MagoDiagnosticReport.Diagnostic>): List<Batch> =
        MagoDiagnosticReport.Safety.entries.mapNotNull { collect(text, diagnostics, it) }.distinctBy { it.fix.text }

    fun globalLabel(safety: MagoDiagnosticReport.Safety): String = when (safety) {
        MagoDiagnosticReport.Safety.SAFE -> "Mago: Fix all problems in file (safe only)"
        MagoDiagnosticReport.Safety.POTENTIALLY_UNSAFE -> "Mago: Fix all problems in file (including potentially unsafe)"
        MagoDiagnosticReport.Safety.UNSAFE -> "Mago: Fix all problems in file (including unsafe)"
    }
}
