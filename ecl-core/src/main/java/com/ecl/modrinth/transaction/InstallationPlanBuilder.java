package com.ecl.modrinth.transaction;

import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.service.DependencyResolutionResult;
import com.ecl.modrinth.service.ResolvedMod;
import com.ecl.util.TextUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

public final class InstallationPlanBuilder {
    public ModInstallationPlan build(ModInstanceContext instance, ModVersion rootVersion,
                                     DependencyResolutionResult resolution) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(rootVersion, "rootVersion");
        Objects.requireNonNull(resolution, "resolution");

        Path modsDirectory = instance.modsDirectory().toAbsolutePath().normalize();
        List<PlannedModFile> files = new ArrayList<>();
        List<String> warnings = new ArrayList<>(resolution.warnings());
        Set<Path> targets = new HashSet<>();
        long totalSize = 0;
        for (ResolvedMod resolved : resolution.installOrder()) {
            String safeName = safeFileName(resolved.file().fileName());
            Path target = modsDirectory.resolve(safeName).normalize();
            if (!target.startsWith(modsDirectory)) {
                throw new IllegalArgumentException("Mod filename escapes mods directory: " + safeName);
            }
            if (!targets.add(target)) {
                throw new IllegalArgumentException("多个模组将写入同一文件名: " + safeName);
            }
            if (!hasExpectedHash(resolved.file())) {
                throw new IllegalArgumentException(
                        "文件 " + safeName + " 缺少 SHA-512 或 SHA-1，拒绝安装不可验证的模组");
            }
            if (resolved.file().size() <= 0) {
                throw new IllegalArgumentException(
                        "文件 " + safeName + " 缺少有效大小，拒绝无边界下载");
            }
            files.add(new PlannedModFile(
                    resolved.version(), resolved.file(), target, null,
                    resolved.dependency(), resolved.requiredByProjectId()));
            totalSize = Math.addExact(totalSize, Math.max(0, resolved.file().size()));
        }
        return new ModInstallationPlan(
                instance,
                rootVersion,
                files,
                resolution.optionalDependencies(),
                resolution.conflicts(),
                warnings,
                totalSize,
                files.size() > 1 || !resolution.optionalDependencies().isEmpty()
                        || !warnings.isEmpty());
    }

    private static boolean hasExpectedHash(com.ecl.modrinth.model.ModFile file) {
        return file != null && HashVerifier.hasUsableExpectedHash(file.hashes());
    }

    private static String safeFileName(String value) {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("\\")
                || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Unsafe mod filename: " + value);
        }
        String safe = TextUtil.replaceInvalidFilenameChars(value.trim());
        if (safe.isBlank() || safe.contains("..")) {
            throw new IllegalArgumentException("Unsafe mod filename: " + value);
        }
        return safe;
    }
}
