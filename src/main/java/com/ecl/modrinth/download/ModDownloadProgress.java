package com.ecl.modrinth.download;

public record ModDownloadProgress(
        String fileName,
        long fileDownloaded,
        long fileTotal,
        long overallDownloaded,
        long overallTotal,
        double bytesPerSecond
) {
}
