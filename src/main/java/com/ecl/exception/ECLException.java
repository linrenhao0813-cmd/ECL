package com.ecl.exception;

/**
 * Base exception for all ECL launcher errors.
 * Unchecked (RuntimeException) so callers are not forced to catch it at every level;
 * specific subclasses can be caught when the caller wants fine-grained handling.
 */
public class ECLException extends RuntimeException {
    public ECLException(String message) {
        super(message);
    }

    public ECLException(String message, Throwable cause) {
        super(message, cause);
    }

    public ECLException(Throwable cause) {
        super(cause);
    }
}
