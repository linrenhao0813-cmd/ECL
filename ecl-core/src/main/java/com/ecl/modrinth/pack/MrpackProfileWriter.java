package com.ecl.modrinth.pack;

import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the ECL profile metadata associated with an installed MRPACK. */
final class MrpackProfileWriter {
    private MrpackProfileWriter() {
    }

    static void write(String profileId, String parentProfile, String packName,
                      String packVersion, String minecraftVersion, String loaderId,
                      String loaderVersion, String sourceProjectId, String sourceVersionId)
            throws IOException {
        JsonObject profile = new JsonObject();
        profile.addProperty("id", profileId);
        profile.addProperty("inheritsFrom", parentProfile);
        profile.addProperty("type", "release");
        profile.addProperty("eclMinecraftVersion", minecraftVersion);
        profile.addProperty("eclModLoader", loaderId);
        profile.addProperty("eclModLoaderVersion", loaderVersion);
        profile.addProperty("eclModpackName", packName);
        profile.addProperty("eclModpackVersion", packVersion);
        if (sourceProjectId != null && !sourceProjectId.isBlank()) {
            profile.addProperty("eclModpackSource", "modrinth");
            profile.addProperty("eclModpackProjectId", sourceProjectId.trim());
        }
        if (sourceVersionId != null && !sourceVersionId.isBlank()) {
            profile.addProperty("eclModpackVersionId", sourceVersionId.trim());
        }
        Path profileDir = MrpackPathPolicy.profileDirectory(profileId);
        Files.createDirectories(profileDir);
        HttpUtil.writeJson(profileDir.resolve(profileId + ".json").toFile(), profile);
    }

    static void update(JsonObject profile, String profileId, String parentProfile,
                       String minecraftVersion, String packVersion, String loaderId,
                       String loaderVersion, String sourceProjectId, String sourceVersionId) {
        profile.addProperty("id", profileId);
        profile.addProperty("inheritsFrom", parentProfile);
        profile.addProperty("eclMinecraftVersion", minecraftVersion);
        profile.addProperty("eclModLoader", loaderId);
        profile.addProperty("eclModLoaderVersion", loaderVersion);
        profile.addProperty("eclModpackVersion", packVersion);
        if (sourceProjectId != null && !sourceProjectId.isBlank()) {
            profile.addProperty("eclModpackProjectId", sourceProjectId.trim());
        }
        if (sourceVersionId != null && !sourceVersionId.isBlank()) {
            profile.addProperty("eclModpackVersionId", sourceVersionId.trim());
        }
        profile.addProperty("eclModpackSource", "modrinth");
    }
}
