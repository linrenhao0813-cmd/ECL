package com.ecl.download;

/**
 * Service interface for downloading Minecraft versions, libraries, and assets.
 */
public interface DownloadService extends AutoCloseable {

    void setListener(GameDownloader.DownloadListener listener);

    void setVerifyExistingFiles(boolean verifyExistingFiles);

    void downloadVersion(String versionId, String versionUrl);

    void downloadVersion(String versionId, String versionUrl, String versionSha1);

    java.util.concurrent.Future<?> downloadVersionAsync(String versionId, String versionUrl);

    java.util.concurrent.Future<?> downloadVersionAsync(String versionId, String versionUrl,
                                                        String versionSha1);

    boolean cancelDownload();

    boolean isDownloadInProgress();

    java.util.List<String> getMissingLibraries(com.google.gson.JsonObject versionJson);

    @Override
    void close();
}
