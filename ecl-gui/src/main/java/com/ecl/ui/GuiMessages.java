package com.ecl.ui;

import com.ecl.util.Messages;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/** Text owned by GUI-only features. */
final class GuiMessages {
    private GuiMessages() { }

    static String get(String key, Object... arguments) {
        if (key == null) return "";
        Locale locale = Messages.locale();
        String pattern;
        try {
            pattern = ResourceBundle.getBundle("i18n.gui", locale).getString(key);
        } catch (MissingResourceException missing) {
            try {
                pattern = ResourceBundle.getBundle("i18n.gui", Locale.ENGLISH).getString(key);
            } catch (MissingResourceException missingEnglish) {
                return key;
            }
        }
        return new MessageFormat(pattern, locale).format(arguments == null ? new Object[0] : arguments);
    }
}
