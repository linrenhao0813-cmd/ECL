package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Encodes journal targets and resolves them only within the transaction's allowed roots. */
final class PackTransactionPaths {
    private final Path instanceRoot;
    private final Path profileFile;
    private final Path versionsRoot;
    private final Path librariesRoot;

    PackTransactionPaths(Path instanceRoot, Path profileFile) {
        this.instanceRoot = instanceRoot;
        this.profileFile = profileFile;
        this.versionsRoot = ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize();
        this.librariesRoot = ECLConfig.getLibrariesDir().toPath().toAbsolutePath().normalize();
    }

    void validateRoots() throws IOException {
        if (instanceRoot.getParent() == null || profileFile.getParent() == null) {
            throw new IOException("Pack transaction roots must have parent directories");
        }
        if (!profileFile.startsWith(versionsRoot)) {
            throw new IOException("Pack profile metadata must be inside the versions directory");
        }
        MrpackPathPolicy.validateExistingAncestors(instanceRoot.getParent(), instanceRoot);
        MrpackPathPolicy.validateExistingAncestors(versionsRoot, profileFile);
    }

    Path normalizeTarget(Path target) {
        Path result = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        if (!result.startsWith(instanceRoot) && !result.equals(profileFile)
                && !result.startsWith(versionsRoot) && !result.startsWith(librariesRoot)) {
            throw new IllegalArgumentException("Pack transaction target is outside its roots: " + target);
        }
        return result;
    }

    Target describeTarget(Path path) {
        if (path.equals(profileFile)) {
            return new Target(Scope.PROFILE, "");
        }
        if (path.startsWith(versionsRoot)) {
            return new Target(Scope.VERSIONS, portable(versionsRoot.relativize(path)));
        }
        if (path.startsWith(librariesRoot)) {
            return new Target(Scope.LIBRARIES, portable(librariesRoot.relativize(path)));
        }
        return new Target(Scope.INSTANCE, portable(instanceRoot.relativize(path)));
    }

    Path resolveTarget(String targetScope, String targetPath) throws IOException {
        Scope scope;
        try {
            scope = Scope.valueOf(targetScope);
        } catch (RuntimeException error) {
            throw new IOException("Invalid pack transaction target scope", error);
        }
        if (scope == Scope.PROFILE) {
            if (targetPath != null && !targetPath.isBlank()) {
                throw new IOException("Invalid profile target in pack transaction journal");
            }
            MrpackPathPolicy.validateExistingAncestors(versionsRoot, profileFile);
            return profileFile;
        }
        if (scope == Scope.VERSIONS) {
            return PackManifest.resolve(versionsRoot, targetPath);
        }
        if (scope == Scope.LIBRARIES) {
            return PackManifest.resolve(librariesRoot, targetPath);
        }
        return PackManifest.resolve(instanceRoot, targetPath);
    }

    void validateExternalRoot(Path root) throws IOException {
        if (root.equals(versionsRoot)) {
            return;
        }
        if (root.equals(librariesRoot)) {
            return;
        }
        throw new IOException("Unsupported external transaction root: " + root);
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    enum Scope { INSTANCE, PROFILE, VERSIONS, LIBRARIES }

    record Target(Scope scope, String relative) {
    }
}
