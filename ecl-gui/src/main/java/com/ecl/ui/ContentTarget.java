package com.ecl.ui;

import com.ecl.util.Messages;

import java.io.File;
import java.util.List;
import java.util.function.Function;

/** Describes one content-library category and its destination folder. */
final class ContentTarget {
    final String title;
    final String subtitle;
    final String initial;
    final String projectType;
    final String[] loaders;
    final String[] allowedExtensions;
    final boolean downloadDependencies;
    final String searchHint;
    final Function<String, File> folderResolver;

    ContentTarget(String title, String subtitle, String initial, String projectType,
                  String[] loaders, String[] allowedExtensions,
                  boolean downloadDependencies, String searchHint,
                  Function<String, File> folderResolver) {
        this.title = title;
        this.subtitle = subtitle;
        this.initial = initial;
        this.projectType = projectType;
        this.loaders = loaders;
        this.allowedExtensions = allowedExtensions;
        this.downloadDependencies = downloadDependencies;
        this.searchHint = searchHint;
        this.folderResolver = folderResolver;
    }

    boolean usesLoader() {
        return loaders != null && loaders.length > 0;
    }

    static List<ContentTarget> create(
            Function<String, File> modsDirectory,
            Function<String, File> versionGameDirectory) {
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
                        version -> new File(versionGameDirectory.apply(version), "modpacks")));
    }
}
