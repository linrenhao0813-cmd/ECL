package com.ecl.game;

/** Structured loader identity detected from explicit metadata, libraries, or the main class. */
public record ModLoaderInfo(String id, String version, DetectionSource source) {
    public enum DetectionSource {
        EXPLICIT,
        LIBRARY,
        MAIN_CLASS
    }
}
