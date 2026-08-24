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
}
