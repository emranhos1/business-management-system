package com.eh.bms.util;

import com.eh.bms.service.AppConfigService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Folders the app writes to, outside the install folder (which an update replaces).
 * Files the user adds go to the storage folder chosen in Settings ({@code storage_path}),
 * so they can live on a safe drive (e.g. D:) and survive a Windows reinstall.
 * The DB keeps their paths relative to that folder, so moving the folder keeps them working.
 */
public final class AppPaths {

    private static final String IMAGES = "images";
    private static final String DOCUMENTS = "documents";
    /** Subfolders that belong to the app; only these are copied when the storage folder changes. */
    private static final List<String> STORAGE_SUBDIRS = List.of(IMAGES, DOCUMENTS);

    private AppPaths() {
    }

    /** {@code %APPDATA%\BMS} on Windows, {@code ~/.bms} elsewhere. Created if missing. */
    public static Path dataDir() {
        String appData = System.getenv("APPDATA");
        Path dir = appData != null ? Path.of(appData, "BMS") : Path.of(System.getProperty("user.home"), ".bms");
        return create(dir);
    }

    /** The storage folder from Settings; {@link #dataDir()} when not set. Created if missing. */
    public static Path storageDir() {
        String value = new AppConfigService().getString(AppConfigService.STORAGE_PATH, null);
        return value == null || value.isBlank() ? dataDir() : create(Path.of(value.trim()));
    }

    /** Logo and other pictures. */
    public static Path imagesDir() {
        return create(storageDir().resolve(IMAGES));
    }

    /** Stored path → real file. Relative paths are inside the storage folder; old absolute paths are kept. */
    public static Path resolve(String stored) {
        Path p = Path.of(stored);
        return p.isAbsolute() ? p : storageDir().resolve(p);
    }

    /** Real file inside the storage folder → path to store in the DB (relative, with '/'). */
    public static String toStored(Path file) {
        return storageDir().toAbsolutePath().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
    }

    /** True if the app can create and delete a file in this folder. */
    public static boolean isWritable(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, "bms-", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Copies the app's subfolders (images, documents) from one storage folder to another. Old files stay. */
    public static void copyStorage(Path from, Path to) throws IOException {
        for (String sub : STORAGE_SUBDIRS) {
            Path source = from.resolve(sub);
            if (!Files.isDirectory(source)) {
                continue;
            }
            Path target = to.resolve(sub);
            try (Stream<Path> files = Files.walk(source)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    Path dest = target.resolve(source.relativize(f).toString());
                    if (Files.isDirectory(f)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(f, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private static Path create(Path dir) {
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create " + dir, e);
        }
    }
}
