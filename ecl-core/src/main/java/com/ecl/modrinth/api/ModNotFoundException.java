package com.ecl.modrinth.api;

public final class ModNotFoundException extends ModrinthApiException {
    public ModNotFoundException(String message) {
        super(message, 404, false);
    }
}
