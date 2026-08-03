package com.ecl.util;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * i18n support for ECL UI strings.
 *
 * <p>Loads {@code i18n/messages.properties} from the classpath.
 * All public methods gracefully fall back to the message key if no translation is found,
 * so missing translations degrade visibly rather than silently.
 */
public final class Messages {

    private static final String BUNDLE_BASE = "i18n/messages";
    private static ResourceBundle bundle;

    private Messages() {
    }

    private static ResourceBundle bundle() {
        ResourceBundle b = bundle;
        if (b == null) {
            b = ResourceBundle.getBundle(BUNDLE_BASE, Locale.getDefault(),
                    Messages.class.getClassLoader());
            bundle = b;
        }
        return b;
    }

    /**
     * Look up a message by key. Returns the key itself if the resource is missing,
     * so callers never get a {@link MissingResourceException}.
     */
    public static String get(String key) {
        try {
            return bundle().getString(key);
        } catch (RuntimeException e) {
            return key;
        }
    }

    /**
     * Look up a message and format it with {@link MessageFormat}.
     */
    public static String format(String key, Object... args) {
        try {
            return MessageFormat.format(bundle().getString(key), args);
        } catch (RuntimeException e) {
            return key;
        }
    }

    /** Reset the cached bundle (useful for testing locale switching). */
    static void reset() {
        bundle = null;
    }
}
