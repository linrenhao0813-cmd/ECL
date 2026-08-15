package com.ecl.auth;

import com.ecl.util.HttpUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
        if (size <= 0) {
            throw new IOException("皮肤文件不能为空");
        }
        if (size > MAX_SKIN_BYTES) {
            throw new IOException("皮肤文件不得超过 1 MB");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0 || bytes.length > MAX_SKIN_BYTES) {
            throw new IOException("皮肤文件在读取过程中发生变化，请重新选择");
        }
        if (bytes.length < 8 || bytes[0] != (byte) 0x89 || bytes[1] != 0x50
                || bytes[2] != 0x4E || bytes[3] != 0x47) {
            throw new IOException("皮肤必须是 PNG 图片");
        }
        int[] dimensions = inspectDimensions(bytes);
        return new SkinImage(file, dimensions[0], dimensions[1], bytes.length, bytes);
    }

    /** Upload a skin only for a Microsoft-authenticated Minecraft profile. */
    public UploadResult upload(AuthProvider auth, Path file, Variant variant) throws IOException {
        if (auth == null || auth.getType() != AuthType.MICROSOFT) {
            throw new IOException("当前账号不是 Microsoft 正版账号，不能使用官方皮肤上传接口");
        }
        return upload(auth.getAccessToken(), file, variant);
    }

    UploadResult upload(String accessToken, Path file, Variant variant) throws IOException {
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

    private static int[] inspectDimensions(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException("无法读取 PNG 皮肤文件");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("皮肤必须是有效的 PNG 图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width != 64 || height != 64 && height != 32) {
                    throw new IOException("皮肤尺寸必须为 64×64，旧版皮肤也支持 64×32");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw new IOException("皮肤 PNG 数据不完整或已损坏");
                }
                return new int[]{width, height};
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException malformed) {
            throw new IOException("皮肤 PNG 数据无效", malformed);
        }
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
