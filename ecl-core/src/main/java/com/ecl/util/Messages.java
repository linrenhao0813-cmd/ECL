package com.ecl.util;

import com.ecl.i18n.I18n;
import com.ecl.i18n.ResourceBundleI18n;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * i18n support for ECL UI strings.
 *
 * <p>Loads {@code i18n/messages.properties} from the classpath.
 * All public methods gracefully fall back to the message key if no translation is found,
 * so missing translations degrade visibly rather than silently.
 */
public final class Messages {

    private static volatile I18n service = new ResourceBundleI18n(Locale.getDefault());

    private Messages() {
    }

    /**
     * Look up a message by key. Returns the key itself if the resource is missing,
     * so callers never get a {@link MissingResourceException}.
     */
    public static String get(String key) {
        return service.text(key);
    }

    /**
     * Look up a message and format it with {@link MessageFormat}.
     */
    public static String format(String key, Object... args) {
        return service.format(key, args);
    }

    public static void setLocale(Locale locale) {
        service.setLocale(locale);
    }

    public static Locale locale() {
        return service.locale();
    }

    public static AutoCloseable onLocaleChanged(Consumer<Locale> listener) {
        return service.onLocaleChanged(listener);
    }

    /** Reset the service (useful for isolated tests). */
    static void reset() {
        service = new ResourceBundleI18n(Locale.getDefault());
    }
}
