package com.eh.bms.service;

import com.eh.bms.config.AppConfig;
import com.eh.bms.config.DatabaseConfig;
import com.eh.bms.util.I18n;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local backup and restore with MySQL's own tools (copied from the finance-app project):
 * {@code mysqldump} writes {@code backup_data_<yyyyMMdd.HHmm>.sql}, {@code mysql} reads one back.
 * Both run as a child process; the password goes in {@code MYSQL_PWD}, never on the command line.
 * Methods block (they wait for the tool): call them off the JavaFX thread. Errors are IOExceptions
 * whose message is already in the app's language.
 */
public class BackupService {

    /** Start of every backup file name; Google Drive looks the files up by it. */
    public static final String FILE_PREFIX = "backup_data";

    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'.'HHmm");
    private static final Pattern URL_PATTERN = Pattern.compile("jdbc:mysql://([^:/]+)(?::(\\d+))?/([^?]+)");
    private static final Pattern REG_LOCATION = Pattern.compile("Location\\s+REG_SZ\\s+(.+)");

    /** Tool name → full path found on this PC, so the drive scan runs once per app start. */
    private static final Map<String, String> FOUND = new ConcurrentHashMap<>();

    /** Dumps the database into a new file in the folder; returns that file. */
    public File exportToLocal(File targetFolder) throws IOException, InterruptedException {
        if (targetFolder == null || !targetFolder.isDirectory()) {
            throw new IOException(I18n.get("backup.err.bad_folder"));
        }
        String[] db = parseConnection();
        File output = new File(targetFolder, FILE_PREFIX + "_" + LocalDateTime.now().format(NAME_FORMAT) + ".sql");

        Process process = start("mysqldump", db);
        try (InputStream in = process.getInputStream(); OutputStream out = new FileOutputStream(output)) {
            in.transferTo(out);
        }
        String errors = read(process.getErrorStream());
        int exit = process.waitFor();
        if (exit != 0) {
            output.delete();
            throw new IOException(I18n.get("backup.err.dump_failed", errors.isBlank() ? exit : errors.trim()));
        }
        if (!output.isFile() || output.length() == 0) {
            output.delete();
            throw new IOException(I18n.get("backup.err.empty_file"));
        }
        return output;
    }

