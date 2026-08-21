package com.ecl.ui;

import com.ecl.util.Messages;

import java.io.File;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** Builds localized content-library descriptors and destination resolvers. */
final class ContentTargetFactory {
    private ContentTargetFactory() {
    }

    static List<ContentTarget> create(
            Function<String, File> modsDirectory,
            Function<String, File> versionGameDirectory,
            Supplier<File> configuredGameRoot) {
        return List.of(
                new ContentTarget(
                        Messages.get("content.mods.title"),
                        Messages.get("content.mods.subtitle"), "M", "mod",
                        new String[]{"fabric", "forge", "neoforge", "quilt"},
                        new String[]{".jar"}, true,
                        Messages.get("content.mods.searchHint"), modsDirectory),
                new ContentTarget(
                        Messages.get("content.shaders.title"),
                        Messages.get("content.shaders.subtitle"), "S", "shader",
                        new String[0], new String[]{".zip"}, false,
                        Messages.get("content.shaders.searchHint"),
                        version -> new File(versionGameDirectory.apply(version), "shaderpacks")),
                new ContentTarget(
                        Messages.get("content.resourcepacks.title"),
                        Messages.get("content.resourcepacks.subtitle"), "R", "resourcepack",
                        new String[0], new String[]{".zip"}, false,
                        Messages.get("content.resourcepacks.searchHint"),
                        version -> new File(versionGameDirectory.apply(version), "resourcepacks")),
                new ContentTarget(
                        Messages.get("content.modpacks.title"),
                        Messages.get("content.modpacks.subtitle"), "P", "modpack",
                        new String[0], new String[]{".mrpack"}, false,
                        Messages.get("content.modpacks.searchHint"),
                        version -> new File(versionGameDirectory.apply(version), "modpacks")),
                new ContentTarget(
                        Messages.get("content.server.title"),
                        Messages.get("content.server.subtitle"), "V", "server",
                        new String[0], new String[]{".jar"}, false,
                        Messages.get("content.server.searchHint"),
                        version -> new File(configuredGameRoot.get(), "server-downloads")));
    }
}
