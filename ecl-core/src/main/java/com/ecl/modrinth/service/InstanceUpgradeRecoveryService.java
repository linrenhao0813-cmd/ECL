package com.ecl.modrinth.service;

import com.ecl.ECLConfig;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.pack.PackUpdateTransaction;
import com.ecl.modrinth.transaction.FileModInstallationTransaction;
import com.ecl.modrinth.transaction.PlannedModFile;
import com.ecl.operation.InstanceOperationCoordinator;
import com.ecl.operation.InstanceOperationSession;
import com.ecl.launch.GameProcessMarker;
import com.ecl.util.FileUtil;
import com.ecl.util.FileLockLease;
import com.ecl.util.HttpUtil;
import com.ecl.util.Messages;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Keeps one verified restore point for the last real update of each instance. */
public final class InstanceUpgradeRecoveryService {
    private static final int SCHEMA_VERSION = 1;
    private static final String MANIFEST_FILE = "recovery.json";
    private final Path root;
    private final InstanceOperationCoordinator operations;
    private final Predicate<UUID> instanceRunning;
    private final Checkpoint checkpoint;

    @FunctionalInterface
    interface Checkpoint {
        void reached(String phase) throws IOException;
    }

    public InstanceUpgradeRecoveryService() {
        this(ECLConfig.getBaseDir().toPath().resolve("upgrade-recovery"), null, ignored -> false);
    }

    public InstanceUpgradeRecoveryService(Path root) {
        this(root, null, ignored -> false);
    }

    public InstanceUpgradeRecoveryService(Path root, InstanceOperationCoordinator operations,
                                          Predicate<UUID> instanceRunning) {
        this(root, operations, instanceRunning, ignored -> { });
    }

