package com.ecl.launch;

/**
 * A fixed-size character ring that retains only the newest tail of captured process output.
 * Append and read are cheap and thread-safe; old head characters are overwritten in place rather
 * than re-shifting the buffer, so bursty game logs stay well behaved.
 */
public final class BoundedLogBuffer {

    private final char[] chars;
    private int start;
    private int size;

    public BoundedLogBuffer(int capacity) {
        chars = new char[Math.max(1, capacity)];
    }

    public synchronized void appendLine(String line) {
        append(line);
        append("\n");
    }

    public synchronized void append(CharSequence text) {
        int textLength = text.length();
        if (textLength == 0) {
            return;
        }
        int capacity = chars.length;
        if (textLength >= capacity) {
            // 只保留 text 的最后 capacity 个字符
            int offset = textLength - capacity;
            copyIn(text, offset, 0, capacity);
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

    public synchronized void clear() {
        start = 0;
        size = 0;
    }

    /** Number of characters currently retained. */
    public synchronized int size() {
        return size;
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
