package com.ecl.download;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Common download boundary used by the content-library UI. */
public interface ContentDownloader {
    List<ModrinthDownloader.Project> searchProjects(
            String query, String gameVersion, String projectType, String loader, int limit)
            throws IOException;

    List<ModrinthDownloader.Project> listOfficialProjects(
            String gameVersion, String projectType, String loader, int limit) throws IOException;

    List<ModrinthDownloader.ProjectVersion> listProjectVersions(
            ModrinthDownloader.Project project, String gameVersion, String loader) throws IOException;

    ModrinthDownloader.DownloadResult downloadVersion(
            ModrinthDownloader.Project project,
            ModrinthDownloader.ProjectVersion selectedVersion,
            String gameVersion,
            String loader,
            File targetDir,
            boolean includeRequiredDependencies,
            ModrinthDownloader.DownloadListener listener,
            String... allowedExtensions
    ) throws IOException;
}
