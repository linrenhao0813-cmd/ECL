package com.ecl.util;

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

    public static String replaceInvalidFilenameChars(String text) {
        return text == null ? null : text.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
