package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoDiagnosticRunner
import com.github.downace.stormfox.lint.MagoTool
import com.github.downace.stormfox.settings.MagoDiagnosticOptions
import com.github.downace.stormfox.settings.MagoLintMode
import com.github.downace.stormfox.settings.MagoGuardMode
import junit.framework.TestCase
import java.io.File

class MagoDiagnosticRunnerTest : TestCase() {
    fun testDiagnosticOptionsAreScopedAndPrecedePathSeparator() {
        val dir = File(".").absoluteFile.normalize()
        val options = MagoDiagnosticOptions(
            lintMode = MagoLintMode.SEMANTICS, lintIgnoreBaseline = true, lintFixableOnly = true,
            twigLintMode = MagoLintMode.PEDANTIC, twigLintFixableOnly = true,
            analyzeIgnoreBaseline = true, analyzeNoStubs = true, analyzeSkipIgnores = true,
            analyzeFixableOnly = true, guardMode = MagoGuardMode.STRUCTURAL,
            guardIgnoreBaseline = true, guardNoStubs = true, guardFixableOnly = true)
        fun args(tool: MagoTool, value: MagoDiagnosticOptions = options) =
            MagoDiagnosticRunner.arguments("mago", "", File(dir, "file.php").path, dir, tool, options = value)
                .drop(2)
        assertEquals(listOf("--stdin-input", "--reporting-format", "json", "--semantics", "--ignore-baseline", "--fixable-only", "--", "file.php"), args(MagoTool.LINT))
        assertEquals(listOf("--stdin-input", "--reporting-format", "json", "--pedantic", "--fixable-only", "--", "file.php"), args(MagoTool.TWIG_LINT))
        assertEquals(listOf("--stdin-input", "--reporting-format", "json", "--ignore-baseline", "--no-stubs", "--skip-ignores", "--fixable-only", "--", "file.php"), args(MagoTool.ANALYZE))
        assertEquals(listOf("--stdin-input", "--reporting-format", "json", "--structural", "--ignore-baseline", "--no-stubs", "--fixable-only", "--", "file.php"), args(MagoTool.GUARD))
        assertTrue(args(MagoTool.GUARD, options.copy(guardMode = MagoGuardMode.PERIMETER)).contains("--perimeter"))
        for (tool in MagoTool.entries) assertEquals(listOf("--stdin-input", "--reporting-format", "json", "--", "file.php"), args(tool, MagoDiagnosticOptions()))
        for (tool in listOf(MagoTool.LINT, MagoTool.TWIG_LINT)) {
            for ((mode, flag) in listOf(MagoLintMode.SEMANTICS to "--semantics", MagoLintMode.PEDANTIC to "--pedantic")) {
                val selected = MagoDiagnosticOptions(lintMode = mode, twigLintMode = mode)
                assertEquals(listOf("--stdin-input", "--reporting-format", "json", flag, "--", "file.php"), args(tool, selected))
            }
        }
        for ((mode, flags) in listOf(MagoGuardMode.BOTH to emptyList(), MagoGuardMode.STRUCTURAL to listOf("--structural"), MagoGuardMode.PERIMETER to listOf("--perimeter"))) {
            assertEquals(listOf("--stdin-input", "--reporting-format", "json") + flags + listOf("--", "file.php"),
                args(MagoTool.GUARD, MagoDiagnosticOptions(guardMode = mode)))
        }
    }
    fun testArgumentsUseNestedWorkspaceAndProtectPathWithSpaces() {
        val workingDirectory = File("diagnostic project").absoluteFile.normalize()
        val workspace = File(workingDirectory, "nested workspace")
        val path = File(workspace, "-source file.php")
        assertEquals(
            listOf("mago", "--config", "/srv/project/config file.toml", "analyze",
                "--stdin-input", "--reporting-format", "json", "--", "-source file.php"),
            MagoDiagnosticRunner.arguments("mago", File(workingDirectory, "config file.toml").path,
                path.path, workingDirectory, MagoTool.ANALYZE, workspace, "/srv/project")
        )
    }

