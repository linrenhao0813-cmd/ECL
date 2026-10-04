package com.ecl.ui;

import com.ecl.util.Messages;

import java.text.MessageFormat;
import java.util.ResourceBundle;

/** Text owned by GUI-only features. */
final class GuiMessages {
    private GuiMessages() { }

    static String get(String key, Object... arguments) {
        String pattern = ResourceBundle.getBundle("i18n.gui", Messages.locale()).getString(key);
        return new MessageFormat(pattern, Messages.locale()).format(arguments);
    }
}
