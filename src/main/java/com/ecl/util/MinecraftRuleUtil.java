package com.ecl.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public final class MinecraftRuleUtil {
    private MinecraftRuleUtil() {
    }

    public static boolean checkRules(JsonArray rules) {
        boolean allowed = rules == null || rules.isEmpty();
        if (rules == null) {
            return allowed;
        }

        for (JsonElement ruleEl : rules) {
            JsonObject rule = ruleEl.getAsJsonObject();
            String action = rule.get("action").getAsString();
            boolean osMatch = true;

            if (rule.has("os")) {
                osMatch = evaluateOsCondition(rule.getAsJsonObject("os"));
            }

            if ("allow".equals(action) && osMatch) {
                allowed = true;
            }
            if ("disallow".equals(action) && osMatch) {
                allowed = false;
            }
        }
        return allowed;
    }

    public static boolean evaluateOsCondition(JsonObject osCondition) {
        boolean match = true;

        if (osCondition.has("name")) {
            match &= osNameMatches(osCondition.get("name").getAsString());
        }
        if (osCondition.has("arch")) {
            match &= osArchMatches(osCondition.get("arch").getAsString());
        }
        if (osCondition.has("version")) {
            match &= osVersionMatches(osCondition.get("version").getAsString());
        }

        return match;
    }

    public static String[] nativeKeys(String nativeClassifier) {
        String osPart = nativeClassifier.split("-")[0];
        List<String> keys = new ArrayList<>();
        addKey(keys, nativeClassifier);
        addKey(keys, "natives-" + nativeClassifier);

        if (nativeClassifier.startsWith("osx-")) {
            String archPart = nativeClassifier.substring("osx-".length());
            addKey(keys, "natives-macos-" + archPart);
            addKey(keys, "natives-osx-" + archPart);
            addKey(keys, "natives-macos");
            addKey(keys, "natives-osx");
            addKey(keys, "macos-" + archPart);
        } else {
            addKey(keys, "natives-" + osPart);
        }

        addKey(keys, osPart);
        return keys.toArray(String[]::new);
    }

    private static void addKey(List<String> keys, String key) {
        if (!keys.contains(key)) {
            keys.add(key);
        }
    }

    private static boolean osNameMatches(String expectedName) {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if ("windows".equals(expectedName)) {
            return osName.contains("win");
        }
        if ("osx".equals(expectedName)) {
            return osName.contains("mac");
        }
        if ("linux".equals(expectedName)) {
            return osName.contains("linux");
        }
        return false;
    }

    private static boolean osArchMatches(String expectedArch) {
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        boolean arm64 = osArch.contains("aarch64") || osArch.contains("arm64");
        if ("x86".equals(expectedArch)) {
            return osArch.contains("86") && !osArch.contains("64") && !osArch.contains("amd64");
        }
        if ("x86_64".equals(expectedArch)) {
            return !arm64 && (osArch.contains("x86_64") || osArch.contains("amd64") || osArch.contains("64"));
        }
        if ("arm64".equals(expectedArch) || "aarch64".equals(expectedArch)) {
            return arm64;
        }
        return osArch.equals(expectedArch);
    }

    private static boolean osVersionMatches(String versionPattern) {
        try {
            return System.getProperty("os.version", "").matches(versionPattern);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
