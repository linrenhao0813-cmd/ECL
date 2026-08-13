package com.ecl.modrinth.ui;

import java.util.ArrayList;
import java.util.List;

/** Small Markdown-to-readable-text formatter for release notes; deliberately has no HTML output. */
public final class MinimalMarkdown {
    private MinimalMarkdown() {
    }

    public static String format(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "此版本没有提供更新日志。";
        }
        List<String> output = new ArrayList<>();
        boolean codeBlock = false;
        for (String sourceLine : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = sourceLine.stripTrailing();
            if (line.stripLeading().startsWith("```")) {
                codeBlock = !codeBlock;
                if (!codeBlock) {
                    output.add("");
                }
                continue;
            }
            if (codeBlock) {
                output.add("    " + line);
                continue;
            }
            String trimmed = line.strip();
            if (trimmed.matches("^#{1,6}\\s+.*")) {
                output.add("");
                output.add("◆ " + trimmed.replaceFirst("^#{1,6}\\s+", ""));
            } else if (trimmed.matches("^[-*+]\\s+.*")) {
                output.add("  • " + inline(trimmed.substring(2).strip()));
            } else if (trimmed.matches("^>\\s?.*")) {
                output.add("  │ " + inline(trimmed.replaceFirst("^>\\s?", "")));
            } else {
                output.add(inline(line));
            }
        }
        while (!output.isEmpty() && output.get(0).isBlank()) {
            output.remove(0);
        }
        List<String> compact = new ArrayList<>();
        for (String line : output) {
            if (!line.isBlank() || compact.isEmpty() || !compact.get(compact.size() - 1).isBlank()) {
                compact.add(line);
            }
        }
        return String.join("\n", compact).strip();
    }

    private static String inline(String value) {
        return value.replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("__(.+?)__", "$1")
                .replaceAll("`([^`]+)`", "‹$1›")
                .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1");
    }
}
