package com.ecl.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinecraftProfileSkinServiceTest {
    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void loadsSkinFromOfficialTextureHost() throws IOException {
        byte[] skin = png();
        FakeTransport transport = new FakeTransport(
                profile("http://textures.minecraft.net/texture/" + HASH), skin);
        MinecraftProfileSkinService service = new MinecraftProfileSkinService(transport);

        assertArrayEquals(skin, service.load("069a79f4-44e9-4726-a5be-fca90e38aaf5").orElseThrow());
        assertEquals("https://sessionserver.mojang.com/session/minecraft/profile/" + UUID, transport.profileUrl);
        assertEquals("https://textures.minecraft.net/texture/" + HASH, transport.textureUrl);
        assertEquals(1024 * 1024, transport.maxBytes);
    }

    @Test
    void rejectsTextureUrlsOutsideOfficialHost() throws IOException {
        FakeTransport transport = new FakeTransport(
                profile("https://textures.minecraft.net.evil.example/texture/" + HASH), png());
        assertFalse(new MinecraftProfileSkinService(transport).load(UUID).isPresent());
        assertNull(transport.textureUrl);
    }

    @Test
    void returnsEmptyForProfilesWithoutSkin() throws IOException {
        FakeTransport transport = new FakeTransport("{\"properties\":[]}", png());
        assertFalse(new MinecraftProfileSkinService(transport).load(UUID).isPresent());
        assertNull(transport.textureUrl);
    }

    @Test
    void rejectsMalformedProfileAndInvalidSkin() throws IOException {
        FakeTransport malformed = new FakeTransport("not-json", png());
        assertThrows(IOException.class, () -> new MinecraftProfileSkinService(malformed).load(UUID));

        FakeTransport invalidSkin = new FakeTransport(profile("https://textures.minecraft.net/texture/" + HASH),
                new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> new MinecraftProfileSkinService(invalidSkin).load(UUID));
    }

    private static String profile(String textureUrl) {
        JsonObject skin = new JsonObject();
        skin.addProperty("url", textureUrl);
        JsonObject textures = new JsonObject();
        textures.add("SKIN", skin);
        JsonObject payload = new JsonObject();
        payload.add("textures", textures);
        JsonObject property = new JsonObject();
        property.addProperty("name", "textures");
        property.addProperty("value", Base64.getEncoder().encodeToString(
                payload.toString().getBytes(StandardCharsets.UTF_8)));
        JsonArray properties = new JsonArray();
        properties.add(property);
        JsonObject profile = new JsonObject();
        profile.add("properties", properties);
        return profile.toString();
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        return bytes.toByteArray();
    }

    private static final class FakeTransport implements MinecraftProfileSkinService.Transport {
        private final String profile;
        private final byte[] png;
        private String profileUrl;
        private String textureUrl;
        private int maxBytes;

        private FakeTransport(String profile, byte[] png) {
            this.profile = profile;
            this.png = png;
        }

        @Override
        public String getProfile(String url) {
            profileUrl = url;
            return profile;
        }

        @Override
        public byte[] getTexture(String url, int limit) {
            textureUrl = url;
            maxBytes = limit;
            return png;
        }
    }
}
