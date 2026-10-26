package com.ecl.modrinth.pack;

import com.ecl.modrinth.model.ReleaseChannel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface ModpackUpdateService {
    default CompletableFuture<MrpackInstaller.InstallResult> applyPreparedUpdate(
            PreparedUpdate prepared, Path gameRoot, MrpackInstaller.Listener listener,
            com.ecl.operation.InstanceOperationSession session) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Update sessions are not supported"));
    }

    CompletableFuture<ModpackUpdate> checkUpdate(ModpackInstance instance, ReleaseChannel channel);

    /** Downloads and parses the exact selected archive without modifying the instance. */
    default CompletableFuture<PreparedUpdate> prepareUpdate(
            ModpackUpdate update, Path gameRoot, MrpackInstaller.Listener listener) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Prepared modpack updates are not supported"));
    }

    CompletableFuture<MrpackInstaller.InstallResult> applyUpdate(
            ModpackUpdate update, Path gameRoot, MrpackInstaller.Listener listener);

    /** Applies the archive and file plan shown by {@link #prepareUpdate}. */
    default CompletableFuture<MrpackInstaller.InstallResult> applyPreparedUpdate(
            PreparedUpdate prepared, Path gameRoot, MrpackInstaller.Listener listener) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Prepared modpack updates are not supported"));
    }

    record PreparedUpdate(ModpackUpdate update, MrpackInstaller.UpdatePreview preview)
            implements AutoCloseable {
        public PreparedUpdate {
            java.util.Objects.requireNonNull(update, "update");
            java.util.Objects.requireNonNull(preview, "preview");
        }

        @Override
        public void close() throws IOException {
            preview.close();
        }

        public void closeQuietly() {
            try {
                close();
            } catch (IOException failure) {
                org.slf4j.LoggerFactory.getLogger(ModpackUpdateService.class)
                        .warn("Unable to clean prepared modpack preview; cleanup will retry on startup or next preview", failure);
            }
        }
    }
}
