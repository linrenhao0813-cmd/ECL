package com.ecl.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BoundedCacheTest {
    @Test
    void evictsTheLeastRecentlyUsedEntryAtTheCapacityLimit() {
        BoundedCache<String, String> cache = new BoundedCache<>(2);
        cache.put("first", "1");
        cache.put("second", "2");

        assertEquals("1", cache.get("first"));
        cache.put("third", "3");

        assertNull(cache.get("second"));
        assertEquals("1", cache.get("first"));
        assertEquals("3", cache.get("third"));
        assertEquals(2, cache.size());
    }

    @Test
    void expiresEntriesUsingTheConfiguredTimeToLive() {
        AtomicLong now = new AtomicLong();
        BoundedCache<String, String> cache = new BoundedCache<>(2, Duration.ofSeconds(5), now::get);
        cache.put("key", "value");

        now.set(Duration.ofSeconds(4).toNanos());
        assertEquals("value", cache.get("key"));

        now.set(Duration.ofSeconds(5).toNanos());
        assertNull(cache.get("key"));
        assertEquals(0, cache.size());
    }
}
