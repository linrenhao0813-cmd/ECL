package com.ecl.exception;

/**
 * Thrown on authentication failures (Microsoft, Yggdrasil, offline).
 */
public class AuthException extends ECLException {
    public AuthException(String message) {
        super(message);
    }

    public AuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
