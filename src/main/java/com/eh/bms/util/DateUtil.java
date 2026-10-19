package com.eh.bms.util;

import com.eh.bms.service.AppConfigService;
import javafx.application.Platform;
import javafx.scene.control.DatePicker;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Formats dates and times with {@code date_format} and {@code time_format} from app_config. Use it for every
 * date and time shown in the app.
 * The pattern is read once and cached; call {@link #reload()} after the setting changes.
 */
public final class DateUtil {

    private static final Logger LOG = Logger.getLogger(DateUtil.class.getName());

    public static final String DEFAULT_PATTERN = "dd/MM/yyyy";

    /** Patterns offered in Settings. */
    public static final List<String> PATTERNS = List.of(
            "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "yyyy-MM-dd", "MM/dd/yyyy");

    public static final String TIME_12 = "hh:mm a";
    public static final String TIME_24 = "HH:mm";
    public static final String DEFAULT_TIME_PATTERN = TIME_12;

    /** Time patterns offered in Settings: 12-hour and 24-hour. */
    public static final List<String> TIME_PATTERNS = List.of(TIME_12, TIME_24);

    /** Date and time patterns and their formatters, swapped together. */
    private record Formats(String pattern, String timePattern, DateTimeFormatter date, DateTimeFormatter dateTime,
                           DateTimeFormatter time) {
        static Formats of(String pattern, String timePattern) {
            // Locale.ENGLISH keeps digits and AM/PM in Latin script in both languages.
            // STRICT: a day that does not exist (31/02) is refused, not quietly turned into 28/02;
            // STRICT needs "u" (year) instead of "y" (year of era), which prints the same.
            String strict = pattern.replace('y', 'u');
            return new Formats(pattern, timePattern,
                    DateTimeFormatter.ofPattern(strict, Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
                    DateTimeFormatter.ofPattern(strict + " " + timePattern, Locale.ENGLISH)
                            .withResolverStyle(ResolverStyle.STRICT),
                    // The clock shows seconds too
                    DateTimeFormatter.ofPattern(timePattern.replace("mm", "mm:ss"), Locale.ENGLISH));
        }
    }

    private static volatile Formats formats;

    private DateUtil() {
    }

    /** Date in the current pattern; Bangla digits in Bangla mode. */
    public static String format(LocalDate date) {
        return date == null ? "" : I18n.digits(formats().date().format(date));
    }

    /** Date plus time in the time format, e.g. 14/10/2026 02:35 PM; Bangla digits in Bangla mode. */
    public static String format(LocalDateTime dateTime) {
        return dateTime == null ? "" : I18n.digits(formats().dateTime().format(dateTime));
    }

    /** Time with seconds in the time format, e.g. 07:56:49 PM or 19:56:49; Bangla digits in Bangla mode. */
    public static String formatTime(LocalTime time) {
        return time == null ? "" : I18n.digits(formats().time().format(time));
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
        // Week numbers mean nothing to shop staff
        picker.setShowWeekNumbers(false);
        // Text typed that is not a valid date; null when the field holds a valid date or nothing
        String[] wrong = new String[1];
        picker.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                return format(date);
            }

            @Override
            public LocalDate fromString(String text) {
                LocalDate date = parse(text);
                wrong[0] = date == null && text != null && !text.isBlank() ? text : null;
                return date;
            }
        });
        // JavaFX empties the field when the date is wrong; put the typed text back, so the user sees what to fix
        // (the form says the date is wrong on save)
        picker.getEditor().focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                Platform.runLater(() -> {
                    if (picker.getValue() == null && wrong[0] != null && picker.getEditor().getText().isEmpty()) {
                        picker.getEditor().setText(wrong[0]);
                    }
                });
            }
        });
    }

    public static String pattern() {
        return formats().pattern();
    }

    /** Re-reads {@code date_format} and {@code time_format} from app_config. */
    public static void reload() {
        AppConfigService config = new AppConfigService();
        use(config.getString(AppConfigService.DATE_FORMAT, DEFAULT_PATTERN),
                config.getString(AppConfigService.TIME_FORMAT, DEFAULT_TIME_PATTERN));
    }

    /** Uses these patterns without touching the DB; an invalid one falls back to its default. */
    public static void use(String pattern, String timePattern) {
        String time = TIME_PATTERNS.contains(timePattern) ? timePattern : DEFAULT_TIME_PATTERN;
        if (!time.equals(timePattern)) {
            LOG.warning("Invalid time_format: " + timePattern);
        }
        try {
            formats = Formats.of(pattern, time);
        } catch (IllegalArgumentException e) {
            LOG.warning("Invalid date_format: " + pattern);
            formats = Formats.of(DEFAULT_PATTERN, time);
        }
    }

    /** The time pattern in use ({@link #TIME_12} or {@link #TIME_24}). */
    public static String timePattern() {
        return formats().timePattern();
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