    /** Replaces the database's data with the contents of a {@code .sql} dump. */
    public void importFromLocal(File sqlFile) throws IOException, InterruptedException {
        if (sqlFile == null || !sqlFile.isFile()) {
            throw new IOException(I18n.get("backup.err.bad_file"));
        }
        String[] db = parseConnection();

        Process process = start("mysql", db);
        try (InputStream in = new FileInputStream(sqlFile); OutputStream out = process.getOutputStream()) {
            in.transferTo(out);
        }
        String errors = read(process.getErrorStream());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException(I18n.get("backup.err.restore_failed", errors.isBlank() ? exit : errors.trim()));
        }
    }

    /** Starts mysqldump / mysql against the app's database (host, port, user, database). */
    private Process start(String tool, String[] db) throws IOException {
        AppConfig cfg = AppConfig.get();
        ProcessBuilder pb = new ProcessBuilder(findTool(tool), "-h", db[0], "-P", db[1],
                "-u", cfg.getString("db.username"), db[2]);
        pb.environment().put("MYSQL_PWD", cfg.getString("db.password", ""));
        try {
            return pb.start();
        } catch (IOException e) {
            throw new IOException(I18n.get("backup.err.tool_start", tool, e.getMessage()), e);
        }
    }

    private String[] parseConnection() throws IOException {
        Matcher m = URL_PATTERN.matcher(AppConfig.get().getString("db.url"));
        if (!m.find()) {
            throw new IOException(I18n.get("backup.err.bad_url"));
        }
        return new String[]{m.group(1), m.group(2) == null ? "3306" : m.group(2), m.group(3)};
    }

    // ---- finding mysqldump / mysql on this PC ----

    /**
     * Full path of a MySQL tool. MySQL may sit on any drive, so these are tried in order, first hit wins:
     * the running server's own folder ({@code SELECT @@basedir}), the PATH, the folders MySQL's Windows
     * installer wrote to the registry (64 and 32-bit views), then the usual MySQL / XAMPP / WAMP / Laragon
     * folders on every drive. The full path is the command itself: a PATH change in ProcessBuilder does
     * not change how Windows finds a bare command name.
     */
    private String findTool(String name) throws IOException {
        String cached = FOUND.get(name);
        if (cached != null && new File(cached).isFile()) {
            return cached;
        }
        for (File bin : candidateBinDirs()) {
            File exe = new File(bin, name + ".exe");
            if (exe.isFile()) {
                FOUND.put(name, exe.getAbsolutePath());
                return exe.getAbsolutePath();
            }
        }
        throw new IOException(I18n.get("backup.err.tool_missing", name + ".exe"));
    }

    private Set<File> candidateBinDirs() {
        Set<File> dirs = new LinkedHashSet<>();
        File server = binDirFromServer();
        if (server != null) {
            dirs.add(server);
        }
        dirs.addAll(binDirsFromPath());
        dirs.addAll(binDirsFromRegistry("/reg:64"));
        dirs.addAll(binDirsFromRegistry("/reg:32"));
        dirs.addAll(binDirsOnAllDrives());
        return dirs;
    }

    /** The bin folder of the MySQL server the app is connected to, e.g. D:\MySQL\MySQL Server 8.0\bin. */
    private File binDirFromServer() {
        try (Connection c = DatabaseConfig.dataSource().getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT @@basedir")) {
            if (rs.next() && rs.getString(1) != null) {
                return new File(rs.getString(1).trim(), "bin");
            }
        } catch (Exception e) {
            // the other lookups follow
        }
        return null;
    }

    private List<File> binDirsFromPath() {
        List<File> dirs = new ArrayList<>();
        String path = System.getenv("Path");
        if (path == null) {
            path = System.getenv("PATH");
        }
        if (path == null) {
            return dirs;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank()) {
                dirs.add(new File(dir.trim().replace("\"", "")));
            }
        }
        return dirs;
    }

    /** MySQL's installer writes HKLM\SOFTWARE\MySQL AB\&lt;product&gt;\Location; a 32-bit one uses the 32-bit view. */
    private List<File> binDirsFromRegistry(String view) {
        List<File> dirs = new ArrayList<>();
        try {
            Process reg = new ProcessBuilder("reg", "query", "HKLM\\SOFTWARE\\MySQL AB", "/s", view).start();
            String out = read(reg.getInputStream());
            reg.waitFor();
            Matcher m = REG_LOCATION.matcher(out);
            while (m.find()) {
                dirs.add(new File(m.group(1).trim(), "bin"));
            }
        } catch (IOException e) {
            // no registry: the drive scan still runs
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return dirs;
    }

    /** Usual install folders on every drive, and any top-level folder with "mysql" in its name. */
    private List<File> binDirsOnAllDrives() {
        List<File> dirs = new ArrayList<>();
        File[] roots = File.listRoots();
        if (roots == null) {
            return dirs;
        }
        for (File root : roots) {
            if (!root.isDirectory()) {
                continue;
            }
            addVersionBins(dirs, new File(root, "Program Files\\MySQL"));
            addVersionBins(dirs, new File(root, "Program Files (x86)\\MySQL"));
            dirs.add(new File(root, "xampp\\mysql\\bin"));
            addVersionBins(dirs, new File(root, "wamp64\\bin\\mysql"));
            addVersionBins(dirs, new File(root, "wamp\\bin\\mysql"));
            addVersionBins(dirs, new File(root, "laragon\\bin\\mysql"));
            File[] top = root.listFiles(File::isDirectory);
            if (top == null) {
                continue;
            }
            for (File folder : top) {
                if (folder.getName().toLowerCase().contains("mysql")) {
                    dirs.add(new File(folder, "bin"));
                    addVersionBins(dirs, folder);
                }
            }
        }
        return dirs;
    }

    /** Adds {@code <parent>\<every subfolder>\bin}. */
    private void addVersionBins(List<File> dirs, File parent) {
        File[] children = parent.listFiles(File::isDirectory);
        if (children == null) {
            return;
        }
        for (File child : children) {
            dirs.add(new File(child, "bin"));
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        in.transferTo(buffer);
        return buffer.toString();
    }
}
