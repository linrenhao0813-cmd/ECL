package com.ecl.modrinth.download;

import java.nio.file.Path;

public record DownloadedModFile(
        ModDownloadRequest request,
        Path temporaryFile,
        HashVerifier.HashResult hashes,
        long size
) {
}
