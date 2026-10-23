package com.ecl.game;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetIndexTest {
    @Test
    void parsesTheVersionMetadataDescriptor() {
        AssetIndex index = AssetIndex.parse(JsonParser.parseString("""
                {"id":"legacy","url":"https://example/legacy.json","sha1":"aa","totalSize":10}
                """).getAsJsonObject());

        assertEquals(new AssetIndex("legacy", "https://example/legacy.json", "aa", 10), index);
        assertTrue(index.hasId());
        assertTrue(index.hasChecksum());
    }

    @Test
    void handlesMissingDescriptorAndOptionalFields() {
        assertNull(AssetIndex.parse(null));
        AssetIndex index = AssetIndex.parse(JsonParser.parseString("{}").getAsJsonObject());
        assertFalse(index.hasId());
        assertFalse(index.hasChecksum());
        assertEquals(-1, index.totalSize());
    }
}
