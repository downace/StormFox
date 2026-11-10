package com.github.downace.stormfox.format;

public final class WaitingFormatter {
    public static void main(String[] args) throws Exception {
        System.out.println("ready");
        System.out.flush();
        System.in.read();
    }
}
