package com.ecl.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Provides shared, reusable {@link Gson} instances.
 * Avoids creating redundant Gson objects with the same configuration.
 */
public final class GsonProvider {

    private static final Gson COMPACT = new Gson();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private GsonProvider() {
    }

    /** Compact (no pretty-print) Gson instance, suitable for wire format. */
    public static Gson compact() {
        return COMPACT;
    }

    /** Pretty-printing Gson instance, suitable for human-readable files. */
    public static Gson pretty() {
        return PRETTY;
    }
}
