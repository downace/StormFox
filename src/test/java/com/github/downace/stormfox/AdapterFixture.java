package com.github.downace.stormfox;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A wrapper that requires EOF, just as container/remote command wrappers may. */
public class AdapterFixture {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        var command = Arrays.asList(args);
        if (command.contains("--version")) {
            System.in.readAllBytes();
            if (mode.equals("unknown")) { System.out.print("unrecognized"); return; }
            if (mode.equals("version-exit")) { System.err.print("version failure"); System.exit(7); }
            System.out.print("wrapper");
            System.err.print("mago 1.60.0");
            return;
        }
        if (command.contains("--show")) {
            System.in.readAllBytes();
            if (mode.equals("source-exit")) { System.err.print("bad source"); System.exit(2); }
            String cwd = System.getProperty("user.dir").replace("\\", "\\\\").replace("\"", "\\\"");
            System.out.print("{\"workspace\":\"" + cwd + "\"}");
            System.err.print("WARN source warning");
            return;
        }
        if (command.contains("--list-rules")) {
            System.in.readAllBytes();
            if (mode.equals("metadata-exit")) { System.exit(1); }
            String json = "[{\"code\":\"test\"}]";
            if (mode.startsWith("metadata-limit")) {
                String padding = " ".repeat(4 * 1024 * 1024 - json.length() + (mode.endsWith("over") ? 1 : 0));
                if (mode.contains("stderr")) {
                    System.out.print(json);
                    System.err.print("é".repeat(4 * 1024 * 1024 + (mode.endsWith("over") ? 1 : 0)));
                } else System.out.print(json + padding);
            } else System.out.print(json);
            return;
        }
        if (command.contains("config")) {
            System.in.readAllBytes();
            if (mode.equals("config-exit")) { System.err.print("invalid config"); System.exit(2); }
            System.out.print("config stdout");
            System.err.print("config stderr");
            return;
        }
        if (command.contains("format")) {
            if (mode.equals("format-early")) {
                System.in.close();
                System.err.print("format early failure");
                System.exit(2);
            }
            byte[] input = System.in.readAllBytes();
            if (mode.equals("format-exit")) { System.err.print("format failure"); System.exit(2); }
            System.out.write(input);
            return;
        }
        if (mode.equals("diagnostic-early")) {
            System.in.close();
            System.err.print("diagnostic early failure");
            System.exit(2);
        }
        System.in.readAllBytes();
        if (!mode.startsWith("diagnostic-empty")) System.out.print("{\"issues\":[]}");
        System.err.write("WARN diagnostic warning".getBytes(StandardCharsets.UTF_8));
        if (mode.equals("diagnostic-one")) System.exit(1);
        if (mode.equals("diagnostic-two")) System.exit(2);
        if (mode.equals("diagnostic-empty-one")) System.exit(1);
    }
}
