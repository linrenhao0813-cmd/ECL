package com.ecl.i18n;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public interface I18n {
    String text(String key);

    String format(String key, Object... arguments);

    Locale locale();

    List<Locale> availableLocales();

    void setLocale(Locale locale);

    AutoCloseable onLocaleChanged(Consumer<Locale> listener);
}
