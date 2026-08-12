package com.ecl.modrinth.api;

public final class NoCompatibleVersionException extends RuntimeException {
    public NoCompatibleVersionException(String message) {
        super(message);
    }
}
