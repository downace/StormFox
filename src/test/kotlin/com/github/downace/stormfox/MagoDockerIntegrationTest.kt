package com.github.downace.stormfox

import com.github.downace.stormfox.format.MagoRunner
import com.github.downace.stormfox.format.MagoValidation
import com.github.downace.stormfox.lint.MagoDiagnosticRunner
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.nio.file.Files

/** Opt in with STORMFOX_TEST_DOCKER=1; requires the local 1.60.1 image and a running daemon. */
@RunWith(AssumptionAwarePlatformTestRunner::class)
class MagoDockerIntegrationTest : BasePlatformTestCase() {
    fun testProductionRunnersAgainstDocker() {
        assumeTrue("STORMFOX_TEST_DOCKER is not 1", System.getenv("STORMFOX_TEST_DOCKER") == "1")
        val root = Files.createTempDirectory("stormfox docker ").toFile()
        val source = root.resolve("src/Test.php").apply { parentFile.mkdirs(); writeText("<?php echo 'disk';\n") }
        val prefix = "docker run --rm -i -v .:/app -w /app ghcr.io/carthage-software/mago:1.60.1"
        val state = MagoSettings.getInstance(project).state
        val oldCommand = state.magoPath
        val oldRoot = state.executionRoot
        val callback = state.onPathChanged
        try {
            // This fixture uses its own temporary project root, not the IDE test project.
            state.onPathChanged = null
            state.magoPath = prefix
            state.executionRoot = "/app"
            val validation = MagoValidation.checkBinary(prefix, root, "/app", project)
            assertTrue(validation.toString(), validation is MagoValidation.Result.Success)
            assertEquals(MagoVersion(1, 60, 1), MagoVersionService.getInstance(project).cachedVersion(root))
            val config = root.resolve("mago.toml").apply { writeText("") }
            val configValidation = MagoValidation.checkConfig(prefix, config.path, root, "/app")
            assertTrue(configValidation.toString(), configValidation is MagoValidation.Result.Success)
            val unsaved = "<?php echo 'unsaved';"
            val formatted = MagoRunner.format(prefix, root, "", source.path, unsaved, executionRoot = "/app")
            assertTrue(formatted.toString(), formatted is MagoRunner.Result.Success)
            assertTrue((formatted as MagoRunner.Result.Success).text.contains("unsaved"))
            assertEquals("<?php echo 'disk';\n", source.readText())
            val withoutStdin = prefix.replace(" --rm -i ", " --rm ")
            val rejected = MagoRunner.format(withoutStdin, root, "", source.path, unsaved, executionRoot = "/app")
            assertTrue(rejected.toString(), rejected is MagoRunner.Result.Failure)
            assertEquals("<?php echo 'disk';\n", source.readText())
        } finally {
            state.executionRoot = oldRoot
            state.magoPath = oldCommand
            state.onPathChanged = callback
        }
        val diagnostics = MagoDiagnosticRunner.run(prefix, root, "", source.path, "<?php\nfunction broken( {\n", executionRoot = "/app")
        assertTrue(diagnostics.toString(), diagnostics is MagoDiagnosticRunner.Result.Success)
        assertTrue(diagnostics.toString(), (diagnostics as MagoDiagnosticRunner.Result.Success).diagnostics.isNotEmpty())
        assertEquals("<?php echo 'disk';\n", source.readText())
    }
}
