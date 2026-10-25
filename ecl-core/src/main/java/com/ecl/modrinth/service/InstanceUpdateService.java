package com.ecl.modrinth.service;

import com.ecl.ECLConfig;
import com.ecl.game.InstanceLaunchProfileStore;
import com.ecl.game.VersionRepository;
import com.ecl.launch.GameProcessMarker;
import com.ecl.operation.InstanceOperationSession;
import com.ecl.launcher.LoaderUpdateService;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.modrinth.pack.ModpackInstance;
import com.ecl.modrinth.pack.ModpackUpdate;
import com.ecl.modrinth.pack.ModpackUpdateService;
import com.ecl.modrinth.pack.MrpackInstaller;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.Messages;
import com.google.gson.JsonObject;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModUpdate;
import com.ecl.modrinth.model.ReleaseChannel;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** One-click pack upgrades or compatible loader and enabled, recognized mod updates. */
public final class InstanceUpdateService {
    private static final java.time.Duration PLAN_LIFETIME = java.time.Duration.ofMinutes(10);
    private final LoaderUpdateService loaders;
    private final LocalModScanner scanner;
    private final ModUpdateService mods;
    private final Predicate<UUID> instanceRunning;
    private final InstanceUpgradeRecoveryService recoveryPoints;
    private final Executor executor;

    public InstanceUpdateService(LoaderUpdateService loaders, LocalModScanner scanner, ModUpdateService mods,
                                 Predicate<UUID> instanceRunning) {
        this(loaders, scanner, mods, instanceRunning,
                ForkJoinPool.commonPool(), new InstanceUpgradeRecoveryService(
                        ECLConfig.getBaseDir().toPath().resolve("upgrade-recovery")));
    }

    public InstanceUpdateService(LoaderUpdateService loaders, LocalModScanner scanner, ModUpdateService mods,
                                 Predicate<UUID> instanceRunning,
                                 InstanceUpgradeRecoveryService recoveryPoints) {
        this(loaders, scanner, mods, instanceRunning, ForkJoinPool.commonPool(), recoveryPoints);
    }

    public InstanceUpdateService(LoaderUpdateService loaders, LocalModScanner scanner, ModUpdateService mods,
                                 Predicate<UUID> instanceRunning, Executor executor,
                                 InstanceUpgradeRecoveryService recoveryPoints) {
        this.loaders = Objects.requireNonNull(loaders);
        this.scanner = Objects.requireNonNull(scanner);
        this.mods = Objects.requireNonNull(mods);
        this.instanceRunning = Objects.requireNonNull(instanceRunning);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.recoveryPoints = Objects.requireNonNull(recoveryPoints);
    }

    public InstanceUpgradeRecoveryService recoveryPoints() {
        return recoveryPoints;
    }

    public boolean hasUnfinishedRecovery(String profileId) {
        return recoveryPoints.hasUnfinishedOperation(profileId);
    }

