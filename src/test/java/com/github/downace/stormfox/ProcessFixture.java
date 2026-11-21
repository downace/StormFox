package com.github.downace.stormfox;

import java.nio.charset.StandardCharsets;

public class ProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "eof" -> {
                System.in.readAllBytes();
                System.out.print("out-é");
                System.err.print("err-狐");
            }
            case "pipes" -> {
                byte[] block = "é".repeat(100_000).getBytes(StandardCharsets.UTF_8);
                System.out.write(block);
                System.err.write(block);
                System.in.readAllBytes();
                System.out.print("done");
            }
            case "limit" -> {
                String text = "é".repeat(Integer.parseInt(args[2]));
                if (args[1].equals("out")) System.out.print(text);
                else System.err.print(text);
            }
            case "early" -> {
                System.in.close();
                System.err.print("useful error");
                System.exit(7);
            }
            case "wait" -> new java.util.concurrent.CountDownLatch(1).await();
            case "blocked-write" -> {
                System.in.read();
                java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]), "ready");
                new java.util.concurrent.CountDownLatch(1).await();
            }
            case "tree" -> {
                String executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
                Process child = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                        ProcessFixture.class.getName(), "wait").start();
                var ready = java.nio.file.Path.of(args[1]);
                var staging = ready.resolveSibling("child.tmp");
                java.nio.file.Files.writeString(staging, Long.toString(child.pid()));
                java.nio.file.Files.move(staging, ready);
                child.waitFor();
            }
        }
    }
}
