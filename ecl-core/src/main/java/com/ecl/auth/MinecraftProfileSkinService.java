package com.ecl.auth;

import com.ecl.util.HttpUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Looks up a public Minecraft profile's skin without using account credentials. */
public final class MinecraftProfileSkinService {
    private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final String TEXTURE_ORIGIN = "https://textures.minecraft.net";
    private static final Pattern UUID_HEX = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern TEXTURE_PATH = Pattern.compile("/texture/[0-9a-f]{32,64}");
    private static final int MAX_PROFILE_CHARS = 32 * 1024;
    private static final int MAX_SKIN_BYTES = 1024 * 1024;

    private final Transport transport;

    public MinecraftProfileSkinService() {
        this(new Transport() {
            @Override
            public String getProfile(String url) throws IOException {
                return HttpUtil.get(url);
            }

            @Override
            public byte[] getTexture(String url, int maxBytes) throws IOException {
                return HttpUtil.getBytes(url, maxBytes);
            }
        });
    }

    MinecraftProfileSkinService(Transport transport) {
        this.transport = transport;
    }

    /** Returns an empty result for accounts without a valid public skin. */
    public Optional<byte[]> load(String uuid) throws IOException {
        String normalized = uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT);
        if (!UUID_HEX.matcher(normalized).matches()) {
            return Optional.empty();
        }
        String profile = transport.getProfile(PROFILE_URL + normalized);
        if (profile == null || profile.length() > MAX_PROFILE_CHARS) {
            throw new IOException("Minecraft profile response is too large or empty");
        }
        Optional<String> texture = textureUrl(profile);
        if (texture.isEmpty()) {
            return Optional.empty();
        }
        byte[] png = transport.getTexture(texture.get(), MAX_SKIN_BYTES);
        if (!isSkinPng(png)) {
            throw new IOException("Minecraft profile skin is not a valid 64-pixel PNG");
        }
        return Optional.of(png);
    }

    private static Optional<String> textureUrl(String profile) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(profile).getAsJsonObject();
            JsonArray properties = root.getAsJsonArray("properties");
            if (properties == null) {
                return Optional.empty();
            }
            for (JsonElement element : properties) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject property = element.getAsJsonObject();
                if (!"textures".equals(string(property, "name"))) {
                    continue;
                }
                String encoded = string(property, "value");
                if (encoded.length() > MAX_PROFILE_CHARS) {
                    throw new IOException("Minecraft texture property is too large");
                }
                String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                JsonObject textures = JsonParser.parseString(decoded).getAsJsonObject().getAsJsonObject("textures");
                if (textures == null || !textures.has("SKIN")) {
                    return Optional.empty();
                }
                return safeTextureUrl(string(textures.getAsJsonObject("SKIN"), "url"));
            }
            return Optional.empty();
        } catch (RuntimeException malformed) {
            throw new IOException("Minecraft profile contains invalid skin metadata", malformed);
        }
    }

    private static Optional<String> safeTextureUrl(String value) {
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (!"textures.minecraft.net".equalsIgnoreCase(uri.getHost())
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getPort() != -1 || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || path == null || !TEXTURE_PATH.matcher(path.toLowerCase(Locale.ROOT)).matches()) {
                return Optional.empty();
            }
            return Optional.of(TEXTURE_ORIGIN + path.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }

    private static boolean isSkinPng(byte[] bytes) {
        if (bytes == null || bytes.length < 24 || bytes.length > MAX_SKIN_BYTES
                || bytes[0] != (byte) 0x89 || bytes[1] != 0x50 || bytes[2] != 0x4e || bytes[3] != 0x47
                || bytes[4] != 0x0d || bytes[5] != 0x0a || bytes[6] != 0x1a || bytes[7] != 0x0a
                || bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
            return false;
        }
        int width = int32(bytes, 16);
        int height = int32(bytes, 20);
        return width == 64 && (height == 64 || height == 32);
    }

    private static int int32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8 | bytes[offset + 3] & 0xff;
    }

    interface Transport {
        String getProfile(String url) throws IOException;

        byte[] getTexture(String url, int maxBytes) throws IOException;
    }
}
