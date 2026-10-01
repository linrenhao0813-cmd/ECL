package com.ecl.ui;

import com.ecl.server.LocalServerManager;
import com.ecl.server.LocalServerProfile;
import com.ecl.util.JavaRuntimeUtil;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Local server controls; closing the page releases polling without stopping server processes. */
final class ServerManagementPage extends VBox implements AutoCloseable {
    private final LauncherUI ui;
    private final LocalServerManager manager;
    private final ListView<LocalServerProfile> servers = new ListView<>();
    private final TextField name = new TextField();
    private final TextField javaPath = new TextField();
    private final TextField memory = new TextField();
    private final CheckBox eula = new CheckBox(GuiMessages.get("server.local.eula"));
    private final TextField command = new TextField();
    private final TextArea console = new TextArea();
    private final Label status = new Label();
    private final Label processStatus = new Label();
    private final Label directory = new Label();
    private final VBox editor = new VBox(10);
    private final VBox listPane = new VBox(10);
    private final VBox body = new VBox(12);
    private final Button save;
    private final Button start;
    private final Button stop;
    private final Button send;
    private final Button importJar;
    private final Timeline refreshTimer;
    private LocalServerProfile selected;
    private boolean running;
    private boolean controllable;
    private boolean busy;
    private boolean refreshPending;
    private boolean closed;

    ServerManagementPage(LauncherUI ui) {
        this(ui, ui.controller.localServers());
    }

    ServerManagementPage(LauncherUI ui, LocalServerManager manager) {
        this.ui = ui;
        this.manager = manager;
        save = button("server.local.save", "primary-button", this::saveSettings);
        save.setId("local-server-save");
        start = button("server.local.start", "primary-button", this::startServer);
        start.setId("local-server-start");
        stop = button("server.local.stop", "secondary-button", this::stopServer);
        stop.setId("local-server-stop");
        send = button("server.local.send", "secondary-button", this::sendCommand);
        importJar = button("server.local.import", "secondary-button", this::importServer);
        buildView();
        refreshTimer = new Timeline(new KeyFrame(Duration.seconds(2), event -> refreshState()));
        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        refreshTimer.play();
        reloadProfiles(null);
    }

