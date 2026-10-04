package com.ecl.util;

import java.security.GeneralSecurityException;

/**
 * Protects the random launcher encryption key with an OS/user-controlled secret.
 * Implementations must not log the supplied bytes and should clear temporary buffers.
 */
@FunctionalInterface
public interface KeyProtectionProvider {
    byte[] protect(byte[] key) throws GeneralSecurityException;

    /** Opens bytes produced by {@link #protect(byte[])}. */
    default byte[] unprotect(byte[] protectedKey) throws GeneralSecurityException {
        throw new GeneralSecurityException("Key provider cannot unprotect keys");
    }
}
