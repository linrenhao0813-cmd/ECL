package com.ecl.modrinth.api;

public class ModInstallationException extends RuntimeException {
    public ModInstallationException(String message) {
        super(message);
    }

    public ModInstallationException(String message, Throwable cause) {
        super(message, cause);
    }
}
