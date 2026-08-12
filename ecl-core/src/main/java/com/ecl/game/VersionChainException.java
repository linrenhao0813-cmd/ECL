package com.ecl.game;

import java.io.IOException;

/**
 * Signals a broken {@code inheritsFrom} chain: a missing parent, an empty link, or a cycle.
 * Kept as a distinct subtype of {@link IOException} so version-resolution failures can be told
 * apart from plain I/O errors.
 */
public final class VersionChainException extends IOException {

    public VersionChainException(String message) {
        super(message);
    }
}