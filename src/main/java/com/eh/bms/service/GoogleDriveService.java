package com.eh.bms.service;

import com.eh.bms.util.AppPaths;
import com.eh.bms.util.I18n;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.FileList;

import java.awt.Desktop;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Backup files on Google Drive (copied from the finance-app project): OAuth2 sign-in in the browser, then
 * upload / download in a {@code BMS} folder (made the first time). The sign-in token is kept in
 * {@code tokens/} under the app data folder, so later starts open no browser. One shared instance, so the
 * Settings page and the logout question see the same connection. Methods block: call them off the JavaFX
 * thread. Errors are IOExceptions whose message is already in the app's language.
 */
public final class GoogleDriveService {

    /** Seconds the browser sign-in may take before it gives up (and frees its local port). */
    public static final int SIGN_IN_SECONDS = 60;

    private static final GoogleDriveService INSTANCE = new GoogleDriveService();

    private static final String APPLICATION_NAME = "Business Management System";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList(DriveScopes.DRIVE_FILE);
    private static final String CREDENTIALS_FILE = "credentials.json";
    private static final String APP_FOLDER = "BMS";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final int SIGN_IN_PORT = 8888;
    private static final String USER_ID = "user";

    private volatile Drive drive;
    private volatile Credential credential;

    private GoogleDriveService() {
    }

    public static GoogleDriveService get() {
        return INSTANCE;
    }

    public boolean isConnected() {
        return drive != null;
    }

    /** A sign-in token is kept from an earlier connect: connecting again opens no browser. */
    public boolean hasStoredCredentials() {
        File[] files = tokensDir().toFile().listFiles();
        return files != null && files.length > 0;
    }

