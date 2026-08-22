package com.ecl.launcher;

import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes and annotates Loader-generated version profiles. */
final class LoaderProfileWriter {
    void writeProfile(Path profileDir, String profileId, JsonObject profile) throws IOException {
        Files.createDirectories(profileDir);
        HttpUtil.writeJson(profileDir.resolve(profileId + ".json").toFile(), profile);
    }

    void annotateProfile(Path jsonFile, String minecraftVersion,
                         ModLoaderInstaller.Loader loader, String loaderVersion)
            throws IOException {
        if (!Files.isRegularFile(jsonFile)) {
            throw new IOException("加载器版本缺少 JSON: " + jsonFile);
        }
        JsonObject json = HttpUtil.readJson(jsonFile.toFile());
        json.addProperty("eclModLoader", loader.id());
        json.addProperty("eclModLoaderVersion", loaderVersion);
        json.addProperty("eclMinecraftVersion", minecraftVersion);
        HttpUtil.writeJson(jsonFile.toFile(), json);
    }
}
