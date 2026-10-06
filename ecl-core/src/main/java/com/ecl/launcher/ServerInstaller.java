package com.ecl.launcher;

import com.ecl.download.ServerJarDownloader;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JavaRuntimeUtil;
import com.google.gson.JsonParser;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.DWORD;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Synchronously prepares a standalone dedicated server; never starts it or accepts its EULA. */
public final class ServerInstaller {
    private final Backend backend;

    public ServerInstaller(ServerJarDownloader downloader, ModLoaderInstaller loaders) {
        this(new OfficialBackend(Objects.requireNonNull(downloader), Objects.requireNonNull(loaders)));
    }

    ServerInstaller(Backend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    /**
     * Installs into a new directory, using a private sibling stage. Interrupt the calling thread
     * to cancel. No client installation, shared library store, or local-server registry is used.
     * Atomic rename support is required; an unsupported filesystem fails without publishing.
     */
    public Path install(String minecraftVersion, ModLoaderInstaller.Loader loader, String loaderVersion,
                        Path targetDirectory, ModLoaderInstaller.Listener listener) throws IOException {
        requireVersion(minecraftVersion);
        if (loader != null) requireVersion(loaderVersion);
        if (targetDirectory == null) throw new IOException("Server target directory is required");
        Path target = targetDirectory.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Cannot install at a filesystem root");
        checkCancelled();
        validateTarget(target);
        if (!Files.isDirectory(parent)) throw new IOException("Server target parent must already exist: " + parent);
        ModLoaderInstaller.Listener progress = listener == null ? message -> { } : listener;
        if (loader != null && !backend.versions(minecraftVersion, loader).contains(loaderVersion)) {
            throw new IOException("Loader version is not compatible with Minecraft " + minecraftVersion);
        }
        checkCancelled();
        String java = backend.java(minecraftVersion, loader);
        checkCancelled();
        Path stage = Files.createTempDirectory(parent, ".ecl-server-install-");
        try {
            progress.onStatus("Downloading verified Minecraft dedicated server...");
            backend.vanilla(minecraftVersion, stage.resolve("server.jar"), progress);
            checkCancelled();
            if (loader != null) {
                Path installer = stage.resolve(".installer.jar");
                progress.onStatus("Downloading verified " + loader.displayName() + " installer...");
                backend.installer(minecraftVersion, loader, loaderVersion, installer, progress);
                checkCancelled();
                progress.onStatus("Installing " + loader.displayName() + " dedicated server...");
                checkCancelled();
                String installerJava = backend.installerJava(loader, java);
                checkCancelled();
                backend.run(installerJava, installer, stage, serverArguments(minecraftVersion, loader, loaderVersion, stage));
                checkCancelled();
                Files.delete(installer);
            }
            validateOutput(stage);
            writeScripts(stage, java, loader);
            Files.writeString(stage.resolve("eula.txt"), "eula=false\n");
            checkCancelled();
            validateTarget(target);
            // Siblings are on the same filesystem. Never use REPLACE_EXISTING or a copy fallback.
            publish(stage, target);
            return target;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                deleteStage(stage);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static void requireVersion(String value) throws IOException {
        FileUtil.requireSafeVersionId(value);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]*") || value.length() > 160) {
            throw new IOException("Invalid version: " + value);
        }
    }

    private static void validateTarget(Path target) throws IOException {
        FileUtil.validateExistingAncestors(target.getRoot(), target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Server target already exists: " + target);
        }
    }

    static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Server installation cancelled");
    }

    static List<String> serverArguments(String mc, ModLoaderInstaller.Loader loader, String version, Path stage) {
        return switch (loader) {
            case FABRIC -> List.of("server", "-mcversion", mc, "-loader", version, "-dir", stage.toString());
            // Quilt joins argv and reparses it: preserve literal quotes via the Java argument file.
            case QUILT -> List.of("install", "server", mc, version, "--install-dir=\"" + stage + "\"");
            case FORGE -> List.of("--installServer", stage.toString());
            case NEOFORGE -> List.of("--install-server", stage.toString());
        };
    }

