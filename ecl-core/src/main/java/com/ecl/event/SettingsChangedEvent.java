package com.ecl.event;

/**
 * Published after the launcher settings have been written to disk.
 *
 * <p>Carries no payload: subscribers that cache settings values should reload them
 * on this event. Kept intentionally small so posting it stays cheap.</p>
 */
public final class SettingsChangedEvent extends Event {

    @Override
    public String name() {
        return "SettingsChanged";
    }
}