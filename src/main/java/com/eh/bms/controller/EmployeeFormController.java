package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Employee;
import com.eh.bms.model.SalaryType;
import com.eh.bms.service.EmployeeService;
import com.eh.bms.service.EmployeeService.EmployeeData;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
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
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Add / edit employee popup (14.1). Name, mobile (11 digits), salary type and base salary are required; the
 * base salary label says per month / day / hour by the type. NID must not belong to another employee.
 * Delete shows only for an employee with no attendance, salary sheet or payment.
 */
public class EmployeeFormController {

    private static final Logger LOG = Logger.getLogger(EmployeeFormController.class.getName());

    @FXML private TextField nameField;
    @FXML private Label nameError;
    @FXML private TextField phoneField;
    @FXML private Label phoneError;
    @FXML private TextField nidField;
    @FXML private Label nidError;
    @FXML private TextField addressField;
    @FXML private Label addressError;
    @FXML private TextField designationField;
    @FXML private Label designationError;
    @FXML private DatePicker joinDatePicker;
    @FXML private Label joinDateError;
    @FXML private ComboBox<SalaryType> salaryTypeBox;
    @FXML private Label salaryTypeError;
    @FXML private Label baseSalaryLabel;
    @FXML private TextField baseSalaryField;
    @FXML private Label baseSalaryError;
    @FXML private Button deleteButton;

    private final EmployeeService service = new EmployeeService();
    private FormValidator form;

    /** Null when adding. */
    private Long employeeId;
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.maxLength(nameField, EmployeeService.NAME_MAX);
        InputFilters.digits(phoneField, EmployeeService.PHONE_LENGTH);
        InputFilters.maxLength(nidField, EmployeeService.NID_MAX);
        InputFilters.maxLength(addressField, EmployeeService.ADDRESS_MAX);
        InputFilters.maxLength(designationField, EmployeeService.DESIGNATION_MAX);
        InputFilters.decimal(baseSalaryField, 12, 2);
        DateUtil.apply(joinDatePicker);
        joinDatePicker.setValue(LocalDate.now());

        salaryTypeBox.getItems().setAll(SalaryType.values());
        salaryTypeBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(SalaryType type) {
                return type == null ? "" : EmployeesListController.salaryTypeText(type);
            }

            @Override
            public SalaryType fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        salaryTypeBox.valueProperty().addListener((obs, old, type) ->
                baseSalaryLabel.setText(I18n.get(EmployeesListController.baseSalaryKey(type))));
        salaryTypeBox.setValue(SalaryType.MONTHLY);

        form = new FormValidator()
                .add(nameField, nameError)
                .add(phoneField, phoneError)
                .add(nidField, nidError)
                .add(addressField, addressError)
                .add(designationField, designationError)
                .add(joinDatePicker, joinDateError)
                .add(salaryTypeBox, salaryTypeError)
                .add(baseSalaryField, baseSalaryError);
    }

    /** Fills the form for editing; call before showing. Leave it uncalled to add a new employee. */
    public void edit(Employee employee) {
        employeeId = employee.getId();
        nameField.setText(employee.getName());
        phoneField.setText(employee.getPhone());
        nidField.setText(employee.getNid());
        addressField.setText(employee.getAddress());
        designationField.setText(employee.getDesignation());
        joinDatePicker.setValue(employee.getJoinDate());
        if (employee.getJoinDate() == null) {
            joinDatePicker.getEditor().clear();
        }
        salaryTypeBox.setValue(employee.getSalaryType());
        baseSalaryField.setText(employee.getBaseSalary() == null ? ""
                : employee.getBaseSalary().stripTrailingZeros().toPlainString());
        // Delete only an employee added by mistake: no attendance, salary sheet or payment (and the user may delete)
        boolean deletable = false;
        try {
            deletable = UserSession.isLoggedIn() && UserSession.get().can(AppModule.EMPLOYEE, Action.DELETE)
                    && service.canDelete(employeeId);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not check if the employee can be deleted", e);
        }
        deleteButton.setVisible(deletable);
        deleteButton.setManaged(deletable);
    }

    /** True if the user saved or deleted; the lists then reload. */
    public boolean isSaved() {
        return saved;
    }

    @FXML
    private void onSave() {
        form.clearAll();
        String name = text(nameField);
        String phone = text(phoneField);
        SalaryType type = salaryTypeBox.getValue();
        BigDecimal baseSalary = amount(text(baseSalaryField));
        String typedDate = joinDatePicker.getEditor().getText();
        LocalDate joinDate = typedDate == null || typedDate.isBlank() ? null : DateUtil.parse(typedDate);

        if (name.isEmpty()) {
            form.error(nameField, I18n.get("msg.required_field", I18n.get("label.name")));
        }
        if (phone.isEmpty()) {
            form.error(phoneField, I18n.get("msg.required_field", I18n.get("party.phone")));
        } else if (phone.length() != EmployeeService.PHONE_LENGTH) {
            form.error(phoneField, I18n.get("msg.invalid_mobile"));
        }
        if (typedDate != null && !typedDate.isBlank()) {
            if (joinDate == null) {
                form.error(joinDatePicker, I18n.get("party.error.date"));
            } else {
                joinDatePicker.setValue(joinDate);
            }
        }
        if (type == null) {
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
            EmployeeService.SaveResult result = service.save(new EmployeeData(employeeId, name, phone,
                    text(nidField), text(addressField), text(designationField), joinDate, type, baseSalary));
            switch (result) {
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.saved"));
                }
                case DUPLICATE_NID -> {
                    form.error(nidField, I18n.get("employee.error.duplicate_nid"));
                    form.focusFirstError();
                }
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save employee", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onDelete() {
        if (employeeId == null || !Dialogs.confirmDelete(I18n.get("employee.confirm_delete", text(nameField)))) {
            return;
        }
        try {
            switch (service.delete(employeeId)) {
                case DELETED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.deleted"));
                }
                case IN_USE -> Notifier.error(I18n.get("employee.error.in_use"));
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not delete employee", e);
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

    /** The typed amount, or null when empty. */
    private static BigDecimal amount(String text) {
        if (text.isEmpty() || text.equals(".")) {
            return null;
        }
        return new BigDecimal(text.endsWith(".") ? text + "0" : text.startsWith(".") ? "0" + text : text);
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }
}
