package com.eh.bms.controller;

import com.eh.bms.model.User;
import com.eh.bms.service.AuthService;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.WindowManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Login screen. */
public class LoginController {

    private static final Logger LOG = Logger.getLogger(LoginController.class.getName());

    private static final String CHANGE_PASSWORD_FXML = "/fxml/views/change-password-form.fxml";

    private final AuthService authService = new AuthService();

    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private Label usernameLabel;
    @FXML private Label passwordLabel;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;
    @FXML private Button loginButton;
    @FXML private Label forgotLabel;

    @FXML
    private void initialize() {
        forgotLabel.textProperty().bind(I18n.bind("login.forgot_hint"));
        titleLabel.textProperty().bind(I18n.bind("app.title"));
        subtitleLabel.textProperty().bind(I18n.bind("login.subtitle"));
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

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
