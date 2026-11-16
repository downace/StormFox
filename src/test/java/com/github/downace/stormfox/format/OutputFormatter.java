package com.github.downace.stormfox.format;

public class OutputFormatter {
    public static void main(String[] args) {
        if (args.length > 1 && args[1].equals("--version")) {
            if (args[0].startsWith("warm")) System.exit(3);
            if (args[0].equals("stderr")) {
                System.err.print("mago 1.18.0");
                return;
            }
            System.out.print("mago " + (args[0].equals("old") ? "1.18.0" : "1.25.0"));
            return;
        }
        if (args[0].equals("old") || args[0].equals("new") || args[0].equals("stderr") || args[0].startsWith("warm")) {
            boolean hasPath = java.util.Arrays.asList(args).contains("--stdin-filepath");
            if (hasPath != (args[0].equals("new") || args[0].equals("warm-new"))) System.exit(2);
            try {
                System.out.write(System.in.readAllBytes());
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            return;
        }
        if (args[0].equals("blank")) System.out.print(" \n\t");
        if (args[0].equals("normal")) System.out.print("<?php\n");
        System.err.print("diagnostic on stderr");
    }
}
