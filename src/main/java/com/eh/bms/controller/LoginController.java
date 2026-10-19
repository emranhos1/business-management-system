package com.eh.bms.controller;

import com.eh.bms.model.User;
import com.eh.bms.service.AuthService;
import com.eh.bms.updater.UpdateService;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.WindowManager;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Login screen. */
public class LoginController {

    private static final Logger LOG = Logger.getLogger(LoginController.class.getName());

    private static final String CHANGE_PASSWORD_FXML = "/fxml/views/change-password-form.fxml";
    /** Shown once by the next login page (set by {@link #showNoticeOnOpen}). */
    private static String noticeOnOpen;
    /** Release notes hold an English part, then the Bangla part after this marker (release-notes/README.md). */
    private static final String BANGLA_MARKER = "<!-- bn -->";
    private static final double NOTES_WIDTH = 480;

    private final AuthService authService = new AuthService();

    @FXML private Label titleLabel;
    @FXML private Label usernameLabel;
    @FXML private Label passwordLabel;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;
    @FXML private Button loginButton;
    @FXML private Label forgotLabel;
    @FXML private Button registerButton;
    @FXML private VBox updateBanner;
    @FXML private Label updateLabel;
    @FXML private Button updateButton;
    @FXML private Button notesButton;
    @FXML private ProgressBar updateProgress;
    @FXML private Label updateError;

    private final UpdateService updateService = new UpdateService();
    private UpdateService.Release release;

    @FXML
    private void initialize() {
        notesButton.textProperty().bind(I18n.bind("update.notes"));
        updateButton.textProperty().bind(I18n.bind("update.button"));
        FormValidator.hideWhenEmpty(updateError);
        updateError.textProperty().addListener((obs, old, text) -> Platform.runLater(this::fitWindow));
        checkForUpdate();

        forgotLabel.textProperty().bind(I18n.bind("login.forgot_hint"));
        registerButton.textProperty().bind(I18n.bind("register.open"));
        if (noticeOnOpen != null) {
            showMessage(noticeOnOpen, false);
            noticeOnOpen = null;
        }
        titleLabel.textProperty().bind(I18n.bind("login.button"));
        usernameLabel.textProperty().bind(I18n.bind("login.username"));
        passwordLabel.textProperty().bind(I18n.bind("login.password"));
        usernameField.promptTextProperty().bind(I18n.bind("login.username"));
        passwordField.promptTextProperty().bind(I18n.bind("login.password"));
        loginButton.textProperty().bind(I18n.bind("login.button"));
        // users.username is 50 long; BCrypt reads at most 72 bytes of a password
        InputFilters.maxLength(usernameField, 50);
        InputFilters.maxLength(passwordField, 72);

        Platform.runLater(usernameField::requestFocus);
    }

    @FXML
    private void onLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (username.isEmpty()) {
            showError(I18n.get("msg.required_field", I18n.get("login.username")));
            usernameField.requestFocus();
            return;
        }
        if (password.isEmpty()) {
            showError(I18n.get("msg.required_field", I18n.get("login.password")));
            passwordField.requestFocus();
            return;
        }
        hideError();

        AuthService.LoginResult result;
        try {
            result = authService.login(username, password);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Login failed", e);
            showError(I18n.get("msg.error"));
            return;
        }

