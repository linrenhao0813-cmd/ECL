package com.ecl;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Main entry point for ECL.
 * Delegates to ECLauncher to keep existing run configs working.
 */
public class ECL {
    private static final String BOOTSTRAPPED_ENV = "ECL_BOOTSTRAPPED";

    public static void main(String[] args) {
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

        addCachedJar(classpath, "org.openjfx", "javafx-base", "21");
        addCachedJar(classpath, "org.openjfx", "javafx-graphics", "21");
        addCachedJar(classpath, "org.openjfx", "javafx-controls", "21");
        addCachedJar(classpath, "org.openjfx", "javafx-fxml", "21");
        addCachedJar(classpath, "org.openjfx", "javafx-media", "21");
        addCachedJar(classpath, "org.openjfx", "javafx-web", "21");
        addCachedJar(classpath, "com.google.code.gson", "gson", "2.10.1");
        addCachedJar(classpath, "org.jsoup", "jsoup", "1.17.2");

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
        File cacheDir = new File(System.getProperty("user.home"),
                ".gradle/caches/modules-2/files-2.1/" + group + "/" + artifact + "/" + version);
        File jar = findJar(cacheDir, artifact + "-" + version);
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
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return new File(System.getProperty("java.home"), "bin/" + executable).getAbsolutePath();
    }
}
