package com.ecl.launcher;

import com.ecl.game.VersionMetadata;
import com.ecl.game.VersionRepository;
import com.ecl.launch.GameProcessMarker;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.service.InstanceOperationLock;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.InstanceOperationLease;
import com.ecl.util.Messages;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/** Upgrades a loader while keeping the existing profile identity and instance directory. */
public final class LoaderUpdateService {
    private final Path versionsDirectory;
    private final Backend backend;
    private final InstanceOperationLock operationLock;
    private final Executor executor;
    private final Predicate<UUID> instanceRunning;
    private final ProfileDependencyPreparer dependencyPreparer;

    public LoaderUpdateService(Path versionsDirectory, ModLoaderInstaller installer,
                               InstanceOperationLock operationLock, Executor executor,
                               Predicate<UUID> instanceRunning) {
        this(versionsDirectory, installer, operationLock, executor, instanceRunning,
                (profileId, listener) -> { });
    }

    public LoaderUpdateService(Path versionsDirectory, ModLoaderInstaller installer,
                               InstanceOperationLock operationLock, Executor executor,
                               Predicate<UUID> instanceRunning, ProfileDependencyPreparer dependencyPreparer) {
        this(versionsDirectory, new Backend() {
            @Override
            public List<String> listVersions(String minecraftVersion, ModLoaderInstaller.Loader loader) throws IOException {
                return installer.listVersions(minecraftVersion, loader);
            }

            @Override
            public ModLoaderInstaller.InstallResult install(String minecraftVersion, ModLoaderInstaller.Loader loader,
                                                            String version, ModLoaderInstaller.Listener listener) throws IOException {
                return installer.install(minecraftVersion, loader, version, listener);
            }
        }, operationLock, executor, instanceRunning, dependencyPreparer);
    }

    public LoaderUpdateService(Path versionsDirectory, Backend backend, InstanceOperationLock operationLock,
                               Executor executor, Predicate<UUID> instanceRunning) {
        this(versionsDirectory, backend, operationLock, executor, instanceRunning,
                (profileId, listener) -> { });
    }

    public LoaderUpdateService(Path versionsDirectory, Backend backend, InstanceOperationLock operationLock,
                               Executor executor, Predicate<UUID> instanceRunning,
                               ProfileDependencyPreparer dependencyPreparer) {
        this.versionsDirectory = Objects.requireNonNull(versionsDirectory).toAbsolutePath().normalize();
        this.backend = Objects.requireNonNull(backend);
        this.operationLock = Objects.requireNonNull(operationLock);
        this.executor = Objects.requireNonNull(executor);
        this.instanceRunning = Objects.requireNonNull(instanceRunning);
        this.dependencyPreparer = Objects.requireNonNull(dependencyPreparer);
    }

