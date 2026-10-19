package com.eh.bms.controller;

import com.eh.bms.service.RegistrationService;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.WindowManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registration page (N1), opened from the login page's button. Same rules as a user the admin adds (username
 * format, password at least 6 characters, not the username, typed twice). After saving, the login page opens again
 * and says the admin must approve the user first.
 */
public class RegisterController {

    private static final Logger LOG = Logger.getLogger(RegisterController.class.getName());

    @FXML private TextField nameField;
    @FXML private Label nameError;
    @FXML private TextField usernameField;
    @FXML private Label usernameError;
    @FXML private PasswordField passwordField;
    @FXML private Label passwordError;
    @FXML private PasswordField confirmField;
    @FXML private Label confirmError;

    private final RegistrationService service = new RegistrationService();
    private FormValidator form;

    @FXML
    private void initialize() {
        InputFilters.maxLength(nameField, 150);
        InputFilters.maxLength(usernameField, 50);
        InputFilters.maxLength(passwordField, UserFormController.PASSWORD_MAX);
        InputFilters.maxLength(confirmField, UserFormController.PASSWORD_MAX);
        form = new FormValidator()
                .add(nameField, nameError)
                .add(usernameField, usernameError)
                .add(passwordField, passwordError)
                .add(confirmField, confirmError);
        Platform.runLater(nameField::requestFocus);
    }

    @FXML
    private void onRegister() {
        form.clearAll();
        String name = text(nameField);
        String username = text(usernameField);
        if (name.isEmpty()) {
            form.error(nameField, I18n.get("msg.required_field", I18n.get("user.name")));
        }
        if (username.isEmpty()) {
            form.error(usernameField, I18n.get("msg.required_field", I18n.get("user.username")));
        } else if (!UserFormController.USERNAME.matcher(username).matches()) {
            form.error(usernameField, I18n.get("user.error.username_format"));
        }
        ChangePasswordFormController.check(form, passwordField, confirmField, username);
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirmSave()) {
            return;
        }

        try {
            switch (service.register(name, username, passwordField.getText())) {
                case REGISTERED -> {
                    LoginController.showNoticeOnOpen(I18n.get("register.done"));
                    WindowManager.showLogin();
                }
                case DUPLICATE_USERNAME -> {
                    form.error(usernameField, I18n.get("user.error.duplicate"));
                    form.focusFirstError();
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not register user", e);
            form.error(usernameField, I18n.get("msg.error"));
        }
    }

    @FXML
    private void onBack() {
        WindowManager.showLogin();
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }
}
