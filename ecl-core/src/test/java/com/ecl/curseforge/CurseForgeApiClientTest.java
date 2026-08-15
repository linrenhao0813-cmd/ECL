package com.ecl.curseforge;

import com.ecl.modrinth.api.ModSearchIndex;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurseForgeApiClientTest {
    @Test
    void mapsMinecraftClassesAndLoaders() throws Exception {
        assertEquals(6, CurseForgeApiClient.classId("mod"));
        assertEquals(6552, CurseForgeApiClient.classId("shader"));
        assertEquals(12, CurseForgeApiClient.classId("resourcepack"));
        assertEquals(4471, CurseForgeApiClient.classId("modpack"));
        assertEquals(1, CurseForgeApiClient.loaderType("forge"));
        assertEquals(4, CurseForgeApiClient.loaderType("fabric"));
        assertEquals(5, CurseForgeApiClient.loaderType("quilt"));
        assertEquals(6, CurseForgeApiClient.loaderType("neoforge"));
    }

    @Test
    void missingKeyFailsBeforeNetworkAccess() {
        CurseForgeApiClient client = new CurseForgeApiClient(() -> "");

        IOException error = assertThrows(IOException.class,
                () -> client.search("sodium", "1.21.1", "mod", "fabric", 0, 20, false));

        assertTrue(error.getMessage().contains("API Key"));
    }

    @Test
    void mapsSearchSortsAndUsesPaginationTotal() {
        assertEquals(2, CurseForgeApiClient.sortField(ModSearchIndex.RELEVANCE));
        assertEquals(6, CurseForgeApiClient.sortField(ModSearchIndex.DOWNLOADS));
        assertEquals(11, CurseForgeApiClient.sortField(ModSearchIndex.NEWEST));
        assertEquals(3, CurseForgeApiClient.sortField(ModSearchIndex.UPDATED));
        assertEquals(1234, CurseForgeApiClient.paginationTotal(
                JsonParser.parseString("{\"totalCount\":1234}").getAsJsonObject(), 20));
    }
}
