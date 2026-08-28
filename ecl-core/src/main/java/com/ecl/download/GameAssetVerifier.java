package com.ecl.download;

import com.ecl.ECLConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Verifies downloaded assets and tracks the asset-index verification marker. */
final class GameAssetVerifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameAssetVerifier.class);
    private final BooleanSupplier verifyExistingFiles;

    GameAssetVerifier(BooleanSupplier verifyExistingFiles) {
        this.verifyExistingFiles = verifyExistingFiles;
    }

    boolean needsDownload(File target, String expectedSha1) {
        return needsDownload(target, expectedSha1, false);
    }

    boolean needsDownload(File target, String expectedSha1, boolean skipHashVerification) {
        if (skipHashVerification) {
            return !target.isFile();
        }
        return InstallHelpers.needsDownload(target, expectedSha1, verifyExistingFiles.getAsBoolean());
    }

    void verifyDownloadedFile(File target, String expectedSha1) throws IOException {
        InstallHelpers.verifyDownloadedFile(target, expectedSha1);
    }

    boolean verifiedMarkerMatches(String assetId, String indexSha1) {
        if (!verifyExistingFiles.getAsBoolean() || !InstallHelpers.hasSha1(indexSha1)) {
            return false;
        }
        File marker = verifiedMarkerFile(assetId);
        if (!marker.isFile()) {
            return false;
        }
        try {
            return indexSha1.equalsIgnoreCase(
                    Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            LOGGER.debug("Failed to read assets verification marker {}", marker, e);
            return false;
        }
    }

    void writeVerifiedMarker(String assetId, String indexSha1) {
        if (!InstallHelpers.hasSha1(indexSha1)) {
            return;
        }
        File marker = verifiedMarkerFile(assetId);
        File parent = marker.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return;
        }
        Path temporary = marker.toPath().resolveSibling(marker.getName() + "."
                + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, indexSha1, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, marker.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, marker.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to write assets verification marker {}", marker, e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Best effort cleanup of a non-authorizing temporary marker.
            }
        }
    }

    private File verifiedMarkerFile(String assetId) {
        return new File(ECLConfig.getAssetsDir(), ".ecl-verified-indexes/" + assetId + ".marker");
    }
}
