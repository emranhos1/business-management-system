package com.eh.bms.controller;

import com.eh.bms.service.DriveBackupService;
import com.eh.bms.service.GoogleDriveService;
import com.eh.bms.util.I18n;
import com.eh.bms.util.WindowManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The question at logout (20.11): Backup now / Skip &amp; logout / Cancel. Backup now needs Google Drive: if it
 * is not connected the popup offers to connect first. One popup whose content changes with each step.
 * Cancel (or Esc) stops a running backup or sign-in and keeps the user logged in; a failed backup never
 * logs out by itself. Call on the JavaFX thread.
 */
final class LogoutBackupDialog {

    private static final Logger LOG = Logger.getLogger(LogoutBackupDialog.class.getName());
    private static final double TEXT_WIDTH = 380;

    private final DriveBackupService backup = new DriveBackupService();
    private final GoogleDriveService drive = GoogleDriveService.get();
    private final VBox root = new VBox();
    private final Stage stage;

    private boolean logout;
    private Task<?> running;
    private Timeline countdown;

    private LogoutBackupDialog() {
        root.getStyleClass().add("confirm-dialog");
        stage = WindowManager.dialog(root, I18n.get("backup.logout.title"), WindowManager.currentWindow());
        // Esc or any other close = Cancel: stop whatever runs and stay logged in
        stage.setOnHidden(e -> stopRunning());
    }

    /** True: go on with the logout (skipped, or backed up). False: stay logged in. */
    static boolean ask() {
        LogoutBackupDialog dialog = new LogoutBackupDialog();
        dialog.showAsk();
        dialog.stage.showAndWait();
        return dialog.logout;
    }

    // ---- steps ----

    private void showAsk() {
        Button now = button("backup.logout.now", "save-button");
        now.setDefaultButton(true);
        now.setOnAction(e -> {
            if (backup.hasDriveConnection()) {
                showRunning();
            } else {
                showNeedsConnect();
            }
        });
        show(message(I18n.get("backup.logout.ask")), buttons(now, skip(), cancel()));
    }

    private void showNeedsConnect() {
        Button connect = button("backup.logout.connect", "save-button");
        connect.setDefaultButton(true);
        connect.setOnAction(e -> showConnecting());
        show(message(I18n.get("backup.logout.connect_ask")), buttons(connect, skip(), cancel()));
    }

    private void showConnecting() {
        AtomicInteger left = new AtomicInteger(GoogleDriveService.SIGN_IN_SECONDS);
        Label status = message(I18n.get("backup.wait_sign_in", left.get()));
        show(working(status), buttons(cancel()));

        countdown = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (left.decrementAndGet() > 0) {
                status.setText(I18n.get("backup.wait_sign_in", left.get()));
            }
        }));
        countdown.setCycleCount(GoogleDriveService.SIGN_IN_SECONDS);
        countdown.play();

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                drive.connect();
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            countdown.stop();
            showRunning();
        });
        task.setOnFailed(e -> {
            countdown.stop();
            showFailure(task.getException());
        });
        start(task, "logout-drive-connect");
    }

    private void showRunning() {
        show(working(message(I18n.get("backup.logout.running"))), buttons(cancel()));

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                backup.backupNow();
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            logout = true;
            stage.close();
        });
        task.setOnFailed(e -> showFailure(task.getException()));
        start(task, "logout-backup");
    }

    private void showFailure(Throwable error) {
        LOG.log(Level.WARNING, "Backup at logout failed", error);
        Label reason = message(error != null && error.getMessage() != null ? error.getMessage() : I18n.get("msg.error"));
        reason.getStyleClass().add("error-label");

        Button retry = button("backup.logout.retry", "save-button");
        retry.setOnAction(e -> showRunning());
        Button connect = button("backup.logout.connect", "save-button");
        connect.setOnAction(e -> showConnecting());
        show(reason, buttons(retry, connect), buttons(skip(), cancel()));
    }

    // ---- parts ----

    private Button skip() {
        Button skip = button("backup.logout.skip", "edit-button");
        skip.setOnAction(e -> {
            logout = true;
            stage.close();
        });
        return skip;
    }

    /** Stops what runs and leaves the popup: the user stays logged in. */
    private Button cancel() {
        Button cancel = button("button.cancel", "no-button");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());
        return cancel;
    }

    private static Button button(String key, String style) {
        Button button = new Button(I18n.get(key));
        button.getStyleClass().add(style);
        return button;
    }

    private static Label message(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(TEXT_WIDTH);
        label.getStyleClass().add("confirm-message");
        return label;
    }

    private static HBox buttons(Button... buttons) {
        HBox row = new HBox(buttons);
        row.getStyleClass().add("dialog-buttons");
        row.setAlignment(Pos.CENTER_RIGHT);
        return row;
    }

    private static HBox working(Label status) {
        HBox row = new HBox(12, new ProgressIndicator(), status);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Swaps the popup's content for the next step and sizes the popup to it. */
    private void show(Node... nodes) {
        root.getChildren().setAll(nodes);
        WindowManager.fitPopup(root);
    }

    private void start(Task<?> task, String name) {
        running = task;
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    /** A cancelled task runs neither its success nor its failure handler, so this never logs out. */
    private void stopRunning() {
        if (countdown != null) {
            countdown.stop();
        }
        if (running != null) {
            running.cancel(true);
        }
    }
}