    fun testArgumentsRejectPathOutsideMappedRoot() {
        val workingDirectory = File("diagnostic project").absoluteFile.normalize()
        val outside = File(workingDirectory.parentFile, "outside.php")
        try {
            MagoDiagnosticRunner.arguments("mago", "", outside.path, workingDirectory,
                MagoTool.LINT, workingDirectory, "/srv/project")
            fail("Expected an outside mapped root failure")
        } catch (expected: MagoPathMappingException) {
            assertTrue(expected.message.orEmpty().contains("outside the mapped project"))
        }
    }

    fun testBlankSuccessfulReportsRetainWarnings() {
        for (stdout in listOf("", " \r\n\t")) {
            val result = MagoDiagnosticRunner.parseOutput(stdout, "WARN tool warning", 0, "WARN source warning",
                "", "source.php", File("."), MagoTool.GUARD) as MagoDiagnosticRunner.Result.Success
            assertEquals(emptyList<Any>(), result.diagnostics)
            assertEquals(listOf("source warning", "tool warning"), result.warnings)
        }
    }

    fun testBlankFindingsExitAndMalformedOutputFail() {
        for ((stdout, exit) in listOf("" to 1, " \n" to 1, "not json" to 0)) {
            assertTrue(MagoDiagnosticRunner.parseOutput(stdout, "", exit, "", "", "source.php",
                File("."), MagoTool.LINT) is MagoDiagnosticRunner.Result.Failure)
        }
    }

    fun testGuardSkippedChecksSample() {
        val stderr = """
             WARN Perimeter guard checks were skipped due to missing configuration.
             WARN Please define perimeter rules in your mago.toml to enable these checks.
             WARN Structural guard checks were skipped based on the current configuration.
             WARN Please review your mago.toml guard settings to enable structural checks.
             INFO No issues found.
        """.trimIndent()
        assertEquals(listOf(
            "Perimeter guard checks were skipped due to missing configuration.",
            "Please define perimeter rules in your mago.toml to enable these checks.",
            "Structural guard checks were skipped based on the current configuration.",
            "Please review your mago.toml guard settings to enable structural checks.",
        ), MagoDiagnosticRunner.warningsFromStderr(stderr))
    }

    fun testWarningsAreExtractedInOrderAndDeduplicatedAcrossStreams() {
        val config = "  WARN mago::config: First warning\nINFO mago: ignored"
        val inspection = """
            2026-11-16T10:20:30.123Z WARN mago::guard: First warning
            ${'\u001B'}[33m WARN${'\u001B'}[0m mago::guard: Second warning
            WARN Third warning
            2026-11-16 10:20:31 WARN mago::guard: Fourth warning
            DEBUG mago: ignored
            ERROR mago: ignored
            arbitrary stderr containing WARN not a log entry
        """.trimIndent()
        assertEquals(listOf("First warning", "Second warning", "Third warning", "Fourth warning"),
            MagoDiagnosticRunner.warningsFromStderr(config, inspection))
    }

    fun testEmptyAndInfoOnlyStderrHaveNoWarnings() {
        assertEquals(emptyList<String>(), MagoDiagnosticRunner.warningsFromStderr("", " INFO mago: all good\nunstructured message"))
    }

    fun testMessagePrefixesAreNotMistakenForLogTargets() {
        val messages = listOf("Warning: текст", "Configuration: missing rules", "mago.toml: missing rules",
            "mago: missing rules", "other::module: missing rules")
        assertEquals(messages, MagoDiagnosticRunner.warningsFromStderr(messages.joinToString("\n") { "WARN $it" }))
        assertEquals(listOf("Warning: текст"),
            MagoDiagnosticRunner.warningsFromStderr("WARN mago::guard: Warning: текст"))
    }
}
