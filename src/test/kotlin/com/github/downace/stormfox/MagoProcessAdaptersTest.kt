package com.github.downace.stormfox

import com.github.downace.stormfox.format.MagoRunner
import com.github.downace.stormfox.format.MagoValidation
import com.github.downace.stormfox.lint.MagoDiagnosticRunner
import com.github.downace.stormfox.lint.MagoRuleMetadataKey
import com.github.downace.stormfox.lint.MagoRuleMetadataRunner
import com.github.downace.stormfox.lint.MagoTool
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class MagoProcessAdaptersTest : BasePlatformTestCase() {
    private fun wrapper(mode: String): String {
        val resource = AdapterFixture::class.java.getResource("AdapterFixture.class")!!
        val classpath = File(resource.toURI()).absolutePath.removeSuffix(
            AdapterFixture::class.java.name.replace('.', File.separatorChar) + ".class")
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        return "\"${java.replace('\\', '/')}\" -cp \"${classpath.replace('\\', '/')}\" ${AdapterFixture::class.java.name} $mode"
    }

    fun testVersionAndValidationRequireEofAndCombineStreams() {
        val directory = Files.createTempDirectory("stormfox-validation").toFile()
        try {
            val known = MagoValidation.checkBinaryUncached(wrapper("known"), directory)
            assertTrue(known.toString(), known is MagoValidation.Result.Success)
            assertEquals("wrapper\nmago 1.60.0", (known as MagoValidation.Result.Success).message)
            assertTrue(MagoValidation.checkBinaryUncached(wrapper("unknown"), directory) is MagoValidation.Result.Success)
            assertTrue(MagoValidation.checkBinaryUncached(wrapper("version-exit"), directory) is MagoValidation.Result.Failure)
            assertEquals(MagoValidation.Result.Success("Configuration is valid"),
                MagoValidation.checkConfig(wrapper("known"), "mago.toml", directory))
            assertEquals(MagoValidation.Result.Failure("invalid config"),
                MagoValidation.checkConfig(wrapper("config-exit"), "mago.toml", directory))
            assertEquals(MagoRunner.Result.Success("狐"), MagoRunner.format(wrapper("known"), directory, "", "test.php", "狐"))
            val unknown = MagoRunner.format(wrapper("unknown"), directory, "", "test.php", "input")
            assertTrue(unknown.toString(), unknown is MagoRunner.Result.Failure && unknown.message.contains("Cannot determine"))
            val failure = MagoRunner.format(wrapper("version-exit"), directory, "", "test.php", "input")
            assertEquals(MagoRunner.Result.Failure("version failure"), failure)
            val formatFailure = MagoRunner.format(wrapper("format-exit"), directory, "", "test.php", "input")
            assertEquals(MagoRunner.Result.Failure("format failure"), formatFailure)
            val early = MagoRunner.format(wrapper("format-early"), directory, "", "test.php", "x".repeat(2_000_000))
            assertEquals(MagoRunner.Result.Failure("format early failure"), early)
        } finally { directory.deleteRecursively() }
    }

    fun testDiagnosticsSourceAndExitContracts() {
        val directory = Files.createTempDirectory("stormfox-diagnostics").toFile()
        try {
            fun run(mode: String, tool: MagoTool) =
                MagoDiagnosticRunner.run(wrapper(mode), directory, "", File(directory, "test.php").path, "狐", tool)
            for (tool in listOf(MagoTool.LINT, MagoTool.ANALYZE, MagoTool.GUARD, MagoTool.TWIG_LINT)) {
                for (mode in listOf("diagnostic-empty", "diagnostic-one")) {
                    val result = run(mode, tool)
                    assertTrue("$tool $mode $result", result is MagoDiagnosticRunner.Result.Success)
                    result as MagoDiagnosticRunner.Result.Success
                    assertTrue(result.diagnostics.isEmpty())
                    assertEquals(listOf("source warning", "diagnostic warning"), result.warnings)
                }
                for (mode in listOf("source-exit", "diagnostic-empty-one", "diagnostic-two")) {
                    assertTrue("$tool $mode", run(mode, tool) is MagoDiagnosticRunner.Result.Failure)
                }
            }
            val early = MagoDiagnosticRunner.run(wrapper("diagnostic-early"), directory, "",
                File(directory, "test.php").path, "x".repeat(2_000_000))
            assertTrue(early.toString(), early is MagoDiagnosticRunner.Result.Failure && early.message.contains("diagnostic early failure"))
        } finally { directory.deleteRecursively() }
    }

    fun testMetadataEofExitAndPerStreamLimits() {
        val directory = Files.createTempDirectory("stormfox-metadata").toFile()
        try {
            fun load(mode: String) = MagoRuleMetadataRunner.load(
                MagoRuleMetadataKey(wrapper(mode), directory.path, "", null), EmptyProgressIndicator())
            assertTrue(load("metadata")!!.containsKey("test"))
            assertNull(load("metadata-exit"))
            for (stream in listOf("stdout", "stderr")) {
                assertTrue(load("metadata-limit-$stream")!!.containsKey("test"))
                assertNull(load("metadata-limit-$stream-over"))
            }
        } finally { directory.deleteRecursively() }
    }
}
