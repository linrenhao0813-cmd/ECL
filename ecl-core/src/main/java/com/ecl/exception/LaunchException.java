package com.ecl.exception;

/**
 * Thrown when game launch fails (missing version, wrong Java, invalid args).
 */
public class LaunchException extends ECLException {
    public LaunchException(String message) {
        super(message);
    }

    public LaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
