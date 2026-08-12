package com.ecl.game;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, fully-resolved description of a game version.
 *
 * <p>Instances are produced by {@link VersionRepository} and represent the <em>effective</em>
 * metadata: the result of walking the {@code inheritsFrom} chain and merging each level into the
 * child, exactly as the game expects. Consumers never touch raw JSON for launch decisions.</p>
 */
public final class VersionMetadata {

    /** Fixed set of standard download keys a version may declare. */
    public static final Set<String> STANDARD_DOWNLOAD_KEYS =
            Set.of("client", "server", "windows_server");

    private final String id;
    private final String inheritsFrom;
    private final String mainClass;
    private final String jar;
    private final String minecraftVersion;
    private final String modLoader;
    private final String type;
    private final int javaMajorVersion;
    private final AssetIndex assetIndex;
    private final Map<String, DownloadObject> downloads;
    private final List<Library> libraries;
    private final VersionArguments arguments;

    VersionMetadata(String id, String inheritsFrom, String mainClass, String jar,
                    String minecraftVersion, String modLoader, String type, int javaMajorVersion,
                    AssetIndex assetIndex, Map<String, DownloadObject> downloads,
                    List<Library> libraries, VersionArguments arguments) {
        this.id = id;
        this.inheritsFrom = inheritsFrom;
        this.mainClass = mainClass == null ? "" : mainClass;
        this.jar = jar;
        this.minecraftVersion = minecraftVersion;
        this.modLoader = modLoader;
        this.type = type;
        this.javaMajorVersion = javaMajorVersion;
        this.assetIndex = assetIndex;
        this.downloads = downloads;
        this.libraries = libraries;
        this.arguments = arguments == null ? VersionArguments.empty() : arguments;
    }

    /** The version id as requested (a loader profile id or a plain release id). */
    public String id() {
        return id;
    }

    /** Direct parent in the inheritance chain, or null for base versions. */
    public String inheritsFrom() {
        return inheritsFrom;
    }

    /** Effective main class after inheritance resolution. Blank when metadata lacks one. */
    public String mainClass() {
        return mainClass;
    }

    /**
     * Id of the version whose client jar belongs on the classpath. Falls back to the base version
     * id when no explicit {@code jar} is declared anywhere in the chain.
     */
    public String clientJarId() {
        return jar == null || jar.isBlank() ? id : jar;
    }

    /** Base Minecraft version this profile runs on (resolved inheritance root). */
    public String minecraftVersion() {
        return minecraftVersion;
    }

    /** Detected mod loader ({@code fabric}/{@code forge}/{@code neoforge}/{@code quilt}), or null. */
    public String modLoader() {
        return modLoader;
    }

    /** Manifest type, e.g. {@code release} or {@code snapshot}; may be blank. */
    public String type() {
        return type;
    }

    /** Required Java feature version, or 0 when the metadata does not specify one. */
    public int javaMajorVersion() {
        return javaMajorVersion;
    }

    /** The asset index this version uses, or null when it has none. */
    public AssetIndex assetIndex() {
        return assetIndex;
    }

    /** Download targets by key ({@code client}, {@code server}, …); never null. */
    public Map<String, DownloadObject> downloads() {
        return downloads;
    }

    /** Effective library list after inheritance merging; never null. */
    public List<Library> libraries() {
        return libraries;
    }

    /** Effective argument model; never null. */
    public VersionArguments arguments() {
        return arguments;
    }

    /** Convenience: the client jar download object, or null when not declared. */
    public DownloadObject clientDownload() {
        return downloads.get("client");
    }

    @Override
    public String toString() {
        return id;
    }
}