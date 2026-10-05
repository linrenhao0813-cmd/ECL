package com.ecl.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone child JVM fixture exercising the same executable-JAR protocol as Minecraft. */
public final class FakeServerMain {
    private FakeServerMain() { }

    public static void main(String[] arguments) throws Exception {
        if (!Files.readString(Path.of("eula.txt")).contains("eula=true")) System.exit(2);
        System.out.println("READY " + String.join(" ", arguments));
        System.out.flush();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String command;
            while ((command = input.readLine()) != null) {
                Files.writeString(Path.of("last-command.txt"), command, StandardCharsets.UTF_8);
                System.out.println("COMMAND " + command);
                System.out.flush();
                if ("stop".equals(command)) {
                    Files.writeString(Path.of("world-saved.txt"), "saved", StandardCharsets.UTF_8);
                    return;
                }
            }
        }
    }
}
