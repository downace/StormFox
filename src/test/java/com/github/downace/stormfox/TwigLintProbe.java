package com.github.downace.stormfox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

public class TwigLintProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[2].equals("--version")) {
            System.out.print("mago " + args[0]);
            return;
        }
        Files.writeString(Path.of(args[1]), String.join(" ", Arrays.copyOfRange(args, 2, args.length)) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.in.readAllBytes();
        if (Arrays.asList(args).contains("config")) {
            System.out.print("{\"workspace\":\".\"}");
        } else {
            System.out.print("{\"issues\":[]}");
        }
    }
}
