package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoDiagnosticRunner
import com.github.downace.stormfox.lint.MagoTool
import junit.framework.TestCase
import java.io.File

class MagoDiagnosticRunnerTest : TestCase() {
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
