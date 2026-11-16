package com.github.downace.stormfox.format

import com.github.downace.stormfox.MagoVersion
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MagoFormattingOutputTest {
    @Test fun realMagoFormatsUnsavedBufferWithoutChangingDisk() {
        val binary = System.getenv("STORMFOX_MAGO_TEST_BINARY")
        assumeTrue("STORMFOX_MAGO_TEST_BINARY is not set", !binary.isNullOrBlank())
        val project = Files.createTempDirectory("stormfox-format-").toFile()
        try {
            val source = File(project, "src/example.php")
            source.parentFile.mkdirs()
            val diskText = "<?php\n\$diskOnly = 123;\n"
            val buffer = "<?php\n\$unsavedOnly=  456;\n"
            source.writeText(diskText)
            File(project, "mago.toml").writeText("[source]\npaths = ['src']\n")
            val result = MagoRunner.format(binary!!, project, "", source.absolutePath, buffer)
            assertTrue(result.toString(), result is MagoRunner.Result.Success)
            val formatted = (result as MagoRunner.Result.Success).text
            assertTrue(formatted.isNotBlank())
            assertTrue(formatted.contains("unsavedOnly"))
            assertFalse(formatted.contains("diskOnly"))
            assertNotEquals(buffer, formatted)
            assertEquals(diskText, source.readText())
        } finally {
            project.deleteRecursively()
        }
    }

    @Test fun oldVersionFirstInvocationOmitsFilepath() {
        assertEquals(MagoRunner.Result.Success("unsaved"), run("old", "unsaved"))
    }

    @Test fun newVersionFirstInvocationIncludesFilepath() {
        assertEquals(MagoRunner.Result.Success("unsaved"), run("new", "unsaved"))
    }

    @Test fun versionOnStderrIsDetected() {
        assertEquals(MagoRunner.Result.Success("unsaved"), run("stderr", "unsaved"))
    }

    @Test fun cachedVersionsSkipProbeAndSelectMatchingArguments() {
        assertEquals(MagoRunner.Result.Success("unsaved"), run("warm-old", "unsaved", MagoVersion(1, 18, 0)))
        assertEquals(MagoRunner.Result.Success("unsaved"), run("warm-new", "unsaved", MagoVersion(1, 25, 0)))
    }

    @Test fun emptyOutputForSourceFailsEvenWithStderr() {
        assertEquals(
            MagoRunner.Result.Failure("mago returned no formatted content for non-empty input; verify stdin forwarding (for docker run, use -i). The original text was preserved."),
            run("empty", "<?php echo 1;"),
        )
    }

    @Test fun blankOutputForWhitespaceSourceFails() {
        assertEquals(
            MagoRunner.Result.Failure("mago returned no formatted content for non-empty input; verify stdin forwarding (for docker run, use -i). The original text was preserved."),
            run("blank", " \n"),
        )
    }

    @Test fun normalOutputSucceeds() {
        assertEquals(MagoRunner.Result.Success("<?php\n"), run("normal", "<?php"))
    }

    @Test fun emptyInputCanProduceEmptyOutput() {
        assertEquals(MagoRunner.Result.Success(""), run("empty", ""))
    }

    private fun run(mode: String, text: String, version: MagoVersion? = null): MagoRunner.Result {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val resource = OutputFormatter::class.java.getResource("OutputFormatter.class")!!
        val classpath = File(resource.toURI()).absolutePath.removeSuffix(
            OutputFormatter::class.java.name.replace('.', File.separatorChar) + ".class",
        )
        val command = "\"${java.replace('\\', '/')}\" -cp \"${classpath.replace('\\', '/')}\" ${OutputFormatter::class.java.name} $mode"
        return MagoRunner.format(command, null, "", "test.php", text, cachedVersion = version)
    }
}
