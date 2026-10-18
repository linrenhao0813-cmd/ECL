package com.ecl.ui;

import com.ecl.util.Messages;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiMessagesTest {
    @Test
    void missingKeysDoNotBreakAnySupportedLocale() {
        Locale previous = Messages.locale();
        try {
            for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.ENGLISH)) {
                Messages.setLocale(locale);
                assertEquals("missing.gui.key", GuiMessages.get("missing.gui.key", "argument"));
                assertEquals("", GuiMessages.get(null));
                String text = GuiMessages.get("instance.install.server.destination", "test-directory");
                assertTrue(text.contains("test-directory"));
                assertFalse(text.contains("{0}"));
                assertFalse(GuiMessages.get("launch.downloadTimeout").contains("launch.downloadTimeout"));
            }
        } finally {
            Messages.setLocale(previous);
        }
    }
}
