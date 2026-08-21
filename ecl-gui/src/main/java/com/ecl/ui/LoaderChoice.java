package com.ecl.ui;

import com.ecl.launcher.ModLoaderInstaller;

/** User-facing loader selection. */
enum LoaderChoice {
    VANILLA(null, "原版"),
    FABRIC(ModLoaderInstaller.Loader.FABRIC, "Fabric"),
    QUILT(ModLoaderInstaller.Loader.QUILT, "Quilt"),
    FORGE(ModLoaderInstaller.Loader.FORGE, "Forge"),
    NEOFORGE(ModLoaderInstaller.Loader.NEOFORGE, "NeoForge");

    final ModLoaderInstaller.Loader loader;
    final String displayName;

    LoaderChoice(ModLoaderInstaller.Loader loader, String displayName) {
        this.loader = loader;
        this.displayName = displayName;
    }

    boolean vanilla() {
        return loader == null;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
