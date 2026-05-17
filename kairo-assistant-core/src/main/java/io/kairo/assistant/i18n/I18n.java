package io.kairo.assistant.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class I18n {

    private static final String DEFAULT_LOCALE = "en";
    private static volatile String currentLocale = DEFAULT_LOCALE;
    private static final ConcurrentHashMap<String, Properties> BUNDLES = new ConcurrentHashMap<>();

    private I18n() {}

    public static void setLocale(String locale) {
        currentLocale = locale != null ? locale.toLowerCase(Locale.ROOT) : DEFAULT_LOCALE;
    }

    public static String locale() {
        return currentLocale;
    }

    public static String t(String key) {
        return resolve(key, currentLocale);
    }

    public static String t(String key, String locale) {
        return resolve(key, locale);
    }

    public static String tf(String key, Object... args) {
        return String.format(resolve(key, currentLocale), args);
    }

    private static String resolve(String key, String locale) {
        Properties props = loadBundle(locale);
        String value = props.getProperty(key);
        if (value != null) return value;
        if (!DEFAULT_LOCALE.equals(locale)) {
            value = loadBundle(DEFAULT_LOCALE).getProperty(key);
        }
        return value != null ? value : key;
    }

    private static Properties loadBundle(String locale) {
        return BUNDLES.computeIfAbsent(locale, loc -> {
            Properties props = new Properties();
            String path = "locales/" + loc + ".properties";
            try (InputStream is = I18n.class.getClassLoader().getResourceAsStream(path)) {
                if (is != null) {
                    props.load(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            }
            return props;
        });
    }

    public static void clearCache() {
        BUNDLES.clear();
    }
}
