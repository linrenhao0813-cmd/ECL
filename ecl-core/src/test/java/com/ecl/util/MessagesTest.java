package com.ecl.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {

    @Test
    void returnsKnownKey() {
        String label = Messages.get("nav.home");
        assertEquals("首页", label);
    }

    @Test
    void returnsKnownFormattedKey() {
        String msg = Messages.format("crash.count", 5);
        assertEquals("5 个", msg);
    }

    @Test
    void returnsKeyItselfForMissingKey() {
        String result = Messages.get("nonexistent.key");
        assertEquals("nonexistent.key", result);
    }

    @Test
    void returnsKeyItselfForMissingFormattedKey() {
        String result = Messages.format("nonexistent.key", "arg");
        assertEquals("nonexistent.key", result);
    }

    @Test
    void multipleFormattedArguments() {
        String msg = Messages.format("status.versionListUpdated.detail", 10, "正式版");
        assertTrue(msg.contains("10"));
        assertTrue(msg.contains("正式版"));
    }

    @Test
    void navLabelsAreAllPresent() {
        assertNotNull(Messages.get("nav.home"));
        assertNotNull(Messages.get("nav.versions"));
        assertNotNull(Messages.get("nav.modrinth"));
        assertNotNull(Messages.get("nav.settings"));
        assertNotNull(Messages.get("nav.logs"));
    }

    @Test
    void authLabelsAreAllPresent() {
        assertNotNull(Messages.get("auth.offline"));
        assertNotNull(Messages.get("auth.microsoft"));
        assertNotNull(Messages.get("auth.yggdrasil"));
    }

    @Test
    void versionLabelsAreAllPresent() {
        assertNotNull(Messages.get("version.featured"));
        assertNotNull(Messages.get("version.release"));
        assertNotNull(Messages.get("version.preview"));
        assertNotNull(Messages.get("version.aprilFools"));
        assertNotNull(Messages.get("version.all"));
    }
}