    public CompletableFuture<Result> update(ModInstanceContext instance, ModLoaderInstaller.Listener listener) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return updateBlocking(instance, null, listener);
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, executor);
    }

    /** Resolves the currently available compatible loader version without changing local files. */
    public CompletableFuture<UpdatePlan> previewUpdate(ModInstanceContext instance) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureStopped(instance);
                VersionMetadata current = new VersionRepository(versionsDirectory.toFile())
                        .resolve(instance.profileId());
                if (!instance.minecraftVersion().equals(current.minecraftVersion())
                        || !instance.loaderName().equals(current.modLoader())) {
                    throw new IOException(Messages.get("loaderUpdate.profileChanged"));
                }
                ModLoaderInstaller.Loader loader = ModLoaderInstaller.Loader.valueOf(
                        instance.loaderName().toUpperCase(Locale.ROOT));
                List<String> versions = backend.listVersions(instance.minecraftVersion(), loader);
                if (versions.isEmpty()) throw new IOException(Messages.get("loaderUpdate.noVersions"));
                String latest = versions.getFirst();
                boolean updateAvailable = current.modLoaderVersion().isBlank()
                        || ModLoaderInstaller.compareVersionsDescending(
                                latest, current.modLoaderVersion()) < 0;
                return new UpdatePlan(instance.profileId(), instance.minecraftVersion(),
                        instance.loaderName(), current.modLoaderVersion(),
                        updateAvailable ? latest : current.modLoaderVersion(), updateAvailable);
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, executor);
    }

    /** Applies the explicit version selected by a prior preview without asking for a newer one. */
    public CompletableFuture<Result> update(ModInstanceContext instance, UpdatePlan plan,
                                            ModLoaderInstaller.Listener listener) {
        Objects.requireNonNull(plan, "plan");
        if (!instance.profileId().equals(plan.profileId())) {
            return CompletableFuture.failedFuture(new IOException(Messages.get("loaderUpdate.planOtherInstance")));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return updateBlocking(instance, plan, listener);
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, executor);
    }

    private Result updateBlocking(ModInstanceContext instance, UpdatePlan plan,
                                  ModLoaderInstaller.Listener listener) throws Exception {
        if (!instance.loader().supportsMods()) {
            throw new IOException(Messages.get("loaderUpdate.unsupported"));
        }
        ensureStopped(instance);
        try (AutoCloseable ignored = operationLock.acquire(instance.instanceId());
             InstanceOperationLease lease = InstanceOperationLease.tryAcquire(instance.gameDirectory())) {
            if (lease == null) throw new IOException(Messages.get("loaderUpdate.busy"));
            ensureStopped(instance);
            VersionMetadata current = new VersionRepository(versionsDirectory.toFile()).resolve(instance.profileId());
            if (!instance.minecraftVersion().equals(current.minecraftVersion())
                    || !instance.loaderName().equals(current.modLoader())) {
                throw new IOException(Messages.get("loaderUpdate.profileChanged"));
            }
            ModLoaderInstaller.Loader loader = ModLoaderInstaller.Loader.valueOf(instance.loaderName().toUpperCase(Locale.ROOT));
            String targetVersion;
            if (plan == null) {
                List<String> versions = backend.listVersions(instance.minecraftVersion(), loader);
                if (versions.isEmpty()) throw new IOException(Messages.get("loaderUpdate.noVersions"));
                targetVersion = versions.getFirst();
                if (!current.modLoaderVersion().isBlank()
                        && ModLoaderInstaller.compareVersionsDescending(
                                targetVersion, current.modLoaderVersion()) >= 0) {
                    dependencyPreparer.prepare(instance.profileId(), listener);
                    checkCancelled();
                    ensureStopped(instance);
                    return new Result(false, loader.displayName(), current.modLoaderVersion());
                }
            } else {
                if (!instance.minecraftVersion().equals(plan.minecraftVersion())
                        || !instance.loaderName().equals(plan.loaderName())
                        || !current.modLoaderVersion().equals(plan.currentVersion())) {
                    throw new IOException(Messages.get("loaderUpdate.planStale"));
                }
                targetVersion = plan.targetVersion();
            }
            if (targetVersion.equals(current.modLoaderVersion())) {
                dependencyPreparer.prepare(instance.profileId(), listener);
                checkCancelled();
                ensureStopped(instance);
                return new Result(false, loader.displayName(), current.modLoaderVersion());
            }
            Path target = FileUtil.safeVersionJson(versionsDirectory.toFile(), instance.profileId()).toPath();
            FileUtil.validateExistingAncestors(versionsDirectory, target);
            JsonObject original = HttpUtil.readJson(target.toFile());
            ModLoaderInstaller.InstallResult installed;
            try {
                installed = backend.install(instance.minecraftVersion(), loader, targetVersion, listener);
            } catch (IOException | RuntimeException failure) {
                restoreIfChanged(target, original, failure);
                throw failure;
            }
            if (installed.profileId().equals(instance.profileId())) {
                IOException failure = new IOException(Messages.get("loaderUpdate.sameProfile"));
                restoreIfChanged(target, original, failure);
                throw failure;
            }
            if (plan != null && !plan.targetVersion().equals(installed.loaderVersion())) {
                IOException failure = new IOException(Messages.get("loaderUpdate.planMismatch"));
                restoreIfChanged(target, original, failure);
                throw failure;
            }
            checkCancelled();
            ensureStopped(instance);
            VersionMetadata prepared = new VersionRepository(versionsDirectory.toFile()).resolve(installed.profileId());
            if (!instance.minecraftVersion().equals(prepared.minecraftVersion())
                    || !instance.loaderName().equals(prepared.modLoader())) {
                throw new IOException(Messages.get("loaderUpdate.incompatible"));
            }
            dependencyPreparer.prepare(installed.profileId(), listener);
            checkCancelled();
            ensureStopped(instance);
            JsonObject updated = new JsonObject();
            // Launch fields now come from the new loader, avoiding old libraries and arguments.
            original.entrySet().stream().filter(entry -> entry.getKey().startsWith("ecl"))
                    .forEach(entry -> updated.add(entry.getKey(), entry.getValue().deepCopy()));
            updated.addProperty("id", instance.profileId());
            updated.addProperty("inheritsFrom", installed.profileId());
            updated.addProperty("eclMinecraftVersion", instance.minecraftVersion());
            updated.addProperty("eclModLoader", loader.id());
            updated.addProperty("eclModLoaderVersion", installed.loaderVersion());
            HttpUtil.writeJson(target.toFile(), updated);
            return new Result(true, loader.displayName(), installed.loaderVersion());
        }
    }

    private void ensureStopped(ModInstanceContext instance) throws IOException {
        checkCancelled();
        if (instanceRunning.test(instance.instanceId()) || GameProcessMarker.isRunning(instance.gameDirectory())) {
            throw new IOException(Messages.get("loaderUpdate.running"));
        }
    }

    private static void restoreIfChanged(Path target, JsonObject original, Throwable failure) {
        try {
            if (!original.equals(HttpUtil.readJson(target.toFile()))) HttpUtil.writeJson(target.toFile(), original);
        } catch (IOException | RuntimeException restoreFailure) {
            try {
                HttpUtil.writeJson(target.toFile(), original);
            } catch (IOException error) {
                restoreFailure.addSuppressed(error);
                failure.addSuppressed(restoreFailure);
            }
        }
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException(Messages.get("loaderUpdate.cancelled"));
        }
    }

    public record Result(boolean updated, String loaderName, String version) { }

    public record UpdatePlan(String profileId, String minecraftVersion, String loaderName,
                             String currentVersion, String targetVersion, boolean updateAvailable) { }

    /** Boundary for official loader metadata and installation. */
    public interface Backend {
        List<String> listVersions(String minecraftVersion, ModLoaderInstaller.Loader loader) throws IOException;

        ModLoaderInstaller.InstallResult install(String minecraftVersion, ModLoaderInstaller.Loader loader,
                                                String version, ModLoaderInstaller.Listener listener) throws IOException;
    }

    @FunctionalInterface
    public interface ProfileDependencyPreparer {
        void prepare(String profileId, ModLoaderInstaller.Listener listener) throws IOException;
    }
}
