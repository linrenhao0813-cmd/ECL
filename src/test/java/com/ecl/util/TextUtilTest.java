package com.ecl.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextUtilTest {
    @Test
    void formatsCountsConsistently() {
        assertEquals("1.0 万", TextUtil.formatCount(10_000));
        assertEquals("1.0 亿", TextUtil.formatCount(100_000_000));
    }

    @Test
    void abbreviationNeverExceedsLimit() {
        assertEquals(12, TextUtil.abbreviate("abcdefghijklmnop", 12).length());
    }

    @Test
    void replacesInvalidFilenameCharacters() {
        assertEquals("a_b_c_", TextUtil.replaceInvalidFilenameChars("a/b:c?"));
    }
}
