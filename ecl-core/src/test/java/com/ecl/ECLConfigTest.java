package com.ecl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ECLConfigTest {
    @Test
    void automaticMemoryIsBoundedAndAligned() {
        int memoryMb = ECLConfig.calculateAutoMemoryMb();

        assertTrue(memoryMb >= ECLConfig.MIN_GAME_MEMORY_MB);
        assertTrue(memoryMb <= ECLConfig.MAX_AUTO_MEMORY_MB);
        assertEquals(0, memoryMb % 256);
    }

    @Test
    void worldBackupSettingsHaveSafeDefaults() {
        assertTrue(ECLConfig.KEY_BACKUP_ON_LAUNCH.defaultValue());
        assertEquals(10, ECLConfig.KEY_BACKUP_KEEP_COUNT.defaultValue());
        assertEquals(false, ECLConfig.KEY_BACKUP_INCLUDE_MODS.defaultValue());
    }

    @Test
    void curseForgeApiKeyHasATypeSafeSettingKey() {
        assertEquals("curseForgeApiKey", ECLConfig.KEY_CURSEFORGE_API_KEY.key());
        assertEquals("", ECLConfig.KEY_CURSEFORGE_API_KEY.defaultValue());
    }
}
