package com.ecl.util;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JsonUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(JsonUtil.class);

    private JsonUtil() {
    }

    public static String getString(JsonObject object, String key) {
        return getString(object, key, null);
    }

    public static String getString(JsonObject object, String key, String defaultValue) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException unexpected) {
            LOGGER.debug("JSON field '{}' is not a string; falling back to default", key, unexpected);
            return defaultValue;
        }
    }

    public static int getInt(JsonObject object, String key, int defaultValue) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException unexpected) {
            LOGGER.debug("JSON field '{}' is not an integer; falling back to default", key, unexpected);
            return defaultValue;
        }
    }

    public static long getLong(JsonObject object, String key, long defaultValue) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException unexpected) {
            LOGGER.debug("JSON field '{}' is not a long; falling back to default", key, unexpected);
            return defaultValue;
        }
    }
}
