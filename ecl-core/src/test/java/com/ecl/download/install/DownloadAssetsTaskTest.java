package com.ecl.download.install;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DownloadAssetsTaskTest {
    @Test
    void acceptsOnlySafeAssetIdsAndFullSha1Hashes() throws Exception {
        String hash = "0123456789abcdef0123456789abcdef01234567";

        assertEquals("1.21", DownloadAssetsTask.requireSafeAssetId("1.21"));
        assertEquals(hash, DownloadAssetsTask.requireSha1(hash, "minecraft/lang/en_us.json"));
        assertThrows(IOException.class,
                () -> DownloadAssetsTask.requireSafeAssetId("../outside"));
        assertThrows(IOException.class,
                () -> DownloadAssetsTask.requireSha1("../malicious", "bad"));
        assertThrows(IOException.class,
                () -> DownloadAssetsTask.requireSha1("abc", "short"));
    }
}
