package com.ecl.modrinth.service;

import com.ecl.modrinth.instance.ModInstanceContext;

import java.util.concurrent.CompletableFuture;

public interface LocalModScanner {
    CompletableFuture<LocalModScanResult> scan(ModInstanceContext instance);

    /** Reads mod files and remote metadata without creating directories or persisting an index/cache. */
    default CompletableFuture<LocalModScanResult> inspect(ModInstanceContext instance) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Read-only mod inspection is not supported"));
    }
}
