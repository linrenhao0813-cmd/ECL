package com.ecl.modrinth.model;

import java.io.File;
import java.util.List;

/** Files produced by a content download operation. */
public record ContentDownloadResult(File mainFile, List<File> files) {
    public ContentDownloadResult {
        files = List.copyOf(files);
    }

    public File getMainFile() { return mainFile; }
    public List<File> getFiles() { return files; }
}