    private void buildView() {
        setId("local-server-management");
        setSpacing(12);
        setPadding(new Insets(16));
        getStyleClass().add("surface");
        setMinWidth(0);
        Label hint = text("server.local.subtitle");
        Label importHint = text("server.local.importHint");
        Button refresh = button("server.local.refresh", "ghost-button", () -> reloadProfiles(selectedId()));
        FlowPane toolbar = new FlowPane(10, 8, importJar, refresh);
        servers.setId("local-server-list");
        servers.setPrefHeight(260);
        servers.setMinHeight(110);
        servers.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(LocalServerProfile profile, boolean empty) {
                super.updateItem(profile, empty);
                setText(empty || profile == null ? null : profile.name());
            }
        });
        servers.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, profile) -> select(profile));
        listPane.getChildren().setAll(text("server.local.list"), servers);
        VBox.setVgrow(servers, Priority.ALWAYS);
        buildEditor();
        setCompact(false);
        console.setId("local-server-console");
        console.setEditable(false);
        console.setWrapText(false);
        console.setPrefRowCount(9);
        console.setPrefHeight(200);
        console.setMaxWidth(Double.MAX_VALUE);
        command.setId("local-server-command");
        command.setPromptText(GuiMessages.get("server.local.commandHint"));
        command.setOnAction(event -> sendCommand());
        ui.applyFieldStyle(command);
        HBox commandRow = new HBox(10, command, send);
        HBox.setHgrow(command, Priority.ALWAYS);
        status.setWrapText(true);
        status.getStyleClass().add("muted-text");
        getChildren().setAll(hint, toolbar, importHint, body,
                text("server.local.console"), console, commandRow, status);
        updateButtons();
    }

    private void buildEditor() {
        name.setId("local-server-name");
        javaPath.setId("local-server-java");
        memory.setId("local-server-memory");
        for (TextField field : List.of(name, javaPath, memory)) {
            ui.applyFieldStyle(field);
            field.textProperty().addListener((observable, previous, value) -> updateButtons());
        }
        eula.setId("local-server-eula");
        eula.setWrapText(true);
        eula.selectedProperty().addListener((observable, previous, value) -> updateButtons());
        Button browse = button("server.local.browseJava", "ghost-button", this::chooseJava);
        HBox javaRow = new HBox(8, javaPath, browse);
        HBox.setHgrow(javaPath, Priority.ALWAYS);
        Hyperlink agreement = new Hyperlink(GuiMessages.get("server.local.eulaLink"));
        agreement.setOnAction(event -> ui.desktopIntegration.openExternalUrl("https://www.minecraft.net/eula"));
        Button open = button("server.local.openDirectory", "ghost-button", this::openDirectory);
        FlowPane actions = new FlowPane(10, 8, save, start, stop, open);
        directory.setWrapText(true);
        directory.getStyleClass().add("muted-text");
        processStatus.setWrapText(true);
        editor.setMinWidth(0);
        editor.getChildren().setAll(
                text("server.local.name"), name, text("server.local.java"), javaRow,
                text("server.local.memory"), memory, eula, agreement,
                text("server.local.saveHint"), processStatus, actions, directory);
    }

    void setCompact(boolean compact) {
        body.getChildren().clear();
        listPane.setMinWidth(0);
        if (compact) {
            listPane.setPrefWidth(-1);
            servers.setPrefHeight(130);
            body.getChildren().setAll(listPane, editor);
        } else {
            servers.setPrefHeight(260);
            listPane.setPrefWidth(230);
            listPane.setMaxWidth(280);
            HBox row = new HBox(16, listPane, editor);
            HBox.setHgrow(editor, Priority.ALWAYS);
            row.setMinWidth(0);
            body.getChildren().setAll(row);
        }
        if (compact) {
            listPane.setMaxWidth(Double.MAX_VALUE);
        }
    }

    private Button button(String key, String style, Runnable action) {
        return ui.createActionButton(GuiMessages.get(key), style, action);
    }

    private static Label text(String key) {
        Label label = new Label(GuiMessages.get(key));
        label.setWrapText(true);
        label.getStyleClass().add("muted-text");
        return label;
    }

    private void select(LocalServerProfile profile) {
        selected = profile;
        running = false;
        controllable = false;
        name.setText(profile == null ? "" : profile.name());
        javaPath.setText(profile == null ? "" : profile.javaPath());
        memory.setText(profile == null ? "" : Integer.toString(profile.memoryMb()));
        eula.setSelected(profile != null && profile.eulaAccepted());
        directory.setText("");
        console.clear();
        processStatus.setText(GuiMessages.get(profile == null ? "server.local.empty" : "server.local.loading"));
        updateButtons();
        refreshState();
    }

    private String selectedId() {
        return selected == null ? null : selected.id();
    }

    private void reloadProfiles(String targetId) {
        if (closed || busy) {
            return;
        }
        busy = true;
        updateButtons();
        ui.controller.runAsync("ecl-server-list", () -> {
            try {
                List<LocalServerProfile> profiles = manager.list();
                onUi(() -> {
                    busy = false;
                    servers.getItems().setAll(profiles);
                    LocalServerProfile target = profiles.stream()
                            .filter(profile -> profile.id().equals(targetId)).findFirst()
                            .orElse(profiles.isEmpty() ? null : profiles.getFirst());
                    servers.getSelectionModel().select(target);
                    if (target == null) {
                        select(null);
                    }
                    updateButtons();
                });
            } catch (Exception error) {
                fail(error);
            }
        });
    }

    private void refreshState() {
        String id = selectedId();
        if (closed || id == null || refreshPending || busy) {
            return;
        }
        refreshPending = true;
        ui.controller.runAsync("ecl-server-console", () -> {
            try {
                boolean active = manager.isRunning(id);
                boolean control = manager.canControl(id);
                String log = String.join(System.lineSeparator(), manager.readLogTail(id, 200));
                String path = manager.directory(id).toString();
                onUi(() -> {
                    refreshPending = false;
                    if (!Objects.equals(id, selectedId())) {
                        refreshState();
                        return;
                    }
                    running = active;
                    controllable = control;
                    processStatus.setText(GuiMessages.get(active
                            ? control ? "server.local.running" : "server.local.detached"
                            : "server.local.stopped"));
                    directory.setText(path);
                    if (!console.getText().equals(log)) {
                        console.setText(log);
                        console.positionCaret(log.length());
                    }
                    updateButtons();
                });
            } catch (Exception error) {
                onUi(() -> {
                    refreshPending = false;
                    if (Objects.equals(id, selectedId())) {
                        showError(error);
                    }
                });
            }
        });
    }

    private boolean hasChanges() {
        return selected != null && (!name.getText().equals(selected.name())
                || !javaPath.getText().equals(selected.javaPath())
                || !memory.getText().equals(Integer.toString(selected.memoryMb()))
                || eula.isSelected() != selected.eulaAccepted());
    }

    private void updateButtons() {
        boolean unavailable = selected == null || busy;
        editor.setDisable(unavailable);
        name.setDisable(running);
        javaPath.setDisable(running);
        memory.setDisable(running);
        eula.setDisable(running);
        servers.setDisable(busy);
        importJar.setDisable(busy);
        save.setDisable(unavailable || running || !hasChanges());
        start.setDisable(unavailable || running || hasChanges() || !selected.eulaAccepted());
        stop.setDisable(unavailable || !running || !controllable);
        send.setDisable(unavailable || !running || !controllable);
        command.setDisable(unavailable || !running || !controllable);
    }

    private void importServer() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(GuiMessages.get("server.local.import"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR", "*.jar"));
        File source = chooser.showOpenDialog(ui.primaryStage);
        if (source == null) {
            return;
        }
        String profileName = source.getName().replaceFirst("(?i)\\.jar$", "");
        Path executable = resolveImportJava(JavaRuntimeUtil.resolveJavaExecutable(ui.javaPath), this::chooseJavaExecutable);
        if (executable == null) {
            return;
        }
        perform(() -> manager.importJar(profileName, source.toPath(), executable, 2048).id(),
                "server.local.imported", true);
    }

    static Path resolveImportJava(String detectedExecutable, java.util.function.Supplier<File> chooser) {
        Path detected = Path.of(detectedExecutable).toAbsolutePath().normalize();
        if (Files.isRegularFile(detected) && Files.isExecutable(detected)) {
            return detected;
        }
        File selected = chooser.get();
        return selected == null ? null : selected.toPath().toAbsolutePath().normalize();
    }

    private File chooseJavaExecutable() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(GuiMessages.get("server.local.java"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java", "java.exe"));
        return chooser.showOpenDialog(ui.primaryStage);
    }

    private void chooseJava() {
        File executable = chooseJavaExecutable();
        if (executable != null) {
            javaPath.setText(executable.getAbsolutePath());
        }
    }

    private void saveSettings() {
        if (selected == null || busy || running) {
            return;
        }
        String id = selected.id();
        String profileName = name.getText().trim();
        Path executable;
        int memoryMb;
        try {
            executable = Path.of(javaPath.getText().trim());
            memoryMb = Integer.parseInt(memory.getText().trim());
        } catch (IllegalArgumentException error) {
            status.setText(GuiMessages.get("server.local.invalidSettings"));
            return;
        }
        boolean accepted = eula.isSelected();
        perform(() -> {
            manager.saveSettings(id, profileName, executable, memoryMb);
            manager.setEulaAccepted(id, accepted);
            return id;
        }, "server.local.saved", true);
    }

    private void startServer() {
        if (selected == null || busy || start.isDisabled()) {
            return;
        }
        String id = selected.id();
        perform(() -> {
            manager.start(id);
            return id;
        }, "server.local.started", false);
    }

    private void stopServer() {
        if (selected == null || busy || stop.isDisabled()) {
            return;
        }
        String id = selected.id();
        perform(() -> {
            manager.stop(id);
            return id;
        }, "server.local.stopSent", false);
    }

    private void sendCommand() {
        if (selected == null || busy || send.isDisabled() || command.getText().isBlank()) {
            return;
        }
        String id = selected.id();
        String line = command.getText();
        perform(() -> {
            manager.sendCommand(id, line);
            return id;
        }, "server.local.commandSent", false);
    }

    private void openDirectory() {
        String id = selectedId();
        if (id == null) {
            return;
        }
        ui.controller.runAsync("ecl-server-folder", () -> {
            try {
                File folder = manager.directory(id).toFile();
                onUi(() -> ui.openLocalFolder(folder, GuiMessages.get("server.local.openDirectory")));
            } catch (Exception error) {
                onUi(() -> showError(error));
            }
        });
    }

    private void perform(ServerAction action, String successKey, boolean reload) {
        if (closed || busy) {
            return;
        }
        busy = true;
        status.setText(GuiMessages.get("server.local.loading"));
        updateButtons();
        ui.controller.runAsync("ecl-server-action", () -> {
            try {
                String id = action.run();
                onUi(() -> {
                    busy = false;
                    status.setText(GuiMessages.get(successKey));
                    if (successKey.equals("server.local.commandSent")) {
                        command.clear();
                    }
                    updateButtons();
                    if (reload) {
                        reloadProfiles(id);
                    } else {
                        refreshState();
                    }
                });
            } catch (Exception error) {
                fail(error);
            }
        });
    }

    private void fail(Exception error) {
        onUi(() -> {
            busy = false;
            showError(error);
            updateButtons();
        });
    }

    private void showError(Exception error) {
        status.setText(GuiMessages.get("server.local.failed", ui.cleanMessage(error)));
    }

    private void onUi(Runnable action) {
        Platform.runLater(() -> {
            if (!closed) {
                action.run();
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        refreshTimer.stop();
    }

    @FunctionalInterface
    private interface ServerAction {
        String run() throws Exception;
    }
}
