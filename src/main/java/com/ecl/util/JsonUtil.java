package com.ecl.util;

import com.google.gson.JsonObject;

public final class JsonUtil {
    private JsonUtil() {
    }

    public static String getString(JsonObject object, String key) {
        return getString(object, key, null);
    }

    public static String getString(JsonObject object, String key, String defaultValue) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return object.get(key).getAsString();
    }

    public static int getInt(JsonObject object, String key, int defaultValue) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }
}
