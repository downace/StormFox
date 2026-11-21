package com.github.downace.stormfox

import com.github.downace.stormfox.config.MagoConfigNavigation
import com.github.downace.stormfox.lint.*
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class MagoTwigDiagnosticTest : BasePlatformTestCase() {
    fun testBatchCollectionSeparatesTwigFromPhpAndText() {
        myFixture.enableInspections(MagoLintInspection(), MagoAnalyzeInspection(), MagoGuardInspection(), MagoTwigLintInspection())
        val twig = MagoDiagnosticAnnotator(MagoTool.TWIG_LINT, true)
        val php = listOf(MagoTool.LINT, MagoTool.ANALYZE, MagoTool.GUARD).map { MagoDiagnosticAnnotator(it, true) }
        myFixture.configureByText("example.TWIG", "{{ value }}")
        assertNotNull(twig.collectInformation(myFixture.file))
        val provider = myFixture.file.viewProvider
        for (language in provider.languages) {
            val root = provider.getPsi(language) ?: continue
            if (language == provider.baseLanguage) assertNotNull(twig.collectInformation(root))
            else assertNull("Only the base PSI root may publish Twig diagnostics", twig.collectInformation(root))
        }
        php.forEach { assertNull(it.collectInformation(myFixture.file)) }
        myFixture.configureByText("example.php", "<?php echo 1;")
        assertNull(twig.collectInformation(myFixture.file))
        php.forEach { assertNotNull(it.collectInformation(myFixture.file)) }
        myFixture.configureByText("example.txt", "{{ value }}")
        assertNull(twig.collectInformation(myFixture.file))
        php.forEach { assertNull(it.collectInformation(myFixture.file)) }
    }

    fun testTwigCommandAndSafeConfigFallback() {
        val directory = File(".").absoluteFile
        val path = File(directory, "templates/example.twig")
        val args = MagoDiagnosticRunner.arguments("mago", "", path.path, directory, MagoTool.TWIG_LINT)
        assertEquals(listOf("twig:lint", "--stdin-input", "--reporting-format", "json", "--", "templates/example.twig"), args.drop(1))
        assertNull(MagoConfigNavigation.ruleOffset("[linter.rules]\nfoo = true\n", "mago.toml", "twig:lint", "foo"))
    }

    fun testTwigRunnerReturnsVersionProbeFailure() {
        val root = Files.createTempDirectory("twig-lint-failed-probe-").toFile()
        try {
            val result = MagoDiagnosticRunner.run(File(root, "missing-mago").path, root, "",
                File(root, "view.twig").path, "{{ x }}", MagoTool.TWIG_LINT)
            assertTrue("A failed version probe must fail Twig lint: $result", result is MagoDiagnosticRunner.Result.Failure)
            assertTrue((result as MagoDiagnosticRunner.Result.Failure).message.isNotBlank())
        } finally {
            root.deleteRecursively()
        }
    }

    fun testTwigRunnerDoesNotInvokeOldVersionAndInvokes160() {
        val root = Files.createTempDirectory("twig-lint-probe-").toFile()
        try {
            val marker = File(root, "arguments")
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath.replace('\\', '/')
            val resource = TwigLintProbe::class.java.getResource("TwigLintProbe.class")!!
            val classpath = File(resource.toURI()).absolutePath.removeSuffix(
                TwigLintProbe::class.java.name.replace('.', File.separatorChar) + ".class",
            ).replace('\\', '/')
            fun command(version: String) =
                "\"$java\" -cp \"$classpath\" ${TwigLintProbe::class.java.name} $version \"${marker.path.replace('\\', '/')}\""
            val path = File(root, "templates/view.twig").path
            val unknownResult = MagoDiagnosticRunner.run(command("unknown"), root, "", path, "{{ x }}", MagoTool.TWIG_LINT)
            assertTrue("Unrecognized version must fail Twig lint: $unknownResult", unknownResult is MagoDiagnosticRunner.Result.Failure)
            assertTrue((unknownResult as MagoDiagnosticRunner.Result.Failure).message.contains("version"))
            assertFalse("An unrecognized version must not receive config or twig:lint commands", marker.exists())
            val oldResult = MagoDiagnosticRunner.run(command("1.59.0"), root, "", path, "{{ x }}", MagoTool.TWIG_LINT)
            assertTrue(oldResult is MagoDiagnosticRunner.Result.Success)
            assertTrue((oldResult as MagoDiagnosticRunner.Result.Success).diagnostics.isEmpty())
            assertFalse("Old Mago must not receive config or twig:lint commands", marker.exists())
            assertTrue(MagoDiagnosticRunner.run(command("1.60.0"), root, "", path, "{{ x }}",
                MagoTool.TWIG_LINT) is MagoDiagnosticRunner.Result.Success)
            assertEquals(listOf("config --show source", "twig:lint --stdin-input --reporting-format json -- templates/view.twig"),
                marker.readLines())
        } finally {
            root.deleteRecursively()
        }
    }
}
