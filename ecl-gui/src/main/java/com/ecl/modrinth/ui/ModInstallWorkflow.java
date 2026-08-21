package com.ecl.modrinth.ui;

import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.service.ResolvedMod;
import com.ecl.modrinth.transaction.ModInstallationPlan;
import com.ecl.modrinth.ui.viewmodel.ModBrowserViewModel;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Prepares, previews and submits a mod installation plan. */
final class ModInstallWorkflow {
    private final ModBrowserViewModel viewModel;
    private final Supplier<ReleaseChannel> releaseChannel;

    ModInstallWorkflow(ModBrowserViewModel viewModel,
                       Supplier<ReleaseChannel> releaseChannel) {
        this.viewModel = viewModel;
        this.releaseChannel = releaseChannel;
    }

    void prepare(ModVersion version, String failureHeader) {
        if (version == null) {
            return;
        }
        viewModel.preparePlan(version, Set.of(), releaseChannel.get())
                .whenComplete((plan, error) -> Platform.runLater(() ->
                        handlePreparedPlan(version, plan, error, failureHeader)));
    }

    void handlePreparedPlan(ModVersion version, ModInstallationPlan plan,
                            Throwable error, String failureHeader) {
        if (error != null) {
            showFailure(failureHeader, error);
            return;
        }
        preview(version, plan);
    }

    private void showFailure(String header, Throwable error) {
        if (ModFailureMessages.isCancellation(error)) {
            return;
        }
        Alert dialog = new Alert(Alert.AlertType.ERROR);
        dialog.setTitle("模组安装失败");
        dialog.setHeaderText(header);
        dialog.setContentText("失败原因：" + ModFailureMessages.planFailureReason(error));
        dialog.getDialogPane().setPrefWidth(560);
        dialog.showAndWait();
    }

    private void preview(ModVersion version, ModInstallationPlan plan) {
        ButtonType install = new ButtonType("确认安装", ButtonBar.ButtonData.OK_DONE);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("安装计划");
        dialog.setHeaderText(plan.installable()
                ? "将安装 " + plan.files().size() + " 个文件"
                : "安装计划存在冲突");
        dialog.getButtonTypes().setAll(install, ButtonType.CANCEL);

        VBox content = new VBox(8);
        content.getChildren().add(new Label("目标目录: " + plan.instance().modsDirectory()));
        content.getChildren().add(new Label(
                "下载大小: " + ModUiFormatter.formatBytes(plan.totalDownloadSize())));
        for (var file : plan.files()) {
            Label label = new Label((file.dependency() ? "依赖  " : "主模组  ")
                    + file.version().name() + " · " + file.version().versionNumber()
                    + " · " + ModUiFormatter.formatBytes(file.file().size()));
            label.setWrapText(true);
            content.getChildren().add(label);
        }
        List<CheckBox> optionalChoices = addOptionalDependencies(content, plan);
        addConflictsAndWarnings(content, plan);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(620);
        dialog.getDialogPane().lookupButton(install).setDisable(!plan.installable());
        Optional<ButtonType> choice = dialog.showAndWait();
        if (choice.orElse(ButtonType.CANCEL) != install) {
            return;
        }
        install(version, plan, optionalChoices);
    }

    private List<CheckBox> addOptionalDependencies(
            VBox content, ModInstallationPlan plan) {
        List<CheckBox> choices = new ArrayList<>();
        if (plan.optionalDependencies().isEmpty()) {
            return choices;
        }
        content.getChildren().add(new Separator());
        content.getChildren().add(new Label("可选依赖"));
        for (ResolvedMod optional : plan.optionalDependencies()) {
            CheckBox check = new CheckBox(optional.version().name()
                    + " · " + optional.version().versionNumber());
            check.setUserData(optional);
            choices.add(check);
            content.getChildren().add(check);
        }
        return choices;
    }

    private void addConflictsAndWarnings(VBox content, ModInstallationPlan plan) {
        for (var conflict : plan.conflicts()) {
            Label label = new Label("冲突: " + conflict.message());
            label.getStyleClass().add("mod-error");
            label.setWrapText(true);
            content.getChildren().add(label);
        }
        for (String warning : plan.warnings()) {
            Label label = new Label("提示: " + warning);
            label.getStyleClass().add("status-detail");
            label.setWrapText(true);
            content.getChildren().add(label);
        }
    }

    private void install(ModVersion version, ModInstallationPlan plan,
                         List<CheckBox> optionalChoices) {
        Set<String> selectedOptional = new LinkedHashSet<>();
        optionalChoices.stream().filter(CheckBox::isSelected).forEach(check -> {
            ResolvedMod optional = (ResolvedMod) check.getUserData();
            selectedOptional.add(optional.version().projectId());
        });
        if (selectedOptional.isEmpty()) {
            viewModel.install(plan);
            return;
        }
        viewModel.preparePlan(version, selectedOptional, releaseChannel.get())
                .whenComplete((expanded, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        showFailure("无法解析所选可选依赖", error);
                        return;
                    }
                    viewModel.install(expanded);
                }));
    }
}
