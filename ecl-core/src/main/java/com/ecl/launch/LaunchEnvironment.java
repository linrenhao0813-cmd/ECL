package com.ecl.launch;

import com.ecl.util.FileUtil;

import java.io.File;
import java.io.IOException;

/**
 * Fixed paths and identity the launch pipeline reads from. Kept as one value so the pipeline can
 * be constructed and tested without touching global launcher configuration.
 *
 * @param versionsDirectory directory that stores {@code versionId}/{@code versionId}.json and jars
 * @param librariesDirectory directory that stores resolved Maven libraries
 * @param assetsDirectory  Minecraft assets root ({@code objects/} and {@code indexes/} children)
 * @param launcherName     value substituted into {@code ${launcher_name}}
 * @param launcherVersion  value substituted into {@code ${launcher_version}}
 */
public record LaunchEnvironment(
        File versionsDirectory,
        File librariesDirectory,
        File assetsDirectory,
        String launcherName,
        String launcherVersion) {

    /** Native libraries staging directory for a given version. */
    public File nativesDirectory(String versionId) {
        try {
            FileUtil.requireSafeVersionId(versionId);
            return FileUtil.safeResolveUnder(versionsDirectory, versionId + "/natives");
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid version id: " + versionId, error);
        }
    }
}