    private static void validateOutput(Path stage) throws IOException {
        Files.walkFileTree(stage, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                checkCancelled();
                FileUtil.validateExistingAncestors(stage, path);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                checkCancelled();
                FileUtil.validateExistingAncestors(stage, path);
                if (!attributes.isRegularFile()) throw new IOException("Invalid installer output: " + path);
                return FileVisitResult.CONTINUE;
            }
        });
        requireFile(stage.resolve("server.jar"));
    }

    private static void requireFile(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) == 0) {
            throw new IOException("Installer did not produce required server file: " + file.getFileName());
        }
    }

    private static void writeScripts(Path stage, String java, ModLoaderInstaller.Loader loader) throws IOException {
        String batArgs;
        String shArgs;
        if (loader == null || loader == ModLoaderInstaller.Loader.FABRIC || loader == ModLoaderInstaller.Loader.QUILT) {
            String jar = loader == null ? "server.jar" : loader.id() + "-server-launch.jar";
            requireFile(stage.resolve(jar));
            if (loader != null && !Files.isDirectory(stage.resolve("libraries"))) {
                throw new IOException("Installer did not produce server libraries");
            }
            batArgs = "-jar \"" + jar + "\" nogui";
            shArgs = "-jar '" + jar + "' nogui";
        } else {
            List<Path> argFiles;
            try (var paths = Files.walk(stage.resolve("libraries"))) {
                argFiles = paths.filter(p -> p.getFileName().toString().equals("win_args.txt")).toList();
            }
            if (argFiles.size() == 1) {
                Path win = argFiles.getFirst();
                Path unix = win.resolveSibling("unix_args.txt");
                requireFile(win);
                requireFile(unix);
                requireFile(stage.resolve("run.bat"));
                requireFile(stage.resolve("run.sh"));
                String relative = stage.relativize(win.getParent()).toString().replace('\\', '/');
                if (!relative.matches("[A-Za-z0-9._/+\\-]+")) throw new IOException("Unsafe installer argument path");
                // Preserve official scripts and user JVM options, but launch with the detected Java.
                if (!Files.exists(stage.resolve("user_jvm_args.txt"))) Files.writeString(stage.resolve("user_jvm_args.txt"), "");
                batArgs = "@user_jvm_args.txt @\"" + relative + "/win_args.txt\" nogui";
                shArgs = "@user_jvm_args.txt @'" + relative + "/unix_args.txt' nogui";
            } else if (argFiles.isEmpty() && loader == ModLoaderInstaller.Loader.FORGE) {
                List<Path> jars;
                try (var paths = Files.list(stage)) {
                    jars = paths.filter(p -> p.getFileName().toString().matches("forge-[A-Za-z0-9._+\\-]+\\.jar")).toList();
                }
                if (jars.size() != 1) throw new IOException("Missing or ambiguous Forge server launcher");
                requireFile(jars.getFirst());
                String jar = jars.getFirst().getFileName().toString();
                batArgs = "-jar \"" + jar + "\" nogui";
                shArgs = "-jar '" + jar + "' nogui";
            } else {
                throw new IOException("Missing or ambiguous server argument files");
            }
        }
        if (java.chars().anyMatch(Character::isISOControl) || java.contains("\"") || java.contains("%") || java.contains("!")) {
            throw new IOException("Java executable cannot safely be represented in a start script");
        }
        Files.writeString(stage.resolve("start.bat"), "@echo off\r\nsetlocal DisableDelayedExpansion\r\ncd /d \"%~dp0\"\r\n\""
                + java + "\" " + batArgs + " %*\r\n");
        Files.writeString(stage.resolve("start.sh"), "#!/bin/sh\ncd -- \"$(dirname -- \"$0\")\" || exit 1\nexec '"
                + java.replace("'", "'\"'\"'") + "' " + shArgs + " \"$@\"\n");
        Path shell = stage.resolve("start.sh");
        if (Files.getFileStore(shell).supportsFileAttributeView("posix")) {
            var permissions = Files.getPosixFilePermissions(shell);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(shell, permissions);
        }
    }

    private static void deleteStage(Path stage) throws IOException {
        FileUtil.deleteDirectory(stage);
    }

    // Java ATOMIC_MOVE permits overwriting an existing empty directory, even without
    // REPLACE_EXISTING. Use a native no-replace rename so concurrent creators cannot lose data.
    static void publish(Path stage, Path target) throws IOException {
        validateTarget(target);
        if (!stage.getParent().equals(target.getParent())) throw new IOException("Server stage must be a sibling");
        if (Platform.isWindows()) {
            if (!Kernel32.INSTANCE.MoveFileEx(stage.toString(), target.toString(), new DWORD(0))) {
                throw new IOException("Cannot atomically publish server directory (Windows error " + Native.getLastError() + ")");
            }
        } else if (Platform.isLinux()) {
            try {
                if (Native.load(Platform.C_LIBRARY_NAME, LinuxRename.class)
                        .renameat2(-100, stage.toString(), -100, target.toString(), 1) != 0) {
                    throw new IOException("Cannot atomically publish server directory (errno " + Native.getLastError() + ")");
                }
            } catch (UnsatisfiedLinkError unsupported) {
                throw new IOException("Filesystem does not support atomic no-replace directory publication", unsupported);
            }
        } else {
            throw new IOException("Atomic no-replace directory publication is unsupported on this platform");
        }
    }

    private interface LinuxRename extends Library {
        int renameat2(int sourceDirectory, String source, int targetDirectory, String target, int flags);
    }

    static int installerJavaRequirement(ModLoaderInstaller.Loader loader, int serverMajor) {
        // Quilt 0.15.1 build.gradle.kts targets 17. Fabric 1.1.2 build.gradle targets 8.
        // Forge/NeoForge processors retain the selected game runtime, including legacy Forge.
        return loader == ModLoaderInstaller.Loader.QUILT ? Math.max(17, serverMajor) : serverMajor;
    }

    interface Backend {
        List<String> versions(String mc, ModLoaderInstaller.Loader loader) throws IOException;
        String java(String mc, ModLoaderInstaller.Loader loader) throws IOException;
        default String installerJava(ModLoaderInstaller.Loader loader, String serverJava) throws IOException {
            return serverJava;
        }
        void vanilla(String mc, Path target, ModLoaderInstaller.Listener listener) throws IOException;
        void installer(String mc, ModLoaderInstaller.Loader loader, String version, Path target,
                       ModLoaderInstaller.Listener listener) throws IOException;
        void run(String java, Path installer, Path stage, List<String> arguments) throws IOException;
    }

    private record OfficialBackend(ServerJarDownloader downloader, ModLoaderInstaller loaders) implements Backend {
        public List<String> versions(String mc, ModLoaderInstaller.Loader loader) throws IOException {
            return loaders.listVersions(mc, loader);
        }

        public String java(String mc, ModLoaderInstaller.Loader loader) throws IOException {
            int required = downloader.requiredJavaVersion(mc);
            String executable = JavaRuntimeUtil.resolveJavaExecutable("", required);
            // Older Forge is known to reject newer Java despite meeting the minimum feature version.
            if (loader == ModLoaderInstaller.Loader.FORGE && required <= 16
                    && JavaRuntimeUtil.detectJavaFeatureVersion(new java.io.File(executable)) != required) {
                throw new IOException("This Forge server requires an installed Java " + required + " runtime");
            }
            return executable;
        }

        public String installerJava(ModLoaderInstaller.Loader loader, String serverJava) throws IOException {
            int serverMajor = JavaRuntimeUtil.detectJavaFeatureVersion(new java.io.File(serverJava));
            int required = installerJavaRequirement(loader, serverMajor);
            return required == serverMajor ? serverJava : JavaRuntimeUtil.resolveJavaExecutable("", required);
        }

        public void vanilla(String mc, Path target, ModLoaderInstaller.Listener listener) throws IOException {
            var artifact = downloader.resolveOfficial(mc, null);
            checkCancelled();
            downloader.download(artifact, target.toFile(), new ServerJarDownloader.Listener() {
                public void onProgress(long downloaded, long total) { listener.onProgress(downloaded, total); }
            });
        }

        public void installer(String mc, ModLoaderInstaller.Loader loader, String version, Path target,
                              ModLoaderInstaller.Listener listener) throws IOException {
            String url = installerUrl(mc, loader, version);
            String digest = new String(HttpUtil.getBytes(url + ".sha1", 4096), StandardCharsets.UTF_8).trim().split("\\s+")[0];
            if (!digest.matches("(?i)[0-9a-f]{40}")) throw new IOException("Missing official installer digest");
            checkCancelled();
            HttpUtil.downloadFileWithProgress(url, target.toFile(), new HttpUtil.ProgressCallback() {
                public void onStart(long total) { listener.onProgress(0, total); }
                public void onProgress(long downloaded, long total) { listener.onProgress(downloaded, total); }
                public void onComplete(java.io.File file) { }
            }, null, 128L * 1024 * 1024, Set.of(URI.create(url).getHost()));
            checkCancelled();
            new LoaderArtifactVerifier().verify(target, "SHA-1", digest);
        }

        public void run(String java, Path installer, Path stage, List<String> arguments) throws IOException {
            new InstallerProcessRunner().run(java, installer, stage, arguments);
        }
    }

    static String installerUrl(String mc, ModLoaderInstaller.Loader loader, String version) throws IOException {
        requireVersion(mc);
        requireVersion(version);
        if (loader == ModLoaderInstaller.Loader.FORGE) {
            String coordinate = mc + "-" + version;
            return "https://maven.minecraftforge.net/net/minecraftforge/forge/" + coordinate + "/forge-" + coordinate + "-installer.jar";
        }
        if (loader == ModLoaderInstaller.Loader.NEOFORGE) {
            return "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + version + "/neoforge-" + version + "-installer.jar";
        }
        String meta = loader == ModLoaderInstaller.Loader.FABRIC ? "https://meta.fabricmc.net/v2" : "https://meta.quiltmc.org/v3";
        var installers = JsonParser.parseString(new String(HttpUtil.getBytes(meta + "/versions/installer", 1024 * 1024),
                StandardCharsets.UTF_8)).getAsJsonArray();
        if (installers.isEmpty()) throw new IOException("No official installer available");
        String installerVersion = installers.get(0).getAsJsonObject().get("version").getAsString();
        requireVersion(installerVersion);
        String base = loader == ModLoaderInstaller.Loader.FABRIC
                ? "https://maven.fabricmc.net/net/fabricmc/fabric-installer/"
                : "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/";
        return base + installerVersion + "/" + loader.id() + "-installer-" + installerVersion + ".jar";
    }
}
