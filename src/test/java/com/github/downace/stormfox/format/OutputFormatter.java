package com.github.downace.stormfox.format;

public class OutputFormatter {
    public static void main(String[] args) {
        if (args[0].equals("blank")) System.out.print(" \n\t");
        if (args[0].equals("normal")) System.out.print("<?php\n");
        System.err.print("diagnostic on stderr");
    }
}
