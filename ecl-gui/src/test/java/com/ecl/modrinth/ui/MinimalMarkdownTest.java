package com.ecl.modrinth.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimalMarkdownTest {
    @Test
    void formatsHeadingsListsLinksAndCodeWithoutHtml() {
        String formatted = MinimalMarkdown.format("""
                # Changes
                - Fixed **startup**
                - See [details](https://example.invalid)
                ```java
                if (ready) launch();
                ```
                """);

        assertTrue(formatted.contains("◆ Changes"));
        assertTrue(formatted.contains("• Fixed startup"));
        assertTrue(formatted.contains("See details"));
        assertTrue(formatted.contains("    if (ready) launch();"));
    }

    @Test
    void suppliesEmptyState() {
        assertEquals("此版本没有提供更新日志。", MinimalMarkdown.format(" "));
    }
}
