package com.eh.bms.controller;

import com.eh.bms.model.UserRole;
import com.eh.bms.service.GoogleDriveService;
import com.eh.bms.service.UserSession;
import com.eh.bms.util.I18n;
import com.eh.bms.util.WindowManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the two backup cards of the Settings page share: one job at a time (every button of both cards is off
 * while {@link #BUSY}). A job runs inside one popup: the question (Yes / No), then, after Yes, the same popup
 * turns into a loading screen (spinner + text) and closes when the job ends; the result goes to the top bar.
 * Call on the JavaFX thread.
 */
final class BackupJobs {

    private static final Logger LOG = Logger.getLogger(BackupJobs.class.getName());
    private static final double TEXT_WIDTH = 380;

    /** True while a backup or restore job runs. */
    static final BooleanProperty BUSY = new SimpleBooleanProperty(false);

    /** A job that returns the success message. */
    interface Job {
        String run() throws Exception;
    }

    /**
     * One popup job. {@code confirm}: the question (null = start at once). {@code destructive}: Yes is red.
     * {@code loading}: the text under the spinner. {@code signIn}: a browser sign-in: counts the minute down
     * and has a Cancel button. {@code after}: runs when the popup closes (may be null).
     */
    record Request(String confirm, boolean destructive, String loading, boolean signIn, Job job, Runnable after) {
    }

    private BackupJobs() {
    }

    /** Backup and restore are for the admin only: hides the card for anyone else. True if it stays. */
    static boolean showOnlyForAdmin(Region card) {
        boolean allowed = UserSession.isLoggedIn() && UserSession.get().getRole() == UserRole.ADMIN;
        card.setVisible(allowed);
        card.setManaged(allowed);
        return allowed;
    }

    static void run(Request request) {
        new Popup(request).open();
    }

    private static final class Popup {

        private final Request request;
        private final VBox root = new VBox();
        private final Stage stage;
        private Timeline countdown;
        private Task<String> task;
        private boolean started;
        private boolean finished;

        Popup(Request request) {
            this.request = request;
            root.getStyleClass().add("confirm-dialog");
            Window owner = Window.getWindows().stream().filter(Window::isFocused).findFirst()
                    .orElse(WindowManager.currentWindow());
            stage = WindowManager.dialog(root, I18n.get("backup.popup.title"), owner);
            // Esc during a sign-in cancels it; during a backup or restore the job runs on and the result still shows
            stage.setOnHidden(e -> {
                if (started && !finished && request.signIn()) {
                    cancel();
                }
            });
        }

        void open() {
            if (request.confirm() == null) {
                start();
            } else {
                showQuestion();
            }
            stage.show();
        }

        private void showQuestion() {
            Label text = message(request.confirm());
            Button yes = new Button(I18n.get("button.yes"));
            yes.getStyleClass().add(request.destructive() ? "delete-button" : "save-button");
            yes.setDefaultButton(true);
            yes.setOnAction(e -> start());
            Button no = new Button(I18n.get("button.no"));
            no.getStyleClass().add("no-button");
            no.setCancelButton(true);
            no.setOnAction(e -> stage.close());
            show(text, buttons(yes, no));
        }

        /** The loading screen, then the job on its own thread. */
        private void start() {
            started = true;
            BUSY.set(true);
            Label status = message(request.signIn()
                    ? I18n.get("backup.wait_sign_in", GoogleDriveService.SIGN_IN_SECONDS) : request.loading());
            HBox working = new HBox(12, new ProgressIndicator(), status);
            working.setAlignment(Pos.CENTER_LEFT);
            if (request.signIn()) {
                // The browser sign-in gives up after a minute (GoogleDriveService): count that minute down
                AtomicInteger left = new AtomicInteger(GoogleDriveService.SIGN_IN_SECONDS);
                countdown = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
                    if (left.decrementAndGet() > 0) {
                        status.setText(I18n.get("backup.wait_sign_in", left.get()));
                    }
                }));
                countdown.setCycleCount(GoogleDriveService.SIGN_IN_SECONDS);
                countdown.play();
                Button cancel = new Button(I18n.get("button.cancel"));
                cancel.getStyleClass().add("no-button");
                cancel.setCancelButton(true);
                cancel.setOnAction(e -> cancel());
                show(working, buttons(cancel));
            } else {
                show(working);
            }

            task = new Task<>() {
                @Override
                protected String call() throws Exception {
                    return request.job().run();
                }
            };
            task.setOnSucceeded(e -> {
                finish();
                showResult(task.getValue(), false);
            });
            task.setOnFailed(e -> {
                finish();
                Throwable error = task.getException();
                LOG.log(Level.WARNING, "Backup job failed", error);
                showResult(error != null && error.getMessage() != null ? error.getMessage() : I18n.get("msg.error"), true);
            });
            Thread thread = new Thread(task, "backup-job");
            thread.setDaemon(true);
            thread.start();
        }

        /** Stops the sign-in (a cancelled task runs neither of its handlers) and closes the popup. */
        private void cancel() {
            if (finished) {
                return;
            }
            task.cancel(true);
            finish();
            stage.close();
        }

        /** The job is over: the buttons come back; the popup stays to show the result. */
        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            if (countdown != null) {
                countdown.stop();
            }
            BUSY.set(false);
            if (request.after() != null) {
                request.after().run();
            }
        }

        /** The result in the same popup (red for an error), closed with its own button. */
        private void showResult(String text, boolean error) {
            Label result = message(text);
            if (error) {
                result.getStyleClass().add("error-label");
            }
            Button close = new Button(I18n.get("button.close"));
            close.getStyleClass().add("save-button");
            close.setDefaultButton(true);
            close.setCancelButton(true);
            close.setOnAction(e -> stage.close());
            show(result, buttons(close));
        }

        private void show(javafx.scene.Node... nodes) {
            root.getChildren().setAll(nodes);
            WindowManager.fitPopup(root);
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
    }
}
