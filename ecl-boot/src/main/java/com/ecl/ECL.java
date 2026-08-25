package com.ecl;

/**
 * Main entry point for ECL.
 * Delegates to ECLauncher to keep existing run configs working.
 *
 * <p>The launcher needs JavaFX on the classpath. Instead of probing the Gradle dependency cache
 * with a hard-coded layout (which breaks on Gradle upgrades or a custom {@code GRADLE_USER_HOME}),
 * a missing JavaFX class simply prints how to run the app with the wrapper.</p>
 */
public class ECL {
    private static final String JAVAFX_CLASS = "javafx.application.Application";

    public static void main(String[] args) {
        if (!hasClass(JAVAFX_CLASS)) {
            System.err.println("ECL 启动失败：当前运行配置没有包含 JavaFX 依赖。");
            System.err.println("请使用项目自带的 Gradle 包装器运行：");
            System.err.println("  .\\gradlew.bat run              # 开发运行");
            System.err.println("  .\\gradlew.bat installDist      # 生成可分发镜像 ecl-boot/build/install/ECL/");
            System.err.println("或在 IntelliJ 中重新导入 Gradle 项目后运行 ecl-boot 的 ECL 主类。");
            return;
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
}
