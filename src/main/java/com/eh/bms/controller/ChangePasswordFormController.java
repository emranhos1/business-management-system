package com.eh.bms.controller;

import com.eh.bms.model.User;
import com.eh.bms.service.AuthService;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Own password at the first login, or after the admin set one for the user (Stage 17 follow-up): the password
 * somebody else chose is replaced before the user gets in. It cannot be the old one or the username.
 */
public class ChangePasswordFormController {

    private static final Logger LOG = Logger.getLogger(ChangePasswordFormController.class.getName());
    static final int PASSWORD_MIN = 6;
    /** BCrypt reads only the first 72 bytes; stay well under so nothing is cut silently. */
    static final int PASSWORD_MAX = 50;

    @FXML private PasswordField passwordField;
    @FXML private Label passwordError;
    @FXML private PasswordField confirmField;
    @FXML private Label confirmError;

    private final AuthService authService = new AuthService();
    private FormValidator form;
    private User user;
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.maxLength(passwordField, PASSWORD_MAX);
        InputFilters.maxLength(confirmField, PASSWORD_MAX);
        form = new FormValidator().add(passwordField, passwordError).add(confirmField, confirmError);
        Platform.runLater(passwordField::requestFocus);
    }

    /** The user who has to choose a password; call before showing. */
    public void setUp(User user) {
        this.user = user;
    }

    public boolean isSaved() {
        return saved;
    }

    @FXML
    private void onSave() {
        form.clearAll();
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        check(form, passwordField, confirmField, user.getUsername());
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirmSave()) {
            return;
        }
        try {
            switch (authService.setFirstPassword(user.getId(), password)) {
                case DONE -> {
                    saved = true;
                    close();
                }
                case SAME_AS_OLD -> {
                    form.error(passwordField, I18n.get("password.error.same"));
                    form.focusFirstError();
                }
                case NOT_ALLOWED -> {
                    form.error(passwordField, I18n.get("msg.error"));
                    form.focusFirstError();
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save the new password", e);
            form.error(passwordField, I18n.get("msg.error"));
        }
    }

    /**
     * Checks a new password and its repeat, showing the first problem on its field: required, long enough, not
     * the username, typed the same twice.
     */
    static void check(FormValidator form, PasswordField passwordField, PasswordField confirmField, String username) {
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        String confirm = confirmField.getText() == null ? "" : confirmField.getText();
        if (password.isEmpty()) {
            form.error(passwordField, I18n.get("msg.required_field", I18n.get("password.new")));
        } else if (password.length() < PASSWORD_MIN) {
            form.error(passwordField, I18n.get("user.error.password_short"));
        } else if (password.equalsIgnoreCase(username)) {
            form.error(passwordField, I18n.get("password.error.username"));
        } else if (!password.equals(confirm)) {
            form.error(confirmField, I18n.get("user.error.password_mismatch"));
        }
    }

    @FXML
    private void onCancel() {
        close();
    }

    private void close() {
        ((Stage) passwordField.getScene().getWindow()).close();
    }
}
