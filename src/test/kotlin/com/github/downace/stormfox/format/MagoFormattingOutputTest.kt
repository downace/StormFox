package com.github.downace.stormfox.format

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MagoFormattingOutputTest {
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

    private fun run(mode: String, text: String): MagoRunner.Result {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val resource = OutputFormatter::class.java.getResource("OutputFormatter.class")!!
        val classpath = File(resource.toURI()).absolutePath.removeSuffix(
            OutputFormatter::class.java.name.replace('.', File.separatorChar) + ".class",
        )
        val command = "\"${java.replace('\\', '/')}\" -cp \"${classpath.replace('\\', '/')}\" ${OutputFormatter::class.java.name} $mode"
        return MagoRunner.format(command, null, "", "test.php", text)
    }
}
