package com.ecl.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopShortcutServiceTest {

    @Test
    void escapesBatchMetacharactersAndNewlines() {
        assertEquals("C:\\Games^&ECL\\100%%^|^^^<^>^(^)  ",
                DesktopShortcutService.escapeBatchCommand(
                "C:\\Games&ECL\\100%|^<>()\r\n"));
    }

    @Test
    void quotesWindowsArgumentsWithTrailingBackslashes() {
        assertEquals("plain", DesktopShortcutService.quoteWindowsArgument("plain"));
        assertEquals("C:\\foo\\bar", DesktopShortcutService.quoteWindowsArgument("C:\\foo\\bar"));
        assertEquals("C:\\foo\\bar\\", DesktopShortcutService.quoteWindowsArgument("C:\\foo\\bar\\"));
        assertEquals("\"C:\\Program Files\\foo\\\\\"",
                DesktopShortcutService.quoteWindowsArgument("C:\\Program Files\\foo\\"));
        assertEquals("\"hello\\\"world\"",
                DesktopShortcutService.quoteWindowsArgument("hello\"world"));
        assertEquals("\"\"", DesktopShortcutService.quoteWindowsArgument(""));
        assertEquals("\"Program Files\"",
                DesktopShortcutService.quoteWindowsArgument("Program Files"));
    }
}
