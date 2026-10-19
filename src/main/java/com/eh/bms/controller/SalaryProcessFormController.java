package com.eh.bms.controller;

import com.eh.bms.model.Employee;
import com.eh.bms.model.SalaryType;
import com.eh.bms.service.AttendanceService.MonthSummary;
import com.eh.bms.service.SalaryService;
import com.eh.bms.service.SalaryService.Draft;
import com.eh.bms.service.SalaryService.ProcessData;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PartyText;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Work out one employee's salary for a month (14.3): present / leave days, days or hours worked (from attendance
 * when the month has any, else typed in), deductions and bonus typed by hand. Gross and net follow the typed
 * values. A month already worked out can be changed, or deleted (its payments then count as advance). Paid above
 * the net (with advance carried from earlier months) carries on to the next months.
 */
public class SalaryProcessFormController {

    private static final Logger LOG = Logger.getLogger(SalaryProcessFormController.class.getName());
    private static final int NOTE_MAX = 255;

    @FXML private Label employeeLabel;
    @FXML private Label salaryLabel;
    @FXML private Label monthLabel;
    @FXML private Label workingDaysLabel;
    @FXML private Label attendanceLabel;
    @FXML private Label attendanceHint;
    @FXML private TextField presentField;
    @FXML private Label presentError;
    @FXML private TextField leaveField;
    @FXML private Label leaveError;
    @FXML private Label unitsCaption;
    @FXML private TextField unitsField;
    @FXML private Label unitsError;
    @FXML private Label unitsHint;
    @FXML private TextField deductionsField;
    @FXML private Label deductionsError;
    @FXML private TextField bonusField;
    @FXML private Label bonusError;
    @FXML private Label grossLabel;
    @FXML private Label grossHint;
    @FXML private Label netLabel;
    @FXML private Label netError;
    @FXML private Label paidLabel;
    @FXML private TextArea noteField;
    @FXML private Label noteError;
    @FXML private Button deleteButton;

