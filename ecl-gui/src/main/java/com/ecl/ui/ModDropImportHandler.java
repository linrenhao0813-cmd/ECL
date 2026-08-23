package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import javafx.application.Platform;
import javafx.scene.layout.Pane;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Handles importing JAR mods dropped onto the launcher window. */
final class ModDropImportHandler {
    private final MainController controller;
    private final Supplier<String> selectedProfile;
    private final Supplier<File> gameRoot;
    private final Function<String, File> versionGameDirectory;
    private final BiConsumer<String, String> status;
    private final Runnable afterImport;

    ModDropImportHandler(MainController controller, Supplier<String> selectedProfile,
                         Supplier<File> gameRoot, Function<String, File> versionGameDirectory,
                         BiConsumer<String, String> status, Runnable afterImport) {
        this.controller = controller;
        this.selectedProfile = selectedProfile;
        this.gameRoot = gameRoot;
        this.versionGameDirectory = versionGameDirectory;
        this.status = status;
        this.afterImport = afterImport;
    }

    void install(Pane root) {
        root.addEventHandler(DragEvent.DRAG_OVER, event -> handleDragOver(root, event));
        root.addEventHandler(DragEvent.DRAG_EXITED, event -> {
            root.getStyleClass().remove("drop-target-active");
            event.consume();
        });
        root.addEventHandler(DragEvent.DRAG_DROPPED, event -> handleDrop(root, event));
    }

    private void handleDragOver(Pane root, DragEvent event) {
        if (hasModFiles(event.getDragboard())) {
            event.acceptTransferModes(TransferMode.COPY);
            root.getStyleClass().add("drop-target-active");
        }
        event.consume();
    }

    private void handleDrop(Pane root, DragEvent event) {
        root.getStyleClass().remove("drop-target-active");
        List<Path> files = event.getDragboard().hasFiles()
                ? event.getDragboard().getFiles().stream().map(File::toPath)
                .filter(ModDropImportHandler::isModJar).toList()
                : List.of();
        event.setDropCompleted(!files.isEmpty());
        event.consume();
        if (files.isEmpty()) {
            status.accept("Mod 导入", "请拖入 .jar 模组文件");
        } else {
            importMods(files);
        }
    }

    private void importMods(List<Path> files) {
        String profileId = selectedProfile.get();
        if (profileId == null || profileId.isBlank()) {
            status.accept("Mod 导入失败", "请先选择一个 Fabric、Quilt、Forge 或 NeoForge 实例");
            return;
        }
        try {
            ModInstanceContext instance = VersionProfileModInstanceContext.load(profileId,
                    ECLConfig.getVersionsDir().toPath(), gameRoot.get().toPath(),
                    versionGameDirectory.apply(profileId).toPath());
            if (!instance.loader().supportsMods()) {
                status.accept("Mod 导入失败", "当前实例不是支持模组的加载器实例");
                return;
            }
            CompletableFuture<List<String>> imported =
                    CompletableFuture.completedFuture(new java.util.ArrayList<>());
            for (Path file : files) {
                imported = imported.thenCompose(names -> controller.modManagementService()
                        .importLocalJar(instance, file).thenApply(mod -> {
                            names.add(file.getFileName().toString());
                            return names;
                        }));
            }
            imported.whenComplete((names, error) -> Platform.runLater(() -> {
                if (error != null) {
                    status.accept("Mod 导入失败", cleanMessage(error));
                } else {
                    status.accept("Mod 导入完成", "已导入 " + names.size()
                            + " 个模组到 " + instance.modsDirectory());
                    afterImport.run();
                }
            }));
        } catch (Exception error) {
            status.accept("Mod 导入失败", cleanMessage(error));
        }
    }

    private static boolean hasModFiles(Dragboard board) {
        return board != null && board.hasFiles()
                && board.getFiles().stream().anyMatch(file -> isModJar(file.toPath()));
    }

    private static boolean isModJar(Path file) {
        return file != null && Files.isRegularFile(file)
                && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static String cleanMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
