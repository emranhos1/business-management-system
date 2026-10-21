package com.eh.bms.service;

import com.eh.bms.util.I18n;

import java.io.File;
import java.io.IOException;

/**
 * Dump → Google Drive and Drive → restore, in one step each. Used by the Settings backup card and by the
 * logout question. The dump goes to the temp folder and is removed afterwards. Blocks: call off the
 * JavaFX thread.
 */
public class DriveBackupService {

    private final BackupService backup = new BackupService();
    private final GoogleDriveService drive = GoogleDriveService.get();

    /** Connected now, or signed in before (the connection then opens by itself, no browser). */
    public boolean hasDriveConnection() {
        return drive.isConnected() || drive.hasStoredCredentials();
    }

    /** Dumps the database and uploads the dump to the BMS folder on Drive; returns the file's name. */
    public String backupNow() throws IOException, InterruptedException {
        File dump = backup.exportToLocal(new File(System.getProperty("java.io.tmpdir")));
        try {
            // Cancel (the thread is interrupted) must stop here, not upload anyway
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException(I18n.get("backup.err.cancelled"));
            }
            drive.upload(dump);
            return dump.getName();
        } finally {
            dump.delete();
        }
    }

    /** The Google account the backups go to (blank if unknown). */
    public String accountEmail() {
        return drive.accountEmail();
    }

    /** Downloads the newest backup from the BMS folder and restores the database from it. */
    public String restoreLatest() throws IOException, InterruptedException {
        File dump = drive.downloadLatest(new File(System.getProperty("java.io.tmpdir")));
        try {
            backup.importFromLocal(dump);
            return dump.getName();
        } finally {
            dump.delete();
        }
    }
}
