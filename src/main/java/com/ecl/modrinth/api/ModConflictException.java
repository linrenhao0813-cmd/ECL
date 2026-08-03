package com.ecl.modrinth.api;

public final class ModConflictException extends DependencyResolutionException {
    public ModConflictException(String message) {
        super(message);
    }
}
