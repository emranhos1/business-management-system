package com.eh.bms.service;

import com.eh.bms.model.AppConfigEntry;
import com.eh.bms.repository.AppConfigRepository;
import com.eh.bms.util.MemoPaper;
import com.eh.bms.util.TransactionHelper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/** Reads and saves settings in the {@code app_config} table (not the yml files). */
public class AppConfigService {

    private static final Logger LOG = Logger.getLogger(AppConfigService.class.getName());

    public static final String DEFAULT_LANGUAGE = "default_language";
    public static final String LOW_STOCK_DEFAULT = "low_stock_default";
    public static final String DATE_FORMAT = "date_format";
    public static final String TIME_FORMAT = "time_format";
    public static final String ITEMS_PER_PAGE = "items_per_page";
    /** Rows per page of the dashboard tables (small, so the whole dashboard fits on one screen). */
    public static final String DASHBOARD_ROWS = "dashboard_rows";
    public static final int DASHBOARD_ROWS_DEFAULT = 5;
    public static final String AUTO_BACKUP = "auto_backup";
    /** Folder for files the user adds (logo, images, documents). Missing = {@code AppPaths.dataDir()}. */
    public static final String STORAGE_PATH = "storage_path";
    /** Paper the sale memo is printed on: a {@link MemoPaper} name (N6). */
    public static final String MEMO_PAPER = "memo_paper";

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

    /** Rows per dashboard table page; at least 1. */
    public int dashboardRows() {
        return Math.max(1, getInt(DASHBOARD_ROWS, DASHBOARD_ROWS_DEFAULT));
    }

    /** "bn" or "en". */
    public String language() {
        return getString(DEFAULT_LANGUAGE, "bn");
    }

    /** Memo paper from Settings (default A5). */
    public MemoPaper memoPaper() {
        return MemoPaper.of(getString(MEMO_PAPER, null));
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
