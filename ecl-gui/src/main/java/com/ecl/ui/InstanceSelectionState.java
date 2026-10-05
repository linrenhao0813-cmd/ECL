package com.ecl.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Instance context shared by the whole launcher.
 *
 * <p>It separates two things the launcher must not conflate: the <em>launch target</em> (the instance
 * the next game launch uses) and the <em>viewed instance</em> (the row currently open in the instance
 * manager). Business code reads this state instead of the selector control, so switching pages or
 * rebuilding the UI never changes the launch target, and browsing another instance never silently
 * retargets the next launch.
 */
final class InstanceSelectionState {
    /** Persisted launch target profile id; an empty string means "nothing selected". */
    private final StringProperty launchTarget = new SimpleStringProperty(this, "launchTarget", "");
    /** Transient selection in the instance manager. */
    private final StringProperty viewedInstance = new SimpleStringProperty(this, "viewedInstance", "");

    /** @return the launch target profile id, or {@code null} when nothing is selected. */
    String launchTarget() {
        return blankToNull(launchTarget.get());
    }

    void setLaunchTarget(String profileId) {
        launchTarget.set(profileId == null ? "" : profileId);
    }

    StringProperty launchTargetProperty() {
        return launchTarget;
    }

    /** @return the instance open in the instance manager, or {@code null}. */
    String viewedInstance() {
        return blankToNull(viewedInstance.get());
    }

    void setViewedInstance(String profileId) {
        viewedInstance.set(profileId == null ? "" : profileId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
