package com.ecl;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.ecl.cli.EclCli;
import com.ecl.util.PlatformUtil;

/**
 * Main entry point for ECL.
 * Delegates to ECLauncher to keep existing run configs working.
 */
public class ECL {
    private static final String BOOTSTRAPPED_ENV = "ECL_BOOTSTRAPPED";
    private static final String JAVAFX_VERSION = "21";
    private static final String GSON_VERSION = "2.10.1";

    public static void main(String[] args) {
        if (isCliMode(args)) {
            System.exit(EclCli.execute(withoutCliFlag(args)));
            return;
        }

        if (!hasClass("javafx.application.Application") && !"1".equals(System.getenv(BOOTSTRAPPED_ENV))) {
            try {
                relaunchWithCachedDependencies(args);
                return;
            } catch (Exception e) {
                System.err.println("ECL 启动失败：当前运行配置没有包含 JavaFX 依赖。");
                System.err.println("请用 Gradle 任务运行，或在 IntelliJ 里重新导入 Gradle 项目。");
                System.err.println("自动补全依赖也失败了：" + e.getMessage());
            }
        }

        ECLauncher.main(args);
    }

    private static boolean isCliMode(String[] args) {
        return Arrays.asList(args).contains("--cli")
                || Boolean.parseBoolean(System.getProperty("java.awt.headless", "false"));
    }

    private static String[] withoutCliFlag(String[] args) {
        return Arrays.stream(args).filter(arg -> !"--cli".equals(arg)).toArray(String[]::new);
    }

    private static boolean hasClass(String className) {
        try {
            Class.forName(className, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void relaunchWithCachedDependencies(String[] args) throws IOException, InterruptedException {
        List<String> classpath = new ArrayList<>();
        String currentClasspath = System.getProperty("java.class.path", "");
        if (!currentClasspath.isBlank()) {
            classpath.add(currentClasspath);
        }

        String javafxClassifier = javafxClassifier();
        addCachedJar(classpath, "org.openjfx", "javafx-base", JAVAFX_VERSION, javafxClassifier);
        addCachedJar(classpath, "org.openjfx", "javafx-graphics", JAVAFX_VERSION, javafxClassifier);
        addCachedJar(classpath, "org.openjfx", "javafx-controls", JAVAFX_VERSION, javafxClassifier);
        addCachedJar(classpath, "com.google.code.gson", "gson", GSON_VERSION);

        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add(ECL.class.getName());
        command.addAll(List.of(args));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.inheritIO();
        builder.environment().put(BOOTSTRAPPED_ENV, "1");
        Process process = builder.start();
        System.exit(process.waitFor());
    }

    private static void addCachedJar(List<String> classpath, String group, String artifact, String version) throws IOException {
        addCachedJar(classpath, group, artifact, version, null);
    }

    private static void addCachedJar(List<String> classpath, String group, String artifact, String version, String classifier) throws IOException {
        File cacheDir = new File(System.getProperty("user.home"),
                ".gradle/caches/modules-2/files-2.1/" + group + "/" + artifact + "/" + version);
        String prefix = classifier == null || classifier.isBlank()
                ? artifact + "-" + version
                : artifact + "-" + version + "-" + classifier;
        File jar = findJar(cacheDir, prefix);
        if (jar == null) {
            throw new IOException("找不到依赖缓存: " + group + ":" + artifact + ":" + version);
        }
        classpath.add(jar.getAbsolutePath());
    }

    private static File findJar(File dir, String prefix) {
        if (dir == null || !dir.exists()) {
            return null;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }

        for (File file : files) {
            if (file.isFile() && file.getName().startsWith(prefix) && file.getName().endsWith(".jar")) {
                return file;
            }
        }

        for (File file : files) {
            if (file.isDirectory()) {
                File found = findJar(file, prefix);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String javaExecutable() {
        String executable = PlatformUtil.isWindows() ? "java.exe" : "java";
        return new File(System.getProperty("java.home"), "bin/" + executable).getAbsolutePath();
    }

    private static String javafxClassifier() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        if (osName.contains("win")) {
            return "win";
        }
        if (osName.contains("mac")) {
            return osArch.contains("aarch64") || osArch.contains("arm64") ? "mac-aarch64" : "mac";
        }
        return "linux";
    }
}
