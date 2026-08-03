package com.ecl.modrinth.service;

import com.ecl.modrinth.instance.ModInstanceContext;

import java.util.concurrent.CompletableFuture;

public interface LocalModScanner {
    CompletableFuture<LocalModScanResult> scan(ModInstanceContext instance);
}