    /** Checks versions and compatible mod updates without writing instance files or indexes. */
    public CompletableFuture<Preview> preview(ModInstanceContext instance, ReleaseChannel channel,
                                              Consumer<Progress> listener, Path metadataRoot,
                                              Path gameRoot, ModpackUpdateService packs) {
        Consumer<Progress> progress = listener == null ? ignored -> { } : listener;
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureStopped(instance);
                return new InitialState(fingerprint(instance, metadataRoot, gameRoot),
                        HttpUtil.readJson(FileUtil.safeVersionJson(
                                metadataRoot.toFile(), instance.profileId())));
            } catch (IOException | RuntimeException error) {
                throw new CompletionException(error);
            }
        }, executor).thenCompose(initial -> previewLoaded(instance, channel, progress,
                metadataRoot, gameRoot, packs, initial));
    }

    private CompletableFuture<Preview> previewLoaded(ModInstanceContext instance, ReleaseChannel channel,
                                                     Consumer<Progress> progress, Path metadataRoot,
                                                     Path gameRoot, ModpackUpdateService packs,
                                                     InitialState initial) {
        if (initial.profile().has("eclModpackSource") || initial.profile().has("eclModpackName")) {
            JsonObject profile = initial.profile();
            String project = JsonUtil.getString(profile, "eclModpackProjectId", "");
            String version = JsonUtil.getString(profile, "eclModpackVersionId", "");
            if (!"modrinth".equalsIgnoreCase(JsonUtil.getString(profile, "eclModpackSource", ""))
                    || project.isBlank() || version.isBlank()) {
                return CompletableFuture.failedFuture(new IOException(
                        Messages.get("instanceUpdate.packSourceUnavailable")));
            }
            ModpackInstance pack = new ModpackInstance(instance.instanceId(), instance.profileId(),
                    JsonUtil.getString(profile, "eclModpackName", instance.profileId()),
                    JsonUtil.getString(profile, "eclModpackVersion", version), instance.minecraftVersion(),
                    instance.loaderName(), project, version, instance.gameDirectory());
            progress.accept(new Progress(Stage.PACK_CHECK, pack.name()));
            return packs.checkUpdate(pack, channel).thenApplyAsync(update -> {
                requireFreshUnchecked(instance, metadataRoot, gameRoot, initial.fingerprint());
                return new Preview(instance, Instant.now(), metadataRoot, gameRoot, initial.fingerprint(),
                        null, List.of(), List.of(), Set.of(), List.of(), pack, update);
            }, executor);
        }
        if (!instance.loader().supportsMods()) {
            return CompletableFuture.failedFuture(new IOException(
                    Messages.get("instanceUpdate.modsUnsupported")));
        }
        progress.accept(new Progress(Stage.LOADER_CHECK, ""));
        CompletableFuture<LoaderUpdateService.UpdatePlan> loaderPlan = loaders.previewUpdate(instance);
        progress.accept(new Progress(Stage.SCAN, ""));
        CompletableFuture<LocalModScanResult> scan = scanner.inspect(instance);
        return loaderPlan.thenCombine(scan, ScanState::new).thenCompose(state -> {
            List<InstalledMod> candidates = updateCandidates(state.scan());
            Set<String> eligible = candidates.stream().map(InstalledMod::projectId)
                    .collect(Collectors.toSet());
            Set<String> protectedProjects = state.scan().installedMods().stream()
                    .filter(mod -> !eligible.contains(mod.projectId()))
                    .map(InstalledMod::projectId).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            List<SkippedMod> skipped = skippedMods(state.scan());
            progress.accept(new Progress(Stage.CHECK, ""));
            return mods.checkUpdates(instance, candidates, channel).thenApplyAsync(updates -> {
                requireFreshUnchecked(instance, metadataRoot, gameRoot, initial.fingerprint());
                return new Preview(instance, Instant.now(), metadataRoot, gameRoot, initial.fingerprint(),
                        state.loaderPlan(), updates, state.scan().installedMods(),
                        protectedProjects, skipped, null, null);
            }, executor);
        });
    }

    /** Resolves dependencies for selected roots before the final confirmation and without writes. */
    public CompletableFuture<PreparedExecution> prepare(Preview preview, Set<String> selectedProjects) {
        return prepare(preview, selectedProjects, null, null);
    }

    public CompletableFuture<PreparedExecution> prepare(Preview preview, Set<String> selectedProjects,
                                                        ModpackUpdateService packs,
                                                        Consumer<Progress> listener) {
        Objects.requireNonNull(preview, "preview");
        Consumer<Progress> progress = listener == null ? ignored -> { } : listener;
        Set<String> selected = selectedProjects == null ? Set.of() : Set.copyOf(selectedProjects);
        return CompletableFuture.runAsync(() -> requireFreshUnchecked(preview), executor)
                .thenCompose(ignored -> prepareFresh(preview, selected, packs, progress));
    }

    private CompletableFuture<PreparedExecution> prepareFresh(Preview preview, Set<String> selected,
                                                               ModpackUpdateService packs,
                                                               Consumer<Progress> progress) {
        if (preview.packProfile()) {
            if (preview.packUpdate() == null || packs == null) {
                return CompletableFuture.failedFuture(new IOException(
                        Messages.get("instanceUpdate.packPlanExpired")));
            }
            progress.accept(new Progress(Stage.PREPARE, preview.packUpdate().availableVersion().versionNumber()));
            return packs.prepareUpdate(preview.packUpdate(), preview.gameRoot(), new MrpackInstaller.Listener() {
                @Override public void onStatus(String message) {
                    progress.accept(new Progress(Stage.PREPARE, message));
                }
            }).thenApply(prepared -> {
                try {
                    requireAcceptedFresh(preview);
                    return new PreparedExecution(preview, List.of(), prepared);
                } catch (IOException | RuntimeException stale) {
                    prepared.closeQuietly();
                    throw new CompletionException(stale);
                }
            });
        }
        Map<String, ModUpdate> updatesByProject = preview.modUpdates().stream()
                .collect(Collectors.toMap(update -> update.installedMod().projectId(), update -> update));
        if (!updatesByProject.keySet().containsAll(selected)) {
            return CompletableFuture.failedFuture(new IOException(
                    Messages.get("instanceUpdate.selectionExpired")));
        }
        List<CompletableFuture<ModUpdateService.PreparedModUpdate>> requests = selected.stream()
                .sorted().map(project -> mods.prepareUpdate(updatesByProject.get(project),
                        preview.protectedProjects())).toList();
        return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .thenApplyAsync(ignored -> {
            List<ModUpdateService.PreparedModUpdate> prepared = requests.stream()
                    .map(CompletableFuture::join)
                    .map(update -> new ModUpdateService.PreparedModUpdate(update.update(),
                            update.installationPlan().withReplacements(preview.installedMods().stream()
                                    .filter(mod -> !preview.protectedProjects().contains(mod.projectId()))
                                    .filter(mod -> Files.isRegularFile(preview.instance().gameDirectory()
                                            .resolve(mod.relativePath())))
                                    .toList())))
                    .toList();
            validatePreparedPlans(prepared);
            requireAcceptedFreshUnchecked(preview);
            return new PreparedExecution(preview, prepared, null);
        }, executor);
    }

    /** Applies the exact versions from the two confirmed plans. */
    public CompletableFuture<Result> execute(PreparedExecution execution,
                                             Consumer<Progress> listener,
                                             ModpackUpdateService packs) {
        Objects.requireNonNull(execution, "execution");
        Preview preview = execution.preview();
        Consumer<Progress> progress = listener == null ? ignored -> { } : listener;
        if (preview.packProfile()) {
            if (execution.packUpdate() == null) {
                execution.closeQuietly();
                return CompletableFuture.completedFuture(new Result(null, 0, 0,
                        List.of(), List.of(), ""));
            }
        }
        CompletableFuture<InstanceUpgradeRecoveryService.Draft> snapshot;
        try {
            snapshot = CompletableFuture.supplyAsync(() -> {
                InstanceOperationSession session = null;
                try {
                    session = recoveryPoints.tryAcquireSession(preview.instance());
                    if (session == null) throw new IOException(Messages.get("instanceRecovery.instanceBusy"));
                    requireAcceptedFresh(preview);
                    ensureStopped(preview.instance());
                    return recoveryPoints.begin(execution, session);
                } catch (Exception error) {
                    if (session != null) {
                        try { session.close(); } catch (IOException closeFailure) { error.addSuppressed(closeFailure); }
                    }
                    execution.closeQuietly();
                    throw new CompletionException(error);
                }
            }, executor);
        } catch (RuntimeException failure) {
            execution.closeQuietly();
            return CompletableFuture.failedFuture(failure);
        }
        return snapshot.thenCompose(restorePoint -> executeWithRestorePoint(execution, progress, packs, restorePoint)).copy();
    }

    private CompletableFuture<Result> executeWithRestorePoint(
            PreparedExecution execution, Consumer<Progress> progress,
            ModpackUpdateService packs, InstanceUpgradeRecoveryService.Draft restorePoint) {
        Preview preview = execution.preview();
        CompletableFuture<Result> operation;
        try {
            if (preview.packProfile()) {
                progress.accept(new Progress(Stage.PACK,
                        preview.packUpdate().availableVersion().versionNumber()));
                List<String> warnings = new ArrayList<>();
                operation = packs.applyPreparedUpdate(execution.packUpdate(), preview.gameRoot(),
                        new MrpackInstaller.Listener() {
                            @Override public void onStatus(String message) {
                                progress.accept(new Progress(Stage.PACK, message));
                            }

                            @Override public void onWarning(String message) {
                                warnings.add(message);
                                onStatus(message);
                            }
                        }, restorePoint.session()).thenApply(result -> new Result(null, 0, 0, List.of(),
                                List.copyOf(warnings), result.version()));
            } else {
                LoaderUpdateService.UpdatePlan loaderPlan = preview.loaderPlan();
                CompletableFuture<LoaderUpdateService.Result> loader = loaders.update(
                        preview.instance(), loaderPlan, new ModLoaderInstaller.Listener() {
                            @Override public void onStatus(String message) {
                                progress.accept(new Progress(Stage.LOADER, message));
                            }
                        }, restorePoint.session());
                operation = loader.thenCompose(loaderResult ->
                        applyPreparedMods(execution, progress, loaderResult, restorePoint.session()));
            }
        } catch (RuntimeException failure) {
            operation = CompletableFuture.failedFuture(failure);
        }
        return operation.handle((result, error) -> {
            Throwable failure = error == null ? null : unwrap(error);
            try {
                recoveryPoints.complete(restorePoint);
            } catch (IOException | RuntimeException recoveryFailure) {
                if (failure == null) failure = recoveryFailure;
                else failure.addSuppressed(recoveryFailure);
            }
            try {
                closeExecution(execution, restorePoint.session());
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
            if (failure != null) throw new CompletionException(failure);
            return result;
        });
    }

    private static void closeExecution(PreparedExecution execution, InstanceOperationSession session) {
        try {
            session.close();
        } catch (IOException failure) {
            throw new CompletionException(failure);
        } finally {
            execution.closeQuietly();
        }
    }

    private CompletableFuture<Result> applyPreparedMods(PreparedExecution execution,
                                                         Consumer<Progress> progress,
                                                         LoaderUpdateService.Result loader, InstanceOperationSession session) {
        List<Failure> failures = new ArrayList<>();
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (ModUpdateService.PreparedModUpdate prepared : execution.modUpdates()) {
            chain = chain.thenCompose(succeeded -> {
                ensureStoppedUnchecked(execution.preview().instance());
                String name = prepared.update().installedMod().displayName();
                progress.accept(new Progress(Stage.MOD, name));
                return mods.applyPreparedUpdate(prepared, session).handle((result, error) -> {
                    if (error != null) {
                        failures.add(new Failure(name, unwrap(error)));
                        return succeeded;
                    }
                    return succeeded + 1;
                });
            });
        }
        return chain.thenApply(succeeded -> new Result(loader, succeeded,
                skippedCount(execution), List.copyOf(failures),
                skippedSummaries(execution), null));
    }

    /**
     * Legacy one-shot pack/loader/mod update.
     *
     * @deprecated retaining this overload only for the launcher runtime-validation tool
     *         ({@code com.ecl.ui.LauncherRuntimeValidation}); it performs the whole upgrade in a
     *         single step and cannot show a preview or roll back through the recovery journal.
     *         Production code must use {@link #preview}/{@link #prepare}/{@link #execute}.
     */
    @Deprecated
    public CompletableFuture<Result> update(ModInstanceContext instance, ReleaseChannel channel, Consumer<Progress> listener,
                                            Path metadataRoot, Path gameRoot, ModpackUpdateService packs) {
        Consumer<Progress> progress = listener == null ? ignored -> { } : listener;
        try {
            ensureStopped(instance);
            JsonObject profile = HttpUtil.readJson(FileUtil.safeVersionJson(metadataRoot.toFile(), instance.profileId()));
            if (!profile.has("eclModpackSource") && !profile.has("eclModpackName")) {
                return update(instance, channel, listener);
            }
            String project = JsonUtil.getString(profile, "eclModpackProjectId", "");
            String version = JsonUtil.getString(profile, "eclModpackVersionId", "");
            if (!"modrinth".equalsIgnoreCase(JsonUtil.getString(profile, "eclModpackSource", ""))
                    || project.isBlank() || version.isBlank()) {
                throw new IOException(Messages.get("instanceUpdate.packSourceUnavailable"));
            }
            ModpackInstance pack = new ModpackInstance(instance.instanceId(), instance.profileId(),
                    JsonUtil.getString(profile, "eclModpackName", instance.profileId()),
                    JsonUtil.getString(profile, "eclModpackVersion", version), instance.minecraftVersion(),
                    instance.loaderName(), project, version, instance.gameDirectory());
            progress.accept(new Progress(Stage.PACK_CHECK, pack.name()));
            return packs.checkUpdate(pack, channel).thenCompose(update -> {
                ensureStoppedUnchecked(instance);
                if (update == null) {
                    return CompletableFuture.completedFuture(new Result(null, 0, 0, List.of(), List.of(), ""));
                }
                progress.accept(new Progress(Stage.PACK, pack.name()));
                List<String> warnings = new ArrayList<>();
                return packs.applyUpdate(update, gameRoot, new MrpackInstaller.Listener() {
                    @Override public void onStatus(String message) {
                        progress.accept(new Progress(Stage.PACK, message));
                    }

                    @Override public void onWarning(String message) {
                        warnings.add(message);
                        onStatus(message);
                    }
                }).thenApply(result -> new Result(null, 0, 0, List.of(), List.copyOf(warnings), result.version()));
            });
        } catch (IOException | IllegalArgumentException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    /**
     * Legacy one-shot loader/mod update.
     *
     * @deprecated retaining this overload only for the launcher runtime-validation tool
     *         ({@code com.ecl.ui.LauncherRuntimeValidation}); production code must use
     *         {@link #preview}/{@link #prepare}/{@link #execute} so the user can review the plan
     *         and the upgrade keeps a verified restore point.
     */
    @Deprecated
    public CompletableFuture<Result> update(ModInstanceContext instance, ReleaseChannel channel, Consumer<Progress> listener) {
        Consumer<Progress> progress = listener == null ? ignored -> { } : listener;
        try {
            ensureStopped(instance);
            if (!instance.loader().supportsMods()) {
                throw new IOException(Messages.get("instanceUpdate.modsUnsupported"));
            }
        } catch (IOException error) {
            return CompletableFuture.failedFuture(error);
        }
        progress.accept(new Progress(Stage.LOADER, ""));
        return loaders.update(instance, new ModLoaderInstaller.Listener() {
            @Override public void onStatus(String message) {
                progress.accept(new Progress(Stage.LOADER, ""));
            }
        }).thenCompose(loader -> {
            ensureStoppedUnchecked(instance);
            progress.accept(new Progress(Stage.SCAN, ""));
            return scanner.scan(instance).thenCompose(scan -> updateMods(instance, channel, progress, loader, scan));
        });
    }

    private CompletableFuture<Result> updateMods(ModInstanceContext instance, ReleaseChannel channel,
                                                  Consumer<Progress> progress, LoaderUpdateService.Result loader,
                                                  LocalModScanResult scan) {
        List<InstalledMod> candidates = scan.installedMods().stream()
                .filter(InstalledMod::enabled)
                .filter(mod -> mod.projectId() != null && !mod.projectId().isBlank() && !mod.projectId().startsWith("local:"))
                .filter(mod -> !scan.duplicateProjects().contains(mod.projectId()))
                .filter(mod -> scan.items().stream().anyMatch(item -> item.recognized() && !item.damaged()
                        && mod.equals(item.installedMod()) && Files.isRegularFile(item.file())))
                .toList();
        Set<String> protectedProjects = scan.installedMods().stream().filter(mod -> !candidates.contains(mod))
                .map(InstalledMod::projectId).filter(Objects::nonNull).collect(Collectors.toSet());
        int skipped = scan.installedMods().size() - candidates.size();
        progress.accept(new Progress(Stage.CHECK, ""));
        return mods.checkUpdates(instance, candidates, channel).thenCompose(updates -> {
            List<Failure> failures = new ArrayList<>();
            CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
            for (ModUpdate update : updates) {
                chain = chain.thenCompose(succeeded -> {
                    ensureStoppedUnchecked(instance);
                    progress.accept(new Progress(Stage.MOD, update.installedMod().displayName()));
                    return mods.applyUpdate(update, protectedProjects).handle((result, error) -> {
                        if (error != null) {
                            failures.add(new Failure(update.installedMod().displayName(), unwrap(error)));
                            return succeeded;
                        }
                        return succeeded + 1;
                    });
                });
            }
            return chain.thenApply(succeeded -> new Result(loader, succeeded, skipped,
                    List.copyOf(failures), scan.warnings(), null));
        });
    }

    private static List<InstalledMod> updateCandidates(LocalModScanResult scan) {
        return scan.installedMods().stream()
                .filter(InstalledMod::enabled)
                .filter(mod -> mod.projectId() != null && !mod.projectId().isBlank()
                        && !mod.projectId().startsWith("local:"))
                .filter(mod -> !scan.duplicateProjects().contains(mod.projectId()))
                .filter(mod -> scan.items().stream().anyMatch(item -> item.recognized() && !item.damaged()
                        && mod.equals(item.installedMod()) && Files.isRegularFile(item.file())))
                .toList();
    }

    private static List<SkippedMod> skippedMods(LocalModScanResult scan) {
        List<SkippedMod> skipped = new ArrayList<>();
        for (LocalModScanItem item : scan.items()) {
            InstalledMod mod = item.installedMod();
            if (item.damaged()) {
                skipped.add(new SkippedMod(item.file().getFileName().toString(), "instanceUpdate.skip.damaged"));
            } else if (!Files.isRegularFile(item.file())) {
                skipped.add(new SkippedMod(item.file().getFileName().toString(), "instanceUpdate.skip.missing"));
            } else if (mod == null || mod.projectId() == null || mod.projectId().isBlank()
                    || mod.projectId().startsWith("local:")) {
                skipped.add(new SkippedMod(item.file().getFileName().toString(), "instanceUpdate.skip.unknown"));
            } else if (!mod.enabled()) {
                skipped.add(new SkippedMod(mod.displayName(), "instanceUpdate.skip.disabled"));
            } else if (scan.duplicateProjects().contains(mod.projectId())) {
                skipped.add(new SkippedMod(mod.displayName(), "instanceUpdate.skip.duplicate"));
            } else if (!item.recognized()) {
                skipped.add(new SkippedMod(mod.displayName(), "instanceUpdate.skip.unknown"));
            }
        }
        return List.copyOf(skipped);
    }

    private static void validatePreparedPlans(List<ModUpdateService.PreparedModUpdate> plans) {
        Map<String, String> versions = new java.util.HashMap<>();
        Map<Path, String> targets = new java.util.HashMap<>();
        for (ModUpdateService.PreparedModUpdate prepared : plans) {
            prepared.installationPlan().files().forEach(file -> {
                String project = file.version().projectId();
                String version = file.version().id();
                String previousVersion = versions.putIfAbsent(project, version);
                if (previousVersion != null && !previousVersion.equals(version)) {
                    throw new CompletionException(new IOException(
                            Messages.format("instanceUpdate.dependencyConflict", project)));
                }
                Path target = file.targetPath().toAbsolutePath().normalize();
                String previousProject = targets.putIfAbsent(target, project);
                if (previousProject != null && !previousProject.equals(project)) {
                    throw new CompletionException(new IOException(
                            Messages.format("instanceUpdate.fileConflict", target.getFileName())));
                }
            });
        }
    }

    private void requireFresh(ModInstanceContext instance, Path metadataRoot, Path gameRoot,
                              String expectedFingerprint) throws IOException {
        if (!fingerprint(instance, metadataRoot, gameRoot).equals(expectedFingerprint)) {
            throw new IOException(Messages.get("instanceUpdate.planStale"));
        }
        ensureStopped(instance);
    }

    private void requireFresh(Preview preview) throws IOException {
        if (Instant.now().isAfter(preview.createdAt().plus(PLAN_LIFETIME))) {
            throw new IOException(Messages.get("instanceUpdate.planExpired"));
        }
        requireAcceptedFresh(preview);
    }

    private void requireAcceptedFresh(Preview preview) throws IOException {
        requireFresh(preview.instance(), preview.metadataRoot(), preview.gameRoot(), preview.fingerprint());
    }

    private void requireAcceptedFreshUnchecked(Preview preview) {
        try {
            requireAcceptedFresh(preview);
        } catch (IOException stale) {
            throw new CompletionException(stale);
        }
    }

    private void requireFreshUnchecked(ModInstanceContext instance, Path metadataRoot, Path gameRoot,
                                       String fingerprint) {
        try {
            requireFresh(instance, metadataRoot, gameRoot, fingerprint);
        } catch (IOException stale) {
            throw new CompletionException(stale);
        }
    }

    private void requireFreshUnchecked(Preview preview) {
        try {
            requireFresh(preview);
        } catch (IOException stale) {
            throw new CompletionException(stale);
        }
    }

    private static String fingerprint(ModInstanceContext instance, Path metadataRoot, Path gameRoot)
            throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
        Path versions = metadataRoot.toAbsolutePath().normalize();
        Path profileFile = FileUtil.safeVersionJson(versions.toFile(), instance.profileId()).toPath();
        FileUtil.validateExistingAncestors(versions, profileFile);
        addFileState(digest, "profile", profileFile);
        String resolved = new VersionRepository(versions.toFile()).resolveRaw(instance.profileId()).toString();
        digest.update(resolved.getBytes(StandardCharsets.UTF_8));

        Path instanceRoot = gameRoot.toAbsolutePath().normalize().resolve("versions")
                .resolve(instance.profileId()).normalize();
        Path profileRoot = gameRoot.toAbsolutePath().normalize().resolve("versions").normalize();
        if (!instanceRoot.startsWith(profileRoot)) throw new IOException("Unsafe instance path");
        Path launchProfile = instanceRoot.resolve(InstanceLaunchProfileStore.PROFILE_RELATIVE_PATH);
        FileUtil.validateExistingAncestors(instanceRoot, launchProfile);
        addFileState(digest, "launch-profile", launchProfile);
        addDirectoryState(digest, instance.gameDirectory(), "mods");
        addDirectoryState(digest, instance.gameDirectory(), "disabled-mods");
        addFileState(digest, "installed-mod-index", instance.gameDirectory().resolve("launcher-mods.json"));

        Path manifest = instance.gameDirectory().resolve(".ecl-pack-manifest.json");
        FileUtil.validateExistingAncestors(instance.gameDirectory(), manifest);
        addFileState(digest, "pack-manifest", manifest);
        if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            JsonObject json = HttpUtil.readJson(manifest.toFile());
            if (json.has("files") && json.get("files").isJsonArray()) {
                for (var element : json.getAsJsonArray("files")) {
                    if (!element.isJsonObject() || !element.getAsJsonObject().has("path")) {
                        throw new IOException("Invalid managed pack manifest entry");
                    }
                    String relative = element.getAsJsonObject().get("path").getAsString();
                    File target = FileUtil.safeResolveUnder(instance.gameDirectory().toFile(), relative);
                    addFileState(digest, "pack:" + relative, target.toPath());
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void addDirectoryState(MessageDigest digest, Path root, String name)
            throws IOException {
        Path directory = root.resolve(name).normalize();
        FileUtil.validateExistingAncestors(root, directory);
        addDigest(digest, "directory:" + name);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            for (Path file : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted().toList()) {
                FileUtil.validateExistingAncestors(root, file);
                addFileState(digest, name + "/" + root.relativize(file), file);
            }
        }
    }

    private static void addFileState(MessageDigest digest, String name, Path file) throws IOException {
        addDigest(digest, name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            addDigest(digest, "missing");
            return;
        }
        addDigest(digest, "file");
        addDigest(digest, "size:" + Files.size(file));
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
    }

    private static void addDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static int skippedCount(PreparedExecution execution) {
        Set<String> includedProjects = includedProjects(execution);
        long skippedUpdates = execution.preview().modUpdates().stream()
                .filter(update -> !includedProjects.contains(update.installedMod().projectId())).count();
        return execution.preview().skippedMods().size() + (int) skippedUpdates;
    }

    private static List<String> skippedSummaries(PreparedExecution execution) {
        List<String> summaries = new ArrayList<>();
        execution.preview().skippedMods().forEach(skipped -> summaries.add(
                Messages.format("instanceUpdate.skipped", skipped.name(), Messages.get(skipped.reasonKey()))));
        Set<String> includedProjects = includedProjects(execution);
        execution.preview().modUpdates().stream()
                .filter(update -> !includedProjects.contains(update.installedMod().projectId()))
                .forEach(update -> summaries.add(Messages.format("instanceUpdate.skipped",
                        update.installedMod().displayName(), Messages.get("instanceUpdate.skip.user"))));
        return List.copyOf(summaries);
    }

    private static Set<String> includedProjects(PreparedExecution execution) {
        return execution.modUpdates().stream()
                .flatMap(prepared -> prepared.installationPlan().files().stream())
                .map(file -> file.version().projectId()).collect(Collectors.toSet());
    }

    private void ensureStoppedUnchecked(ModInstanceContext instance) {
        try {
            ensureStopped(instance);
        } catch (IOException error) {
            throw new CompletionException(error);
        }
    }

    private void ensureStopped(ModInstanceContext instance) throws IOException {
        if (instanceRunning.test(instance.instanceId()) || GameProcessMarker.isRunning(instance.gameDirectory())) {
            throw new IOException(Messages.get("instanceUpdate.instanceRunning"));
        }
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }

    private record InitialState(String fingerprint, JsonObject profile) { }

    private record ScanState(LoaderUpdateService.UpdatePlan loaderPlan, LocalModScanResult scan) { }

    public record Preview(ModInstanceContext instance, Instant createdAt, Path metadataRoot,
                          Path gameRoot, String fingerprint,
                          LoaderUpdateService.UpdatePlan loaderPlan, List<ModUpdate> modUpdates,
                          List<InstalledMod> installedMods,
                          Set<String> protectedProjects, List<SkippedMod> skippedMods,
                          ModpackInstance packInstance, ModpackUpdate packUpdate) {
        public Preview {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(createdAt, "createdAt");
            metadataRoot = Objects.requireNonNull(metadataRoot, "metadataRoot").toAbsolutePath().normalize();
            gameRoot = Objects.requireNonNull(gameRoot, "gameRoot").toAbsolutePath().normalize();
            Objects.requireNonNull(fingerprint, "fingerprint");
            modUpdates = modUpdates == null ? List.of() : List.copyOf(modUpdates);
            installedMods = installedMods == null ? List.of() : List.copyOf(installedMods);
            protectedProjects = protectedProjects == null ? Set.of() : Set.copyOf(protectedProjects);
            skippedMods = skippedMods == null ? List.of() : List.copyOf(skippedMods);
        }

        public boolean packProfile() {
            return packInstance != null;
        }

        public boolean hasUpdates() {
            return packProfile() ? packUpdate != null
                    : loaderPlan != null && loaderPlan.updateAvailable() || !modUpdates.isEmpty();
        }
    }

    public record PreparedExecution(Preview preview,
                                    List<ModUpdateService.PreparedModUpdate> modUpdates,
                                    ModpackUpdateService.PreparedUpdate packUpdate)
            implements AutoCloseable {
        public PreparedExecution(Preview preview,
                                 List<ModUpdateService.PreparedModUpdate> modUpdates) {
            this(preview, modUpdates, null);
        }

        public PreparedExecution {
            Objects.requireNonNull(preview, "preview");
            modUpdates = modUpdates == null ? List.of() : List.copyOf(modUpdates);
        }

        @Override
        public void close() throws IOException {
            if (packUpdate != null) packUpdate.close();
        }

        public void closeQuietly() {
            if (packUpdate != null) packUpdate.closeQuietly();
        }
    }

    public record SkippedMod(String name, String reasonKey) { }

    public enum Stage { LOADER_CHECK, LOADER, SCAN, CHECK, PREPARE, MOD, PACK_CHECK, PACK }
    public record Progress(Stage stage, String detail) { }
    public record Failure(String name, Throwable cause) { }
    public record Result(LoaderUpdateService.Result loader, int updatedMods, int skippedMods,
                         List<Failure> failures, List<String> warnings, String packVersion) { }
}
