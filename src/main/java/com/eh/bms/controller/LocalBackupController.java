package com.eh.bms.controller;

import com.eh.bms.controller.BackupJobs.Request;
import com.eh.bms.service.BackupService;
import com.eh.bms.util.I18n;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;

/**
 * Settings, first card of row 2 (20.6, 20.7, admin only): backup and restore on this computer with
 * {@code mysqldump} / {@code mysql}. Each job runs in a popup through {@link BackupJobs}.
 */
public class LocalBackupController {

    @FXML private Region root;
    @FXML private Button backupButton;
    @FXML private Button restoreButton;

    private final BackupService backupService = new BackupService();

    @FXML
    private void initialize() {
        if (!BackupJobs.showOnlyForAdmin(root)) {
            return;
        }
        backupButton.disableProperty().bind(BackupJobs.BUSY);
        restoreButton.disableProperty().bind(BackupJobs.BUSY);
    }

    @FXML
    private void onBackup() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("backup.choose_folder"));
        File folder = chooser.showDialog(root.getScene().getWindow());
        if (folder == null) {
            return;
        }
        BackupJobs.run(new Request(I18n.get("backup.confirm_local", folder.getAbsolutePath()), false,
                I18n.get("backup.loading.backup"), false,
                () -> {
                    File saved = backupService.exportToLocal(folder);
                    return I18n.get("backup.done.local", saved.getParent(), saved.getName());
                }, null));
    }

    @FXML
    private void onRestore() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("backup.choose_file"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.get("backup.files"), "*.sql"));
        File file = chooser.showOpenDialog(root.getScene().getWindow());
        if (file == null) {
            return;
        }
        BackupJobs.run(new Request(I18n.get("backup.confirm_restore_local", file.getName()), true,
                I18n.get("backup.loading.restore"), false, () -> {
                    backupService.importFromLocal(file);
                    return I18n.get("backup.done.restore_local", file.getParent(), file.getName());
                }, null));
    }
}
