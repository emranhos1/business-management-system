package com.eh.bms.service;

import com.eh.bms.model.AppConfigEntry;
import com.eh.bms.repository.AppConfigRepository;
import com.eh.bms.util.TransactionHelper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** Reads and saves settings in the {@code app_config} table (not the yml files). */
public class AppConfigService {

    private static final Logger LOG = Logger.getLogger(AppConfigService.class.getName());

    public static final String DEFAULT_LANGUAGE = "default_language";
    public static final String LOW_STOCK_DEFAULT = "low_stock_default";
    public static final String DATE_FORMAT = "date_format";
    public static final String TIME_FORMAT = "time_format";
    public static final String ITEMS_PER_PAGE = "items_per_page";
    public static final String AUTO_BACKUP = "auto_backup";
    /** Folder for files the user adds (logo, images, documents). Missing = {@code AppPaths.dataDir()}. */
    public static final String STORAGE_PATH = "storage_path";
    /** Weekly off days: comma list of {@link DayOfWeek} names (e.g. "FRIDAY,SATURDAY"); empty = none. */
    public static final String WEEKLY_OFF_DAYS = "weekly_off_days";
    /** Default for {@link #WEEKLY_OFF_DAYS}. */
    public static final String WEEKLY_OFF_DAYS_DEFAULT = "FRIDAY";
    /** "true": a leave day is paid for daily (one day) and hourly (one work day of hours) employees. */
    public static final String PAID_LEAVE = "paid_leave";
    /** Work day start and end, "HH:mm" (auto attendance times of hourly employees, paid leave hours). */
    public static final String WORK_START_TIME = "work_start_time";
    public static final String WORK_END_TIME = "work_end_time";
    public static final LocalTime WORK_START_DEFAULT = LocalTime.of(9, 0);
    public static final LocalTime WORK_END_DEFAULT = LocalTime.of(17, 0);
    /** First day auto attendance may fill (ISO date, set when the feature first ran); older days are never filled. */
    public static final String ATTENDANCE_AUTO_FROM = "attendance_auto_from";

    private final AppConfigRepository repository = new AppConfigRepository();
    private final AuditService auditService = new AuditService();

    public String getString(String key, String defaultValue) {
        return TransactionHelper.inTransaction(session -> repository.findValue(session, key))
                .orElse(defaultValue);
    }

    /** Integer setting; a missing or non-numeric value gives the default. */
    public int getInt(String key, int defaultValue) {
        String value = getString(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOG.warning("app_config " + key + " is not a number: " + value);
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getString(key, null);
        return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    /** Rows per table page; at least 1. */
    public int itemsPerPage() {
        return Math.max(1, getInt(ITEMS_PER_PAGE, 20));
    }

    /** "bn" or "en". */
    public String language() {
        return getString(DEFAULT_LANGUAGE, "bn");
    }

    /** Weekly off days from Settings; a missing key gives the default (Friday), an empty value none. */
    public Set<DayOfWeek> weeklyOffDays() {
        return parseOffDays(getString(WEEKLY_OFF_DAYS, WEEKLY_OFF_DAYS_DEFAULT));
    }

    /** Paid leave setting (default off). */
    public boolean paidLeave() {
        return getBoolean(PAID_LEAVE, false);
    }

    public LocalTime workStart() {
        return parseTime(getString(WORK_START_TIME, null), WORK_START_DEFAULT);
    }

    public LocalTime workEnd() {
        return parseTime(getString(WORK_END_TIME, null), WORK_END_DEFAULT);
    }

    /** Hours of one work day (end - start); the defaults' 8 hours when the saved times are not in order. */
    public BigDecimal workDayHours() {
        LocalTime start = workStart();
        LocalTime end = workEnd();
        if (!end.isAfter(start)) {
            start = WORK_START_DEFAULT;
            end = WORK_END_DEFAULT;
        }
        return BigDecimal.valueOf(Duration.between(start, end).toMinutes())
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    /** "HH:mm" to a time; a missing or wrong value gives the default. */
    public static LocalTime parseTime(String value, LocalTime defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            LOG.warning("app_config time is not HH:mm: " + value);
            return defaultValue;
        }
    }

    /** A time as saved in app_config ("HH:mm"). */
    public static String formatTime(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    /** Saves the weekly off days (an empty set = none). */
    public void setWeeklyOffDays(Set<DayOfWeek> days) {
        set(WEEKLY_OFF_DAYS, formatOffDays(days));
    }

    /** "FRIDAY,SATURDAY" to days; blanks and unknown names are skipped. */
    public static Set<DayOfWeek> parseOffDays(String value) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (value == null) {
            return days;
        }
        for (String part : value.split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            try {
                days.add(DayOfWeek.valueOf(name));
            } catch (IllegalArgumentException e) {
                LOG.warning("app_config " + WEEKLY_OFF_DAYS + " has an unknown day: " + part);
            }
        }
        return days;
    }

    /** Days to "FRIDAY,SATURDAY" in week order; none gives "". */
    public static String formatOffDays(Collection<DayOfWeek> days) {
        if (days == null || days.isEmpty()) {
            return "";
        }
        return EnumSet.copyOf(days).stream().map(DayOfWeek::name).collect(Collectors.joining(","));
    }

    public void set(String key, String value) {
        setAll(Map.of(key, value));
    }

    /** Saves several settings in one transaction; only changed values are written and audited. */
    public void setAll(Map<String, String> values) {
        Map<String, String> ordered = new LinkedHashMap<>(values);
        TransactionHelper.runInTransaction(session -> {
            for (Map.Entry<String, String> e : ordered.entrySet()) {
                String key = e.getKey();
                String value = e.getValue();
                AppConfigEntry entry = repository.findByKey(session, key).orElse(null);
                if (entry == null) {
                    entry = new AppConfigEntry();
                    entry.setConfigKey(key);
                    entry.setConfigValue(value);
                    repository.save(session, entry);
                    auditService.created(session, "APP_CONFIG", entry.getId(), key + "=" + value);
                    continue;
                }
                String old = entry.getConfigValue();
                if (Objects.equals(old, value)) {
                    continue;
                }
                entry.setConfigValue(value);
                auditService.updated(session, "APP_CONFIG", entry.getId(), key + "=" + old, key + "=" + value);
            }
        });
    }
}
