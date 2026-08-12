package com.ecl.modrinth.api;

public class ModrinthApiException extends RuntimeException {
    private final int statusCode;
    private final boolean retryable;

    public ModrinthApiException(String message, int statusCode, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public ModrinthApiException(String message, int statusCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
