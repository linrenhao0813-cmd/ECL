package com.ecl.game;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface GameRepository {
    VersionMetadata resolve(String versionId) throws IOException;

    List<String> installedVersions();

    Path instanceDirectory(String versionId, InstanceIsolation isolation, Path customDirectory);
}
