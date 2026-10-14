package com.eh.bms.util;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Holds the current UI language and resolves i18n keys.
 * Labels bound with {@link #bind(String, Object...)} update instantly when the language changes.
 * Change the language on the JavaFX thread.
 */
public final class I18n {

    private static final Logger LOG = Logger.getLogger(I18n.class.getName());
    private static final String BUNDLE = "i18n.messages";
    private static final ResourceBundle.Control NO_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    public static final Locale BANGLA = Locale.forLanguageTag("bn");
    public static final Locale ENGLISH = Locale.ENGLISH;

    private static final ObjectProperty<Locale> LOCALE = new SimpleObjectProperty<>(BANGLA);

    /**
     * Bundles are picked by the current locale on every lookup, not swapped by a locale listener:
     * bound labels may recompute before such a listener runs and would get the old language.
     */
    private static final Map<Locale, ResourceBundle> BUNDLES = new ConcurrentHashMap<>();

    private I18n() {
    }

    public static Locale getLocale() {
        return LOCALE.get();
    }

    public static ReadOnlyObjectProperty<Locale> localeProperty() {
        return LOCALE;
    }

    /** Switches the UI language. Only {@link #BANGLA} and {@link #ENGLISH} are supported. */
    public static void setLocale(Locale locale) {
        if (!BANGLA.equals(locale) && !ENGLISH.equals(locale)) {
            throw new IllegalArgumentException("Unsupported language: " + locale);
        }
        LOCALE.set(locale);
    }

    /** Switches the UI language by code ("bn" or "en"), as stored in app_config. */
    public static void setLanguage(String code) {
        setLocale(Locale.forLanguageTag(code));
    }

    /** Bundle for FXMLLoader (%key). Values resolved this way do not update on language change. */
    public static ResourceBundle getBundle() {
        return BUNDLES.computeIfAbsent(getLocale(), I18n::load);
    }

    /** Text for a key in the current language. Args fill {0}, {1}, ... placeholders. */
    public static String get(String key, Object... args) {
        String pattern;
        try {
            pattern = getBundle().getString(key);
        } catch (MissingResourceException e) {
            LOG.warning("Missing i18n key: " + key + " (" + getLocale() + ")");
            return "!" + key + "!";
        }
        return args.length == 0 ? pattern : new MessageFormat(pattern, getLocale()).format(args);
    }

    /** Text that follows the current language. Use with textProperty().bind(...). */
    public static StringBinding bind(String key, Object... args) {
        return Bindings.createStringBinding(() -> get(key, args), LOCALE);
    }

    private static ResourceBundle load(Locale locale) {
        return ResourceBundle.getBundle(BUNDLE, locale, NO_FALLBACK);
    }
}
