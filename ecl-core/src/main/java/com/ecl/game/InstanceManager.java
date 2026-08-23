package com.ecl.game;

import com.ecl.util.FileUtil;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Safe copy, rename and deletion operations for version metadata and isolated instance data. */
public final class InstanceManager {
    private final Path versionsRoot;
    private final Path instancesRoot;

    public InstanceManager(Path versionsRoot, Path instancesRoot) {
        this.versionsRoot = Objects.requireNonNull(versionsRoot).toAbsolutePath().normalize();
        this.instancesRoot = Objects.requireNonNull(instancesRoot).toAbsolutePath().normalize();
    }

    public void copyInstance(String sourceId, String targetId) throws IOException {
        Path sourceVersion = versionDirectory(sourceId); Path targetVersion = versionDirectory(targetId);
        Path sourceInstance = instanceDirectory(sourceId); Path targetInstance = instanceDirectory(targetId);
        requireSource(sourceVersion); requireUnused(targetVersion); requireUnused(targetInstance);
        try {
            copyTree(sourceVersion, targetVersion);
            if (Files.exists(sourceInstance)) copyTree(sourceInstance, targetInstance);
            renameVersionJson(targetVersion, sourceId, targetId);
        } catch (IOException | RuntimeException failure) {
            deleteCreatedTarget(targetInstance, failure);
            deleteCreatedTarget(targetVersion, failure);
            throw failure;
        }
    }

    public void renameInstance(String sourceId, String targetId) throws IOException {
        Path sourceVersion = versionDirectory(sourceId); Path targetVersion = versionDirectory(targetId);
        Path sourceInstance = instanceDirectory(sourceId); Path targetInstance = instanceDirectory(targetId);
        requireSource(sourceVersion); requireUnused(targetVersion); requireUnused(targetInstance);
        boolean versionMoved = false;
        boolean instanceMoved = false;
        try {
            Files.createDirectories(targetVersion.getParent());
            Files.move(sourceVersion, targetVersion);
            versionMoved = true;
            if (Files.exists(sourceInstance)) {
                Files.createDirectories(targetInstance.getParent());
                Files.move(sourceInstance, targetInstance);
                instanceMoved = true;
            }
            renameVersionJson(targetVersion, sourceId, targetId);
        } catch (IOException | RuntimeException failure) {
            if (instanceMoved) {
                rollbackMove(targetInstance, sourceInstance, failure);
            }
            if (versionMoved) {
                rollbackMove(targetVersion, sourceVersion, failure);
                try {
                    renameVersionJson(sourceVersion, targetId, sourceId);
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    public void deleteInstance(String id) throws IOException {
        Path version = versionDirectory(id); Path instance = instanceDirectory(id);
        if (Files.exists(version)) FileUtil.deleteDirectory(version);
        if (Files.exists(instance)) FileUtil.deleteDirectory(instance);
    }

    private Path versionDirectory(String id) { return safe(versionsRoot, id); }
    private Path instanceDirectory(String id) { return safe(instancesRoot, id); }
    private Path safe(Path root, String id) {
        String value = id == null ? "" : id.trim();
        if (value.isBlank() || value.contains("..") || value.contains("/") || value.contains("\\")) throw new IllegalArgumentException("无效实例名称");
        Path target = root.resolve(value).normalize(); if (!target.getParent().equals(root)) throw new IllegalArgumentException("无效实例路径"); return target;
    }
    private void requireSource(Path path) throws IOException { if (!Files.isDirectory(path)) throw new IOException("实例不存在: " + path.getFileName()); }
    private void requireUnused(Path path) throws IOException { if (Files.exists(path)) throw new IOException("目标实例已存在: " + path.getFileName()); }
    private void renameVersionJson(Path directory, String oldId, String newId) throws IOException {
        Path oldFile = directory.resolve(oldId + ".json"); Path newFile = directory.resolve(newId + ".json");
        if (Files.isRegularFile(oldFile)) Files.move(oldFile, newFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteCreatedTarget(Path target, Throwable failure) {
        try {
            if (Files.exists(target)) {
                FileUtil.deleteDirectory(target);
            }
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void rollbackMove(Path moved, Path original, Throwable failure) {
        try {
            if (Files.exists(moved) && !Files.exists(original)) {
                Files.createDirectories(original.getParent());
                Files.move(moved, original);
            }
        } catch (IOException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException { Files.createDirectories(target.resolve(source.relativize(dir))); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isSymbolicLink()) {
                    Files.copy(file, target.resolve(source.relativize(file)),
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
