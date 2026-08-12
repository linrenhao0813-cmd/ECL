package com.ecl.game;

import com.google.gson.JsonArray;

import java.util.List;

/**
 * A single entry in a version's JVM or game argument list.
 */
public sealed interface ArgumentToken permits ArgumentToken.Literal, ArgumentToken.Conditional {

    /** A plain argument that always applies. */
    record Literal(String value) implements ArgumentToken {

        @Override
        public String toString() {
            return value;
        }
    }

    /** An argument guarded by a platform rule ({@code isApplicableToCurrentPlatform()} checks it). */
    record Conditional(JsonArray rules, List<String> values) implements ArgumentToken {

        /** Values fixed at construction: rules may be null, value list is never null. */
        public Conditional {
            values = values == null ? List.of() : List.copyOf(values);
        }

        @Override
        public String toString() {
            return "(conditioned)";
        }
    }
}