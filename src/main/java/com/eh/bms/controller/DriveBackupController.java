package com.eh.bms.controller;

import com.eh.bms.controller.BackupJobs.Request;
import com.eh.bms.service.DriveBackupService;
import com.eh.bms.service.GoogleDriveService;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Settings, right card (20.8 - 20.10, admin only): connect / disconnect Google Drive, back up to it and
 * restore from it. Jobs run through {@link BackupJobs}.
 */
public class DriveBackupController {

    @FXML private Region root;
    @FXML private Label stateLabel;
    @FXML private Button connectButton;
    @FXML private Button disconnectButton;
    @FXML private Button backupButton;
    @FXML private Button restoreButton;

    private final DriveBackupService driveBackup = new DriveBackupService();
    private final GoogleDriveService drive = GoogleDriveService.get();
    private final BooleanProperty connected = new SimpleBooleanProperty(false);

    @FXML
    private void initialize() {
        if (!BackupJobs.showOnlyForAdmin(root)) {
            return;
        }
        connectButton.disableProperty().bind(BackupJobs.BUSY.or(connected));
        disconnectButton.disableProperty().bind(BackupJobs.BUSY.or(connected.not()));
        backupButton.disableProperty().bind(BackupJobs.BUSY.or(connected.not()));
        restoreButton.disableProperty().bind(BackupJobs.BUSY.or(connected.not()));
        showState(Optional.empty());
        refresh();
    }

    @FXML
    private void onConnect() {
        BackupJobs.run(new Request(null, false, null, true, () -> {
            drive.connect();
            return I18n.get("backup.done.connected");
        }, this::refresh));
    }

    @FXML
    private void onDisconnect() {
        BackupJobs.run(new Request(I18n.get("backup.confirm_disconnect"), false,
                I18n.get("backup.loading.disconnect"), false, () -> {
                    drive.disconnect();
                    return I18n.get("backup.done.disconnected");
                }, this::refresh));
    }

    @FXML
    private void onBackup() {
        BackupJobs.run(new Request(I18n.get("backup.confirm_drive"), false,
                I18n.get("backup.loading.upload"), false, () -> {
                    String name = driveBackup.backupNow();
                    return I18n.get("backup.done.drive", driveBackup.accountEmail(), name);
                }, this::refresh));
    }

    @FXML
    private void onRestore() {
        BackupJobs.run(new Request(I18n.get("backup.confirm_restore_drive"), true,
                I18n.get("backup.loading.restore"), false, () -> {
                    String name = driveBackup.restoreLatest();
                    return I18n.get("backup.done.restore_drive", driveBackup.accountEmail(), name);
                }, this::refresh));
    }

    /** Opens a kept connection (no browser) and reads the last backup time, then shows both. */
    private void refresh() {
        Task<Optional<LocalDateTime>> task = new Task<>() {
            @Override
            protected Optional<LocalDateTime> call() throws Exception {
                return drive.connectIfStored() ? drive.lastBackupTime() : Optional.empty();
            }
        };
        task.setOnSucceeded(e -> showState(task.getValue()));
        // Offline or a revoked token: the state still shows from what is known
        task.setOnFailed(e -> showState(Optional.empty()));
        Thread thread = new Thread(task, "backup-drive-state");
        thread.setDaemon(true);
        thread.start();
    }

    private void showState(Optional<LocalDateTime> last) {
        Platform.runLater(() -> {
            connected.set(drive.isConnected());
            stateLabel.setText(!connected.get() ? I18n.get("backup.drive.state.off")
                    : last.map(t -> I18n.get("backup.drive.last", DateUtil.format(t.toLocalDate()) + " "
                            + DateUtil.formatTime(t.toLocalTime())))
                    .orElse(I18n.get("backup.drive.state.on")));
        });
    }
}
