package com.ecl.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class RuleEvaluator {
    private RuleEvaluator() {
    }

    public static boolean isAllowed(JsonArray rules) {
        return isAllowed(rules, Map.of());
    }

    public static boolean isAllowed(JsonArray rules, Map<String, Boolean> features) {
        if (rules == null) return true;
        boolean allowed = rules.isEmpty();

        for (JsonElement element : rules) {
            if (!element.isJsonObject()) continue;
            JsonObject rule = element.getAsJsonObject();
            String action = JsonUtil.getString(rule, "action", "");
            JsonObject os = rule.has("os") && rule.get("os").isJsonObject()
                    ? rule.getAsJsonObject("os") : null;
            JsonObject requiredFeatures = rule.has("features") && rule.get("features").isJsonObject()
                    ? rule.getAsJsonObject("features") : null;
            boolean matches = matchesOs(os) && matchesFeatures(requiredFeatures, features);
            if (matches && "allow".equals(action)) allowed = true;
            if (matches && "disallow".equals(action)) allowed = false;
        }
        return allowed;
    }

    private static boolean matchesOs(JsonObject os) {
        if (os == null) return true;

        String currentOs = PlatformUtil.current().minecraftName();
        if (os.has("name") && !JsonUtil.getString(os, "name", "").equals(currentOs)) {
            return false;
        }
        if (os.has("arch") && !matchesArchitecture(JsonUtil.getString(os, "arch", ""))) {
            return false;
        }
        return !os.has("version") || matchesPattern(
                JsonUtil.getString(os, "version", ""), System.getProperty("os.version", ""));
    }

    private static boolean matchesArchitecture(String expected) {
        return matchesArchitecture(expected, System.getProperty("os.arch", ""));
    }

    static boolean matchesArchitecture(String expected, String actualArchitecture) {
        String actual = actualArchitecture == null ? "" : actualArchitecture.toLowerCase(Locale.ROOT);
        if ("x86".equalsIgnoreCase(expected)) {
            return actual.matches("x86|i[3-6]86");
        }
        if ("x86_64".equalsIgnoreCase(expected) || "amd64".equalsIgnoreCase(expected)) {
            return actual.equals("x86_64") || actual.equals("amd64") || actual.equals("x64");
        }
        return matchesPattern(expected, actual);
    }

    private static boolean matchesPattern(String pattern, String value) {
        try {
            return Pattern.compile(pattern).matcher(value).find();
        } catch (PatternSyntaxException ignored) {
            return pattern.equalsIgnoreCase(value);
        }
    }

    private static boolean matchesFeatures(JsonObject required, Map<String, Boolean> features) {
        if (required == null) return true;
        Map<String, Boolean> actualFeatures = features == null ? Map.of() : features;
        for (String key : required.keySet()) {
            if (!required.get(key).isJsonPrimitive()) {
                return false;
            }
            boolean expected = required.get(key).getAsBoolean();
            if (actualFeatures.getOrDefault(key, false) != expected) {
                return false;
            }
        }
        return true;
    }
}
