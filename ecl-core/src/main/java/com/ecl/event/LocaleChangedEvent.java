package com.ecl.event;

import java.util.Locale;

public final class LocaleChangedEvent extends Event {
    private final Locale previous;
    private final Locale current;

    public LocaleChangedEvent(Locale previous, Locale current) {
        this.previous = previous;
        this.current = current;
    }

    public Locale previous() {
        return previous;
    }

    public Locale current() {
        return current;
    }
}
