package com.ecl.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class CrashAnalyzer {
    private static final int MAX_REPORT_CHARS = 50000;
    private static final int MAX_EVIDENCE_LINES = 12;

    public static class Report {
        private final String title;
        private final String explanation;
        private final List<String> suggestions;
        private final List<String> evidence;
        private final File crashReportFile;

        public Report(String title, String explanation, List<String> suggestions, List<String> evidence, File crashReportFile) {
            this.title = title;
            this.explanation = explanation;
            this.suggestions = suggestions;
            this.evidence = evidence;
            this.crashReportFile = crashReportFile;
        }

        public String getTitle() {
            return title;
        }

        public String getExplanation() {
            return explanation;
        }

        public List<String> getSuggestions() {
            return suggestions;
        }

        public List<String> getEvidence() {
            return evidence;
        }

        public File getCrashReportFile() {
            return crashReportFile;
        }
    }

    public static Report analyzeGameExit(String version, int exitCode, String processOutput, File gameDir) {
        return analyzeGameExit(version, exitCode, processOutput, gameDir, 0);
    }

    public static Report analyzeGameExit(String version, int exitCode, String processOutput, File gameDir, long launchStartedAt) {
        File crashReport = findLatestCrashReport(gameDir, launchStartedAt);
        String crashText = readFileTail(crashReport);
        String latestLog = readFileTail(new File(gameDir, "logs/latest.log"));
        String combined = join(processOutput, crashText, latestLog);
        return analyzeText(version, exitCode, combined, crashReport);
    }

    public static Report analyzeLaunchException(String version, Throwable throwable, File gameDir) {
        StringBuilder text = new StringBuilder();
        Throwable cursor = throwable;
        while (cursor != null) {
            text.append(cursor.getClass().getName()).append(": ")
                    .append(cursor.getMessage() == null ? "" : cursor.getMessage())
                    .append('\n');
            cursor = cursor.getCause();
        }
        return analyzeText(version, -1, text.toString(), findLatestCrashReport(gameDir));
    }

    private static Report analyzeText(String version, int exitCode, String text, File crashReportFile) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        String title = "游戏异常退出";
        String explanation = "Minecraft 退出码为 " + exitCode + "。启动器没有识别到更具体的错误类型。";

        if (containsAny(lower, "unsupportedclassversionerror", "class file version", "has been compiled by a more recent version")) {
            title = "Java 版本不匹配";
            explanation = "错误表示当前 Java 版本过低，无法运行这个 Minecraft 或模组。";
            suggestions.add("在高级设置里选择合适的 Java。1.20.5 及以上通常需要 Java 21；1.18 到 1.20.4 通常需要 Java 17。");
            suggestions.add("不要混用系统旧 Java。优先选择 JDK 安装目录里的 bin\\java.exe。");
        } else if (containsAny(lower, "outofmemoryerror", "java heap space", "gc overhead limit exceeded")) {
            title = "内存不足";
            explanation = "游戏或模组加载时内存不够，Java 抛出了内存溢出错误。";
            suggestions.add("关闭其他占用内存的程序，或减少本次启动加载的内容。");
            suggestions.add("减少大型材质包、光影或高负载模组。");
        } else if (containsAny(lower, "duplicatemodsfoundexception", "duplicate mod", "found duplicate mods")) {
            title = "存在重复模组";
            explanation = "mods 文件夹里存在相同模组的多个版本，加载器无法决定使用哪一个。";
            suggestions.add("打开 mods 目录，删除重复模组，只保留一个版本。");
            suggestions.add("重点检查同名文件、旧版本残留、同一个前置库的多个版本。");
        } else if (containsAny(lower, "incompatible mod set", "mod loading has failed", "modloadingexception", "requires version", "depends on", "requires any version")) {
            title = "模组版本或前置依赖不兼容";
            explanation = "加载器报告模组依赖不满足，常见原因是模组版本、Minecraft 版本或 Fabric/Forge/NeoForge/Quilt 加载器不匹配。";
            suggestions.add("确认每个模组都对应当前 Minecraft 版本和当前加载器。");
            suggestions.add("安装缺失的前置模组，例如 Fabric API、Architectury、Cloth Config 等。");
            suggestions.add("最近新增过模组时，先移除最近新增的文件再启动验证。");
        } else if (containsAny(lower, "noclassdeffounderror", "classnotfoundexception", "nosuchmethoderror", "nosuchfielderror")) {
            title = "缺少类或接口不兼容";
            explanation = "某个模组或依赖库调用了不存在的类/方法，通常是模组版本不匹配或缺少前置依赖。";
            suggestions.add("检查报错附近提到的模组名，把它更新到匹配当前 Minecraft 和加载器的版本。");
            suggestions.add("如果刚安装了新模组，先移除它和相关前置依赖再重试。");
        } else if (containsAny(lower, "mixin apply failed", "mixintransformererror", "invalidmixinexception", "critical injection failure")) {
            title = "Mixin 注入失败";
            explanation = "模组修改 Minecraft 内部代码时失败，通常是模组之间冲突或版本不兼容。";
            suggestions.add("更新 Fabric/Forge/NeoForge/Quilt 加载器和相关模组。");
            suggestions.add("优先排查优化类、渲染类、核心库类模组之间的冲突。");
        } else if (containsAny(lower, "glfw error", "opengl", "pixel format not accelerated", "lwjgl", "failed to create window")) {
            title = "显卡驱动或 OpenGL 环境异常";
            explanation = "Minecraft 无法创建图形窗口，常见原因是显卡驱动过旧、远程桌面环境不支持 OpenGL，或光影/渲染模组冲突。";
            suggestions.add("更新显卡驱动，优先使用 NVIDIA/AMD/Intel 官方驱动。");
            suggestions.add("关闭光影和渲染优化模组后重试。");
            suggestions.add("避免在不支持 OpenGL 的远程桌面环境里启动游戏。");
        } else if (containsAny(lower, "accessdeniedexception", "permission denied", "另一个程序正在使用此文件", "being used by another process")) {
            title = "文件权限或占用问题";
            explanation = "游戏文件被系统、杀毒软件、同步软件或另一个进程占用，导致启动失败。";
            suggestions.add("关闭正在运行的 Minecraft 和 Java 进程后重试。");
            suggestions.add("如果目录在 OneDrive/网盘同步目录里，建议把游戏目录移动到普通本地目录。");
            suggestions.add("检查杀毒软件是否拦截了游戏或模组文件。");
        } else if (containsAny(lower, "could not find or load main class", "unable to access jarfile", "zip end header not found", "invalid or corrupt jarfile")) {
            title = "游戏文件或依赖库损坏";
            explanation = "客户端 jar、依赖库或模组 jar 文件不完整或损坏。";
            suggestions.add("重新下载当前游戏版本。");
            suggestions.add("删除最近下载失败的模组或资源文件后重试。");
            suggestions.add("如果网络不稳定，切换网络或稍后重试下载。");
        } else if (containsAny(lower, "connection timed out", "failed to download", "unknownhostexception", "sslhandshakeexception")) {
            title = "网络下载失败";
            explanation = "启动或补全资源时网络连接失败。";
            suggestions.add("检查网络连接和代理设置。");
            suggestions.add("重新刷新版本列表或重新启动下载。");
            suggestions.add("如果官方源较慢，等待启动器自动切换镜像源。");
        } else if (exitCode == -1073740791 || containsAny(lower, "exit code: -1073740791", "0xc0000409")) {
            title = "原生库或显卡驱动崩溃";
            explanation = "进程以 Windows 原生崩溃码退出，常见于显卡驱动、LWJGL、光影或注入类软件冲突。";
            suggestions.add("更新显卡驱动并关闭覆盖层软件，例如录屏、帧率显示、注入插件。");
            suggestions.add("禁用光影和渲染类模组后重试。");
        }

        if (suggestions.isEmpty()) {
            suggestions.add("查看下方关键日志，优先定位提到的模组文件名、异常类名或缺失依赖。");
            suggestions.add("尝试移除最近新增的模组、光影或材质包。");
            suggestions.add("确认当前 Java、Minecraft 版本和加载器版本匹配。");
        }

        List<String> evidence = extractEvidence(text);
        if (evidence.isEmpty() && crashReportFile != null) {
            evidence.add("已找到崩溃报告: " + crashReportFile.getAbsolutePath());
        }

        return new Report(title, explanation, suggestions, evidence, crashReportFile);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> extractEvidence(String text) {
        List<String> evidence = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return evidence;
        }

        String[] lines = text.split("\\R");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (containsAny(lower,
                    "exception", "error", "caused by", "failed", "crash", "fatal",
                    "incompatible", "requires", "missing", "mixin", "mod ")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank() && evidence.size() < MAX_EVIDENCE_LINES) {
                    evidence.add(trimmed);
                }
            }
        }
        return evidence;
    }

    private static File findLatestCrashReport(File gameDir) {
        return findLatestCrashReport(gameDir, 0);
    }

    private static File findLatestCrashReport(File gameDir, long afterMillis) {
        if (gameDir == null) {
            return null;
        }
        File crashDir = new File(gameDir, "crash-reports");
        long threshold = afterMillis <= 0 ? 0 : afterMillis - 5000;
        File[] reports = crashDir.listFiles((dir, name) -> {
            if (!name.endsWith(".txt")) {
                return false;
            }
            File file = new File(dir, name);
            return file.lastModified() >= threshold;
        });
        if (reports == null || reports.length == 0) {
            return null;
        }
        return List.of(reports).stream()
                .max(Comparator.comparingLong(File::lastModified))
                .orElse(null);
    }

    private static String readFileTail(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return "";
        }
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (text.length() <= MAX_REPORT_CHARS) {
                return text;
            }
            return text.substring(text.length() - MAX_REPORT_CHARS);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String join(String... texts) {
        StringBuilder sb = new StringBuilder();
        for (String text : texts) {
            if (text != null && !text.isBlank()) {
                sb.append(text).append('\n');
            }
        }
        return sb.toString();
    }
}
