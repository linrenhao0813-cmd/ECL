package com.ecl.auth;

import com.ecl.util.HttpUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

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

    @Test
    void rejectsOfflineAccountBeforeCallingOfficialApi(@TempDir Path directory) throws Exception {
        Path skin = directory.resolve("skin.png");
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", skin.toFile());
        MinecraftSkinService service = new MinecraftSkinService((token, boundary, body) -> {
            throw new AssertionError("transport must not be called for an offline account");
        });

        IOException error = assertThrows(IOException.class, () -> service.upload(
                new OfflineAuth("Steve"), skin, MinecraftSkinService.Variant.CLASSIC));

        assertTrue(error.getMessage().contains("Microsoft"));
    }

    @Test
    void rejectsOversizedDimensionsBeforePixelDecode(@TempDir Path directory) throws Exception {
        Path bomb = directory.resolve("huge.png");
        Files.write(bomb, pngHeader(100_000, 100_000));

        IOException error = assertThrows(IOException.class,
                () -> new MinecraftSkinService().inspect(bomb));

        assertTrue(error.getMessage().contains("64×64"));
    }

    @Test
    void rejectsEmptyAndOversizedFiles(@TempDir Path directory) throws Exception {
        Path empty = directory.resolve("empty.png");
        Files.createFile(empty);
        Path oversized = directory.resolve("oversized.png");
        Files.write(oversized, new byte[1024 * 1024 + 1]);
        MinecraftSkinService service = new MinecraftSkinService();

        assertThrows(IOException.class, () -> service.inspect(empty));
        assertThrows(IOException.class, () -> service.inspect(oversized));
    }

    @Test
    void reportsUnauthorizedAndServerErrors(@TempDir Path directory) throws Exception {
        Path skin = directory.resolve("skin.png");
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", skin.toFile());
        MinecraftSkinService unauthorized = new MinecraftSkinService((token, boundary, body) ->
                new HttpUtil.Response(401, "", MinecraftSkinService.SKIN_URL, Map.of()));
        MinecraftSkinService failed = new MinecraftSkinService((token, boundary, body) ->
                new HttpUtil.Response(500, "", MinecraftSkinService.SKIN_URL, Map.of()));

        assertThrows(IOException.class, () -> unauthorized.upload(
                "token", skin, MinecraftSkinService.Variant.CLASSIC));
        IOException serverError = assertThrows(IOException.class, () -> failed.upload(
                "token", skin, MinecraftSkinService.Variant.CLASSIC));
        assertTrue(serverError.getMessage().contains("500"));
    }

    private static byte[] pngHeader(int width, int height) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
            output.writeInt(13);
            byte[] type = "IHDR".getBytes(StandardCharsets.US_ASCII);
            output.write(type);
            ByteArrayOutputStream dataBytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(dataBytes)) {
                data.writeInt(width);
                data.writeInt(height);
                data.write(new byte[]{8, 6, 0, 0, 0});
            }
            byte[] data = dataBytes.toByteArray();
            output.write(data);
            CRC32 crc = new CRC32();
            crc.update(type);
            crc.update(data);
            output.writeInt((int) crc.getValue());
        }
        return bytes.toByteArray();
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
