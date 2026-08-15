package com.ecl.server;

/** 公开服务器的玩法分类。 */
public enum ServerCategory {
    ALL("all", "全部"),
    SURVIVAL("survival", "生存"),
    SMP("smp", "SMP"),
    PVP("pvp", "PVP"),
    TECH("tech", "生电"),
    ENTERTAINMENT("entertainment", "娱乐");

    private final String id;
    private final String label;

    ServerCategory(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
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