    /** Signs in (the browser opens only when no token is kept) and opens the Drive connection. */
    public synchronized void connect() throws IOException {
        try {
            NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            Credential signedIn = signIn(transport);
            drive = new Drive.Builder(transport, JSON_FACTORY, signedIn).setApplicationName(APPLICATION_NAME).build();
            credential = signedIn;
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(I18n.get("backup.err.cancelled"), e);
        } catch (Exception e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    /** Opens the connection from a kept token, never the browser. False when there is no usable token. */
    public synchronized boolean connectIfStored() throws IOException {
        if (drive != null) {
            return true;
        }
        if (!hasStoredCredentials()) {
            return false;
        }
        try {
            NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            Credential kept = newFlow(transport).loadCredential(USER_ID);
            if (kept == null || kept.getRefreshToken() == null) {
                return false;
            }
            drive = new Drive.Builder(transport, JSON_FACTORY, kept).setApplicationName(APPLICATION_NAME).build();
            credential = kept;
            return true;
        } catch (GeneralSecurityException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Takes back Google's access and forgets the kept token. */
    public synchronized void disconnect() throws IOException {
        Credential current = credential;
        if (current != null) {
            String token = current.getRefreshToken() != null ? current.getRefreshToken() : current.getAccessToken();
            if (token != null) {
                try {
                    revoke(token);
                } catch (IOException e) {
                    // offline: the kept token is still removed below
                }
            }
        }
        deleteTokens();
        credential = null;
        drive = null;
    }

    /** The Google account the connection signs in as; blank if Google does not tell. */
    public String accountEmail() {
        try {
            ensureConnected();
            com.google.api.services.drive.model.About about = drive.about().get().setFields("user(emailAddress)").execute();
            return about.getUser() == null || about.getUser().getEmailAddress() == null
                    ? "" : about.getUser().getEmailAddress();
        } catch (IOException e) {
            return "";
        }
    }

    /** Uploads the file into the BMS folder on Drive. */
    public void upload(File file) throws IOException {
        ensureConnected();
        if (file == null || !file.isFile()) {
            throw new IOException(I18n.get("backup.err.bad_file"));
        }
        com.google.api.services.drive.model.File meta = new com.google.api.services.drive.model.File();
        meta.setName(file.getName());
        meta.setParents(Collections.singletonList(appFolderId()));
        drive.files().create(meta, new FileContent("application/sql", file)).setFields("id").execute();
    }

    /** Downloads the newest backup in the BMS folder into the folder; returns the file. */
    public File downloadLatest(File targetFolder) throws IOException {
        ensureConnected();
        com.google.api.services.drive.model.File latest = newest()
                .orElseThrow(() -> new IOException(I18n.get("backup.err.no_drive_backup")));
        File output = new File(targetFolder, latest.getName());
        try (OutputStream out = new FileOutputStream(output)) {
            drive.files().get(latest.getId()).executeMediaAndDownloadTo(out);
        }
        return output;
    }

    /** When the newest backup in the BMS folder was made (this PC's time zone); empty if there is none. */
    public Optional<LocalDateTime> lastBackupTime() throws IOException {
        ensureConnected();
        return newest().map(f -> Instant.ofEpochMilli(f.getModifiedTime().getValue())
                .atZone(ZoneId.systemDefault()).toLocalDateTime());
    }

    private Optional<com.google.api.services.drive.model.File> newest() throws IOException {
        FileList result = drive.files().list()
                .setQ("'" + appFolderId() + "' in parents and name contains '" + BackupService.FILE_PREFIX
                        + "' and trashed = false")
                .setOrderBy("modifiedTime desc")
                .setPageSize(1)
                .setFields("files(id, name, modifiedTime)")
                .execute();
        List<com.google.api.services.drive.model.File> files = result.getFiles();
        return files == null || files.isEmpty() ? Optional.empty() : Optional.of(files.get(0));
    }

    /** The BMS folder on Drive; made the first time. */
    private String appFolderId() throws IOException {
        FileList result = drive.files().list()
                .setQ("mimeType = '" + FOLDER_MIME + "' and name = '" + APP_FOLDER + "' and trashed = false")
                .setFields("files(id, name)")
                .execute();
        List<com.google.api.services.drive.model.File> folders = result.getFiles();
        if (folders != null && !folders.isEmpty()) {
            return folders.get(0).getId();
        }
        com.google.api.services.drive.model.File meta = new com.google.api.services.drive.model.File();
        meta.setName(APP_FOLDER);
        meta.setMimeType(FOLDER_MIME);
        return drive.files().create(meta).setFields("id").execute().getId();
    }

    /** With a kept token the connection opens by itself (no browser); otherwise the user must connect first. */
    private void ensureConnected() throws IOException {
        if (drive == null && !connectIfStored()) {
            throw new IOException(I18n.get("backup.err.not_connected"));
        }
    }

    // ---- sign-in ----

    private Path tokensDir() {
        return AppPaths.dataDir().resolve("tokens");
    }

    private void deleteTokens() throws IOException {
        Path dir = tokensDir();
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    private void revoke(String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL("https://oauth2.googleapis.com/revoke?token=" + token)
                .openConnection();
        conn.setRequestMethod("POST");
        conn.getResponseCode();
        conn.disconnect();
    }

    /**
     * credentials.json is never in git, so a release build does not contain it: it is copied by hand next to
     * BMS.exe (the install folder) once. A developer run finds it in the working folder or the classpath.
     */
    private InputStream openCredentials() throws IOException {
        String appPath = System.getProperty("jpackage.app-path"); // set by the jpackage launcher (BMS.exe)
        File[] places = {
                appPath == null ? null : new File(new File(appPath).getParentFile(), CREDENTIALS_FILE),
                new File(CREDENTIALS_FILE)
        };
        for (File f : places) {
            if (f != null && f.isFile()) {
                return Files.newInputStream(f.toPath());
            }
        }
        return GoogleDriveService.class.getResourceAsStream("/" + CREDENTIALS_FILE);
    }

    private GoogleAuthorizationCodeFlow newFlow(NetHttpTransport transport) throws IOException {
        InputStream in = openCredentials();
        if (in == null) {
            throw new IOException(I18n.get("backup.err.no_credentials"));
        }
        GoogleClientSecrets secrets;
        try (InputStream stream = in) {
            secrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(stream));
        }
        return new GoogleAuthorizationCodeFlow.Builder(transport, JSON_FACTORY, secrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(tokensDir().toFile()))
                .setAccessType("offline")
                .build();
    }

    private Credential signIn(NetHttpTransport transport) throws Exception {
        GoogleAuthorizationCodeFlow flow = newFlow(transport);
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(SIGN_IN_PORT).build();
        AuthorizationCodeInstalledApp app = new AuthorizationCodeInstalledApp(flow, receiver, this::openBrowser);

        // A user who closes the browser tab leaves authorize() waiting for ever, holding the port. Give up
        // after a minute (or when the caller's thread is interrupted by Cancel) and free the port.
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "google-sign-in");
            t.setDaemon(true);
            return t;
        });
        Future<Credential> future = executor.submit(() -> app.authorize(USER_ID));
        try {
            return future.get(SIGN_IN_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException(I18n.get("backup.err.sign_in_timeout", SIGN_IN_SECONDS), e);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IOException(I18n.get("backup.err.sign_in_failed"), cause);
        } finally {
            try {
                receiver.stop();
            } catch (Exception ignored) {
                // the port must be freed even if the receiver never fully started
            }
            executor.shutdownNow();
        }
    }

    /** The library's own browse() only prints the address and hides every failure: open the browser here. */
    private void openBrowser(String url) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            } catch (Exception ignored) {
                // the Windows command below
            }
        }
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (IOException e) {
            throw new IOException(I18n.get("backup.err.no_browser", url), e);
        }
    }
}
