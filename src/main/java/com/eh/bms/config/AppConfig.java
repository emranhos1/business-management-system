package com.eh.bms.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads {@code application.yml}, then {@code application-{profile}.yml} for the active
 * profile ({@code app.profile}). Profile values override base values.
 * Keys are read with dot paths, e.g. {@code db.pool.max-size}.
 */
public final class AppConfig {

    private static final String BASE_FILE = "application.yml";
    private static final String PROFILE_KEY = "app.profile";

    private static AppConfig instance;

    private final Map<String, Object> values;
    private final String profile;

    private AppConfig(Map<String, Object> values, String profile) {
        this.values = values;
        this.profile = profile;
    }

    public static synchronized AppConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static AppConfig load() {
        Map<String, Object> merged = readYaml(BASE_FILE, true);
        Object profileValue = find(merged, PROFILE_KEY);
        if (profileValue == null || profileValue.toString().isBlank()) {
            throw new IllegalStateException("Missing '" + PROFILE_KEY + "' in " + BASE_FILE);
        }
        String profile = profileValue.toString().trim();
        deepMerge(merged, readYaml("application-" + profile + ".yml", true));
        return new AppConfig(merged, profile);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYaml(String file, boolean required) {
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream(file)) {
            if (in == null) {
                if (required) {
                    throw new IllegalStateException("Config file not found: " + file);
                }
                return new LinkedHashMap<>();
            }
            Object data = new Yaml().load(in);
            return data == null ? new LinkedHashMap<>() : new LinkedHashMap<>((Map<String, Object>) data);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read config file: " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> e : source.entrySet()) {
            Object existing = target.get(e.getKey());
            if (existing instanceof Map && e.getValue() instanceof Map) {
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) existing);
                deepMerge(copy, (Map<String, Object>) e.getValue());
                target.put(e.getKey(), copy);
            } else {
                target.put(e.getKey(), e.getValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object find(Map<String, Object> map, String path) {
        Object current = map;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(part);
        }
        return current;
    }

    public String getProfile() {
        return profile;
    }

    /** Required value; throws if missing. Empty string is allowed (e.g. blank password). */
    public String getString(String path) {
        Object v = find(values, path);
        if (v == null) {
            throw new IllegalStateException("Missing config key: " + path);
        }
        return v.toString();
    }

    public String getString(String path, String defaultValue) {
        Object v = find(values, path);
        return v == null ? defaultValue : v.toString();
    }

    public int getInt(String path, int defaultValue) {
        Object v = find(values, path);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Config key '" + path + "' must be a number: " + v, e);
        }
    }
}
