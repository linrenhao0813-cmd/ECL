package com.ecl.auth;

import com.ecl.util.HttpUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** Validates and uploads Minecraft: Java Edition skins for an authenticated profile. */
public final class MinecraftSkinService {
    static final String SKIN_URL = "https://api.minecraftservices.com/minecraft/profile/skins";
    private static final long MAX_SKIN_BYTES = 1024 * 1024;
    private final Transport transport;

    public MinecraftSkinService() {
        this((token, boundary, body) -> HttpUtil.postMultipart(SKIN_URL, boundary, body,
                Map.of("Authorization", "Bearer " + token)));
    }

    MinecraftSkinService(Transport transport) {
        this.transport = transport;
    }

    public SkinImage inspect(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("请选择有效的 PNG 皮肤文件");
        }
        long size = Files.size(file);
        if (size <= 0 || size > MAX_SKIN_BYTES) {
            throw new IOException("皮肤文件必须小于 1 MB");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 8 || bytes[0] != (byte) 0x89 || bytes[1] != 0x50
                || bytes[2] != 0x4E || bytes[3] != 0x47) {
            throw new IOException("皮肤必须是 PNG 图片");
        }
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null || !((image.getWidth() == 64 && image.getHeight() == 64)
                || (image.getWidth() == 64 && image.getHeight() == 32))) {
            throw new IOException("皮肤尺寸必须为 64×64，旧版皮肤也支持 64×32");
        }
        return new SkinImage(file, image.getWidth(), image.getHeight(), size, bytes);
    }

    public UploadResult upload(String accessToken, Path file, Variant variant) throws IOException {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IOException("Microsoft 登录已失效，请重新登录后上传皮肤");
        }
        SkinImage skin = inspect(file);
        Variant selectedVariant = variant == null ? Variant.CLASSIC : variant;
        String boundary = "----ECLSkin" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipartBody(boundary, selectedVariant.apiValue, skin.imageBytes());
        HttpUtil.Response response = transport.upload(accessToken, boundary, body);
        if (response.statusCode() == 401) {
            throw new IOException("Minecraft 登录已过期，请重新登录后重试");
        }
        if (response.statusCode() == 403) {
            throw new IOException("Minecraft 皮肤服务拒绝了请求，当前应用可能尚未获得游戏服务 API 权限");
        }
        if (!response.isSuccess()) {
            throw new IOException("皮肤上传失败：Minecraft 服务返回 HTTP " + response.statusCode());
        }
        return parseResult(response.body(), selectedVariant);
    }

    static byte[] multipartBody(String boundary, String variant, byte[] png) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(png.length + 512);
        write(output, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"variant\"\r\n\r\n"
                + variant + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n");
        output.write(png);
        write(output, "\r\n--" + boundary + "--\r\n");
        return output.toByteArray();
    }

    private static UploadResult parseResult(String json, Variant fallback) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String name = root.has("name") ? root.get("name").getAsString() : "";
            JsonArray skins = root.getAsJsonArray("skins");
            if (skins != null && !skins.isEmpty()) {
                JsonObject skin = skins.get(0).getAsJsonObject();
                String url = skin.has("url") ? skin.get("url").getAsString() : "";
                String variant = skin.has("variant") ? skin.get("variant").getAsString() : fallback.apiValue;
                return new UploadResult(name, variant, url);
            }
            return new UploadResult(name, fallback.apiValue, "");
        } catch (RuntimeException ignored) {
            return new UploadResult("", fallback.apiValue, "");
        }
    }

    private static void write(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    public enum Variant {
        CLASSIC("classic", "经典（宽手臂）"),
        SLIM("slim", "纤细（细手臂）");

        private final String apiValue;
        private final String displayName;

        Variant(String apiValue, String displayName) {
            this.apiValue = apiValue;
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public record SkinImage(Path path, int width, int height, long fileSize, byte[] imageBytes) {
        public SkinImage {
            imageBytes = imageBytes.clone();
        }

        @Override
        public byte[] imageBytes() {
            return imageBytes.clone();
        }
    }

    public record UploadResult(String profileName, String variant, String textureUrl) {
    }

    @FunctionalInterface
    interface Transport {
        HttpUtil.Response upload(String accessToken, String boundary, byte[] body) throws IOException;
    }
}
