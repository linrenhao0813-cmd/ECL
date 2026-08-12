package com.ecl.launch;

import java.io.IOException;

/**
 * Failure while preparing or starting a game process. The {@code kind} lets the UI and diagnostics
 * react to the common failure families without parsing message text.
 */
public final class LaunchException extends IOException {

    public enum Kind {
        /** The version metadata is missing or unusable. */
        VERSION_INVALID,
        /** A required file (client jar, library) is absent after download work was skipped. */
        MISSING_FILES,
        /** No compatible or auto-downloaded Java runtime is available. */
        JAVA_UNAVAILABLE,
        /** The assembled command exceeds the OS argument limit. */
        COMMAND_TOO_LONG,
        /** The operating system refused to create the process. */
        PROCESS_CREATION,
        /** Everything else. */
        UNKNOWN
    }

    private final Kind kind;

    public LaunchException(Kind kind, String message) {
        super(message);
        this.kind = kind == null ? Kind.UNKNOWN : kind;
    }

    public LaunchException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind == null ? Kind.UNKNOWN : kind;
    }

    public Kind kind() {
        return kind;
    }
}