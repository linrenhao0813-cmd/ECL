package com.ecl.pack;

import java.io.IOException;
import java.nio.file.Path;

public interface PackService {
    PackPreview preview(Path archive) throws IOException;

    PackImportResult importPack(Path archive, Path instancesRoot, String preferredName) throws IOException;

    Path exportInstance(Path instanceDirectory, String minecraftVersion,
                        PackFormat format, Path output) throws IOException;
}