    private final SalaryService service = new SalaryService();
    private FormValidator form;
    private Draft draft;
    private Employee employee;
    private YearMonth month;
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.digits(presentField, 2);
        InputFilters.digits(leaveField, 2);
        InputFilters.decimal(unitsField, 6, 2);
        InputFilters.decimal(deductionsField, 12, 2);
        InputFilters.decimal(bonusField, 12, 2);
        InputFilters.maxLength(noteField, NOTE_MAX);
        FormValidator.hideWhenEmpty(salaryLabel, workingDaysLabel, attendanceHint, unitsHint, grossHint, netError,
                paidLabel);
        form = new FormValidator()
                .add(presentField, presentError)
                .add(leaveField, leaveError)
                .add(unitsField, unitsError)
                .add(deductionsField, deductionsError)
                .add(bonusField, bonusError)
                .add(noteField, noteError);
        unitsField.textProperty().addListener((obs, old, text) -> recalc());
        deductionsField.textProperty().addListener((obs, old, text) -> recalc());
        bonusField.textProperty().addListener((obs, old, text) -> recalc());
    }

    /**
     * Loads the employee's month (saved sheet, or prefilled from attendance); call before showing. False (and says
     * why) when the form cannot be used, e.g. a month after this one.
     */
    public boolean setUp(long employeeId, YearMonth month) {
        this.month = month;
        if (month.isAfter(YearMonth.now())) {
            Notifier.error(I18n.get("salary.error.future_month"));
            return false;
        }
        try {
            draft = service.draft(employeeId, month);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load the salary sheet form", e);
            Notifier.error(I18n.get("msg.error"));
            return false;
        }
        employee = draft.employee();
        SalaryType type = employee.getSalaryType();

        employeeLabel.setText(employee.getName());
        salaryLabel.setText(I18n.get("salary.type_base", SalaryController.typeText(type),
                SalaryController.baseText(employee)));
        monthLabel.setText(SalaryController.monthText(month));
        workingDaysLabel.setText(I18n.get("salary.working_days_hint",
                I18n.digits(String.valueOf(draft.workingDays())),
                I18n.digits(String.valueOf(month.lengthOfMonth()))));
        showAttendance(draft.attendance(), type);

        unitsCaption.setText(I18n.get(type == SalaryType.HOURLY ? "salary.worked_hours" : "salary.worked_days"));
        if (type != SalaryType.MONTHLY) {
            unitsCaption.getStyleClass().add("required");
        }
        if (!draft.processed() && draft.attendance() != null && draft.attendance().hasRows()) {
            unitsHint.setText(I18n.get("salary.units_from_attendance"));
        } else if (type == SalaryType.MONTHLY) {
            unitsHint.setText(I18n.get("salary.units_monthly_hint"));
        }

        presentField.setText(String.valueOf(draft.presentDays()));
        leaveField.setText(String.valueOf(draft.leaveDays()));
        unitsField.setText(plain(draft.workedUnits()));
        deductionsField.setText(plain(draft.deductions()));
        bonusField.setText(plain(draft.bonus()));
        noteField.setText(draft.note());

        BigDecimal paid = draft.paid() == null ? BigDecimal.ZERO : draft.paid();
        BigDecimal carry = draft.carryIn() == null ? BigDecimal.ZERO : draft.carryIn();
        String paidText = paid.signum() > 0 ? I18n.get(draft.processed() ? "salary.paid_so_far"
                : "salary.advance_so_far", MoneyUtil.format(paid)) : "";
        if (carry.signum() > 0) {
            String carryText = I18n.get("salary.carry_in_info", MoneyUtil.format(carry));
            paidText = paidText.isEmpty() ? carryText : paidText + "; " + carryText;
        }
        paidLabel.setText(paidText);
        boolean deletable = draft.processed() && SalaryController.can(Action.DELETE);
        deleteButton.setVisible(deletable);
        deleteButton.setManaged(deletable);
        recalc();
        return true;
    }

    public boolean isSaved() {
        return saved;
    }

    private void showAttendance(MonthSummary att, SalaryType type) {
        if (att == null || !att.hasRows()) {
            attendanceLabel.setText(I18n.get("salary.attendance.none"));
            return;
        }
        attendanceLabel.setText(I18n.get("salary.attendance.summary", I18n.digits(String.valueOf(att.present())),
                I18n.digits(String.valueOf(att.halfDay())), I18n.digits(String.valueOf(att.leave())),
                I18n.digits(String.valueOf(att.absent()))));
        if (type == SalaryType.HOURLY) {
            String hint = I18n.get("salary.attendance.hours", SalaryController.number(att.hours()));
            if (att.daysWithoutTime() > 0) {
                hint += " " + I18n.get("salary.attendance.no_time", I18n.digits(String.valueOf(att.daysWithoutTime())));
            }
            attendanceHint.setText(hint);
        }
    }

    /** Gross and net from the typed values; shows "—" while required days / hours are missing. */
    private void recalc() {
        if (employee == null) {
            return;
        }
        netError.setText("");
        SalaryType type = employee.getSalaryType();
        BigDecimal units = decimal(unitsField);
        if (type != SalaryType.MONTHLY && units == null) {
            grossLabel.setText("—");
            grossHint.setText("");
            netLabel.setText("—");
            SalaryController.setStyle(netLabel, null);
            WindowManager.fitPopup(netLabel);
            return;
        }
        BigDecimal gross = SalaryService.gross(type, employee.getBaseSalary(), units);
        BigDecimal net = SalaryService.net(gross, decimal(deductionsField), decimal(bonusField));
        grossLabel.setText(MoneyUtil.format(gross));
        grossHint.setText(type == SalaryType.MONTHLY ? I18n.get("salary.gross_hint.MONTHLY")
                : I18n.get("salary.gross_hint." + type.name(), SalaryController.number(units),
                MoneyUtil.format(employee.getBaseSalary())));
        netLabel.setText(MoneyUtil.format(net));
        SalaryController.setStyle(netLabel, net.signum() < 0 ? PartyText.PAY_STYLE : null);
        WindowManager.fitPopup(netLabel);
    }

    @FXML
    private void onSave() {
        form.clearAll();
        netError.setText("");
        SalaryType type = employee.getSalaryType();
        int days = month.lengthOfMonth();
        Integer present = integer(presentField);
        Integer leave = integer(leaveField);
        BigDecimal units = decimal(unitsField);
        BigDecimal deductions = decimal(deductionsField);
        BigDecimal bonus = decimal(bonusField);
        String daysText = I18n.digits(String.valueOf(days));

        if (present != null && present > days) {
            form.error(presentField, I18n.get("salary.error.days_max", daysText));
        }
        if (leave != null && leave > days) {
            form.error(leaveField, I18n.get("salary.error.days_max", daysText));
        } else if (present != null && leave != null && present <= days && present + leave > days) {
            form.error(leaveField, I18n.get("salary.error.present_leave_max", daysText));
        }
        if (units == null) {
            if (type != SalaryType.MONTHLY) {
                form.error(unitsField, I18n.get("msg.required_field", unitsCaption.getText()));
            }
        } else if (type != SalaryType.HOURLY && units.compareTo(BigDecimal.valueOf(days)) > 0) {
            form.error(unitsField, I18n.get("salary.error.days_max", daysText));
        }
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        BigDecimal gross = SalaryService.gross(type, employee.getBaseSalary(), units);
        BigDecimal net = SalaryService.net(gross, deductions, bonus);
        if (net.signum() < 0) {
            showNetError(I18n.get("salary.error.negative_net"));
            return;
        }
        if (!Dialogs.confirm(I18n.get("salary.process.confirm", employee.getName(),
                SalaryController.monthText(month), MoneyUtil.format(net)))) {
            return;
        }

        try {
            SalaryService.Result r = service.process(new ProcessData(employee.getId(), month,
                    present == null ? 0 : present, leave == null ? 0 : leave, units, deductions, bonus,
                    noteField.getText()));
            switch (r.status()) {
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.saved"));
                }
                case NEGATIVE_NET -> showNetError(I18n.get("salary.error.negative_net"));
                case FUTURE_MONTH -> Notifier.error(I18n.get("salary.error.future_month"));
                case NOT_FOUND -> Notifier.error(I18n.get("salary.error.not_found"));
                default -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save the salary sheet", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onDelete() {
        if (draft == null || !draft.processed() || !Dialogs.confirmDelete(I18n.get("salary.process.confirm_delete",
                employee.getName(), SalaryController.monthText(month)))) {
            return;
        }
        try {
            switch (service.deleteSheet(employee.getId(), month)) {
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.deleted"));
                }
                case NOT_FOUND -> Notifier.error(I18n.get("salary.error.not_found"));
                default -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not delete the salary sheet", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onCancel() {
        close();
    }

    /** Error under the net salary; the deductions field is where it is usually fixed. */
    private void showNetError(String message) {
        netError.setText(message);
        WindowManager.fitPopup(netError);
        deductionsField.requestFocus();
    }

    /** The typed number, or null when empty. */
    private static BigDecimal decimal(TextField field) {
        String text = field.getText() == null ? "" : field.getText().trim();
        if (text.isEmpty() || text.equals(".")) {
            return null;
        }
        if (text.endsWith(".")) {
            text += "0";
        }
        if (text.startsWith(".")) {
            text = "0" + text;
        }
        return new BigDecimal(text);
    }

    private static Integer integer(TextField field) {
        String text = field.getText() == null ? "" : field.getText().trim();
        return text.isEmpty() ? null : Integer.valueOf(text);
    }

    /** For an input field: plain digits, no extra zeros; empty for null. */
    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private void close() {
        ((Stage) presentField.getScene().getWindow()).close();
    }
}
