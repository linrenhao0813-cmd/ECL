package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.MicrosoftAccountStore;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;

/** Presents the existing launcher account controls without creating a second authentication state. */
final class AccountManagementPage extends VBox {
    private final LauncherUI ui;
    private final Label feedback = new Label();

    AccountManagementPage(LauncherUI ui) {
        this.ui = ui;
        setSpacing(18);
        setId("account-management");
        getStyleClass().add("account-management-page");
        Label current = new Label();
        current.textProperty().bind(Bindings.createStringBinding(() ->
                        LauncherUI.AUTH_MICROSOFT.equals(ui.authTypeCombo.getValue()) && ui.microsoftAccountCombo.getValue() == null
                                ? GuiMessages.get("accounts.notSelected")
                                : ui.authSummaryLabel.getText() + "  ·  " + ui.usernameField.getText(),
                ui.authTypeCombo.valueProperty(), ui.authSummaryLabel.textProperty(),
                ui.usernameField.textProperty(), ui.microsoftAccountCombo.valueProperty()));
        current.setWrapText(true);
        current.getStyleClass().add("account-current");
        getChildren().addAll(ui.createSurface(GuiMessages.get("accounts.current"), null, current),
                ui.createSurface(GuiMessages.get("accounts.title"), GuiMessages.get("accounts.subtitle"), createEditor()));
        ui.updateAuthFields();
    }

    private VBox createEditor() {
        VBox mode = field(GuiMessages.get("accounts.mode"), ui.authTypeCombo);
        VBox username = field(GuiMessages.get("accounts.username"), ui.usernameField);
        followVisibility(username, ui.usernameField);
        VBox saved = field(GuiMessages.get("accounts.saved"), ui.microsoftAccountCombo);
        followVisibility(saved, ui.microsoftAccountCombo);
        VBox server = field(GuiMessages.get("accounts.server"), ui.yggdrasilServerField);
        followVisibility(server, ui.yggdrasilServerField);
        VBox password = field(GuiMessages.get("accounts.password"), ui.passwordField);
        followVisibility(password, ui.passwordField);

        Button remove = ui.createActionButton(GuiMessages.get("accounts.remove"), "ghost-button", this::removeAccount);
        remove.disableProperty().bind(ui.microsoftAccountCombo.valueProperty().isNull()
                .or(ui.microsoftLoginBtn.disableProperty()));
        FlowPane microsoftActions = actions(ui.microsoftLoginBtn, ui.microsoftAddAccountBtn, remove);
        followVisibility(microsoftActions, ui.microsoftAccountCombo);
        FlowPane skinActions = actions(ui.skinUploadBtn, ui.offlineSkinRemoveBtn);
        followVisibility(skinActions, ui.skinUploadBtn);
        Button apply = ui.createActionButton(GuiMessages.get("accounts.apply"), "primary-button", this::saveSelection);
        apply.setId("account-apply");
        apply.disableProperty().bind(ui.authTypeCombo.disableProperty());
        feedback.setWrapText(true);
        feedback.getStyleClass().add("status-detail");
        Label help = new Label();
        help.textProperty().bind(ui.authHintLabel.textProperty());
        help.setWrapText(true);
        help.getStyleClass().add("status-detail");
        return new VBox(16, mode, username, saved, microsoftActions, server, password,
                skinActions, help, apply, feedback);
    }

    private VBox field(String title, Node control) {
        detach(control);
        if (control instanceof javafx.scene.layout.Region region) {
            region.setMinWidth(0);
            region.setMaxWidth(Double.MAX_VALUE);
        }
        Label label = new Label(title);
        label.setLabelFor(control);
        label.getStyleClass().add("field-label");
        VBox field = new VBox(7, label, control);
        field.setMaxWidth(620);
        return field;
    }

    private static FlowPane actions(Node... nodes) {
        for (Node node : nodes) detach(node);
        return new FlowPane(10, 8, nodes);
    }

    private static void detach(Node node) {
        if (node.getParent() instanceof Pane parent) parent.getChildren().remove(node);
    }

    private static void followVisibility(Node container, Node control) {
        container.visibleProperty().bind(control.visibleProperty());
        container.managedProperty().bind(container.visibleProperty());
    }

    private void saveSelection() {
        String type = ui.authTypeCombo.getValue();
        String username = ui.usernameField.getText().trim();
        if (LauncherUI.AUTH_MICROSOFT.equals(type) && ui.selectedMicrosoftAccount == null) {
            feedback.setText(GuiMessages.get("accounts.loginFirst"));
            return;
        }
        if (username.isBlank()) {
            feedback.setText(GuiMessages.get("accounts.nameRequired"));
            ui.usernameField.requestFocus();
            return;
        }
        if (LauncherUI.AUTH_YGGDRASIL.equals(type) && ui.yggdrasilServerField.getText().isBlank()) {
            feedback.setText(GuiMessages.get("accounts.serverRequired"));
            ui.yggdrasilServerField.requestFocus();
            return;
        }
        ui.settingsManager.set(ECLConfig.KEY_AUTH_TYPE, type);
        ui.settingsManager.set(ECLConfig.KEY_USERNAME, username);
        if (LauncherUI.AUTH_YGGDRASIL.equals(type)) {
            ui.settingsManager.set(ECLConfig.KEY_YGGDRASIL_SERVER, ui.yggdrasilServerField.getText().trim());
        }
        feedback.setText(GuiMessages.get(ui.settingsManager.save() ? "accounts.savedOk" : "accounts.saveFailed"));
        ui.updateRuntimeSummary();
    }

    private void removeAccount() {
        MicrosoftAccountStore.Account account = ui.microsoftAccountCombo.getValue();
        if (account == null) return;
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                GuiMessages.get("accounts.removeHint", account.username()), ButtonType.OK, ButtonType.CANCEL);
        confirmation.initOwner(ui.primaryStage);
        confirmation.setTitle(GuiMessages.get("accounts.remove"));
        confirmation.setHeaderText(GuiMessages.get("accounts.remove"));
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        if (!ui.microsoftAccountStore.remove(account.uuid())) {
            feedback.setText(GuiMessages.get("accounts.removeFailed"));
            return;
        }
        ui.settingsManager.remove("microsoftRefreshToken");
        ui.settingsManager.remove("microsoftAccessToken");
        ui.settingsManager.remove(ECLConfig.KEY_MICROSOFT_ACCESS_TOKEN_EXPIRES_AT);
        ui.settingsManager.remove(ECLConfig.KEY_MICROSOFT_PROFILE_UUID);
        ui.settingsManager.remove(ECLConfig.KEY_MICROSOFT_PROFILE_NAME);
        ui.microsoftAccountCombo.setValue(null);
        ui.microsoftAccountCombo.getItems().setAll(ui.microsoftAccountStore.list());
        if (ui.microsoftAccountCombo.getItems().isEmpty()) {
            ui.authTypeCombo.setValue(LauncherUI.AUTH_OFFLINE);
        } else {
            ui.microsoftAccountCombo.getSelectionModel().selectFirst();
        }
        ui.settingsManager.set(ECLConfig.KEY_AUTH_TYPE, ui.authTypeCombo.getValue());
        feedback.setText(GuiMessages.get(ui.settingsManager.save() ? "accounts.removed" : "accounts.saveFailed"));
        ui.updateAuthFields();
    }
}
