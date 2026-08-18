package com.ecl.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadSourceUtilTest {
    @Test
    void addsMirrorsInStableOrderForMinecraftLibraries() {
        String official = "https://libraries.minecraft.net/a/b.jar";
        List<String> candidates = DownloadSourceUtil.candidates(official);
        assertEquals(official, candidates.get(0));
        assertTrue(candidates.contains("https://bmclapi2.bangbang93.com/maven/a/b.jar"));
        assertTrue(candidates.contains("https://libraries.fastmcmirror.org/a/b.jar"));
    }

    @Test
    void leavesUnknownHostsUntouched() {
        String url = "https://example.com/file";
        assertEquals(List.of(url), DownloadSourceUtil.candidates(url));
    }

    @Test
    void addsBmclapiFallbackForPistonServerFiles() {
        String official = "https://piston-data.mojang.com/v1/objects/hash/server.jar";
        assertEquals(List.of(
                        official,
                        "https://bmclapi2.bangbang93.com/v1/objects/hash/server.jar"),
                DownloadSourceUtil.candidates(official));
    }
}
