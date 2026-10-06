package com.ecl.ui;

import com.ecl.util.Messages;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerInstallMessagesTest {
    @Test
    void allServerLabelsAreTranslatedInEverySupportedLocale() {
        Locale previous = Messages.locale();
        try {
            for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.ENGLISH)) {
                Messages.setLocale(locale);
                for (String suffix : List.of("mode.client", "mode.server", "server.destination", "server.notice",
                        "server.invalid", "server.task", "server.taskStatus", "server.cancelled",
                        "server.failed", "server.finished", "server.complete", "server.subtitle", "server.choiceSubtitle",
                        "server.vanilla", "server.fabric", "server.selected", "server.cleanupFailed")) {
                    String text = GuiMessages.get("instance.install." + suffix, "server-root", "Fabric");
                    assertFalse(text.isBlank());
                    assertFalse(text.contains("instance.install."));
                    assertFalse(text.contains("{0}"));
                }
                assertTrue(GuiMessages.get("instance.install.server.destination", "server-root").contains("server-root"));
                assertTrue(GuiMessages.get("instance.install.server.notice").contains("https://www.minecraft.net/eula"));
            }
        } finally {
            Messages.setLocale(previous);
        }
    }
}
