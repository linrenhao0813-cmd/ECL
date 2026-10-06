package com.ecl.util;

import java.util.ArrayList;
import java.util.List;

public final class MinecraftRuleUtil {
    private MinecraftRuleUtil() {
    }

    public static String[] nativeKeys(String nativeClassifier) {
        String osPart = nativeClassifier.split("-")[0];
        List<String> keys = new ArrayList<>();
        addKey(keys, nativeClassifier);
        addKey(keys, "natives-" + nativeClassifier);
        addKey(keys, "natives-" + osPart);
        addKey(keys, osPart);
        return keys.toArray(String[]::new);
    }

    private static void addKey(List<String> keys, String key) {
        if (!keys.contains(key)) {
            keys.add(key);
        }
    }

}
