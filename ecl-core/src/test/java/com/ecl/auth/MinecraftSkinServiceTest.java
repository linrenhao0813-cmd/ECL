package com.ecl.auth;

import com.ecl.util.HttpUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftSkinServiceTest {
    @Test
    void validatesAndUploadsPngWithoutCorruptingBytes(@TempDir Path directory) throws Exception {
        Path skin = directory.resolve("skin.png");
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", skin.toFile());
        byte[] png = Files.readAllBytes(skin);
        AtomicReference<byte[]> sent = new AtomicReference<>();
        MinecraftSkinService service = new MinecraftSkinService((token, boundary, body) -> {
            assertEquals("minecraft-token", token);
            assertTrue(new String(body, StandardCharsets.ISO_8859_1)
                    .contains("name=\"variant\"\r\n\r\nslim"));
            sent.set(body);
            return new HttpUtil.Response(200,
                    "{\"name\":\"Alex\",\"skins\":[{\"variant\":\"slim\",\"url\":\"https://textures.minecraft.net/texture/test\"}]}",
                    MinecraftSkinService.SKIN_URL, Map.of());
        });

        MinecraftSkinService.UploadResult result = service.upload(
                "minecraft-token", skin, MinecraftSkinService.Variant.SLIM);

        assertEquals("Alex", result.profileName());
        assertEquals("slim", result.variant());
        byte[] body = sent.get();
        int pngOffset = indexOf(body, png);
        assertTrue(pngOffset > 0);
        assertArrayEquals(png, java.util.Arrays.copyOfRange(body, pngOffset, pngOffset + png.length));
    }

    @Test
    void rejectsInvalidDimensions(@TempDir Path directory) throws Exception {
        Path skin = directory.resolve("wide.png");
        ImageIO.write(new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB), "png", skin.toFile());

        IOException error = assertThrows(IOException.class,
                () -> new MinecraftSkinService().inspect(skin));

        assertTrue(error.getMessage().contains("64×64"));
    }

    @Test
    void explainsRestrictedApiAccess(@TempDir Path directory) throws Exception {
        Path skin = directory.resolve("skin.png");
        ImageIO.write(new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB), "png", skin.toFile());
        MinecraftSkinService service = new MinecraftSkinService((token, boundary, body) ->
                new HttpUtil.Response(403, "", MinecraftSkinService.SKIN_URL, Map.of()));

        IOException error = assertThrows(IOException.class,
                () -> service.upload("token", skin, MinecraftSkinService.Variant.CLASSIC));

        assertTrue(error.getMessage().contains("API 权限"));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
