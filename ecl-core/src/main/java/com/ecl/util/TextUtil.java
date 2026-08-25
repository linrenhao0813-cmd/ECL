package com.ecl.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TextUtil {
    private TextUtil() {
    }

    public static String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        if (maxLength <= 3) return text.substring(0, Math.max(0, maxLength));
        int head = Math.max(1, (maxLength - 3) / 2);
        int tail = maxLength - head - 3;
        return text.substring(0, head) + "..." + text.substring(text.length() - tail);
    }

    public static String formatCount(long value) {
        if (value >= 100_000_000L) return String.format(Locale.ROOT, "%.1f 亿", value / 100_000_000.0);
        if (value >= 10_000L) return String.format(Locale.ROOT, "%.1f 万", value / 10_000.0);
        return Long.toString(value);
    }

    /**
     * Sanitizes a string for use as a single filename component. Replaces characters that are
     * invalid on Windows, trims surrounding whitespace, and maps blank, {@code "."}, {@code ".."}
     * or Windows reserved names (CON, PRN, AUX, NUL, COM1-9, LPT1-9) to a safe default so callers
     * never derive a path-traversal or device-name filename.
     */
    public static String replaceInvalidFilenameChars(String text) {
        if (text == null) {
            return null;
        }
        String replaced = text.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (replaced.isEmpty() || ".".equals(replaced) || "..".equals(replaced)
                || isWindowsReservedName(replaced)) {
            return "_";
        }
        return replaced;
    }

    /** Windows device/reserved names, ignoring a trailing extension, e.g. {@code CON}, {@code LPT1}. */
    public static boolean isWindowsReservedName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String name = value.replaceFirst("\\..*$", "").toUpperCase(Locale.ROOT);
        return name.equals("CON") || name.equals("PRN") || name.equals("AUX")
                || name.equals("NUL") || name.matches("COM[1-9]") || name.matches("LPT[1-9]");
    }

    /**
     * Splits a user-entered command line without asking a platform shell to interpret it.
     * Single and double quotes group whitespace; a backslash only escapes a matching quote
     * or another backslash, so Windows paths keep their separators.
     */
    public static List<String> parseCommandLine(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return List.of();
        }

        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean tokenStarted = false;
        for (int index = 0; index < commandLine.length(); index++) {
            char character = commandLine.charAt(index);
            if (quote == 0 && Character.isWhitespace(character)) {
                if (tokenStarted) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
                continue;
            }
            if (character == '\'' || character == '"') {
                if (quote == 0) {
                    quote = character;
                    tokenStarted = true;
                    continue;
                }
                if (quote == character) {
                    quote = 0;
                    continue;
                }
            }
            if (character == '\\' && index + 1 < commandLine.length()) {
                char next = commandLine.charAt(index + 1);
                if (next == '\\' || next == quote || (quote == 0 && (next == '\'' || next == '"'))) {
                    current.append(next);
                    index++;
                    tokenStarted = true;
                    continue;
                }
            }
            current.append(character);
            tokenStarted = true;
        }
        if (quote != 0) {
            throw new IllegalArgumentException("Unclosed quote in command line");
        }
        if (tokenStarted) {
            arguments.add(current.toString());
        }
        return List.copyOf(arguments);
    }

    /** Formats arguments so {@link #parseCommandLine(String)} can reconstruct them exactly. */
    public static String formatCommandLine(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        List<String> formatted = new ArrayList<>(arguments.size());
        for (String argument : arguments) {
            if (argument == null) {
                continue;
            }
            if (!argument.isEmpty()
                    && argument.chars().noneMatch(character -> Character.isWhitespace(character)
                    || character == '\'' || character == '"')) {
                formatted.add(argument);
                continue;
            }
            formatted.add("\"" + argument
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"") + "\"");
        }
        return String.join(" ", formatted);
    }
}
