package com.ecl.download;

import com.ecl.modrinth.model.ContentDownloadResult;
import com.ecl.modrinth.model.ContentProject;
import com.ecl.modrinth.model.ContentVersion;
import com.ecl.modrinth.download.ModrinthDownloader;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** Common download boundary used by the content-library UI. */
public interface ContentDownloader {
    List<ContentProject> searchProjects(
            String query, String gameVersion, String projectType, String loader, int limit)
            throws IOException;

    List<ContentProject> listOfficialProjects(
            String gameVersion, String projectType, String loader, int limit) throws IOException;

    List<ContentVersion> listProjectVersions(
            ContentProject project, String gameVersion, String loader) throws IOException;

    ContentDownloadResult downloadVersion(
            ContentProject project,
            ContentVersion selectedVersion,
            String gameVersion,
            String loader,
            File targetDir,
            boolean includeRequiredDependencies,
            ModrinthDownloader.DownloadListener listener,
            String... allowedExtensions
    ) throws IOException;
}
