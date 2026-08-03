package com.ecl.modrinth.instance;

import java.nio.file.Path;
import java.util.UUID;

public interface ModInstanceContext {
    UUID instanceId();

    String profileId();

    String minecraftVersion();

    ModLoader loader();

    default String loaderName() {
        return loader().apiName();
    }

    Path gameDirectory();

    Path modsDirectory();
}
