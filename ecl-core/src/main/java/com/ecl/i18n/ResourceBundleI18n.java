package com.ecl.i18n;

import com.ecl.event.EventBus;
import com.ecl.event.LocaleChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** UTF-8 language service with explicit English fallback and live change notifications. */
public final class ResourceBundleI18n implements I18n {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceBundleI18n.class);
    public static final Locale SIMPLIFIED_CHINESE = Locale.forLanguageTag("zh-CN");
    public static final Locale TRADITIONAL_CHINESE = Locale.forLanguageTag("zh-TW");
    public static final Locale ENGLISH = Locale.ENGLISH;
    private static final List<Locale> AVAILABLE = List.of(SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE, ENGLISH);

    private final CopyOnWriteArrayList<Consumer<Locale>> listeners = new CopyOnWriteArrayList<>();
    private final EventBus eventBus;
    private volatile Locale locale;
    private volatile Properties selected;
    private final Properties english;

    public ResourceBundleI18n(Locale initialLocale) {
        this(initialLocale, null);
    }

    public ResourceBundleI18n(Locale initialLocale, EventBus eventBus) {
        this.eventBus = eventBus;
        this.english = load("i18n/messages_en.properties");
        setInitialLocale(initialLocale);
    }

    @Override
    public String text(String key) {
        if (key == null) return "";
        String value = selected.getProperty(key);
        if (value == null) value = english.getProperty(key);
        return value == null ? key : value;
    }

    @Override
    public String format(String key, Object... arguments) {
        MessageFormat formatter = new MessageFormat(text(key), locale);
        return formatter.format(arguments == null ? new Object[0] : arguments);
    }

    @Override
    public Locale locale() {
        return locale;
    }

    @Override
    public List<Locale> availableLocales() {
        return AVAILABLE;
    }

    @Override
    public synchronized void setLocale(Locale requested) {
        Locale normalized = normalize(requested);
        Locale previous = locale;
        if (normalized.equals(previous)) return;
        locale = normalized;
        selected = load(resourceFor(normalized));
        listeners.forEach(listener -> listener.accept(normalized));
        if (eventBus != null) eventBus.post(new LocaleChangedEvent(previous, normalized));
    }

    @Override
    public AutoCloseable onLocaleChanged(Consumer<Locale> listener) {
        if (listener == null) return () -> { };
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void setInitialLocale(Locale requested) {
        locale = normalize(requested);
        selected = load(resourceFor(locale));
    }

    private static Locale normalize(Locale requested) {
        String tag = requested == null ? "" : requested.toLanguageTag();
        return AVAILABLE.stream().filter(locale -> locale.toLanguageTag().equalsIgnoreCase(tag))
                .findFirst().orElse(SIMPLIFIED_CHINESE);
    }

    private static String resourceFor(Locale locale) {
        if (TRADITIONAL_CHINESE.equals(locale)) return "i18n/messages_zh_TW.properties";
        if (ENGLISH.equals(locale)) return "i18n/messages_en.properties";
        return "i18n/messages.properties";
    }

    private static Properties load(String resource) {
        Properties properties = new Properties();
        try (InputStream input = ResourceBundleI18n.class.getClassLoader().getResourceAsStream(resource)) {
            if (input != null) {
                properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        } catch (IOException failure) {
            LOGGER.warn("Failed to load message resource bundle {}", resource, failure);
        }
        return properties;
    }
}
