package com.ecl.game;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loader recognition shared by isolation and Mod compatibility decisions. */
public final class LibraryAnalyzer {
    private static final List<LoaderPattern> LOADERS = List.of(
            new LoaderPattern("fabric", Pattern.compile("^net\\.fabricmc:fabric-loader:(.+)$"),
                    List.of("net.fabricmc.")),
            new LoaderPattern("quilt", Pattern.compile("^org\\.quiltmc:quilt-loader:(.+)$"),
                    List.of("org.quiltmc.")),
            new LoaderPattern("neoforge", Pattern.compile(
                    "^net\\.neoforged(?:\\.fancymodloader)?:(?:neoforge|loader|core):(.+)$"),
                    List.of("net.neoforged.", "cpw.mods.modlauncher.")),
            new LoaderPattern("forge", Pattern.compile(
                    "^net\\.minecraftforge:(?:forge|fmlloader):(.+)$"),
                    List.of("net.minecraftforge.", "cpw.mods.fml.")),
            new LoaderPattern("liteloader", Pattern.compile("^com\\.mumfrey:liteloader:(.+)$"),
                    List.of("com.mumfrey.")));

    private LibraryAnalyzer() {
    }

    public static ModLoaderInfo analyze(String explicitId, String explicitVersion,
                                        String mainClass, List<Library> libraries) {
        if (explicitId != null && !explicitId.isBlank()) {
            return new ModLoaderInfo(explicitId.trim().toLowerCase(Locale.ROOT),
                    blankToEmpty(explicitVersion), ModLoaderInfo.DetectionSource.EXPLICIT);
        }
        if (libraries != null) {
            for (LoaderPattern loader : LOADERS) {
                for (Library library : libraries) {
                    Matcher matcher = loader.coordinate().matcher(library.name());
                    if (matcher.matches()) {
                        return new ModLoaderInfo(loader.id(), matcher.group(1),
                                ModLoaderInfo.DetectionSource.LIBRARY);
                    }
                }
            }
        }
        String entrypoint = blankToEmpty(mainClass);
        for (LoaderPattern loader : LOADERS) {
            if (loader.mainClassPrefixes().stream().anyMatch(entrypoint::startsWith)) {
                return new ModLoaderInfo(loader.id(), "", ModLoaderInfo.DetectionSource.MAIN_CLASS);
            }
        }
        return null;
    }

    public static boolean isModded(VersionMetadata metadata) {
        return metadata != null && metadata.modLoaderInfo() != null;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private record LoaderPattern(String id, Pattern coordinate, List<String> mainClassPrefixes) {
    }
}
