package com.github.downace.stormfox.format

import com.github.downace.stormfox.MagoVersion
import com.github.downace.stormfox.MagoVersionService
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TwigFormattingTest {
    @Test fun runnerProbesAndGatesTwigAndMapsPaths() {
        object : Fixture() {}.apply { name = "testRunner" }.runBare()
    }

    private fun checkRunner() {
        val root = Files.createTempDirectory("twig-format-").toFile()
        try {
            val marker = File(root, "arguments")
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath.replace('\\', '/')
            val resource = TwigFormatterProbe::class.java.getResource("TwigFormatterProbe.class")!!
            val classpath = File(resource.toURI()).absolutePath.removeSuffix(
                TwigFormatterProbe::class.java.name.replace('.', File.separatorChar) + ".class",
            ).replace('\\', '/')
            fun command(version: String) =
                "\"$java\" -cp \"$classpath\" ${TwigFormatterProbe::class.java.name} $version \"${marker.path.replace('\\', '/')}\""
            val path = File(root, "src/view.TWIG").absolutePath
            assertTrue(MagoRunner.format(command("1.59.0"), root, "", path, "buffer") is MagoRunner.Result.Failure)
            assertFalse(marker.exists())
            assertEquals(MagoRunner.Result.Success("buffer"),
                MagoRunner.format(command("1.60.0"), root, "mago.toml", path, "buffer", executionRoot = "/app"))
            assertEquals(listOf("--config", "/app/mago.toml", "twig:fmt", "--stdin-input", "--stdin-filepath", "/app/src/view.TWIG"),
                marker.readLines())
            marker.delete()
            assertTrue(MagoRunner.format(command("1.60.0"), root, "", path, "buffer",
                cachedVersion = MagoVersion(1, 59, 0)) is MagoRunner.Result.Failure)
            assertFalse(marker.exists())
            assertEquals(MagoRunner.Result.Success("buffer"),
                MagoRunner.format(command("1.60.0"), root, "", File(root, "a.php").path, "buffer"))
            assertEquals("format", marker.readLines().first())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun realTwigFormatsBufferAndPreservesExcludedAndDisk() {
        object : Fixture() {}.apply { name = "testRealRunner" }.runBare()
    }

    private fun checkRealRunner() {
        val binary = System.getenv("STORMFOX_TWIG_TEST_BINARY")
        assumeTrue("STORMFOX_TWIG_TEST_BINARY is not set", !binary.isNullOrBlank())
        val root = Files.createTempDirectory("twig-real-").toFile()
        try {
            val source = File(root, "view.twig").apply { writeText("disk") }
            val excluded = File(root, "excluded.twig").apply { writeText("disk excluded") }
            File(root, "mago.toml").writeText("[source]\npaths = ['.']\nexcludes = ['excluded.twig']\n")
            val buffer = "{{  value  }}"
            val result = MagoRunner.format(binary!!, root, "", source.path, buffer)
            assertTrue(result.toString(), result is MagoRunner.Result.Success)
            assertNotEquals(buffer, (result as MagoRunner.Result.Success).text)
            assertEquals("disk", source.readText())
            assertEquals(MagoRunner.Result.Success(buffer),
                MagoRunner.format(binary, root, "", excluded.path, buffer))
            assertEquals("disk excluded", excluded.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun serviceSelectsOnlySupportedTwigAndPhp() {
        object : Fixture() {}.apply { name = "testSelection" }.runBare()
    }

    inner abstract class Fixture : BasePlatformTestCase() {
        fun testRunner() = checkRunner()
        fun testRealRunner() = checkRealRunner()
        fun testSelection() {
            val settings = MagoSettings.getInstance(project).state
            val previousEnabled = settings.enabled
            val previousTwigEnabled = settings.twigEnabled
            val previousPath = settings.magoPath
            try {
                settings.enabled = true
                settings.twigEnabled = null
                settings.magoPath = "twig-selection-test"
                val versions = MagoVersionService.getInstance(project)
                val directory = project.basePath?.let(::File)
                val service = MagoFormattingService()
                val twig = myFixture.configureByText("view.TWIG", "{{ value }}")
                val token = versions.beginValidation(settings.magoPath, directory)!!
                versions.finish(token, settings.magoPath, MagoValidation.Result.Success("mago 1.59.0"), directory)
                assertFalse(service.canFormat(twig))
                val token2 = versions.beginValidation(settings.magoPath, directory)!!
                versions.finish(token2, settings.magoPath, MagoValidation.Result.Success("mago 1.60.0"), directory)
                assertTrue(service.canFormat(twig))
                assertFalse(service.canFormat(myFixture.configureByText("other.txt", "plain")))
                val php = myFixture.configureByText("other.php", "<?php echo 1;")
                for (phpEnabled in listOf(false, true)) {
                    for (twigEnabled in listOf(false, true)) {
                        settings.enabled = phpEnabled
                        settings.twigEnabled = twigEnabled
                        assertEquals(phpEnabled, service.canFormat(php))
                        assertEquals(twigEnabled, service.canFormat(twig))
                    }
                    settings.twigEnabled = null
                    assertEquals(phpEnabled, service.canFormat(php))
                    assertEquals(phpEnabled, service.canFormat(twig))
                }
            } finally {
                settings.enabled = previousEnabled
                settings.twigEnabled = previousTwigEnabled
                settings.magoPath = previousPath
            }
        }
    }
}
