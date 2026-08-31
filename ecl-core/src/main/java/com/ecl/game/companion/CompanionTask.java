package com.ecl.game.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A validated, offline-safe task submitted to one world save. */
public record CompanionTask(int schemaVersion, UUID taskId, String instruction, String createdAt,
                            TargetPolicy targetPolicy, UUID targetPlayerUuid, boolean autoSummon,
                            String source) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_INSTRUCTION_LENGTH = 256;
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private static final String COUNT = "[0-9一二两三四五六七八九十]+";
    private static final Pattern MINE = Pattern.compile("^(?:帮我)?挖(?:矿|" + COUNT
            + "(?:格|块|个矿块?))$");
    private static final Pattern CHOP = Pattern.compile("^(?:帮我)?砍(?:树|" + COUNT
            + "(?:棵|个)?树)$");
    private static final Pattern SHOVEL = Pattern.compile("^(?:帮我)?(?:做|制作)(?:一|1|个|一个)?"
            + "(?:把)?木(?:锹|铲)$");
    private static final Pattern IRON = Pattern.compile("^(?:帮我)?(?:做|制作)(?:" + COUNT
            + ")?(?:个)?铁锭$");
    private static final Set<String> CONTROL_COMMANDS = Set.of(
            "跟随", "跟着我", "开始跟随", "停止跟随", "别跟着", "不要跟随", "停下", "停止", "取消",
            "follow", "stay", "stop");

    public CompanionTask {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("不兼容的 Companion 任务协议: " + schemaVersion);
        }
        if (taskId == null) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }
        instruction = normalizeInstruction(instruction);
        if (!isSupportedInstruction(instruction)) {
            throw new IllegalArgumentException("不支持的 Companion 指令");
        }
        createdAt = requireText(createdAt, "createdAt", 80);
        try {
            Instant.parse(createdAt);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("createdAt 不是有效时间", error);
        }
        targetPolicy = targetPolicy == null ? TargetPolicy.BOUND_PLAYER : targetPolicy;
        if (targetPolicy != TargetPolicy.BOUND_PLAYER) {
            throw new IllegalArgumentException("不支持的目标策略");
        }
        source = requireText(source, "source", 32);
    }

    public static CompanionTask create(String instruction, UUID targetPlayerUuid, boolean autoSummon) {
        return new CompanionTask(CURRENT_SCHEMA_VERSION, UUID.randomUUID(), instruction,
                Instant.now().toString(), TargetPolicy.BOUND_PLAYER, targetPlayerUuid,
                autoSummon, "ECL");
    }

    public int requestedActions() {
        String command = instruction.toLowerCase(Locale.ROOT);
        if (SHOVEL.matcher(command).matches()) {
            return 1;
        }
        if (MINE.matcher(command).matches()) {
            return parseCount(command, 3);
        }
        if (CHOP.matcher(command).matches()) {
            return parseCount(command, 6);
        }
        return parseCount(command, 1);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", schemaVersion);
        json.addProperty("taskId", taskId.toString());
        json.addProperty("instruction", instruction);
        json.addProperty("createdAt", createdAt);
        json.addProperty("targetPolicy", targetPolicy.name());
        if (targetPlayerUuid != null) {
            json.addProperty("targetPlayerUuid", targetPlayerUuid.toString());
        }
        json.addProperty("autoSummon", autoSummon);
        json.addProperty("source", source);
        return json;
    }

    public static CompanionTask fromJson(JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException("任务 JSON 为空");
        }
        int schema = required(json, "schemaVersion").getAsInt();
        UUID id = parseUuid(required(json, "taskId").getAsString(), "taskId");
        UUID player = optionalUuid(json, "targetPlayerUuid");
        return new CompanionTask(schema, id,
                required(json, "instruction").getAsString(),
                required(json, "createdAt").getAsString(),
                TargetPolicy.parse(optionalString(json, "targetPolicy", "BOUND_PLAYER")),
                player,
                optionalBoolean(json, "autoSummon", true),
                optionalString(json, "source", "ECL"));
    }

    public static boolean isSupportedInstruction(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_INSTRUCTION_LENGTH
                || value.indexOf('\0') >= 0 || value.startsWith("/") || value.contains("\\")) {
            return false;
        }
        String command = value.trim().toLowerCase(Locale.ROOT);
        return CONTROL_COMMANDS.contains(command) || MINE.matcher(command).matches()
                || CHOP.matcher(command).matches()
                || SHOVEL.matcher(command).matches() || IRON.matcher(command).matches()
                || command.matches("^mine(?:\\s+\\d+)?(?:\\s+blocks?)?$")
                || command.matches("^chop(?:\\s+\\d+)?(?:\\s+(?:logs?|trees?))?$")
                || command.matches("^(?:craft|make)(?: a)? wooden shovel$")
                || command.matches("^(?:craft|make)(?: \\d+)?(?: iron ingots?| an iron ingot)$");
    }

    public enum TargetPolicy {
        BOUND_PLAYER;

        static TargetPolicy parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("未知目标策略: " + value, error);
            }
        }
    }

    private static String normalizeInstruction(String value) {
        return requireText(value, "instruction", MAX_INSTRUCTION_LENGTH).trim();
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("无效的 " + field);
        }
        return value;
    }

    private static JsonElement required(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("任务缺少字段: " + name);
        }
        return value;
    }

    private static String optionalString(JsonObject json, String name, String fallback) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static boolean optionalBoolean(JsonObject json, String name, boolean fallback) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }

    private static UUID optionalUuid(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() || value.getAsString().isBlank()
                ? null : parseUuid(value.getAsString(), name);
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(field + " 不是有效 UUID", error);
        }
    }

    private static int parseCount(String command, int fallback) {
        Matcher matcher = NUMBER.matcher(command);
        if (matcher.find()) {
            try {
                return Math.max(1, Math.min(64, Integer.parseInt(matcher.group(1))));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        String[] numerals = {"十", "九", "八", "七", "六", "五", "四", "三", "二", "两", "一"};
        int[] values = {10, 9, 8, 7, 6, 5, 4, 3, 2, 2, 1};
        for (int i = 0; i < numerals.length; i++) {
            if (command.contains(numerals[i])) {
                return values[i];
            }
        }
        return fallback;
    }
}
