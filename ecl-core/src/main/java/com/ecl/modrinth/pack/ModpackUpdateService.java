package com.ecl.modrinth.pack;

import com.ecl.modrinth.model.ReleaseChannel;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface ModpackUpdateService {
    CompletableFuture<ModpackUpdate> checkUpdate(ModpackInstance instance, ReleaseChannel channel);

    CompletableFuture<MrpackInstaller.InstallResult> applyUpdate(
            ModpackUpdate update, Path gameRoot, MrpackInstaller.Listener listener);
}
