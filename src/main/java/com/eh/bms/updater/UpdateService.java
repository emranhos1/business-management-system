package com.eh.bms.updater;

import com.eh.bms.config.AppConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleConsumer;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * Checks GitHub Releases for a newer version and downloads its installer. The repo is public, so no
 * token is needed and no JSON is read: {@code releases/latest} redirects to the newest tag, and every
 * release built by {@code .github/workflows/release.yml} has {@code BMS-Update-<version>.exe} and its
 * {@code .sha256}; the release text is {@code release-notes/v<version>.md} at that tag.
 */
public class UpdateService {

    private static final Logger LOG = Logger.getLogger(UpdateService.class.getName());

    private static final String ASSET_PREFIX = "BMS-Update-";
    private static final String TAG_PATH = "/releases/tag/v";
    /** Per-PC settings: the last download folder and the installers downloaded so far (one path a line). */
    private static final String FOLDER_KEY = "updateDownloadFolder";
    private static final String DOWNLOADS_KEY = "downloadedInstallers";

    private final Preferences prefs = Preferences.userNodeForPackage(UpdateService.class);

    /** A newer release that can be installed. */
    public record Release(String version, String notes, URI installer, String sha256) {
    }

    private final String currentVersion = AppConfig.get().getString("app.version");
    private final String repo = AppConfig.get().getString("app.github.repo", "");
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** Same, but keeps the redirect so its Location (the newest tag) can be read. */
    private final HttpClient noRedirect = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public String currentVersion() {
        return currentVersion;
    }

    /** The newer release, if any. Never throws: offline or any error = empty. Call off the JavaFX thread. */
    public Optional<Release> check() {
        // unfiltered "@project.version@" = a run straight from an IDE; never offer updates there
        if (repo.isBlank() || currentVersion.contains("@")) {
            return Optional.empty();
        }
        try {
            HttpResponse<Void> latest = noRedirect.send(request("https://github.com/" + repo + "/releases/latest").GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            String location = latest.headers().firstValue("Location").orElse("");
            int tag = location.indexOf(TAG_PATH);
            if (tag < 0) {
                return Optional.empty(); // no release yet
            }
            String version = location.substring(tag + TAG_PATH.length());
            if (compareVersions(version, currentVersion) <= 0) {
                return Optional.empty();
            }
            String installer = "https://github.com/" + repo + "/releases/download/v" + version + "/" + ASSET_PREFIX + version + ".exe";
            // the .sha256 is uploaded after the .exe: without it the release is not ready to install
            HttpResponse<String> sum = http.send(request(installer + ".sha256").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (sum.statusCode() != 200) {
                return Optional.empty();
            }
            String hash = sum.body().trim().split("\\s+")[0];
            return Optional.of(new Release(version, notes(version), URI.create(installer), hash));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.info("Update check failed: " + e);
            return Optional.empty();
        }
    }

    /** The release text (English, "<!-- bn -->", Bangla); empty when it cannot be read. */
    private String notes(String version) throws InterruptedException {
        try {
            HttpResponse<String> r = http.send(request("https://raw.githubusercontent.com/" + repo + "/v" + version
                    + "/release-notes/v" + version + ".md").GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return r.statusCode() == 200 ? r.body() : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** The folder the last update was saved in (null the first time), so it need not be browsed to again. */
    public Path lastFolder() {
        String saved = prefs.get(FOLDER_KEY, "");
        return saved.isBlank() || !Files.isDirectory(Path.of(saved)) ? null : Path.of(saved);
    }

    /**
     * Downloads the installer into the folder the user picked. It is kept only if its size matches the
     * server's Content-Length and its SHA-256 matches the release's .sha256; an intact earlier download of the
     * same version in that folder is used again. Older installers this app downloaded are then deleted.
     */
    public Path download(Release release, Path folder, DoubleConsumer progress) throws IOException, InterruptedException {
        prefs.put(FOLDER_KEY, folder.toAbsolutePath().toString());
        Path target = folder.resolve(ASSET_PREFIX + release.version() + ".exe");
        String expected = release.sha256();
        if (Files.isRegularFile(target) && expected.equalsIgnoreCase(sha256(target))) {
            progress.accept(1);
            keepOnly(target);
            return target;
        }
        HttpResponse<InputStream> response = http.send(request(release.installer().toString()).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        Path part = folder.resolve(target.getFileName() + ".part");
        MessageDigest digest = digest();
        long done = 0;
        try (InputStream in = new DigestInputStream(response.body(), digest);
             OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    progress.accept((double) done / total);
                }
            }
        }
        if ((total > 0 && done != total) || !expected.equalsIgnoreCase(HexFormat.of().formatHex(digest.digest()))) {
            Files.deleteIfExists(part);
            throw new IOException("corrupt download");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        keepOnly(target);
        return target;
    }

    /** Opens the installer's wizard; the app must exit next so its files can be replaced. */
    public void launch(Path installer) throws IOException {
        new ProcessBuilder(installer.toString()).start();
    }

    /**
     * Deletes the installers this app downloaded earlier, except {@code keep}. Folders are never scanned: only
     * the exact paths recorded at download time are touched, so the user's other files stay. A file that cannot
     * be deleted stays recorded for next time.
     */
    private void keepOnly(Path keep) {
        String keepPath = keep.toAbsolutePath().normalize().toString();
        List<String> left = new ArrayList<>(List.of(keepPath));
        for (String recorded : prefs.get(DOWNLOADS_KEY, "").split("\n")) {
            if (recorded.isBlank() || recorded.equals(keepPath)) {
                continue;
            }
            try {
                Files.deleteIfExists(Path.of(recorded));
            } catch (IOException e) {
                LOG.info("Could not delete old update " + recorded + ": " + e);
                left.add(recorded);
            }
        }
        prefs.put(DOWNLOADS_KEY, String.join("\n", left));
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Dotted numeric versions ("1.10.0" > "1.9.2"); a non-numeric part counts as 0. */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? number(pa[i]) : 0;
            int y = i < pb.length ? number(pb[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int number(String part) {
        try {
            return Integer.parseInt(part.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
