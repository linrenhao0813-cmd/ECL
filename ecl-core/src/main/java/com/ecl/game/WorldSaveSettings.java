package com.ecl.game;

/** Settings that can be changed from the launcher for a single-player world. */
public record WorldSaveSettings(Difficulty difficulty, GameMode gameMode,
                                boolean allowCommands, boolean openToLan, int lanPort) {
    public static final int DEFAULT_LAN_PORT = 25565;

    public WorldSaveSettings(Difficulty difficulty, GameMode gameMode,
                             boolean allowCommands, boolean openToLan) {
        this(difficulty, gameMode, allowCommands, openToLan, DEFAULT_LAN_PORT);
    }

    public WorldSaveSettings {
        difficulty = difficulty == null ? Difficulty.NORMAL : difficulty;
        gameMode = gameMode == null ? GameMode.SURVIVAL : gameMode;
        if (lanPort < 1 || lanPort > 65535) {
            throw new IllegalArgumentException("LAN port must be between 1 and 65535");
        }
    }

    public static WorldSaveSettings defaults() {
        return new WorldSaveSettings(Difficulty.NORMAL, GameMode.SURVIVAL, false, false);
    }

    public enum Difficulty {
        PEACEFUL(0), EASY(1), NORMAL(2), HARD(3);

        private final int id;

        Difficulty(int id) { this.id = id; }

        public int id() { return id; }

        public static Difficulty fromId(int id) {
            for (Difficulty value : values()) {
                if (value.id == id) return value;
            }
            return NORMAL;
        }
    }

    public enum GameMode {
        SURVIVAL(0), CREATIVE(1), ADVENTURE(2), SPECTATOR(3);

        private final int id;

        GameMode(int id) { this.id = id; }

        public int id() { return id; }

        public static GameMode fromId(int id) {
            for (GameMode value : values()) {
                if (value.id == id) return value;
            }
            return SURVIVAL;
        }
    }
}
