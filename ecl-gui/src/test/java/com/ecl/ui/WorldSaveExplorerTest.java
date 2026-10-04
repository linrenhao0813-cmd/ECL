package com.ecl.ui;

import com.ecl.game.WorldSave;
import com.ecl.game.WorldSaveSettings;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSaveExplorerTest extends ApplicationTest {
    private final AtomicReference<WorldSave> opened = new AtomicReference<>();
    private WorldSaveExplorer explorer;
    private TableView<WorldSave> table;
    private TextField search;
    private TreeView<?> tree;
    private WorldSave first;
    private WorldSave second;

    @Override
    @SuppressWarnings("unchecked")
    public void start(Stage stage) {
        first = world("Alpine", "1.21.1", "fabric", 100);
        second = world("Coast", "1.20.1", "vanilla", 200);
        explorer = new WorldSaveExplorer(new LauncherUI(), new VBox(), opened::set, () -> { });
        stage.setScene(new Scene(explorer, 1100, 650));
        stage.show();
        table = (TableView<WorldSave>) explorer.lookup("#save-file-table");
        search = (TextField) explorer.lookup("#save-search");
        tree = (TreeView<?>) explorer.lookup("#save-navigation-tree");
        explorer.setWorlds(List.of(first, second), null, null);
    }

    @Test
    void navigationSupportsEnterUpBackAndForward() {
        interact(() -> {
            table.getSelectionModel().select(first);
            table.fireEvent(key(KeyCode.ENTER, false));
            assertEquals(first, opened.get());
            assertTrue(search.isDisabled());
            explorer.fireEvent(key(KeyCode.UP, true));
            assertNull(opened.get());
            assertEquals(List.of(first), table.getItems());
            explorer.fireEvent(key(KeyCode.LEFT, true));
            assertEquals(first, opened.get());
            explorer.fireEvent(key(KeyCode.LEFT, true));
            assertNull(opened.get());
            assertEquals(2, table.getItems().size());
            explorer.fireEvent(key(KeyCode.RIGHT, true));
            assertEquals(first, opened.get());
        });
    }

    @Test
    void searchAndTreeSelectionFilterWithoutOpeningAWorld() {
        interact(() -> {
            search.setText("ALPINE");
            assertEquals(List.of(first), table.getItems());
            search.setText("does-not-exist");
            assertTrue(table.getItems().isEmpty());
            assertNotNull(table.getPlaceholder());
            search.clear();
            tree.getSelectionModel().select(1);
            assertEquals(1, table.getItems().size());
            assertNull(opened.get());
            explorer.fireEvent(key(KeyCode.UP, true));
            assertEquals(2, table.getItems().size());
        });
    }

    @Test
    void refreshKeepsOpenWorldAndReturnsToRootIfItDisappears() {
        interact(() -> {
            table.getSelectionModel().select(first);
            table.fireEvent(key(KeyCode.ENTER, false));
            explorer.setWorlds(List.of(first, second), first.groupId(), first.name());
            assertEquals(first, opened.get());
            explorer.setWorlds(List.of(second), null, null);
            assertNull(opened.get());
            assertEquals(List.of(second), table.getItems());
        });
    }

    private static WorldSave world(String name, String version, String loader, long modified) {
        return new WorldSave(name, Path.of(System.getProperty("java.io.tmpdir"), "explorer-fixtures", name),
                "instance-" + name, version, loader, "", modified, WorldSaveSettings.defaults());
    }

    private static KeyEvent key(KeyCode code, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, alt, false);
    }
}
