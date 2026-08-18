package com.ecl.i18n;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceBundleI18nTest {
    @Test
    void switchesLocaleLiveAndFallsBackToEnglishThenKey() throws Exception {
        ResourceBundleI18n i18n = new ResourceBundleI18n(Locale.ENGLISH);
        AtomicReference<Locale> changed = new AtomicReference<>();
        AutoCloseable subscription = i18n.onLocaleChanged(changed::set);

        assertEquals("Home", i18n.text("nav.home"));
        i18n.setLocale(ResourceBundleI18n.TRADITIONAL_CHINESE);
        assertEquals("首頁", i18n.text("nav.home"));
        assertEquals(ResourceBundleI18n.TRADITIONAL_CHINESE, changed.get());
        assertEquals("missing.key", i18n.text("missing.key"));
        subscription.close();
    }

    @Test
    void publicServerLabelsAreTranslatedInEverySupportedLocale() {
        ResourceBundleI18n i18n = new ResourceBundleI18n(
                ResourceBundleI18n.SIMPLIFIED_CHINESE);
        assertEquals("服务器分类", i18n.text("server.category.title"));

        i18n.setLocale(ResourceBundleI18n.ENGLISH);
        assertEquals("Server categories", i18n.text("server.category.title"));
        assertEquals("Loaded 12 public servers from cache",
                i18n.format("server.status.loaded", "cache", 12));

        i18n.setLocale(ResourceBundleI18n.TRADITIONAL_CHINESE);
        assertEquals("伺服器分類", i18n.text("server.category.title"));
    }

    @Test
    void everySupportedBundleContainsExactlyTheSameKeys() throws Exception {
        Properties simplified = load("i18n/messages.properties");
        Properties english = load("i18n/messages_en.properties");
        Properties traditional = load("i18n/messages_zh_TW.properties");

        assertEquals(simplified.stringPropertyNames(), english.stringPropertyNames());
        assertEquals(simplified.stringPropertyNames(), traditional.stringPropertyNames());
    }

    private static Properties load(String resource) throws Exception {
        Properties properties = new Properties();
        try (var input = ResourceBundleI18nTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing resource: " + resource);
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
