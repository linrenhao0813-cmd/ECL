package com.ecl.ui;

import java.io.File;
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
}
