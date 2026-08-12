package com.ecl.exception;

/** Unrecoverable launcher failure handled by the top-level crash boundary. */
public final class EclFatalError extends Error {
    private final ErrorCode errorCode;

    public EclFatalError(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ErrorCode.INTERNAL_FATAL;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
