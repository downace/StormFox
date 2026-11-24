package com.github.downace.stormfox.format;

import java.nio.file.Files;
import java.nio.file.Path;

public class WaitingTwigVersionProbe {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[0]), "started");
        Thread.sleep(60000);
    }
}
