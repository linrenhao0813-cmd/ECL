package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.config.SettingsManager;
import com.ecl.util.HttpUtil;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsNavigationTest extends ApplicationTest {
    private LauncherUI launcher;
    private Stage stage;

    @Override
    public void start(Stage primaryStage) {
        launcher = new LauncherUI();
        stage = new Stage();
        launcher.start(stage);
    }

    @Override
    public void stop() {
        if (launcher != null) {
            launcher.stop();
        }
        if (stage != null) {
            stage.close();
        }
    }

    @Test
    void invalidSaveRetainsTheOriginalTabAndPromptsAgain() {
        interact(() -> {
            TabPane tabs = openSettings();
            Tab downloads = tabs.getTabs().get(1);
            tabs.getSelectionModel().select(downloads);
            TextField concurrency = field(downloads, "settings-download-concurrency");
            concurrency.setText("invalid");
            AtomicInteger prompts = choose(SettingsPageGuard.Choice.SAVE);

            tabs.getSelectionModel().select(2);
            assertSame(downloads, tabs.getSelectionModel().getSelectedItem());
            assertEquals("invalid", concurrency.getText());
            assertEquals(1, prompts.get());
            tabs.getSelectionModel().select(3);
            assertSame(downloads, tabs.getSelectionModel().getSelectedItem());
            assertEquals(2, prompts.get());
            assertFalse(launcher.accountSettingsSelected);
        });
    }

    @Test
    void invalidDefaultsKeepInputsWhenTheUserChoosesSave() {
        interact(() -> {
            TabPane tabs = openSettings();
            Tab defaults = tabs.getTabs().get(2);
            tabs.getSelectionModel().select(defaults);
            field(defaults, "settings-default-java").setText("");
            TextField memory = field(defaults, "settings-default-memory");
            memory.setText("not-a-number");
            AtomicInteger prompts = choose(SettingsPageGuard.Choice.SAVE);

            launcher.setActiveView(AppView.HOME);
            assertEquals(AppView.SETTINGS, launcher.activeView);
            assertSame(defaults, tabs.getSelectionModel().getSelectedItem());
            assertEquals("not-a-number", memory.getText());
            launcher.renderActiveView();
            assertSame(tabs, settingsTabs());
            assertEquals(2, prompts.get());
        });
    }

    @Test
    void cancelAndDiscardApplyToTabChanges() {
        interact(() -> {
            TabPane tabs = openSettings();
            Tab downloads = tabs.getTabs().get(1);
            tabs.getSelectionModel().select(downloads);
            TextField original = field(downloads, "settings-download-concurrency");
            String saved = original.getText();
            original.setText("invalid");
            choose(SettingsPageGuard.Choice.CANCEL);
            tabs.getSelectionModel().select(0);
            assertSame(downloads, tabs.getSelectionModel().getSelectedItem());
            assertEquals("invalid", original.getText());

            AtomicInteger prompts = choose(SettingsPageGuard.Choice.DISCARD);
            tabs.getSelectionModel().select(0);
            assertSame(tabs.getTabs().getFirst(), tabs.getSelectionModel().getSelectedItem());
            assertEquals(saved, field(downloads, "settings-download-concurrency").getText());
            assertNotSame(original, field(downloads, "settings-download-concurrency"));
            tabs.getSelectionModel().select(downloads);
            tabs.getSelectionModel().select(0);
            assertEquals(1, prompts.get());
        });
    }

    @Test
    void topLevelNavigationRetainsDraftOnCancelAndDiscardsOnlyAfterConfirmation() {
        interact(() -> {
            TabPane tabs = openSettings();
            Tab downloads = tabs.getTabs().get(1);
            tabs.getSelectionModel().select(downloads);
            TextField concurrency = field(downloads, "settings-download-concurrency");
            String saved = concurrency.getText();
            concurrency.setText("invalid");
            Node workspace = launcher.workspacePane.getChildren().getFirst();
            AtomicInteger prompts = choose(SettingsPageGuard.Choice.CANCEL);

            launcher.setActiveView(AppView.HOME);
            assertEquals(AppView.SETTINGS, launcher.activeView);
            assertSame(workspace, launcher.workspacePane.getChildren().getFirst());
            assertEquals("invalid", concurrency.getText());
            assertEquals(1, prompts.get());

            prompts = choose(SettingsPageGuard.Choice.DISCARD);
            launcher.setActiveView(AppView.HOME);
            assertEquals(AppView.HOME, launcher.activeView);
            assertEquals(1, prompts.get());
            launcher.setActiveView(AppView.SETTINGS);
            assertNotSame(tabs, settingsTabs());
            assertEquals(saved, field(settingsTabs().getTabs().get(1), "settings-download-concurrency").getText());
            launcher.setActiveView(AppView.HOME);
            assertEquals(1, prompts.get(), "a clean new settings page must not retain the old guard");
        });
    }

    @Test
    void pageRebuildCannotDiscardTheCurrentDraftWithoutConfirmation() {
        interact(() -> {
            TabPane tabs = openSettings();
            Tab downloads = tabs.getTabs().get(1);
            tabs.getSelectionModel().select(downloads);
            TextField concurrency = field(downloads, "settings-download-concurrency");
            String saved = concurrency.getText();
            concurrency.setText("invalid");
            AtomicInteger prompts = choose(SettingsPageGuard.Choice.CANCEL);

            launcher.renderActiveView();
            assertSame(tabs, settingsTabs());
            assertEquals("invalid", concurrency.getText());
            assertEquals(1, prompts.get());

            choose(SettingsPageGuard.Choice.DISCARD);
            launcher.renderActiveView();
            assertNotSame(tabs, settingsTabs());
            assertEquals(saved, field(settingsTabs().getTabs().get(1), "settings-download-concurrency").getText());
        });
    }

    @Test
    void explicitSaveAndDiscardClearOnlyTheirOwnDrafts() {
        interact(() -> {
            int previous = launcher.settingsManager.get(ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT);
            try {
                TabPane tabs = openSettings();
                Tab downloads = tabs.getTabs().get(1);
                tabs.getSelectionModel().select(downloads);
                int requested = previous == 1 ? 2 : 1;
                field(downloads, "settings-download-concurrency").setText(Integer.toString(requested));
                button(downloads, "settings-download-save").fire();
                assertEquals(requested, launcher.settingsManager.get(ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT));
                AtomicInteger prompts = choose(SettingsPageGuard.Choice.CANCEL);
                tabs.getSelectionModel().select(2);
                assertEquals(0, prompts.get());

                Tab defaults = tabs.getTabs().get(2);
                String savedMemory = field(defaults, "settings-default-memory").getText();
                field(defaults, "settings-default-memory").setText("invalid");
                button(defaults, "settings-default-discard").fire();
                assertEquals(savedMemory, field(defaults, "settings-default-memory").getText());
                tabs.getSelectionModel().select(downloads);
                field(downloads, "settings-download-concurrency").setText("invalid");
                button(downloads, "settings-download-discard").fire();
                assertEquals(Integer.toString(requested), field(downloads, "settings-download-concurrency").getText());
                launcher.setActiveView(AppView.HOME);
                assertEquals(AppView.HOME, launcher.activeView);
                assertEquals(0, prompts.get());
            } finally {
                launcher.settingsManager.set(ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT, previous);
                launcher.settingsManager.save();
                HttpUtil.setDownloadMaxConcurrent(previous);
            }
        });
    }

    @Test
    void failedDownloadWriteRestoresOnlyFormSettingsAndDiscardUsesThePreviousValues() {
        interact(() -> {
            SettingsManager originalManager = launcher.settingsManager;
            FailingSettingsManager failing = new FailingSettingsManager();
            try {
                failing.set(ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT, 3);
                failing.remove(ECLConfig.KEY_DOWNLOAD_RATE_LIMIT_KB);
                failing.remove(ECLConfig.KEY_MOD_RELEASE_CHANNEL);
                failing.remove(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE);
                failing.set(ECLConfig.KEY_USERNAME, "UnrelatedAccount");
                launcher.settingsManager = failing;
                TabPane tabs = openSettings();
                Tab downloads = tabs.getTabs().get(1);
                tabs.getSelectionModel().select(downloads);
                TextField concurrency = field(downloads, "settings-download-concurrency");
                concurrency.setText("invalid");
                button(downloads, "settings-download-save").fire();
                assertEquals(0, failing.writeAttempts);
                assertEquals(3, failing.get(ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT));
                int activeConcurrency = HttpUtil.getDownloadMaxConcurrent();
                long activeRate = HttpUtil.getDownloadRateLimitBytesPerSecond();
                concurrency.setText("1");
                choose(SettingsPageGuard.Choice.SAVE);
                tabs.getSelectionModel().select(0);
                assertSame(downloads, tabs.getSelectionModel().getSelectedItem());
                assertEquals("1", concurrency.getText());
                assertEquals(1, failing.writeAttempts);
                assertEquals(3, failing.get(ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT));
                assertFalse(failing.has(ECLConfig.KEY_DOWNLOAD_RATE_LIMIT_KB));
                assertFalse(failing.has(ECLConfig.KEY_MOD_RELEASE_CHANNEL));
                assertFalse(failing.has(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE));
                assertEquals("UnrelatedAccount", failing.get(ECLConfig.KEY_USERNAME));
                assertEquals(activeConcurrency, HttpUtil.getDownloadMaxConcurrent());
                assertEquals(activeRate, HttpUtil.getDownloadRateLimitBytesPerSecond());
                button(downloads, "settings-download-discard").fire();
                assertEquals("3", field(downloads, "settings-download-concurrency").getText());
                tabs.getSelectionModel().select(0);
                assertSame(tabs.getTabs().getFirst(), tabs.getSelectionModel().getSelectedItem());
            } finally {
                launcher.settingsManager = originalManager;
                failing.close();
            }
        });
    }

    @Test
    void failedDefaultWritePreservesRuntimeValuesAndRestoresDiscardBaseline() {
        interact(() -> {
            SettingsManager originalManager = launcher.settingsManager;
            String previousJava = launcher.javaPath;
            int previousMemory = launcher.maxMemoryMb;
            String previousJvm = launcher.extraJvmArgs;
            FailingSettingsManager failing = new FailingSettingsManager();
            try {
                failing.remove(ECLConfig.KEY_JAVA_PATH);
                failing.set(ECLConfig.KEY_MAX_MEMORY_MB, 2048);
                failing.set(ECLConfig.KEY_JVM_ARGS, "");
                failing.set(ECLConfig.KEY_USERNAME, "UnrelatedAccount");
                launcher.settingsManager = failing;
                launcher.javaPath = "";
                launcher.maxMemoryMb = 2048;
                launcher.extraJvmArgs = "";
                TabPane tabs = openSettings();
                Tab defaults = tabs.getTabs().get(2);
                tabs.getSelectionModel().select(defaults);
                field(defaults, "settings-default-memory").setText("4096");
                field(defaults, "settings-default-jvm").setText("-XX:+UseG1GC");
                choose(SettingsPageGuard.Choice.SAVE);
                launcher.setActiveView(AppView.HOME);
                assertEquals(AppView.SETTINGS, launcher.activeView);
                assertEquals(1, failing.writeAttempts);
                assertEquals("4096", field(defaults, "settings-default-memory").getText());
                assertEquals("-XX:+UseG1GC", field(defaults, "settings-default-jvm").getText());
                assertFalse(failing.has(ECLConfig.KEY_JAVA_PATH));
                assertEquals(2048, failing.get(ECLConfig.KEY_MAX_MEMORY_MB));
                assertEquals("", failing.get(ECLConfig.KEY_JVM_ARGS));
                assertEquals("UnrelatedAccount", failing.get(ECLConfig.KEY_USERNAME));
                assertEquals("", launcher.javaPath);
                assertEquals(2048, launcher.maxMemoryMb);
                assertEquals("", launcher.extraJvmArgs);
                button(defaults, "settings-default-discard").fire();
                assertEquals("2048", field(defaults, "settings-default-memory").getText());
                assertEquals("", field(defaults, "settings-default-jvm").getText());
                launcher.setActiveView(AppView.HOME);
                assertEquals(AppView.HOME, launcher.activeView);
            } finally {
                launcher.settingsManager = originalManager;
                launcher.javaPath = previousJava;
                launcher.maxMemoryMb = previousMemory;
                launcher.extraJvmArgs = previousJvm;
                failing.close();
            }
        });
    }

    @Test
    void successfulSaveAllowsTheRequestedTabSwitch() {
        interact(() -> {
            int previous = launcher.settingsManager.get(ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT);
            try {
                TabPane tabs = openSettings();
                Tab downloads = tabs.getTabs().get(1);
                tabs.getSelectionModel().select(downloads);
                int requested = previous == 1 ? 2 : 1;
                field(downloads, "settings-download-concurrency").setText(Integer.toString(requested));
                AtomicInteger prompts = choose(SettingsPageGuard.Choice.SAVE);
                tabs.getSelectionModel().select(2);
                assertSame(tabs.getTabs().get(2), tabs.getSelectionModel().getSelectedItem());
                assertEquals(requested, launcher.settingsManager.get(ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT));
                assertEquals(1, prompts.get());
                launcher.setActiveView(AppView.HOME);
                assertEquals(AppView.HOME, launcher.activeView);
                assertEquals(1, prompts.get());
            } finally {
                launcher.settingsManager.set(ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT, previous);
                launcher.settingsManager.save();
                HttpUtil.setDownloadMaxConcurrent(previous);
            }
        });
    }

    @Test
    void persistenceFailureKeepsGuardDirtyUntilASubsequentSuccessfulSave() {
        interact(() -> {
            Tab form = new Tab("Form");
            Tab other = new Tab("Other");
            TabPane tabs = new TabPane(form, other);
            AtomicInteger attempts = new AtomicInteger();
            SettingsPageGuard guard = new SettingsPageGuard(tabs, tab -> SettingsPageGuard.Choice.SAVE);
            guard.onSave(form, () -> attempts.incrementAndGet() >= 3);
            guard.markDirty(form);

            tabs.getSelectionModel().select(other);
            assertSame(form, tabs.getSelectionModel().getSelectedItem());
            assertFalse(guard.confirmDeparture());
            assertSame(form, tabs.getSelectionModel().getSelectedItem());
            assertTrue(guard.confirmDeparture());
            assertTrue(guard.confirmDeparture());
            assertEquals(3, attempts.get());
        });
    }

    private TabPane openSettings() {
        launcher.setActiveView(AppView.SETTINGS);
        return settingsTabs();
    }

    private TabPane settingsTabs() {
        return (TabPane) stage.getScene().lookup("#settings-tabs");
    }

    private AtomicInteger choose(SettingsPageGuard.Choice choice) {
        AtomicInteger prompts = new AtomicInteger();
        launcher.pageFactory.setSettingsChoiceProvider(tab -> {
            prompts.incrementAndGet();
            return choice;
        });
        return prompts;
    }

    private static TextField field(Tab tab, String id) {
        return (TextField) tab.getContent().lookup("#" + id);
    }

    private static Button button(Tab tab, String id) {
        return (Button) tab.getContent().lookup("#" + id);
    }

    private static final class FailingSettingsManager extends SettingsManager {
        private int writeAttempts;

        @Override
        public synchronized boolean save() {
            writeAttempts++;
            return false;
        }
    }
}
