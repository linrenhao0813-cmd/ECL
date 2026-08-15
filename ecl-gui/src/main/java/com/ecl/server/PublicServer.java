package com.ecl.server;

import java.util.List;

/**
 * 一条公开 Minecraft 服务器的静态描述，配合 {@link ServerCatalog} 从内置 JSON 目录加载。
 *
 * @param name        服务器名称
 * @param category    分类 id，见 {@link ServerCategory#id()}
 * @param ip          服务器主机名，例如 mc.hypixel.net
 * @param port        端口，默认 25565
 * @param version     支持的客户端版本区间描述，例如 "1.8 – 1.21"
 * @param description 中文简介
 * @param region      所在地区，例如 国际 / 北美 / 欧洲
 * @param website     官网地址，可为空
 * @param tags        标签列表
 * @param iconText    用于图标占位的短字符
 */
public record PublicServer(
        String name,
        String category,
        String ip,
        int port,
        String version,
        String description,
        String region,
        String website,
        List<String> tags,
        String iconText
) {
    public PublicServer {
        tags = tags == null ? List.of() : List.copyOf(tags);
        ip = ip == null ? "" : ip.trim();
        description = description == null ? "" : description;
        region = region == null ? "" : region;
        website = website == null ? "" : website;
        iconText = iconText == null || iconText.isBlank() ? "◈" : iconText;
    }

    /** 完整连接地址：默认端口省略端口号。 */
    public String address() {
        if (ip.isEmpty()) {
            return "";
        }
        return port == 25565 ? ip : ip + ":" + port;
    }

    public ServerCategory categoryEnum() {
        return ServerCategory.fromId(category);
    }
}
