package com.eh.bms.controller;

import com.eh.bms.model.User;
import com.eh.bms.model.UserRole;
import com.eh.bms.service.UserService;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Notifier;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Approve a registration (N1): the admin must pick the role (no default, user decision). */
public class ApproveUserFormController {

    private static final Logger LOG = Logger.getLogger(ApproveUserFormController.class.getName());

    @FXML private TextField nameField;
    @FXML private TextField usernameField;
    @FXML private ComboBox<UserRole> roleBox;
    @FXML private Label roleError;

    private final UserService service = new UserService();
    private FormValidator form;
    private long userId;
    private boolean saved;

    @FXML
    private void initialize() {
        // The support login's role is not for the shop to give
        roleBox.getItems().setAll(UserRole.ADMIN, UserRole.MANAGER, UserRole.CASHIER, UserRole.EMPLOYEE);
        roleBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(UserRole role) {
                return role == null ? "" : I18n.get("role." + role.name());
            }

            @Override
            public UserRole fromString(String text) {
                throw new UnsupportedOperationException();
            }
        });
        form = new FormValidator().add(roleBox, roleError);
    }

    /** The registration to approve; call before showing. */
    public void setUp(User user) {
        userId = user.getId();
        nameField.setText(user.getName());
        usernameField.setText(user.getUsername());
    }

    /** True if the user was approved; the lists then reload. */
    public boolean isSaved() {
        return saved;
    }

    @FXML
    private void onSave() {
        form.clearAll();
        UserRole role = roleBox.getValue();
        if (role == null) {
            form.error(roleBox, I18n.get("msg.required_field", I18n.get("user.role")));
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirm(I18n.get("user.confirm_approve", nameField.getText(), I18n.get("role." + role.name())))) {
            return;
        }
        try {
            switch (service.approve(userId, role)) {
                case DONE -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("user.approved"));
                }
                case SELF, LAST_ADMIN, NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not approve user", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onCancel() {
        close();
    }

    private void close() {
        ((Stage) nameField.getScene().getWindow()).close();
    }
}
