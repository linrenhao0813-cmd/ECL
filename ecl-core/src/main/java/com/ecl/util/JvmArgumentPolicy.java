package com.ecl.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rejects JVM arguments that can load extra code or run a shell command. */
public final class JvmArgumentPolicy {
    private JvmArgumentPolicy() {
    }

    public static List<String> requireSafe(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }
        List<String> safe = new ArrayList<>(arguments.size());
        for (String argument : arguments) {
            if (argument == null || argument.isBlank()) {
                continue;
            }
            requireSafe(argument);
            safe.add(argument);
        }
        return List.copyOf(safe);
    }

    public static void requireSafe(String argument) {
        if (argument == null || argument.isBlank()) {
            return;
        }
        String trimmed = argument.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("@")) {
            throw new IllegalArgumentException("JVM 参数不允许使用参数文件: " + argument);
        }
        if (lower.startsWith("-javaagent")
                || lower.startsWith("-agentpath")
                || lower.startsWith("-agentlib")
                || lower.startsWith("-xbootclasspath")
                || lower.startsWith("-xx:onerror")
                || lower.startsWith("-xx:onoutofmemoryerror")) {
            throw new IllegalArgumentException("JVM 参数包含不允许的选项: " + argument);
        }
    }
}
