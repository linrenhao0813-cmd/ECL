package com.ecl.auth.offline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps character indexes and the bounded access-ordered local texture cache consistent. */
final class OfflineSkinCharacterRegistry {
    static final long MAX_TEXTURE_BYTES = 1024 * 1024;
    private static final int MAX_TEXTURES = 16;

    private final Map<String, byte[]> textures = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, OfflineSkinYggdrasilResponses.Character> byUuid = new ConcurrentHashMap<>();
    private final Map<String, OfflineSkinYggdrasilResponses.Character> byName = new ConcurrentHashMap<>();

    synchronized OfflineSkinYggdrasilResponses.Character register(String uuid, String username,
                                                                    Path skinPng, boolean slim)
            throws IOException {
        long size = Files.size(skinPng);
        if (size <= 0 || size > MAX_TEXTURE_BYTES) {
            throw new IOException("Offline skin must be between 1 byte and 1 MB");
        }
        byte[] png;
        try (InputStream input = Files.newInputStream(skinPng)) {
            png = input.readNBytes((int) MAX_TEXTURE_BYTES + 1);
        }
        if (png.length == 0 || png.length > MAX_TEXTURE_BYTES) {
            throw new IOException("Offline skin changed while it was being read");
        }
        String hash = OfflineSkinTextureSigner.sha1Hex(png);
        synchronized (textures) {
            textures.put(hash, png);
        }
        var character = new OfflineSkinYggdrasilResponses.Character(uuid, username, hash, slim);
        byUuid.put(uuid, character);
        byName.put(username, character);
        pruneUnusedTextures();
        return character;
    }

    synchronized void unregister(OfflineSkinYggdrasilResponses.Character character) {
        byUuid.remove(character.uuid(), character);
        byName.remove(character.name(), character);
        pruneUnusedTextures();
    }

    synchronized OfflineSkinYggdrasilResponses.Character byName(String username) {
        return byName.get(username);
    }

    synchronized OfflineSkinYggdrasilResponses.Character byUuid(String uuid) {
        return byUuid.get(uuid);
    }

    synchronized int userCount() {
        return byUuid.size();
    }

    byte[] texture(String hash) {
        synchronized (textures) {
            return textures.get(hash);
        }
    }

    private void pruneUnusedTextures() {
        synchronized (textures) {
            Iterator<String> hashes = textures.keySet().iterator();
            while (textures.size() > MAX_TEXTURES && hashes.hasNext()) {
                String candidate = hashes.next();
                boolean referenced = byUuid.values().stream()
                        .anyMatch(character -> character.textureHash().equals(candidate));
                if (!referenced) hashes.remove();
            }
        }
    }
}
