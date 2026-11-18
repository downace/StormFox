package com.github.downace.stormfox.format;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class TwigFormatterProbe {
    public static void main(String[] args) throws Exception {
        String version = args[0];
        Path marker = Path.of(args[1]);
        if (args.length == 3 && args[2].equals("--version")) {
            System.out.print("mago " + version);
            return;
        }
        Files.writeString(marker, String.join("\n", Arrays.copyOfRange(args, 2, args.length)));
        System.out.write(System.in.readAllBytes());
    }
}
