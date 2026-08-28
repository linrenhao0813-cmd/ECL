package com.ecl.modrinth.download;

import com.ecl.modrinth.model.ModFile;
import com.ecl.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Chooses the file to download for a Modrinth version and validates existing local files. */
final class ModrinthFileSelector {
    private ModrinthFileSelector() {
    }

    static ModFile selectPrimaryFile(List<ModFile> files, String... allowedExtensions)
            throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IOException("这个版本没有可下载文件");
        }
        ModFile fallback = null;
        for (ModFile file : files) {
            if (!isAllowedFilename(file.fileName(), allowedExtensions)) {
                continue;
            }
            if (fallback == null) {
                fallback = file;
            }
            if (file.primary()) {
                return file;
            }
        }
        if (fallback == null) {
            throw new IOException("没有找到可导入的下载文件");
        }
        return fallback;
    }

    static boolean isAllowedFilename(String filename, String... allowedExtensions) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        String[] extensions = allowedExtensions == null || allowedExtensions.length == 0
                ? new String[]{".jar"} : allowedExtensions;
        for (String extension : extensions) {
            if (extension != null && lower.endsWith(extension.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static boolean existingFileSatisfies(File target, ModFile file) {
        if (!target.isFile() || target.length() != file.size()
                || !HashVerifier.hasUsableExpectedHash(file.hashes())) {
            return false;
        }
        try {
            new HashVerifier().verify(target.toPath(), file.hashes());
            return true;
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    static boolean existingFileSatisfies(File target, String sha1) {
        return target.isFile() && sha1 != null && sha1.matches("(?i)[0-9a-f]{40}")
                && FileUtil.verifySha1(target, sha1);
    }
}
