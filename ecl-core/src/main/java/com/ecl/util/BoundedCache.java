package com.ecl.util;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** A small synchronized LRU cache with an optional time-to-live. */
public final class BoundedCache<K, V> {
    private final int maxEntries;
    private final long ttlNanos;
    private final LongSupplier ticker;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);

    public BoundedCache(int maxEntries) {
        this(maxEntries, Duration.ZERO);
    }

    public BoundedCache(int maxEntries, Duration ttl) {
        this(maxEntries, ttl, System::nanoTime);
    }

    BoundedCache(int maxEntries, Duration ttl, LongSupplier ticker) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must not be negative");
        }
        this.maxEntries = maxEntries;
        this.ttlNanos = ttl.isZero() ? Long.MAX_VALUE : ttl.toNanos();
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    public synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (isExpired(entry, ticker.getAsLong())) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    public synchronized void put(K key, V value) {
        long now = ticker.getAsLong();
        removeExpired(now);
        entries.put(Objects.requireNonNull(key, "key"),
                new Entry<>(Objects.requireNonNull(value, "value"), now));
        trimToLimit();
    }

    /**
     * Store {@code value} only when no live entry exists.
     *
     * @return the existing value, or {@code null} when the supplied value was stored
     */
    public synchronized V putIfAbsent(K key, V value) {
        long now = ticker.getAsLong();
        removeExpired(now);
        Entry<V> existing = entries.get(key);
        if (existing != null) {
            return existing.value();
        }
        entries.put(Objects.requireNonNull(key, "key"),
                new Entry<>(Objects.requireNonNull(value, "value"), now));
        trimToLimit();
        return null;
    }

    public synchronized V computeIfAbsent(K key, Function<? super K, ? extends V> factory) {
        long now = ticker.getAsLong();
        removeExpired(now);
        Entry<V> existing = entries.get(key);
        if (existing != null) {
            return existing.value();
        }
        V value = Objects.requireNonNull(factory.apply(key), "computed value");
        entries.put(Objects.requireNonNull(key, "key"), new Entry<>(value, now));
        trimToLimit();
        return value;
    }

    public synchronized boolean remove(K key, V value) {
        Entry<V> existing = entries.get(key);
        if (existing == null || !Objects.equals(existing.value(), value)) {
            return false;
        }
        entries.remove(key);
        return true;
    }

    public synchronized int size() {
        removeExpired(ticker.getAsLong());
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    private boolean isExpired(Entry<V> entry, long now) {
        return ttlNanos != Long.MAX_VALUE && now - entry.writtenAt() >= ttlNanos;
    }

    private void removeExpired(long now) {
        if (ttlNanos == Long.MAX_VALUE) {
            return;
        }
        entries.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
    }

    private void trimToLimit() {
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while (entries.size() > maxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private record Entry<V>(V value, long writtenAt) {
    }
}
