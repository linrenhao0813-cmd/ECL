package com.ecl.ui;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Keeps unsaved settings intact until a successful save or an explicit discard. */
final class SettingsPageGuard {
    enum Choice { SAVE, DISCARD, CANCEL }

    private final TabPane tabs;
    private final Function<Tab, Choice> choiceProvider;
    private final Set<Tab> dirty = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Tab, Runnable> discardActions = new IdentityHashMap<>();
    private final Map<Tab, BooleanSupplier> saveActions = new IdentityHashMap<>();
    private final Map<Tab, BooleanSupplier> dirtyChecks = new IdentityHashMap<>();
    private boolean reverting;

    SettingsPageGuard(TabPane tabs, Function<Tab, Choice> choiceProvider) {
        this(tabs, choiceProvider, true);
    }

    SettingsPageGuard(TabPane tabs, Function<Tab, Choice> choiceProvider, boolean guardTabSwitches) {
        this.tabs = tabs;
        this.choiceProvider = choiceProvider;
        if (guardTabSwitches) {
            tabs.getSelectionModel().selectedItemProperty().addListener(
                    (observable, previous, selected) -> handleTabChange(previous, selected));
        }
    }

    void markDirty(Tab tab) {
        if (!reverting && tab != null) {
            dirty.add(tab);
        }
    }

    void clearDirty(Tab tab) {
        dirty.remove(tab);
    }

    void onDiscard(Tab tab, Runnable action) {
        discardActions.put(tab, action);
    }

    void onSave(Tab tab, BooleanSupplier action) {
        saveActions.put(tab, action);
    }

    void onDirtyCheck(Tab tab, BooleanSupplier check) {
        dirtyChecks.put(tab, check);
    }

    boolean confirmDeparture() {
        Tab current = tabs.getSelectionModel().getSelectedItem();
        if (current != null && !resolveChanges(current)) {
            return false;
        }
        for (Tab tab : tabs.getTabs()) {
            if (!resolveChanges(tab)) {
                select(tab);
                return false;
            }
        }
        return true;
    }

    private void handleTabChange(Tab previous, Tab selected) {
        if (!reverting && previous != null && selected != null && previous != selected
                && !resolveChanges(previous)) {
            select(previous);
        }
    }

    private boolean resolveChanges(Tab tab) {
        if (!dirty.contains(tab) && !dirtyChecks.getOrDefault(tab, () -> false).getAsBoolean()) {
            return true;
        }
        Choice choice = choiceProvider.apply(tab);
        if (choice == Choice.SAVE) {
            BooleanSupplier save = saveActions.get(tab);
            if (save == null || !save.getAsBoolean()) {
                return false;
            }
        } else if (choice == Choice.DISCARD) {
            Runnable discard = discardActions.get(tab);
            if (discard != null) {
                discard.run();
            }
        } else {
            return false;
        }
        dirty.remove(tab);
        return true;
    }

    private void select(Tab tab) {
        reverting = true;
        try {
            tabs.getSelectionModel().select(tab);
        } finally {
            reverting = false;
        }
    }
}
