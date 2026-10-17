package com.eh.bms.controller;

import com.eh.bms.service.AuthService;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.WindowManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Login screen. */
public class LoginController {

    private static final Logger LOG = Logger.getLogger(LoginController.class.getName());

    private final AuthService authService = new AuthService();

    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private Label usernameLabel;
    @FXML private Label passwordLabel;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;
    @FXML private Button loginButton;

    @FXML
    private void initialize() {
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
        }
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
