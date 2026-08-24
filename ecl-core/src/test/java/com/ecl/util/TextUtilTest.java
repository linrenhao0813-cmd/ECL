package com.ecl.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void parsesQuotedCommandLineArguments() {
        assertEquals(
                java.util.List.of("-Djava.library.path=C:\\Program Files\\ECL", "-Xmx2G", ""),
                TextUtil.parseCommandLine("\"-Djava.library.path=C:\\Program Files\\ECL\" -Xmx2G \"\""));
    }

    @Test
    void parsesSingleQuotesAndEscapedQuotes() {
        assertEquals(
                java.util.List.of("-Dmessage=hello world", "-Dquote=\"yes\""),
                TextUtil.parseCommandLine("'-Dmessage=hello world' -Dquote=\\\"yes\\\""));
    }

    @Test
    void rejectsUnclosedQuotes() {
        assertThrows(IllegalArgumentException.class,
                () -> TextUtil.parseCommandLine("-Dpath=\"C:\\Program Files"));
    }

    @Test
    void formatsArgumentsForLosslessParsing() {
        java.util.List<String> arguments = java.util.List.of(
                "-XX:+UseG1GC", "-Dmessage=hello world", "-Dpath=C:\\Program Files\\", "");

        assertEquals(arguments, TextUtil.parseCommandLine(TextUtil.formatCommandLine(arguments)));
    }
}
