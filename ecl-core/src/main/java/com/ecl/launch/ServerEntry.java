package com.ecl.launch;

/** A named, persistent Minecraft direct-connect destination. */
public record ServerEntry(String name, String address, int port) {
    public ServerEntry {
        name = name == null ? "" : name.trim();
        address = address == null ? "" : address.trim();
        if (name.isBlank() || address.isBlank()) {
            throw new IllegalArgumentException("服务器名称和地址不能为空");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("服务器端口必须在 1 到 65535 之间");
        }
    }

    public String launchAddress() {
        return address.indexOf(':') >= 0 && !address.startsWith("[")
                ? "[" + address + "]:" + port : address + ":" + port;
    }
}
