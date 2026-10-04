package com.ecl.download;

import java.io.File;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Verifies downloaded assets against their declared SHA-1 digests. */
final class GameAssetVerifier {
    private final BooleanSupplier verifyExistingFiles;

    GameAssetVerifier(BooleanSupplier verifyExistingFiles) {
        this.verifyExistingFiles = verifyExistingFiles;
    }

    boolean needsDownload(File target, String expectedSha1) {
        return InstallHelpers.needsDownload(target, expectedSha1, verifyExistingFiles.getAsBoolean());
    }

    void verifyDownloadedFile(File target, String expectedSha1) throws IOException {
        InstallHelpers.verifyDownloadedFile(target, expectedSha1);
    }

}
