package com.ecl.modrinth.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteImageLoaderTest {
    @Test
    void acceptsNormalIconDimensions() {
        assertTrue(RemoteImageLoader.isSourceSizeAllowed(512, 512));
    }

    @Test
    void rejectsInvalidOrDecompressionBombDimensions() {
        assertFalse(RemoteImageLoader.isSourceSizeAllowed(0, 48));
        assertFalse(RemoteImageLoader.isSourceSizeAllowed(8_193, 1));
        assertFalse(RemoteImageLoader.isSourceSizeAllowed(8_000, 8_000));
    }
}
