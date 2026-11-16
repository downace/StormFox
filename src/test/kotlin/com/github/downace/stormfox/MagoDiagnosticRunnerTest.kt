package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoDiagnosticRunner
import junit.framework.TestCase

class MagoDiagnosticRunnerTest : TestCase() {
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
