package com.ecl.game.companion;

/** Wire-level lifecycle states shared by ECL and the Minecraft companion mod. */
public enum CompanionTaskStatus {
    QUEUED,
    WAITING_FOR_PLAYER,
    WAITING_FOR_COMPANION,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    REJECTED,
    CANCELLED;

    public static CompanionTaskStatus parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("任务状态为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("未知任务状态: " + value, error);
        }
    }

    public boolean terminal() {
        return this == SUCCEEDED || this == PARTIAL || this == FAILED
                || this == REJECTED || this == CANCELLED;
    }
}
