package com.ecl.server;

import com.ecl.util.Messages;

/** 公开服务器的玩法分类。 */
public enum ServerCategory {
    ALL("all", "server.category.all"),
    SURVIVAL("survival", "server.category.survival"),
    SMP("smp", "server.category.smp"),
    PVP("pvp", "server.category.pvp"),
    TECH("tech", "server.category.tech"),
    ENTERTAINMENT("entertainment", "server.category.entertainment");

    private final String id;
    private final String labelKey;

    ServerCategory(String id, String labelKey) {
        this.id = id;
        this.labelKey = labelKey;
    }

    public String id() {
        return id;
    }

    public String label() {
        return Messages.get(labelKey);
    }

    /** 依据分类 id 解析枚举，未知值回退到 {@link #ALL}。 */
    public static ServerCategory fromId(String id) {
        if (id == null) {
            return ALL;
        }
        for (ServerCategory category : values()) {
            if (category.id.equalsIgnoreCase(id.trim())) {
                return category;
            }
        }
        return ALL;
    }
}