        switch (result.status()) {
            case INVALID_CREDENTIALS -> {
                showError(I18n.get("login.error.invalid"));
                passwordField.clear();
                passwordField.requestFocus();
            }
            case INACTIVE -> {
                showError(I18n.get("login.error.inactive"));
                passwordField.clear();
            }
            case PENDING -> {
                showError(I18n.get("login.error.pending"));
                passwordField.clear();
            }
            case SUCCESS -> WindowManager.showMain();
            case MUST_CHANGE_PASSWORD -> firstLogin(result.user());
        }
    }

    /**
     * The password was chosen by somebody else (the config's first admin password, or one the admin set): the user
     * picks their own before getting in. An admin is then shown a recovery code, once. Closing the popup without
     * saving leaves the user on the login screen.
     */
    private void firstLogin(User user) {
        FXMLLoader loader = WindowManager.loader(CHANGE_PASSWORD_FXML);
        Parent root = WindowManager.load(loader, CHANGE_PASSWORD_FXML);
        ChangePasswordFormController form = loader.getController();
        form.setUp(user);
        WindowManager.dialog(root, I18n.get("password.change.title")).showAndWait();
        if (!form.isSaved()) {
            passwordField.clear();
            passwordField.requestFocus();
            return;
        }
        authService.startSession(user);
        WindowManager.showMain();
    }

    /** Asks GitHub in the background, so a slow or missing connection never delays login; offline = no banner. */
    private void checkForUpdate() {
        Thread check = new Thread(() -> updateService.check().ifPresent(found -> Platform.runLater(() -> showBanner(found))),
                "update-check");
        check.setDaemon(true);
        check.start();
    }

    private void showBanner(UpdateService.Release found) {
        release = found;
        updateLabel.textProperty().bind(Bindings.createStringBinding(() -> I18n.get("update.available",
                I18n.digits(found.version()), I18n.digits(updateService.currentVersion())), I18n.localeProperty()));
        updateBanner.setVisible(true);
        updateBanner.setManaged(true);
        fitWindow();
    }

    /** The login window has a fixed size: it grows by the banner's height (never past the screen) and stays centred. */
    private void fitWindow() {
        Window window = updateBanner.getScene() == null ? null : updateBanner.getScene().getWindow();
        if (window == null) {
            return;
        }
        VBox box = (VBox) updateBanner.getParent();
        double extra = updateBanner.isManaged() ? updateBanner.prefHeight(updateBanner.getPrefWidth()) + box.getSpacing() : 0;
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double height = Math.min(WindowManager.LOGIN_SIZE + extra, screen.getHeight());
        window.setY(Math.max(screen.getMinY(), window.getY() - (height - window.getHeight()) / 2));
        window.setHeight(height);
    }

    /** Release text in the current language: English part, then the Bangla part after the marker. */
    @FXML
    private void onShowNotes() {
        String body = release.notes();
        int marker = body.indexOf(BANGLA_MARKER);
        if (marker >= 0) {
            body = I18n.BANGLA.equals(I18n.getLocale()) ? body.substring(marker + BANGLA_MARKER.length()) : body.substring(0, marker);
        }
        // plain words: drop the Markdown heading marks and bold stars
        String text = body.replaceAll("(?m)^#+\\s*", "").replace("**", "").trim();

        Label notes = new Label(text.isEmpty() ? I18n.get("update.notes.empty") : text);
        notes.setWrapText(true);
        notes.setMaxWidth(NOTES_WIDTH);
        notes.getStyleClass().add("confirm-message");
        Button close = new Button(I18n.get("button.close"));
        close.getStyleClass().add("cancel-button");
        close.setCancelButton(true);
        HBox buttons = new HBox(close);
        buttons.getStyleClass().add("dialog-buttons");
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(notes, buttons);
        root.getStyleClass().add("confirm-dialog");

        Stage stage = WindowManager.dialog(root, I18n.get("update.notes.title", I18n.digits(release.version())));
        close.setOnAction(e -> stage.close());
        stage.showAndWait();
    }

    /**
     * Asks where to save the installer (the last folder, else Downloads), downloads it (size and SHA-256 checked),
     * opens its wizard and closes the app so its files can be replaced.
     */
    @FXML
    private void onUpdate() {
        UpdateService.Release target = release;
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("update.folder", I18n.digits(target.version())));
        File downloads = new File(System.getProperty("user.home"), "Downloads");
        Path last = updateService.lastFolder();
        if (last != null) {
            chooser.setInitialDirectory(last.toFile());
        } else if (downloads.isDirectory()) {
            chooser.setInitialDirectory(downloads);
        }
        File folder = chooser.showDialog(updateButton.getScene().getWindow());
        if (folder == null) {
            return;
        }

        updateButton.textProperty().unbind();
        updateButton.setText(I18n.get("update.downloading", I18n.digits("0")));
        updateButton.setDisable(true);
        updateError.setText("");
        updateProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        updateProgress.setVisible(true);
        updateProgress.setManaged(true);
        fitWindow();

        Thread worker = new Thread(() -> {
            try {
                Path installer = updateService.download(target, folder.toPath(), p -> Platform.runLater(() -> {
                    updateProgress.setProgress(p);
                    // floor, so it never says 100% while the last bytes are still coming
                    updateButton.setText(I18n.get("update.downloading", I18n.digits(String.valueOf((int) Math.floor(p * 100)))));
                }));
                updateService.launch(installer);
                Platform.runLater(Platform::exit);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.log(Level.WARNING, "Update download failed", e);
                Platform.runLater(() -> {
                    updateButton.textProperty().bind(I18n.bind("update.button"));
                    updateButton.setDisable(false);
                    updateProgress.setVisible(false);
                    updateProgress.setManaged(false);
                    updateError.setText(I18n.get("update.error"));
                });
            }
        }, "update-download");
        worker.setDaemon(true);
        worker.start();
    }

    /** The registration page (N1) in place of this one. */
    @FXML
    private void onRegister() {
        WindowManager.showRegister();
    }

    /** A green notice the next login page shows once, e.g. after a registration (N1). */
    static void showNoticeOnOpen(String notice) {
        noticeOnOpen = notice;
    }

    private void showError(String message) {
        showMessage(message, true);
    }

    /** The one message line on the card: red for an error, green for a notice (no message bar on this screen). */
    private void showMessage(String message, boolean error) {
        errorLabel.getStyleClass().removeAll("error-label", "success-label");
        errorLabel.getStyleClass().add(error ? "error-label" : "success-label");
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
