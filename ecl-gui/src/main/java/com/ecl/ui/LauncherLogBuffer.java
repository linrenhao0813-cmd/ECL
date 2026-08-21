package com.ecl.ui;

/** Fixed-size character ring retaining only the newest launcher log tail. */
final class LauncherLogBuffer {
    private final char[] chars;
    private int start;
    private int size;

    LauncherLogBuffer(int capacity) {
        chars = new char[Math.max(1, capacity)];
    }

    synchronized void appendLine(String line) {
        append(line);
        append(System.lineSeparator());
    }

    synchronized void append(CharSequence text) {
        int textLength = text.length();
        if (textLength == 0) {
            return;
        }
        int capacity = chars.length;
        if (textLength >= capacity) {
            copyIn(text, textLength - capacity, 0, capacity);
            start = 0;
            size = capacity;
            return;
        }
        int writePos = (start + size) % capacity;
        int remaining = capacity - size;
        if (textLength <= remaining) {
            copyIn(text, 0, writePos, textLength);
            size += textLength;
            return;
        }
        if (remaining > 0) {
            copyIn(text, 0, writePos, remaining);
        }
        int secondChunk = textLength - remaining;
        copyIn(text, remaining, start, secondChunk);
        start = (start + secondChunk) % capacity;
        size = capacity;
    }

    synchronized void clear() {
        start = 0;
        size = 0;
    }

    private void copyIn(CharSequence text, int textOffset, int destOffset, int length) {
        if (length <= 0) {
            return;
        }
        int capacity = chars.length;
        int wrappedDest = destOffset % capacity;
        int contiguous = capacity - wrappedDest;
        if (length <= contiguous) {
            for (int i = 0; i < length; i++) {
                chars[wrappedDest + i] = text.charAt(textOffset + i);
            }
            return;
        }
        for (int i = 0; i < contiguous; i++) {
            chars[wrappedDest + i] = text.charAt(textOffset + i);
        }
        int second = length - contiguous;
        for (int i = 0; i < second; i++) {
            chars[i] = text.charAt(textOffset + contiguous + i);
        }
    }

    @Override
    public synchronized String toString() {
        StringBuilder result = new StringBuilder(size);
        int firstPart = Math.min(size, chars.length - start);
        result.append(chars, start, firstPart);
        if (firstPart < size) {
            result.append(chars, 0, size - firstPart);
        }
        return result.toString();
    }
}
