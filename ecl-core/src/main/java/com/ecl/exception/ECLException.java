package com.ecl.exception;

import java.util.Map;

/** Recoverable business failure with a stable code and redacted diagnostic context. */
public class ECLException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Map<String, String> context;

    public ECLException(String message) {
        this(ErrorCode.UNKNOWN, message, null, Map.of());
    }

    public ECLException(String message, Throwable cause) {
        this(ErrorCode.UNKNOWN, message, cause, Map.of());
    }

    public ECLException(Throwable cause) {
        this(ErrorCode.UNKNOWN, cause == null ? null : cause.getMessage(), cause, Map.of());
    }

    public ECLException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, Map.of());
    }

    public ECLException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, cause, Map.of());
    }

    public ECLException(ErrorCode errorCode, String message, Throwable cause,
                        Map<String, String> context) {
        super(message, cause);
        this.errorCode = errorCode == null ? ErrorCode.UNKNOWN : errorCode;
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String supportCode() {
        return errorCode.value();
    }

    public String suggestion() {
        return errorCode.suggestion();
    }

    public Map<String, String> context() {
        return context;
    }
}
