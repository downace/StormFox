package com.github.downace.stormfox.settings

import com.github.downace.stormfox.lint.MagoTool

public enum class MagoLintMode { NORMAL, SEMANTICS, PEDANTIC }
public enum class MagoGuardMode { BOTH, STRUCTURAL, PERIMETER }

data class MagoDiagnosticOptions(
    val lintMode: MagoLintMode = MagoLintMode.NORMAL,
    val lintIgnoreBaseline: Boolean = false,
    val lintFixableOnly: Boolean = false,
    val twigLintMode: MagoLintMode = MagoLintMode.NORMAL,
    val twigLintIgnoreBaseline: Boolean = false,
    val twigLintFixableOnly: Boolean = false,
    val analyzeIgnoreBaseline: Boolean = false,
    val analyzeNoStubs: Boolean = false,
    val analyzeSkipIgnores: Boolean = false,
    val analyzeFixableOnly: Boolean = false,
    val guardMode: MagoGuardMode = MagoGuardMode.BOTH,
    val guardIgnoreBaseline: Boolean = false,
    val guardNoStubs: Boolean = false,
    val guardFixableOnly: Boolean = false,
) {
    internal fun arguments(tool: MagoTool): List<String> = buildList {
        fun lint(mode: MagoLintMode, baseline: Boolean, fixable: Boolean) {
            when (mode) {
                MagoLintMode.NORMAL -> Unit
                MagoLintMode.SEMANTICS -> add("--semantics")
                MagoLintMode.PEDANTIC -> add("--pedantic")
            }
            if (baseline) add("--ignore-baseline")
            if (fixable) add("--fixable-only")
        }
        when (tool) {
            MagoTool.LINT -> lint(lintMode, lintIgnoreBaseline, lintFixableOnly)
            MagoTool.TWIG_LINT -> lint(twigLintMode, twigLintIgnoreBaseline, twigLintFixableOnly)
            MagoTool.ANALYZE -> {
                if (analyzeIgnoreBaseline) add("--ignore-baseline")
                if (analyzeNoStubs) add("--no-stubs")
                if (analyzeSkipIgnores) add("--skip-ignores")
                if (analyzeFixableOnly) add("--fixable-only")
            }
            MagoTool.GUARD -> {
                when (guardMode) {
                    MagoGuardMode.BOTH -> Unit
                    MagoGuardMode.STRUCTURAL -> add("--structural")
                    MagoGuardMode.PERIMETER -> add("--perimeter")
                }
                if (guardIgnoreBaseline) add("--ignore-baseline")
                if (guardNoStubs) add("--no-stubs")
                if (guardFixableOnly) add("--fixable-only")
            }
        }
    }
}