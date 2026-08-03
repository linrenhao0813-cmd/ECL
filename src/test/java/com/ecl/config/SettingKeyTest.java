package com.ecl.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingKeyTest {

    @Test
    void storesKeyTypeAndDefault() {
        SettingKey<String> key = new SettingKey<>("testKey", String.class, "default");
        assertEquals("testKey", key.key());
        assertEquals(String.class, key.type());
        assertEquals("default", key.defaultValue());
    }

    @Test
    void acceptsNullDefault() {
        SettingKey<String> key = new SettingKey<>("nullable", String.class, null);
        assertNull(key.defaultValue());
    }

    @Test
    void rejectsNullKey() {
        assertThrows(NullPointerException.class,
                () -> new SettingKey<>(null, String.class, ""));
    }

    @Test
    void rejectsNullType() {
        assertThrows(NullPointerException.class,
                () -> new SettingKey<>("key", null, ""));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsMismatchedDefaultType() {
        assertThrows(IllegalArgumentException.class,
                () -> new SettingKey("key", Integer.class, "notAnInteger"));
    }

    @Test
    void equalityIsBasedOnKeyName() {
        SettingKey<String> a = new SettingKey<>("same", String.class, "x");
        SettingKey<String> b = new SettingKey<>("same", String.class, "y");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentKeysAreNotEqual() {
        SettingKey<String> a = new SettingKey<>("a", String.class, "");
        SettingKey<String> b = new SettingKey<>("b", String.class, "");
        assertNotEquals(a, b);
    }

    @Test
    void integerKey() {
        SettingKey<Integer> key = new SettingKey<>("intKey", Integer.class, 42);
        assertEquals(Integer.valueOf(42), key.defaultValue());
        assertEquals(Integer.class, key.type());
    }

    @Test
    void longKey() {
        SettingKey<Long> key = new SettingKey<>("longKey", Long.class, 100L);
        assertEquals(Long.valueOf(100L), key.defaultValue());
    }

    @Test
    void booleanKey() {
        SettingKey<Boolean> key = new SettingKey<>("boolKey", Boolean.class, true);
        assertEquals(true, key.defaultValue());
    }
}
