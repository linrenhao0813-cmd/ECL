package com.ecl.launch;

import com.ecl.auth.AuthProvider;
import com.ecl.auth.OfflineSkin;

import java.io.File;
import java.util.List;

/**
 * Immutable set of values the launch pipeline needs, independent of how they were gathered.
 * The builder keeps construction readable and lets tests assemble options without a UI.
 */
public final class LaunchOptions {

    private final String versionId;
    private final AuthProvider auth;
    private final File gameDirectory;
    private final File instanceDirectory;
    private final LaunchEnvironment environment;
    private final int maxMemoryMb;
    private final int minMemoryMb;
    private final List<String> jvmArguments;
    private final String javaExecutablePath;
    private final int gameWidth;
    private final int gameHeight;
    private final boolean fullscreen;
    private final String serverAddress;
    private final int processorCount;
    private final OfflineSkin offlineSkin;

    private LaunchOptions(Builder builder) {
        this.versionId = builder.versionId;
        this.auth = builder.auth;
        this.gameDirectory = builder.gameDirectory;
        this.instanceDirectory = builder.instanceDirectory == null
                ? builder.gameDirectory : builder.instanceDirectory;
        this.environment = builder.environment;
        this.maxMemoryMb = builder.maxMemoryMb;
        this.minMemoryMb = builder.minMemoryMb;
        this.jvmArguments = builder.jvmArguments == null ? List.of()
                : List.copyOf(builder.jvmArguments);
        this.javaExecutablePath = builder.javaExecutablePath == null ? "" : builder.javaExecutablePath;
        this.gameWidth = Math.max(320, builder.gameWidth);
        this.gameHeight = Math.max(240, builder.gameHeight);
        this.fullscreen = builder.fullscreen;
        this.serverAddress = builder.serverAddress == null ? "" : builder.serverAddress.trim();
        this.processorCount = Math.max(0, builder.processorCount);
        this.offlineSkin = builder.offlineSkin;
    }

    public String versionId() {
        return versionId;
    }

    public AuthProvider auth() {
        return auth;
    }

    /** Working directory and game root of the launched instance. */
    public File gameDirectory() {
        return gameDirectory;
    }

    /** Stable instance root; may differ from the custom game run directory. */
    public File instanceDirectory() {
        return instanceDirectory;
    }

    public LaunchEnvironment environment() {
        return environment;
    }

    public int maxMemoryMb() {
        return maxMemoryMb;
    }

    public int minMemoryMb() {
        return minMemoryMb;
    }

    /** Extra JVM arguments added before the metadata-declared ones. */
    public List<String> jvmArguments() {
        return jvmArguments;
    }

    /** User-configured Java executable; blank lets the pipeline resolve one automatically. */
    public String javaExecutablePath() {
        return javaExecutablePath;
    }

    public int gameWidth() {
        return gameWidth;
    }

    public int gameHeight() {
        return gameHeight;
    }

    public boolean fullscreen() {
        return fullscreen;
    }

    /** Direct-connect server address (host or host:port), possibly blank. */
    public String serverAddress() {
        return serverAddress;
    }

    /** Explicit processor-count override; 0 leaves the JVM default alone. */
    public int processorCount() {
        return processorCount;
    }

    /** Locally imported skin for an offline account, or {@code null} when not applicable. */
    public OfflineSkin offlineSkin() {
        return offlineSkin;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String versionId;
        private AuthProvider auth;
        private File gameDirectory;
        private File instanceDirectory;
        private LaunchEnvironment environment;
        private int maxMemoryMb = 2048;
        private int minMemoryMb = 512;
        private List<String> jvmArguments;
        private String javaExecutablePath = "";
        private int gameWidth = 1280;
        private int gameHeight = 720;
        private boolean fullscreen;
        private String serverAddress = "";
        private int processorCount;
        private OfflineSkin offlineSkin;

        public Builder versionId(String versionId) {
            this.versionId = versionId;
            return this;
        }

        public Builder auth(AuthProvider auth) {
            this.auth = auth;
            return this;
        }

        public Builder gameDirectory(File gameDirectory) {
            this.gameDirectory = gameDirectory;
            return this;
        }

        public Builder instanceDirectory(File instanceDirectory) {
            this.instanceDirectory = instanceDirectory;
            return this;
        }

        public Builder environment(LaunchEnvironment environment) {
            this.environment = environment;
            return this;
        }

        public Builder maxMemoryMb(int maxMemoryMb) {
            this.maxMemoryMb = maxMemoryMb;
            return this;
        }

        public Builder minMemoryMb(int minMemoryMb) {
            this.minMemoryMb = minMemoryMb;
            return this;
        }

        public Builder jvmArguments(List<String> jvmArguments) {
            this.jvmArguments = jvmArguments == null ? List.of() : List.copyOf(jvmArguments);
            return this;
        }

        public Builder javaExecutablePath(String javaExecutablePath) {
            this.javaExecutablePath = javaExecutablePath;
            return this;
        }

        public Builder gameResolution(int width, int height) {
            this.gameWidth = width;
            this.gameHeight = height;
            return this;
        }

        public Builder fullscreen(boolean fullscreen) {
            this.fullscreen = fullscreen;
            return this;
        }

        public Builder serverAddress(String serverAddress) {
            this.serverAddress = serverAddress;
            return this;
        }

        public Builder processorCount(int processorCount) {
            this.processorCount = processorCount;
            return this;
        }

        public Builder offlineSkin(OfflineSkin offlineSkin) {
            this.offlineSkin = offlineSkin;
            return this;
        }

        public LaunchOptions build() {
            return new LaunchOptions(this);
        }
    }
}
