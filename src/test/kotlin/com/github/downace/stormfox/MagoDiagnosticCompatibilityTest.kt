package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoDiagnosticRunner
import com.github.downace.stormfox.lint.MagoTool
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.charset.StandardCharsets

class MagoDiagnosticCompatibilityTest : BasePlatformTestCase() {
    fun testOptionalTwigCliUsesStdinAndSourceWorkspace() {
        val binary = System.getenv("STORMFOX_MAGO_TWIG_TEST_BINARY") ?: return
        val directory = Files.createTempDirectory("stormfox-twig-")
        val source = Files.createDirectory(directory.resolve("templates"))
        val path = source.resolve("example.twig")
        val config = directory.resolve("mago.toml")
        val diskText = "{{ untouched }}"
        try {
            Files.writeString(path, diskText)
            Files.writeString(config, "[source]\npaths = ['templates']\n")
            val result = MagoDiagnosticRunner.run(binary, directory.toFile(), config.toString(), path.toString(),
                "{% if %}", MagoTool.TWIG_LINT)
            assertTrue("Expected Twig JSON report, got $result", result is MagoDiagnosticRunner.Result.Success)
            assertTrue("Expected unsaved Twig diagnostics", (result as MagoDiagnosticRunner.Result.Success).diagnostics.isNotEmpty())
            assertEquals(diskText, Files.readString(path))
            Files.writeString(config, "[source]\npaths = ['templates']\nexcludes = ['templates/**']\n")
            val excluded = MagoDiagnosticRunner.run(binary, directory.toFile(), config.toString(), path.toString(),
                "{% if %}", MagoTool.TWIG_LINT)
            assertTrue("Expected excluded Twig success, got $excluded", excluded is MagoDiagnosticRunner.Result.Success)
            assertEmpty((excluded as MagoDiagnosticRunner.Result.Success).diagnostics)
            assertEquals(diskText, Files.readString(path))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(config)
            Files.deleteIfExists(source)
            Files.deleteIfExists(directory)
        }
    }

    fun testOptionalCliPreservesWarningsForExcludedSource() {
        val binary = System.getenv("STORMFOX_MAGO_TEST_BINARY") ?: return
        val directory = Files.createTempDirectory("stormfox-excluded-")
        val sourceDirectory = Files.createDirectory(directory.resolve("src"))
        val path = sourceDirectory.resolve("Sample.php")
        val config = directory.resolve("mago.toml")
        val text = "<?php\nECHO TRUE;\n"
        try {
            Files.writeString(path, text)
            Files.writeString(config, "[source]\npaths = ['src']\nexcludes = ['src/**']\n")
            for (tool in listOf(MagoTool.GUARD, MagoTool.ANALYZE)) {
                val command = GeneralCommandLine(MagoDiagnosticRunner.arguments(binary, config.toString(),
                    path.toString(), directory.toFile(), tool))
                    .withWorkDirectory(directory.toFile()).withCharset(StandardCharsets.UTF_8)
                val handler = CapturingProcessHandler(command)
                handler.processInput.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
                val direct = handler.runProcess(30_000)
                assertTrue("Direct $tool invocation failed: ${direct.stderr}", direct.exitCode in 0..1 && !direct.isTimeout)
                val expectedWarnings = MagoDiagnosticRunner.warningsFromStderr(direct.stderr)
                val result = MagoDiagnosticRunner.run(binary, directory.toFile(), config.toString(), path.toString(), text, tool)
                assertTrue("Expected successful $tool result, got $result", result is MagoDiagnosticRunner.Result.Success)
                result as MagoDiagnosticRunner.Result.Success
                assertEmpty(result.diagnostics)
                assertEquals("Warnings from $tool", expectedWarnings, result.warnings)
                if (tool == MagoTool.GUARD) assertTrue("Expected guard warnings", expectedWarnings.isNotEmpty())
            }
            assertEquals(text, Files.readString(path))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(config)
            Files.deleteIfExists(sourceDirectory)
            Files.deleteIfExists(directory)
        }
    }

    fun testOptionalCliLintParsesReplacementFix() {
        val binary = System.getenv("STORMFOX_MAGO_TEST_BINARY") ?: return
        val directory = Files.createTempDirectory("stormfox-lint-fix-")
        val sourceDirectory = Files.createDirectory(directory.resolve("src"))
        val path = sourceDirectory.resolve("Sample.php")
        val config = directory.resolve("mago.toml")
        val text = "<?php\nECHO TRUE;\n"
        try {
            Files.writeString(path, text)
            Files.writeString(config, "[source]\npaths = ['src']\n")
            val result = MagoDiagnosticRunner.run(binary, directory.toFile(), config.toString(), path.toString(), text, MagoTool.LINT)
            assertTrue("Expected successful lint result, got $result", result is MagoDiagnosticRunner.Result.Success)
            result as MagoDiagnosticRunner.Result.Success
            assertTrue("Expected lowercase-keyword finding with a fix, got ${result.diagnostics}",
                result.diagnostics.any { it.code == "lowercase-keyword" && it.fix != null })
            assertEquals(text, Files.readString(path))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(config)
            Files.deleteIfExists(sourceDirectory)
            Files.deleteIfExists(directory)
        }
    }
}
