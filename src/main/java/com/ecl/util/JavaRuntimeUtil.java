package com.ecl.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class JavaRuntimeUtil {
    private JavaRuntimeUtil() {
    }

    public static String detectSystemJavaExecutable() {
        return resolveJavaExecutable(null);
    }

    public static String resolveJavaExecutable(String configuredPath) {
        File configured = resolveJavaCandidate(configuredPath);
        if (configured != null) {
            return configured.getAbsolutePath();
        }

        File fromJavaHome = resolveJavaCandidate(System.getProperty("java.home"));
        if (fromJavaHome != null) {
            return fromJavaHome.getAbsolutePath();
        }

        File fromEnv = resolveJavaCandidate(System.getenv("JAVA_HOME"));
        if (fromEnv != null) {
            return fromEnv.getAbsolutePath();
        }

        File installed = findInstalledJava();
        if (installed != null) {
            return installed.getAbsolutePath();
        }

        return executableName();
    }

    public static boolean isUsableJavaPath(String path) {
        return resolveJavaCandidate(path) != null;
    }

    private static File resolveJavaCandidate(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        File candidate = new File(path.trim());
        if (candidate.isDirectory()) {
            File fromBin = new File(candidate, "bin/" + executableName());
            if (fromBin.isFile()) {
                return fromBin;
            }

            File nestedExecutable = new File(candidate, executableName());
            if (nestedExecutable.isFile()) {
                return nestedExecutable;
            }
            return null;
        }

        return candidate.isFile() ? candidate : null;
    }

    private static File findInstalledJava() {
        List<File> roots = new ArrayList<>();
        String userHome = System.getProperty("user.home", "");
        String programFiles = System.getenv("ProgramFiles");

        if (!userHome.isBlank()) {
            roots.add(new File(userHome, ".jdks"));
        }
        if (programFiles != null && !programFiles.isBlank()) {
            roots.add(new File(programFiles, "Java"));
            roots.add(new File(programFiles, "Eclipse Adoptium"));
            roots.add(new File(programFiles, "Microsoft"));
        }

        for (File root : roots) {
            File javaExecutable = findJavaUnderRoot(root);
            if (javaExecutable != null) {
                return javaExecutable;
            }
        }
        return null;
    }

    private static File findJavaUnderRoot(File root) {
        if (root == null || !root.exists()) {
            return null;
        }

        File direct = resolveJavaCandidate(root.getAbsolutePath());
        if (direct != null) {
            return direct;
        }

        File[] children = root.listFiles(File::isDirectory);
        if (children == null || children.length == 0) {
            return null;
        }

        Arrays.sort(children, Comparator.comparing(File::getName).reversed());
        for (File child : children) {
            File candidate = resolveJavaCandidate(child.getAbsolutePath());
            if (candidate != null) {
                return candidate;
            }
        }

        return null;
    }

    private static String executableName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
    }
}