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
}
