package com.github.downace.stormfox.format;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Real-CLI characterization, not a selection formatter or an enabling gate.
 * Marker survival deliberately does not assert PSI ownership, safe extraction, or atomic splicing.
 */
public class MagoSelectionFeasibilityTest {
    private static final String BEGIN = "/* STORMFOX_FEASIBILITY_BEGIN */";
    private static final String END = "/* STORMFOX_FEASIBILITY_END */";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private String binary;
    private Path config;

    @Before public void requireKnownCli() throws Exception {
        binary = System.getenv().getOrDefault("MAGO_FEASIBILITY_BINARY", "mago");
        String version;
        try {
            version = run(List.of("--version"), "");
        } catch (IOException unavailable) {
            assumeTrue("Installed mago unavailable: " + unavailable, false);
            return;
        }
        assumeTrue("Characterization requires mago 1.59.0; got " + version, version.trim().equals("mago 1.59.0"));
        config = temporary.getRoot().toPath().resolve("mago.toml");
        Files.writeString(config, "[formatter]\n", StandardCharsets.UTF_8);
    }

    @Test public void exactIgnoreRangeDoesNotProtectEnclosingConstruct() throws Exception {
        String input = "<?php\nfunction  f( ){\n// @mago-format-ignore-start\n$x=  1;\n"
            + "// @mago-format-ignore-end\n$y=  2;\n}\n";
        String output = format(input);
        assertEquals("<?php\n\nfunction f()\n{\n    // @mago-format-ignore-start\n$x=  1;\n"
            + "// @mago-format-ignore-end\n    $y = 2;\n}\n", output);
        evidence("ignore-range", input, output);
    }

    @Test public void exactIgnoreNextDoesNotProtectEnclosingConstruct() throws Exception {
        String input = "<?php\nfunction  f( ){\n// @mago-format-ignore-next\n$x=  1;\n$y=  2;\n}\n";
        String output = format(input);
        assertEquals("<?php\n\nfunction f()\n{\n    // @mago-format-ignore-next\n$x=  1;\n    $y = 2;\n}\n", output);
        evidence("ignore-next", input, output);
    }

    @Test public void markersSurviveButDoNotSupplySyntaxBoundaries() throws Exception {
        List<Probe> probes = List.of(
            new Probe("same-line", "<?php\nfunction  f( ){ $a=1; [[ $b=  2; ]] $c=3; }\n",
                "$b = 2;"),
            new Probe("docblock-and-leading-indent",
                "<?php\nclass  C{\n  [[\n/** doc */\npublic function  f( ){return  1;}\n]]\n}\n",
                "/** doc */\n    public function f()"),
            new Probe("trailing-comment", "<?php\nfunction f(){\n[[\n$x=1; // keep\n]]\n$y=2;\n}\n",
                "$x = 1; // keep"),
            new Probe("nested", "<?php\nfunction  f( ){\nif( true ){\n[[ $x=  1; ]]\n}\n}\n",
                "$x = 1;"),
            new Probe("existing-ignore-range",
                "<?php\n// @mago-format-ignore-start\n[[ $x=  1; ]]\n// @mago-format-ignore-end\n",
                "$x=  1;"),
            new Probe("existing-ignore-next", "<?php\n// @mago-format-ignore-next\n[[ $x=  1; ]]\n",
                "$x=  1;"),
            new Probe("crlf-unicode", "<?php\r\nfunction  f( ){\r\n  [[ $s= 'Привет 🦊'; ]]\r\n}\r\n",
                "$s = 'Привет 🦊';"),
            new Probe("php-html", "<p>untouched</p><?php [[ echo  1; ]] ?><b>end</b>",
                "echo 1;"),
            new Probe("heredoc", "<?php\nfunction  f( ){\n  [[\n  $s=<<<TXT\n  hello\n  TXT;\n  ]]\n}\n",
                "$s = <<<TXT\n        hello\n        TXT;")
        );
        for (Probe probe : probes) {
            String input = probe.source.replace("[[", BEGIN).replace("]]", END);
            String output = format(input);
            assertEquals(probe.name, 1, occurrences(output, BEGIN));
            assertEquals(probe.name, 1, occurrences(output, END));
            int begin = output.indexOf(BEGIN) + BEGIN.length();
            int end = output.indexOf(END);
            assertTrue(probe.name, begin <= end);
            assertTrue(probe.name + ": " + output, output.substring(begin, end).contains(probe.payload));
            evidence(probe.name, input, output);
            if (probe.name.equals("same-line")) {
                assertTrue(output.contains("$a = 1; " + BEGIN));
                assertTrue(output.contains("$b = 2; " + END));
            }
        }
    }

    @Test public void multipleMarkersSurviveOneInvocation() throws Exception {
        String secondBegin = BEGIN.replace("BEGIN", "BEGIN_2");
        String secondEnd = END.replace("END", "END_2");
        String input = "<?php\nfunction f(){\n" + BEGIN + "\n$a=1;\n" + END + "\n$middle=  0;\n"
            + secondBegin + "\n$b=2;\n" + secondEnd + "\n}\n";
        String output = format(input);
        for (String marker : List.of(BEGIN, END, secondBegin, secondEnd)) {
            assertEquals(marker, 1, occurrences(output, marker));
        }
        assertTrue(output.indexOf(BEGIN) < output.indexOf(END));
        assertTrue(output.indexOf(END) < output.indexOf(secondBegin));
        assertTrue(output.indexOf(secondBegin) < output.indexOf(secondEnd));
        assertTrue(output.contains("$middle = 0;"));
        evidence("multiple-targets-unselected-middle-also-changes", input, output);
    }

    private String format(String input) throws Exception {
        return run(List.of("--config", config.toString(), "--no-extensions", "format",
            "--stdin-input", "--stdin-filepath", temporary.getRoot().toPath().resolve("buffer.php").toString()), input);
    }

    private String run(List<String> arguments, String input) throws Exception {
        Path stdout = temporary.newFile().toPath();
        Path stderr = temporary.newFile().toPath();
        var command = new java.util.ArrayList<String>();
        command.add(binary);
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(temporary.getRoot()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("MAGO_"));
        Process process = builder.start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue("CLI timeout: " + command, process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(Files.readString(stderr), 0, process.exitValue());
            return Files.readString(stdout, StandardCharsets.UTF_8);
        } finally {
            process.destroyForcibly();
        }
    }

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }

    private static void evidence(String name, String input, String output) {
        System.out.println(name + "\nINPUT: " + escaped(input) + "\nOUTPUT: " + escaped(output));
    }

    private static String escaped(String text) {
        return text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    private record Probe(String name, String source, String payload) {}
}
