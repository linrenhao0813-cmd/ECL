package com.ecl.exception;

/**
 * Thrown when launcher configuration is missing, corrupt, or invalid.
 */
public class ConfigException extends ECLException {
    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
