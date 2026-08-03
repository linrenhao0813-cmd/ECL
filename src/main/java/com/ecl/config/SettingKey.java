package com.ecl.config;

import java.util.Objects;

/**
 * A type-safe configuration key with a default value.
 * <p>
 * Usage:
 * <pre>{@code
 *   public static final SettingKey<String> JAVA_PATH = new SettingKey<>("javaPath", String.class, "");
 *   String path = settings.get(JAVA_PATH);
 *   settings.set(JAVA_PATH, "/usr/bin/java");
 * }</pre>
 *
 * @param <T> the value type (String, Integer, Long, Boolean)
 */
public final class SettingKey<T> {

    private final String key;
    private final Class<T> type;
    private final T defaultValue;

    public SettingKey(String key, Class<T> type, T defaultValue) {
        this.key = Objects.requireNonNull(key, "key");
        this.type = Objects.requireNonNull(type, "type");
        this.defaultValue = defaultValue;
        // validate default value type
        if (defaultValue != null && !type.isInstance(defaultValue)) {
            throw new IllegalArgumentException(
                    "defaultValue type " + defaultValue.getClass() + " does not match declared type " + type);
        }
    }

    public String key() {
        return key;
    }

    public Class<T> type() {
        return type;
    }

    public T defaultValue() {
        return defaultValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SettingKey<?> that)) return false;
        return key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "SettingKey{" + key + "}";
    }
}
