package com.ecl.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed, merged argument model of a Minecraft version. Modern versions declare structured
 * {@code jvm} and {@code game} argument lists with per-argument rules; older versions fall back to
 * the flat {@code minecraftArguments} string. Both forms are preserved so the launch pipeline can
 * pick whichever the metadata uses.
 */
public final class VersionArguments {

    private final List<ArgumentToken> jvm;
    private final List<ArgumentToken> game;
    private final List<String> legacyJvmArguments;
    private final String legacyMinecraftArguments;

    /** Shortcut for an empty argument set. */
    public static VersionArguments empty() {
        return new VersionArguments(List.of(), List.of(), List.of(), null);
    }

    private VersionArguments(List<ArgumentToken> jvm, List<ArgumentToken> game,
                             List<String> legacyJvmArguments, String legacyMinecraftArguments) {
        this.jvm = List.copyOf(jvm);
        this.game = List.copyOf(game);
        this.legacyJvmArguments = List.copyOf(legacyJvmArguments);
        this.legacyMinecraftArguments = legacyMinecraftArguments;
    }

    static VersionArguments parse(JsonObject version) {
        List<ArgumentToken> jvm = List.of();
        List<ArgumentToken> game = List.of();
        if (version.has("arguments") && version.get("arguments").isJsonObject()) {
            JsonObject arguments = version.getAsJsonObject("arguments");
            jvm = parseTokens(arguments.get("jvm"));
            game = parseTokens(arguments.get("game"));
        }

        List<String> legacyJvm = List.of();
        if (version.has("jvmArguments") && version.get("jvmArguments").isJsonArray()) {
            legacyJvm = parseStringArray(version.getAsJsonArray("jvmArguments"));
        }

        String legacyGame = null;
        if (version.has("minecraftArguments") && version.get("minecraftArguments").isJsonPrimitive()) {
            legacyGame = version.get("minecraftArguments").getAsString();
        }
        return new VersionArguments(jvm, game, legacyJvm, legacyGame);
    }

    private static List<ArgumentToken> parseTokens(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<ArgumentToken> tokens = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) {
                tokens.add(new ArgumentToken.Literal(item.getAsString()));
            } else if (item.isJsonObject()) {
                JsonObject tokenObject = item.getAsJsonObject();
                if (!tokenObject.has("value")) {
                    continue;
                }
                JsonElement value = tokenObject.get("value");
                List<String> values;
                if (value.isJsonArray()) {
                    values = parseStringArray(value.getAsJsonArray());
                } else if (value.isJsonPrimitive()) {
                    values = List.of(value.getAsString());
                } else {
                    continue;
                }
                JsonArray rules = tokenObject.has("rules") && tokenObject.get("rules").isJsonArray()
                        ? tokenObject.getAsJsonArray("rules") : null;
                tokens.add(new ArgumentToken.Conditional(rules, values));
            }
        }
        return tokens;
    }

    private static List<String> parseStringArray(JsonArray array) {
        List<String> strings = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element != null && element.isJsonPrimitive()) {
                strings.add(element.getAsString());
            }
        }
        return List.copyOf(strings);
    }

    public List<ArgumentToken> jvm() {
        return jvm;
    }

    public List<ArgumentToken> game() {
        return game;
    }

    public List<String> legacyJvmArguments() {
        return legacyJvmArguments;
    }

    public String legacyMinecraftArguments() {
        return legacyMinecraftArguments;
    }

    /** Whether the version uses the modern structured {@code arguments} form. */
    public boolean usesStructuredArguments() {
        return !jvm.isEmpty() || !game.isEmpty();
    }

    /** Whether the version declares anything at all to append. */
    public boolean isEmpty() {
        return jvm.isEmpty() && game.isEmpty() && legacyJvmArguments.isEmpty()
                && (legacyMinecraftArguments == null || legacyMinecraftArguments.isBlank());
    }
}