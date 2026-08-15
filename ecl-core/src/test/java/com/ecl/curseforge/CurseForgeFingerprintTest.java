package com.ecl.curseforge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CurseForgeFingerprintTest {
    @Test
    void calculatesUnsignedMurmur2AndIgnoresCurseForgeWhitespace(@TempDir Path temp)
            throws Exception {
        Path compact = temp.resolve("compact.jar");
        Path spaced = temp.resolve("spaced.jar");
        Files.writeString(compact, "hello");
        Files.writeString(spaced, "h e\nl\tl\ro");

        assertEquals(2_788_266_382L, CurseForgeFingerprint.calculate(compact));
        assertEquals(CurseForgeFingerprint.calculate(compact),
                CurseForgeFingerprint.calculate(spaced));
    }
}