    InstanceUpgradeRecoveryService(Path root, InstanceOperationCoordinator operations,
                                    Predicate<UUID> instanceRunning, Checkpoint checkpoint) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.operations = operations == null ? new InstanceOperationCoordinator() : operations;
        this.instanceRunning = Objects.requireNonNull(instanceRunning, "instanceRunning");
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
    }

    public InstanceOperationSession tryAcquireSession(ModInstanceContext instance) throws IOException {
        return operations.tryAcquireSession(instance.instanceId(), instance.gameDirectory());
    }

    public Draft begin(InstanceUpdateService.PreparedExecution execution) throws IOException {
        ModInstanceContext instance = execution.preview().instance();
        InstanceOperationSession session = operations.tryAcquireSession(instance.instanceId(), instance.gameDirectory());
        if (session == null) throw new IOException(Messages.get("instanceRecovery.instanceBusy"));
        try {
            return beginOwned(execution, session, session);
        } catch (IOException | RuntimeException failure) {
            try { session.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    public Draft begin(InstanceUpdateService.PreparedExecution execution, InstanceOperationSession session) throws IOException {
        ModInstanceContext instance = execution.preview().instance();
        InstanceOperationSession.Borrow borrow = session.borrow(instance.instanceId(), instance.gameDirectory());
        try {
            return beginOwned(execution, session, borrow);
        } catch (IOException | RuntimeException failure) {
            try { borrow.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private Draft beginOwned(InstanceUpdateService.PreparedExecution execution, InstanceOperationSession session,
                             AutoCloseable ownership) throws IOException {
        ModInstanceContext instance = execution.preview().instance();
        if (instanceRunning.test(instance.instanceId()) || GameProcessMarker.isRunning(instance.gameDirectory())) {
            throw new IOException(Messages.get("instanceRecovery.instanceBusy"));
        }
        FileLockLease lease = acquireRecoveryLock(instance.profileId());
        try {
            return beginLocked(execution, lease, session, ownership);
        } catch (IOException | RuntimeException failure) {
            try { lease.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private Draft beginLocked(InstanceUpdateService.PreparedExecution execution, FileLockLease lease,
                              InstanceOperationSession session, AutoCloseable ownership) throws IOException {
        InstanceUpdateService.Preview preview = execution.preview();
        String profileId = preview.instance().profileId();
        FileUtil.requireSafeVersionId(profileId);
        Path profileDirectory = profileDirectory(profileId);
        recoverSwap(profileDirectory, roots(profileId, preview.instance(), preview.metadataRoot(), preview.gameRoot()));
        cleanupUnstartedStaging(profileDirectory);
        if (Files.exists(profileDirectory.resolve("pending"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(Messages.get("instanceRecovery.interrupted"));
        }
        Files.createDirectories(profileDirectory);
        Path pending = profileDirectory.resolve("pending");
        Path staging = profileDirectory.resolve("staging-" + UUID.randomUUID());
        Files.createDirectory(staging);
        try {
            Path payload = Files.createDirectory(staging.resolve("payload"));
            List<Ref> references = collectReferences(execution);
            List<Entry> entries = snapshotBefore(references, execution, payload);
            Manifest manifest = new Manifest(profileId, preview.instance().instanceId().toString(),
                    preview.instance().gameDirectory().toAbsolutePath().normalize().toString(),
                    preview.metadataRoot().toAbsolutePath().normalize().toString(),
                    preview.gameRoot().toAbsolutePath().normalize().toString(),
                    Instant.now().toString(), preview.packProfile(), State.PREPARING,
                    false, entries);
            writeManifest(staging, manifest);
            forceDirectory(staging);
            moveDirectory(staging, pending);
            return new Draft(pending, execution, lease, session, ownership);
        } catch (IOException | RuntimeException failure) {
            cleanupDirectory(staging);
            throw failure;
        }
    }

    /** Called after either success or failure; only publishes a point if files actually changed. */
    public void complete(Draft draft) throws IOException {
        try (draft) {
            InstanceUpdateService.Preview preview = draft.execution().preview();
            finalizePending(draft.directory(), roots(preview.instance().profileId(),
                    preview.instance(), preview.metadataRoot(), preview.gameRoot()));
        }
    }

    /** Completes an interrupted update snapshot and reports the latest safe restore point. */
    public RestorePoint inspect(String profileId, ModInstanceContext instance,
                                Path metadataRoot, Path gameRoot) throws Exception {
        PreviewRoots roots = roots(profileId, instance, metadataRoot, gameRoot);
        try (InstanceOperationSession session = acquireSession(instance)) {
            recoverTransactions(instance, roots, metadataRoot, session);
            try (FileLockLease recovery = acquireRecoveryLock(profileId)) {
                return inspectLocked(profileId, roots);
            }
        }
    }

    private InstanceOperationSession acquireSession(ModInstanceContext instance) throws IOException {
        InstanceOperationSession session = operations.tryAcquireSession(instance.instanceId(), instance.gameDirectory());
        if (session == null) throw new IOException(Messages.get("instanceRecovery.instanceBusy"));
        try {
            if (instanceRunning.test(instance.instanceId()) || GameProcessMarker.isRunning(instance.gameDirectory())) {
                throw new IOException(Messages.get("instanceRecovery.instanceBusy"));
            }
            return session;
        } catch (IOException | RuntimeException failure) {
            try { session.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private void recoverTransactions(ModInstanceContext instance, PreviewRoots roots, Path metadataRoot,
                                     InstanceOperationSession session) throws IOException {
        Path profile = FileUtil.safeVersionJson(metadataRoot.toFile(), roots.profileId()).toPath();
        FileModInstallationTransaction.recoverIncompleteTransactions(instance.gameDirectory(), session);
        PackUpdateTransaction.recoverIncompleteTransactions(roots.instanceRoot(), profile, session);
        if (PackUpdateTransaction.hasIncompleteTransactions(roots.instanceRoot())) {
            throw new IOException(Messages.get("instanceRecovery.interrupted"));
        }
    }

    private Path prepareRecovery(String profileId, PreviewRoots roots) throws IOException {
        Path directory = profileDirectory(profileId);
        Path pending = directory.resolve("pending");
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
            Manifest manifest = readManifest(pending, roots);
            verifyPayloads(pending, manifest);
        }
        recoverSwap(directory, roots);
        cleanupUnstartedStaging(directory);
        if (Files.isDirectory(pending, LinkOption.NOFOLLOW_LINKS)) finalizePending(pending, roots);
        return directory.resolve("active");
    }

    private RestorePoint inspectLocked(String profileId, PreviewRoots roots) throws IOException {
        Path active = prepareRecovery(profileId, roots);
        if (!Files.isDirectory(active, LinkOption.NOFOLLOW_LINKS)) return null;
        Manifest manifest = readManifest(active, roots);
        verifyPayloads(active, manifest);
        List<String> conflicts = manifest.state() == State.ACTIVE
                ? conflicts(manifest, roots) : List.of();
        return new RestorePoint(profileId, Instant.parse(manifest.createdAt()), manifest.state().name(),
                manifest.entries().stream().map(entry -> displayPath(entry.ref())).toList(),
                conflicts, manifest.pack(), manifest.state() == State.RESTORING);
    }

    /** Restores an active point; conflicts must be explicitly confirmed by their exact paths. */
    public RestoreResult restore(String profileId, ModInstanceContext instance,
                                 Path metadataRoot, Path gameRoot,
                                 Set<String> confirmedConflicts)
            throws Exception {
        PreviewRoots roots = roots(profileId, instance, metadataRoot, gameRoot);
        try (InstanceOperationSession session = acquireSession(instance)) {
            recoverTransactions(instance, roots, metadataRoot, session);
            try (FileLockLease recovery = acquireRecoveryLock(profileId)) {
                Path active = prepareRecovery(profileId, roots);
                Manifest manifest = readManifest(active, roots);
                verifyPayloads(active, manifest);
                if (manifest.state() == State.RESTORED) {
                    throw new IOException(Messages.get("instanceRecovery.alreadyRestored"));
                }
                if (manifest.state() == State.ACTIVE) {
                    List<String> currentConflicts = conflicts(manifest, roots);
                    Set<String> approved = confirmedConflicts == null ? Set.of() : Set.copyOf(confirmedConflicts);
                    if (!approved.containsAll(currentConflicts)) {
                        return new RestoreResult(false, currentConflicts, manifest.createdAt());
                    }
                    manifest = manifest.with(State.RESTORING, true, manifest.entries());
                    writeManifest(active, manifest);
                } else if (manifest.state() != State.RESTORING
                        || !manifest.conflictsConfirmed()) {
                    throw new IOException(Messages.get("instanceRecovery.invalidState"));
                }
                for (Entry entry : manifest.entries()) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new java.util.concurrent.CancellationException("Rollback cancelled");
                    }
                    restoreEntry(active, entry, roots, manifest.pack());
                    manifest = manifest.markRestored(entry.key());
                    writeManifest(active, manifest);
                }
                manifest = manifest.with(State.RESTORED, true, manifest.entries());
                writeManifest(active, manifest);
                return new RestoreResult(true, List.of(), manifest.createdAt());
            }
        }
    }

    /** Prevents launching an instance while an update or rollback was interrupted. */
    public boolean hasUnfinishedOperation(String profileId) {
        try {
            FileUtil.requireSafeVersionId(profileId);
            Path directory = profileDirectory(profileId);
            if (Files.exists(directory.resolve("pending"), LinkOption.NOFOLLOW_LINKS)) return true;
            Path active = directory.resolve("active");
            return Files.isDirectory(active, LinkOption.NOFOLLOW_LINKS)
                    && readState(active) == State.RESTORING;
        } catch (IOException | RuntimeException invalid) {
            return true;
        }
    }

    private void finalizePending(Path pending, PreviewRoots roots) throws IOException {
        Manifest manifest = readManifest(pending, roots);
        verifyPayloads(pending, manifest);
        if (manifest.state() == State.ACTIVE) {
            publish(pending, profileDirectory(roots.profileId()), roots);
            return;
        }
        if (manifest.state() != State.PREPARING) throw new IOException(Messages.get("instanceRecovery.invalidState"));
        Map<String, Entry> before = new LinkedHashMap<>();
        for (Entry entry : manifest.entries()) before.put(entry.key(), entry);
        if (manifest.pack()) {
            for (Ref ref : readPackReferences(roots.instanceRoot())) {
                before.computeIfAbsent(ref.key(), ignored -> Entry.absent(ref));
            }
        }
        List<Entry> changed = new ArrayList<>();
        for (Entry entry : before.values()) {
            FileState after = state(resolve(entry.ref(), roots, manifest.pack()), rootFor(entry.ref(), roots));
            if (!same(entry.beforeExists(), entry.beforeSha256(), after.exists(), after.sha256())) {
                changed.add(entry.after(after.exists(), after.sha256()));
            }
        }
        if (changed.isEmpty()) {
            cleanupDirectory(pending);
            return;
        }
        writeManifest(pending, manifest.with(State.ACTIVE, false, changed));
        publish(pending, profileDirectory(roots.profileId()), roots);
    }

    private List<Ref> collectReferences(InstanceUpdateService.PreparedExecution execution)
            throws IOException {
        InstanceUpdateService.Preview preview = execution.preview();
        LinkedHashMap<String, Ref> refs = new LinkedHashMap<>();
        add(refs, new Ref(Scope.METADATA, preview.instance().profileId()));
        add(refs, new Ref(Scope.INSTANCE, "launcher-mods.json"));
        if (preview.packProfile()) {
            add(refs, new Ref(Scope.INSTANCE, ".ecl-pack-manifest.json"));
            readPackReferences(preview.instance().gameDirectory()).forEach(ref -> add(refs, ref));
            if (execution.packUpdate() != null) {
                execution.packUpdate().preview().initialTargetSha512().keySet()
                        .forEach(path -> add(refs, new Ref(Scope.INSTANCE, path)));
            }
        } else {
            for (ModUpdateService.PreparedModUpdate prepared : execution.modUpdates()) {
                for (PlannedModFile file : prepared.installationPlan().files()) {
                    Path target = file.targetPath().toAbsolutePath().normalize();
                    if (!target.startsWith(preview.instance().gameDirectory().toAbsolutePath().normalize())) {
                        throw new IOException("Update target escapes the instance: " + target);
                    }
                    add(refs, new Ref(Scope.INSTANCE, portable(
                            preview.instance().gameDirectory().toAbsolutePath().normalize().relativize(target))));
                    preview.installedMods().stream()
                            .filter(installed -> installed.projectId().equals(file.version().projectId()))
                            .map(installed -> new Ref(Scope.INSTANCE,
                                    portable(installed.relativePath().normalize())))
                            .forEach(ref -> add(refs, ref));
                }
            }
        }
        return List.copyOf(refs.values());
    }

    private List<Entry> snapshotBefore(List<Ref> refs,
                                       InstanceUpdateService.PreparedExecution execution,
                                       Path payloadRoot) throws IOException {
        List<Entry> entries = new ArrayList<>(refs.size());
        int index = 0;
        for (Ref ref : refs) {
            Path target = resolve(ref, execution.preview());
            Path root = rootFor(ref, execution.preview());
            FileState before = state(target, root);
            String payload = "";
            if (before.exists()) {
                payload = index++ + ".bin";
                Path backup = payloadRoot.resolve(payload);
                checkpoint.reached("before-payload-copy");
                Files.copy(target, backup);
                forceFile(backup);
                if (!sha256(backup).equals(before.sha256())) {
                    throw new IOException("Restore point copy failed verification: " + displayPath(ref));
                }
            }
            entries.add(new Entry(ref.scope(), ref.path(), before.exists(), before.sha256(),
                    payload, false, "", false));
        }
        return List.copyOf(entries);
    }

    private List<Ref> readPackReferences(Path instanceRoot) throws IOException {
        List<Ref> refs = new ArrayList<>();
        Path manifest = instanceRoot.resolve(".ecl-pack-manifest.json");
        FileUtil.validateExistingAncestors(instanceRoot, manifest);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return refs;
        JsonObject json = HttpUtil.readJson(manifest.toFile());
        if (!json.has("files") || !json.get("files").isJsonArray()) {
            throw new IOException("Installed pack manifest is invalid");
        }
        for (JsonElement element : json.getAsJsonArray("files")) {
            if (!element.isJsonObject() || !element.getAsJsonObject().has("path")) {
                throw new IOException("Installed pack manifest entry is invalid");
            }
            String relative = element.getAsJsonObject().get("path").getAsString();
            requireManagedPackPath(relative);
            FileUtil.safeResolveUnder(instanceRoot.toFile(), relative);
            refs.add(new Ref(Scope.INSTANCE, relative));
        }
        return List.copyOf(refs);
    }

    private Path resolve(Ref ref, InstanceUpdateService.Preview preview) throws IOException {
        return resolve(ref, roots(preview.instance().profileId(), preview.instance(),
                preview.metadataRoot(), preview.gameRoot()), preview.packProfile());
    }

    private Path rootFor(Ref ref, InstanceUpdateService.Preview preview) {
        try {
            return rootFor(ref, roots(preview.instance().profileId(), preview.instance(),
                    preview.metadataRoot(), preview.gameRoot()));
        } catch (IOException invalid) {
            throw new IllegalArgumentException(invalid);
        }
    }

    private Path resolve(Ref ref, PreviewRoots roots, boolean pack) throws IOException {
        Path root = rootFor(ref, roots);
        String relative = switch (ref.scope()) {
            case INSTANCE -> ref.path();
            case METADATA -> ref.path() + "/" + ref.path() + ".json";
        };
        validateReference(ref, pack, roots.profileId());
        Path target = FileUtil.safeResolveUnder(root.toFile(), relative).toPath();
        FileUtil.validateExistingAncestors(root, target);
        return target;
    }

    private Path rootFor(Ref ref, PreviewRoots roots) {
        return switch (ref.scope()) {
            case INSTANCE -> roots.instanceRoot();
            case METADATA -> roots.metadataRoot();
        };
    }

    private PreviewRoots roots(String profileId, ModInstanceContext instance,
                               Path metadataRoot, Path gameRoot) throws IOException {
        FileUtil.requireSafeVersionId(profileId);
        if (!profileId.equals(instance.profileId())) throw new IOException("Instance identity mismatch");
        Path normalizedGameRoot = gameRoot.toAbsolutePath().normalize();
        FileUtil.validateExistingAncestors(normalizedGameRoot, normalizedGameRoot);
        return new PreviewRoots(profileId, instance.instanceId().toString(),
                instance.gameDirectory().toAbsolutePath().normalize(),
                metadataRoot.toAbsolutePath().normalize(), normalizedGameRoot);
    }

    private Manifest readManifest(Path directory, PreviewRoots roots) throws IOException {
        FileUtil.validateExistingAncestors(root, directory);
        Path file = directory.resolve(MANIFEST_FILE);
        FileUtil.validateExistingAncestors(directory, file);
        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("Upgrade restore manifest is invalid", error);
        }
        if (integer(json, "schemaVersion") != SCHEMA_VERSION
                || !roots.profileId().equals(text(json, "profileId"))
                || !roots.instanceId().equals(text(json, "instanceId"))
                || !samePath(roots.instanceRoot(), Path.of(text(json, "instanceRoot")))
                || !samePath(roots.metadataRoot(), Path.of(text(json, "metadataRoot")))
                || !samePath(roots.gameRoot(), Path.of(text(json, "gameRoot")))) {
            throw new IOException("Upgrade restore point belongs to another instance or schema");
        }
        State state;
        try {
            state = State.valueOf(text(json, "state"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Upgrade restore state is invalid", invalid);
        }
        JsonArray array = json.has("entries") && json.get("entries").isJsonArray()
                ? json.getAsJsonArray("entries") : new JsonArray();
        List<Entry> entries = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        boolean pack = json.has("pack") && json.get("pack").getAsBoolean();
        for (JsonElement element : array) {
            Entry entry = parseEntry(element, directory, pack, roots.profileId());
            if (!unique.add(entry.key())) throw new IOException("Duplicate restore target");
            entries.add(entry);
        }
        return new Manifest(roots.profileId(), roots.instanceId(), roots.instanceRoot().toString(),
                roots.metadataRoot().toString(), roots.gameRoot().toString(),
                text(json, "createdAt"), pack, state,
                json.has("conflictsConfirmed") && json.get("conflictsConfirmed").getAsBoolean(),
                entries);
    }

    private Entry parseEntry(JsonElement element, Path directory, boolean pack, String profileId)
            throws IOException {
        if (!element.isJsonObject()) throw new IOException("Invalid restore entry");
        JsonObject json = element.getAsJsonObject();
        Scope scope;
        try {
            scope = Scope.valueOf(text(json, "scope"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid restore target scope", invalid);
        }
        String path = text(json, "path");
        validateReference(new Ref(scope, path), pack, profileId);
        String payload = text(json, "payload");
        if (!payload.isBlank()) {
            if (!payload.matches("[0-9]+\\.bin")) throw new IOException("Invalid backup payload path");
            Path payloadPath = directory.resolve("payload").resolve(payload).normalize();
            if (!payloadPath.startsWith(directory.resolve("payload"))) {
                throw new IOException("Backup payload escapes its directory");
            }
            FileUtil.validateExistingAncestors(directory, payloadPath);
        }
        boolean beforeExists = bool(json, "beforeExists");
        boolean afterExists = bool(json, "afterExists");
        String beforeHash = text(json, "beforeSha256");
        String afterHash = text(json, "afterSha256");
        if (beforeExists && !beforeHash.matches("(?i)[0-9a-f]{64}")
                || afterExists && !afterHash.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("Restore entry checksum is invalid");
        }
        if (beforeExists != !payload.isBlank()) throw new IOException("Restore payload is incomplete");
        return new Entry(scope, path, beforeExists, beforeHash, payload,
                afterExists, afterHash, bool(json, "restored"));
    }

    private static void validateReference(Ref ref, boolean pack, String profileId) throws IOException {
        if (ref.scope() == Scope.METADATA) {
            if (!profileId.equals(ref.path())) throw new IOException("Invalid profile metadata target");
            return;
        }
        Path normalized = com.ecl.util.ManagedPackPathPolicy.requireRelativePath(ref.path());
        if (ref.path().equals("launcher-mods.json") || ref.path().equals(".ecl-pack-manifest.json")) return;
        if (normalized.getNameCount() > 1 && (normalized.getName(0).toString().equals("mods")
                || normalized.getName(0).toString().equals("disabled-mods"))) return;
        if (pack) {
            requireManagedPackPath(ref.path());
            return;
        }
        throw new IOException("Update restore target is outside mod files: " + ref.path());
    }

    private static void requireManagedPackPath(String path) throws IOException {
        com.ecl.util.ManagedPackPathPolicy.requireManagedRelativePath(path);
    }

    private static void add(Map<String, Ref> refs, Ref ref) {
        refs.putIfAbsent(ref.key(), ref);
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static FileState state(Path target, Path root) throws IOException {
        FileUtil.validateExistingAncestors(root, target);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return new FileState(false, "");
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed upgrade target is not a regular file: " + target);
        }
        return new FileState(true, sha256(target));
    }

    private List<String> conflicts(Manifest manifest, PreviewRoots roots) throws IOException {
        List<String> result = new ArrayList<>();
        for (Entry entry : manifest.entries()) {
            Path target = resolve(entry.ref(), roots, manifest.pack());
            Path root = rootFor(entry.ref(), roots);
            FileState current = state(target, root);
            if (!same(entry.afterExists(), entry.afterSha256(), current.exists(), current.sha256())) {
                result.add(displayPath(entry.ref()));
            }
        }
        return List.copyOf(result);
    }

    private void verifyPayloads(Path directory, Manifest manifest) throws IOException {
        for (Entry entry : manifest.entries()) {
            if (!entry.beforeExists()) continue;
            Path payload = directory.resolve("payload").resolve(entry.payload()).normalize();
            FileUtil.validateExistingAncestors(directory, payload);
            if (!Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)
                    || !sha256(payload).equals(entry.beforeSha256())) {
                throw new IOException(Messages.get("instanceRecovery.damagedPayload") + ": " + displayPath(entry.ref()));
            }
        }
    }

    private void restoreEntry(Path directory, Entry entry, PreviewRoots roots, boolean pack)
            throws IOException {
        Path target = resolve(entry.ref(), roots, pack);
        Path targetRoot = rootFor(entry.ref(), roots);
        FileState current = state(target, targetRoot);
        if (same(entry.beforeExists(), entry.beforeSha256(), current.exists(), current.sha256())) return;
        if (!entry.beforeExists()) {
            Files.deleteIfExists(target);
            return;
        }
        Path payload = directory.resolve("payload").resolve(entry.payload()).normalize();
        if (!Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)
                || !sha256(payload).equals(entry.beforeSha256())) {
            throw new IOException(Messages.get("instanceRecovery.damagedPayload"));
        }
        FileUtil.validateExistingAncestors(targetRoot, target);
        Files.createDirectories(target.getParent());
        FileUtil.validateExistingAncestors(targetRoot, target);
        Path staged = Files.createTempFile(target.getParent(), ".ecl-restore-", ".part");
        try {
            Files.copy(payload, staged, StandardCopyOption.REPLACE_EXISTING);
            if (!sha256(staged).equals(entry.beforeSha256())) {
                throw new IOException(Messages.get("instanceRecovery.damagedPayload"));
            }
            forceFile(staged);
            FileUtil.moveReplacing(staged, target);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private void publish(Path pending, Path profileDirectory, PreviewRoots roots) throws IOException {
        verifyPayloads(pending, readManifest(pending, roots));
        Path active = profileDirectory.resolve("active");
        Path previous = profileDirectory.resolve("previous");
        if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) cleanupDirectory(previous);
        boolean hadActive = Files.exists(active, LinkOption.NOFOLLOW_LINKS);
        if (hadActive) moveDirectory(active, previous);
        checkpoint.reached("after-active-moved");
        try {
            moveDirectory(pending, active);
            checkpoint.reached("after-pending-published");
        } catch (IOException failure) {
            if (hadActive && !Files.exists(active)) moveDirectory(previous, active);
            throw failure;
        }
        verifyPayloads(active, readManifest(active, roots));
        if (hadActive) cleanupDirectory(previous);
        forceDirectory(profileDirectory);
    }

    private void recoverSwap(Path profileDirectory, PreviewRoots roots) throws IOException {
        if (!Files.isDirectory(profileDirectory, LinkOption.NOFOLLOW_LINKS)) return;
        Path active = profileDirectory.resolve("active");
        Path previous = profileDirectory.resolve("previous");
        if (!Files.exists(active, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(previous, LinkOption.NOFOLLOW_LINKS)) {
            moveDirectory(previous, active);
        } else if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)
                && Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
            verifyPayloads(active, readManifest(active, roots));
            cleanupDirectory(previous);
        }
    }

    private void cleanupUnstartedStaging(Path profileDirectory) throws IOException {
        if (!Files.isDirectory(profileDirectory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.list(profileDirectory)) {
            for (Path entry : entries.filter(path -> path.getFileName().toString().startsWith("staging-"))
                    .toList()) cleanupDirectory(entry);
        }
    }

    private Path profileDirectory(String profileId) throws IOException {
        FileUtil.requireSafeVersionId(profileId);
        Path directory = root.resolve(profileId).normalize();
        FileUtil.validateExistingAncestors(root, directory);
        return directory;
    }

    private FileLockLease acquireRecoveryLock(String profileId) throws IOException {
        Path directory = profileDirectory(profileId);
        Path lockFile = directory.resolve("operation.lock");
        FileUtil.validateExistingAncestors(root, lockFile);
        FileLockLease lease = FileLockLease.tryAcquire(lockFile);
        if (lease == null) throw new IOException(Messages.get("instanceRecovery.instanceBusy"));
        return lease;
    }

    private static void writeManifest(Path directory, Manifest manifest) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("profileId", manifest.profileId());
        json.addProperty("instanceId", manifest.instanceId());
        json.addProperty("instanceRoot", manifest.instanceRoot());
        json.addProperty("metadataRoot", manifest.metadataRoot());
        json.addProperty("gameRoot", manifest.gameRoot());
        json.addProperty("createdAt", manifest.createdAt());
        json.addProperty("pack", manifest.pack());
        json.addProperty("state", manifest.state().name());
        json.addProperty("conflictsConfirmed", manifest.conflictsConfirmed());
        JsonArray entries = new JsonArray();
        for (Entry entry : manifest.entries()) {
            JsonObject item = new JsonObject();
            item.addProperty("scope", entry.scope().name());
            item.addProperty("path", entry.path());
            item.addProperty("beforeExists", entry.beforeExists());
            item.addProperty("beforeSha256", entry.beforeSha256());
            item.addProperty("payload", entry.payload());
            item.addProperty("afterExists", entry.afterExists());
            item.addProperty("afterSha256", entry.afterSha256());
            item.addProperty("restored", entry.wasRestored());
            entries.add(item);
        }
        json.add("entries", entries);
        Path temporary = Files.createTempFile(directory, "recovery-", ".tmp");
        try {
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
            forceFile(temporary);
            FileUtil.moveReplacing(temporary, directory.resolve(MANIFEST_FILE));
            forceDirectory(directory);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static State readState(Path directory) throws IOException {
        JsonObject json = HttpUtil.readJson(directory.resolve(MANIFEST_FILE).toFile());
        try {
            return State.valueOf(text(json, "state"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid recovery point state", invalid);
        }
    }

    private static boolean same(boolean leftExists, String leftHash,
                                boolean rightExists, String rightHash) {
        return leftExists == rightExists && (!leftExists || leftHash.equalsIgnoreCase(rightHash));
    }

    private static String displayPath(Ref ref) {
        return ref.scope().name().toLowerCase(java.util.Locale.ROOT) + ":" + ref.path();
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean samePath(Path first, Path second) {
        String left = first.toAbsolutePath().normalize().toString();
        String right = second.toAbsolutePath().normalize().toString();
        return com.ecl.util.OperatingSystem.isWindows()
                ? left.equalsIgnoreCase(right) : left.equals(right);
    }

    private static String text(JsonObject object, String key) throws IOException {
        try {
            return object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString() : "";
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid recovery field: " + key, invalid);
        }
    }

    private static int integer(JsonObject object, String key) throws IOException {
        try {
            return object.has(key) ? object.get(key).getAsInt() : -1;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid recovery field: " + key, invalid);
        }
    }

    private static boolean bool(JsonObject object, String key) throws IOException {
        try {
            return object.has(key) && object.get(key).getAsBoolean();
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid recovery field: " + key, invalid);
        }
    }


    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
        forceDirectory(target.getParent());
    }

    private static void forceDirectory(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException unsupported) {
            // Directory fsync is not supported on every Windows file system.
        }
    }

    private static void cleanupDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.walk(directory)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static String normalizePath(String value) {
        return value.replace('\\', '/');
    }

    private record Ref(Scope scope, String path) {
        private Ref {
            path = normalizePath(path);
        }

        private String key() { return scope.name() + ":" + path; }
    }

    private record FileState(boolean exists, String sha256) { }

    private record PreviewRoots(String profileId, String instanceId, Path instanceRoot,
                                Path metadataRoot, Path gameRoot) { }

    private record Manifest(String profileId, String instanceId, String instanceRoot,
                            String metadataRoot, String gameRoot, String createdAt,
                            boolean pack, State state,
                            boolean conflictsConfirmed, List<Entry> entries) {
        private Manifest {
            entries = List.copyOf(entries);
        }

        private Manifest with(State newState, boolean confirmed, List<Entry> newEntries) {
            return new Manifest(profileId, instanceId, instanceRoot, metadataRoot, gameRoot,
                    createdAt, pack,
                    newState, confirmed, newEntries);
        }

        private Manifest markRestored(String key) {
            return with(state, conflictsConfirmed, entries.stream().map(entry ->
                    entry.key().equals(key) ? entry.restored() : entry).toList());
        }
    }

    private record Entry(Scope scope, String path, boolean beforeExists,
                         String beforeSha256, String payload, boolean afterExists,
                         String afterSha256, boolean wasRestored) {
        private Ref ref() { return new Ref(scope, path); }
        private String key() { return ref().key(); }

        private Entry after(boolean exists, String hash) {
            return new Entry(scope, path, beforeExists, beforeSha256, payload,
                    exists, hash, false);
        }

        private Entry restored() {
            return new Entry(scope, path, beforeExists, beforeSha256, payload,
                    afterExists, afterSha256, true);
        }

        private static Entry absent(Ref ref) {
            return new Entry(ref.scope(), ref.path(), false, "", "", false, "", false);
        }
    }

    private enum Scope { INSTANCE, METADATA }
    private enum State { PREPARING, ACTIVE, RESTORING, RESTORED }

    public record Draft(Path directory, InstanceUpdateService.PreparedExecution execution,
                        FileLockLease lease, InstanceOperationSession session,
                        AutoCloseable ownership) implements AutoCloseable {
        @Override public void close() throws IOException {
            IOException failure = null;
            try { lease.close(); } catch (IOException error) { failure = error; }
            try { ownership.close(); } catch (Exception error) {
                if (failure == null) failure = new IOException("Cannot release recovery ownership", error);
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }

    public record RestorePoint(String profileId, Instant createdAt, String state,
                               List<String> files, List<String> conflicts,
                               boolean pack, boolean restorationPending) {
        public RestorePoint {
            files = List.copyOf(files);
            conflicts = List.copyOf(conflicts);
        }
    }

    public record RestoreResult(boolean restored, List<String> conflicts, String createdAt) {
        public RestoreResult {
            conflicts = List.copyOf(conflicts);
        }
    }
}
