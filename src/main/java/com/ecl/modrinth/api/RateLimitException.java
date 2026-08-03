package com.ecl.modrinth.api;

public final class RateLimitException extends ModrinthApiException {
    public RateLimitException(String message) {
        super(message, 429, true);
    }
}
