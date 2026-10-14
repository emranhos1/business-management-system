package com.eh.bms.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Folders the app writes to, outside the install folder (which an update replaces). */
public final class AppPaths {

    private AppPaths() {
    }

    /** {@code %APPDATA%\BMS} on Windows, {@code ~/.bms} elsewhere. Created if missing. */
    public static Path dataDir() {
        String appData = System.getenv("APPDATA");
        Path dir = appData != null ? Path.of(appData, "BMS") : Path.of(System.getProperty("user.home"), ".bms");
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create " + dir, e);
        }
    }
}
