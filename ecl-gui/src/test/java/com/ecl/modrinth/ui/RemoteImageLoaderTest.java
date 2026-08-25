package com.ecl.modrinth.ui;

import org.junit.jupiter.api.Test;

import java.net.URI;

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

    @Test
    void acceptsOnlyProviderOwnedHttpsIconHosts() {
        assertTrue(RemoteImageLoader.isTrustedIconUri(
                URI.create("https://cdn.modrinth.com/data/project/icon.png")));
        assertTrue(RemoteImageLoader.isTrustedIconUri(
                URI.create("https://media.forgecdn.net/avatars/icon.png")));
        assertFalse(RemoteImageLoader.isTrustedIconUri(
                URI.create("http://cdn.modrinth.com/data/project/icon.png")));
        assertFalse(RemoteImageLoader.isTrustedIconUri(
                URI.create("https://127.0.0.1/icon.png")));
        assertFalse(RemoteImageLoader.isTrustedIconUri(
                URI.create("https://cdn.modrinth.com.attacker.example/icon.png")));
    }
}
