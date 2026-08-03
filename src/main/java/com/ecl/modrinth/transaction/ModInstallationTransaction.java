package com.ecl.modrinth.transaction;

import java.io.IOException;
import java.nio.file.Path;

public interface ModInstallationTransaction extends AutoCloseable {
    Path temporaryDirectory();

    void stageDownloadedFile(Path temporaryFile, Path finalFile);

    void stageReplacement(Path oldFile, Path newFile);

    void stageReplacement(Path oldFile, Path temporaryFile, Path finalFile);

    void commit() throws IOException;

    void rollback();

    @Override
    void close();
}
