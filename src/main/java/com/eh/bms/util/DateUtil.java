package com.eh.bms.util;

import com.eh.bms.service.AppConfigService;
import javafx.scene.control.DatePicker;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Formats dates with the {@code date_format} from app_config. Use it for every date shown in the app.
 * The pattern is read once and cached; call {@link #reload()} after the setting changes.
 */
public final class DateUtil {

    private static final Logger LOG = Logger.getLogger(DateUtil.class.getName());

    public static final String DEFAULT_PATTERN = "dd/MM/yyyy";

    /** Patterns offered in Settings. */
    public static final List<String> PATTERNS = List.of(
            "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "yyyy-MM-dd", "MM/dd/yyyy");

    /** Pattern and its formatters, swapped together. */
    private record Formats(String pattern, DateTimeFormatter date, DateTimeFormatter dateTime) {
        static Formats of(String pattern) {
            // Locale.ENGLISH keeps digits and AM/PM in Latin script in both languages
            return new Formats(pattern,
                    DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH),
                    DateTimeFormatter.ofPattern(pattern + " hh:mm a", Locale.ENGLISH));
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH);

    private static volatile Formats formats;

    private DateUtil() {
    }

    /** Date in the current pattern; Bangla digits in Bangla mode. */
    public static String format(LocalDate date) {
        return date == null ? "" : I18n.digits(formats().date().format(date));
    }

    /** Date plus 12-hour time, e.g. 14/10/2026 02:35 PM; Bangla digits in Bangla mode. */
    public static String format(LocalDateTime dateTime) {
        return dateTime == null ? "" : I18n.digits(formats().dateTime().format(dateTime));
    }

    /** 12-hour time with seconds, e.g. 07:56:49 PM; Bangla digits in Bangla mode. */
    public static String formatTime(LocalTime time) {
        return time == null ? "" : I18n.digits(TIME.format(time));
    }

    /** Parses text in the current pattern (Bangla digits allowed); null for blank or invalid text. */
    public static LocalDate parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(InputFilters.toAsciiDigits(text.trim()), formats().date());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Makes a DatePicker show and accept dates in the current pattern. */
    public static void apply(DatePicker picker) {
        picker.setPromptText(pattern());
        picker.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                return format(date);
            }

            @Override
            public LocalDate fromString(String text) {
                return parse(text);
            }
        });
    }

    public static String pattern() {
        return formats().pattern();
    }

    /** Re-reads {@code date_format} from app_config. */
    public static void reload() {
        use(new AppConfigService().getString(AppConfigService.DATE_FORMAT, DEFAULT_PATTERN));
    }

    /** Uses this pattern without touching the DB; an invalid pattern falls back to the default. */
    public static void use(String pattern) {
        try {
            formats = Formats.of(pattern);
        } catch (IllegalArgumentException e) {
            LOG.warning("Invalid date_format: " + pattern);
            formats = Formats.of(DEFAULT_PATTERN);
        }
    }

    /** True if the pattern can format a date. */
    public static boolean isValid(String pattern) {
        try {
            DateTimeFormatter.ofPattern(pattern).format(LocalDate.now());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Formats formats() {
        Formats f = formats;
        if (f == null) {
            reload();
            f = formats;
        }
        return f;
    }
}
