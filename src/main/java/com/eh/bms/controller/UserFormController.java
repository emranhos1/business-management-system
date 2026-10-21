package com.eh.bms.controller;

import com.eh.bms.model.SalaryType;
import com.eh.bms.model.User;
import com.eh.bms.model.UserRole;
import com.eh.bms.service.UserService;
import com.eh.bms.service.UserService.UserData;
import com.eh.bms.service.UserSession;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.Notifier;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Add / edit user popup (17.1, N12: every user is staff). Name, mobile (11 digits), role (পদ), salary type and
 * salary are required; NID must not belong to another user. A new user needs a password (at least 6 characters,
 * typed twice); when editing, a blank password keeps the old one. The username is fixed after saving, and you
 * cannot change your own role. Delete shows only for a user added by mistake (no history, not you, not the last
 * admin).
 */
public class UserFormController {

    private static final Logger LOG = Logger.getLogger(UserFormController.class.getName());
    /** Also used by the registration form (N1). */
    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,50}");
    private static final int PASSWORD_MIN = 6;
    /** BCrypt reads only the first 72 bytes, so a longer password would be cut silently. */
    static final int PASSWORD_MAX = 50;

    @FXML private TextField nameField;
    @FXML private Label nameError;
    @FXML private TextField phoneField;
    @FXML private Label phoneError;
    @FXML private TextField nidField;
    @FXML private Label nidError;
    @FXML private TextField addressField;
    @FXML private TextField usernameField;
    @FXML private Label usernameError;
    @FXML private Label usernameHint;
    @FXML private PasswordField passwordField;
    @FXML private Label passwordError;
    @FXML private Label passwordHint;
    @FXML private Label passwordLabel;
    @FXML private PasswordField confirmField;
    @FXML private Label confirmError;
    @FXML private Label confirmLabel;
    @FXML private ComboBox<UserRole> roleBox;
    @FXML private Label roleError;
    @FXML private Label roleHint;
    @FXML private DatePicker joinDatePicker;
    @FXML private Label joinDateError;
    @FXML private ComboBox<SalaryType> salaryTypeBox;
    @FXML private Label salaryTypeError;
    @FXML private Label baseSalaryLabel;
    @FXML private TextField baseSalaryField;
    @FXML private Label baseSalaryError;
    @FXML private Button deleteButton;

    private final UserService service = new UserService();
    private FormValidator form;
    /** Null when adding. */
    private Long userId;
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.maxLength(nameField, UserService.NAME_MAX);
        InputFilters.digits(phoneField, UserService.PHONE_LENGTH);
        InputFilters.maxLength(nidField, UserService.NID_MAX);
        InputFilters.maxLength(addressField, UserService.ADDRESS_MAX);
        InputFilters.maxLength(usernameField, 50);
        InputFilters.maxLength(passwordField, PASSWORD_MAX);
        InputFilters.maxLength(confirmField, PASSWORD_MAX);
        InputFilters.decimal(baseSalaryField, 12, 2);
        DateUtil.apply(joinDatePicker);
        joinDatePicker.setValue(LocalDate.now());

        // The support login's role is not for the shop to give
        roleBox.getItems().setAll(UserRole.ADMIN, UserRole.MANAGER, UserRole.CASHIER, UserRole.EMPLOYEE);
        roleBox.setConverter(converter(role -> I18n.get("role." + role.name())));
        // A new user starts with the role that can do the least
        roleBox.setValue(UserRole.EMPLOYEE);

        salaryTypeBox.getItems().setAll(SalaryType.values());
        salaryTypeBox.setConverter(converter(SalaryText::typeText));
        salaryTypeBox.valueProperty().addListener((obs, old, type) ->
                baseSalaryLabel.setText(I18n.get(SalaryText.baseSalaryKey(type))));
        salaryTypeBox.setValue(SalaryType.MONTHLY);

        form = new FormValidator()
                .add(nameField, nameError)
                .add(phoneField, phoneError)
                .add(nidField, nidError)
                .add(usernameField, usernameError)
                .add(passwordField, passwordError)
                .add(confirmField, confirmError)
                .add(roleBox, roleError)
                .add(joinDatePicker, joinDateError)
                .add(salaryTypeBox, salaryTypeError)
                .add(baseSalaryField, baseSalaryError);
    }

    /** Fills the form for editing; call before showing. Leave it uncalled to add a new user. */
    public void edit(User user) {
        userId = user.getId();
        nameField.setText(user.getName());
        phoneField.setText(user.getPhone());
        nidField.setText(user.getNid());
        addressField.setText(user.getAddress());
        usernameField.setText(user.getUsername());
        usernameField.setDisable(true);
        show(usernameHint, true);
        // A blank password keeps the old one, so it is optional here
        passwordLabel.getStyleClass().remove("required");
        confirmLabel.getStyleClass().remove("required");
        boolean self = UserSession.isLoggedIn() && user.getId() == UserSession.get().getUserId();
        // A password set for somebody else is temporary; one set for yourself stays
        passwordHint.setText(I18n.get(self ? "user.password.hint_edit_self" : "user.password.hint_edit"));
        roleBox.setValue(user.getRole());
        roleBox.setDisable(self);
        show(roleHint, self);
        joinDatePicker.setValue(user.getJoinDate());
        if (user.getJoinDate() == null) {
            joinDatePicker.getEditor().clear();
        }
        // A registration not set up yet has no salary: monthly, blank amount, for the admin to fill in
        salaryTypeBox.setValue(user.getSalaryType() == null ? SalaryType.MONTHLY : user.getSalaryType());
        baseSalaryField.setText(user.getBaseSalary() == null ? ""
                : user.getBaseSalary().stripTrailingZeros().toPlainString());
        boolean deletable = service.canDelete(userId);
        deleteButton.setVisible(deletable);
        deleteButton.setManaged(deletable);
    }

    /** True if the user was saved or deleted; the list then reloads. */
    public boolean isSaved() {
        return saved;
    }

    @FXML
    private void onSave() {
        form.clearAll();
        String name = text(nameField);
        String phone = text(phoneField);
        String username = text(usernameField);
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        String confirm = confirmField.getText() == null ? "" : confirmField.getText();
        UserRole role = roleBox.getValue();
        SalaryType salaryType = salaryTypeBox.getValue();
        BigDecimal baseSalary = amount(text(baseSalaryField));
        String typedDate = joinDatePicker.getEditor().getText();
        LocalDate joinDate = typedDate == null || typedDate.isBlank() ? null : DateUtil.parse(typedDate);
        boolean adding = userId == null;

        if (name.isEmpty()) {
            form.error(nameField, I18n.get("msg.required_field", I18n.get("user.name")));
        }
        if (phone.isEmpty()) {
            form.error(phoneField, I18n.get("msg.required_field", I18n.get("party.phone")));
        } else if (phone.length() != UserService.PHONE_LENGTH) {
            form.error(phoneField, I18n.get("msg.invalid_mobile"));
        }
        if (adding) {
            if (username.isEmpty()) {
                form.error(usernameField, I18n.get("msg.required_field", I18n.get("user.username")));
            } else if (!USERNAME.matcher(username).matches()) {
                form.error(usernameField, I18n.get("user.error.username_format"));
            }
            if (password.isEmpty()) {
                form.error(passwordField, I18n.get("msg.required_field", I18n.get("user.password")));
            }
        }
        // A password typed (new, or a change) must be long enough and typed the same twice
        if (!password.isEmpty() || !confirm.isEmpty()) {
            if (!password.isEmpty() && password.length() < PASSWORD_MIN) {
                form.error(passwordField, I18n.get("user.error.password_short"));
            } else if (!password.equals(confirm)) {
                form.error(confirmField, I18n.get("user.error.password_mismatch"));
            }
        }
        if (role == null) {
            form.error(roleBox, I18n.get("msg.required_field", I18n.get("user.role")));
        }
        if (typedDate != null && !typedDate.isBlank()) {
            if (joinDate == null) {
                form.error(joinDatePicker, I18n.get("party.error.date"));
            } else {
                joinDatePicker.setValue(joinDate);
            }
        }
        if (salaryType == null) {
            form.error(salaryTypeBox, I18n.get("msg.required_field", I18n.get("employee.salary_type")));
        }
        if (baseSalary == null) {
            form.error(baseSalaryField, I18n.get("msg.required_field", baseSalaryLabel.getText()));
        } else if (baseSalary.signum() <= 0) {
            form.error(baseSalaryField, I18n.get("employee.error.zero_salary"));
        }
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirmSave()) {
            return;
        }

        try {
            UserData data = new UserData(userId, name, username, password, role, phone, text(nidField),
                    text(addressField), joinDate, salaryType, baseSalary);
            switch (service.save(data)) {
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.saved"));
                }
                case DUPLICATE_USERNAME -> {
                    form.error(usernameField, I18n.get("user.error.duplicate"));
                    form.focusFirstError();
                }
                case DUPLICATE_NID -> {
                    form.error(nidField, I18n.get("employee.error.duplicate_nid"));
                    form.focusFirstError();
                }
                case OWN_ROLE -> {
                    form.error(roleBox, I18n.get("user.error.own_role"));
                    form.focusFirstError();
                }
                case LAST_ADMIN -> {
                    form.error(roleBox, I18n.get("user.error.last_admin"));
                    form.focusFirstError();
                }
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save user", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onDelete() {
        if (userId == null || !Dialogs.confirmDelete(I18n.get("user.confirm_delete", text(nameField)))) {
            return;
        }
        try {
            switch (service.delete(userId)) {
                case DELETED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.deleted"));
                }
                case IN_USE -> Notifier.error(I18n.get("user.error.in_use"));
                case SELF -> Notifier.error(I18n.get("user.error.self"));
                case LAST_ADMIN -> Notifier.error(I18n.get("user.error.last_admin"));
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not delete user", e);
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

    private static <T> StringConverter<T> converter(Function<T, String> toText) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : toText.apply(value);
            }

            @Override
            public T fromString(String text) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** The typed amount, or null when empty. */
    private static BigDecimal amount(String text) {
        if (text.isEmpty() || text.equals(".")) {
            return null;
        }
        return new BigDecimal(text.endsWith(".") ? text + "0" : text.startsWith(".") ? "0" + text : text);
    }

    private static void show(Label label, boolean shown) {
        label.setVisible(shown);
        label.setManaged(shown);
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }
}
