package com.ecl.modrinth.ui;

import com.ecl.modrinth.api.ModSearchIndex;
import com.ecl.util.Messages;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModSearchIndexCellTest extends ApplicationTest {
    @Override public void start(Stage stage) { }

    @Test
    void sortingLabelsFollowTheSelectedLocaleAndClearEmptyCells() {
        Locale previous = Messages.locale();
        try {
            interact(() -> {
                ModSearchIndexCell cell = new ModSearchIndexCell();
                Messages.setLocale(Locale.ENGLISH);
                cell.updateItem(ModSearchIndex.RELEVANCE, false);
                assertEquals("Relevance", cell.getText());
                cell.updateItem(ModSearchIndex.DOWNLOADS, false);
                assertEquals("Downloads", cell.getText());
                Messages.setLocale(Locale.TRADITIONAL_CHINESE);
                cell.updateItem(ModSearchIndex.RELEVANCE, false);
                assertEquals("相關度", cell.getText());
                Messages.setLocale(Locale.SIMPLIFIED_CHINESE);
                cell.updateItem(ModSearchIndex.RELEVANCE, false);
                assertEquals("相关度", cell.getText());
                cell.updateItem(null, true);
                assertNull(cell.getText());
            });
        } finally {
            Messages.setLocale(previous);
        }
    }
}
