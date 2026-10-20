package com.eh.bms.controller;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.SalaryType;
import com.eh.bms.model.User;
import com.eh.bms.service.AccountLedgerService;
import com.eh.bms.service.AccountService;
import com.eh.bms.service.SalaryService;
import com.eh.bms.service.SalaryService.Draft;
import com.eh.bms.service.SalaryService.MonthData;
import com.eh.bms.service.SalaryService.Pay;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PartyText;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Salary popup (N12): works out a user's month and pays in one save. The month's figures (days / hours worked for a
 * daily / hourly salary, deductions with their reason, bonus) give gross, net and what is still due; the money part
 * (amount, emergency advance, account, date) is optional: blank = only work out the month. A payment never goes
 * above the due unless it is an emergency advance, and money out never above what the account holds on that date.
 */
public class SalaryFormController {

    private static final Logger LOG = Logger.getLogger(SalaryFormController.class.getName());
    private static final String FXML_PATH = "/fxml/views/salary-form.fxml";
    private static final int NOTE_MAX = 255;

    @FXML private Label userLabel;
    @FXML private Label salaryLabel;
    @FXML private ComboBox<Month> monthBox;
    @FXML private ComboBox<Integer> yearBox;
    @FXML private Label monthError;
    @FXML private Label paidLabel;
    @FXML private HBox unitsRow;
    @FXML private Label unitsCaption;
    @FXML private TextField unitsField;
    @FXML private Label unitsError;
    @FXML private TextField deductionsField;
    @FXML private Label deductionsError;
    @FXML private Label reasonCaption;
    @FXML private TextField reasonField;
    @FXML private Label reasonError;
    @FXML private TextField bonusField;
    @FXML private Label bonusError;
    @FXML private Label grossLabel;
    @FXML private Label grossHint;
    @FXML private Label netLabel;
    @FXML private Label netError;
    @FXML private Label dueLabel;
    @FXML private TextField amountField;
    @FXML private Label amountError;
    @FXML private CheckBox emergencyBox;
    @FXML private ComboBox<Account> accountBox;
    @FXML private Label accountError;
    @FXML private Label accountBalanceLabel;
    @FXML private DatePicker datePicker;
    @FXML private Label dateError;

    private final SalaryService service = new SalaryService();
    private final AccountService accountService = new AccountService();
    private final AccountLedgerService accountLedger = new AccountLedgerService();
    private FormValidator form;

    private User user;
    /** The chosen month as saved so far; null while it could not be read. */
    private Draft draft;
    /** Balance of every account, read once when the popup opens. */
    private Map<Long, BigDecimal> balances = new HashMap<>();
    /** True while the month and year are being set, so their listeners do not load twice. */
    private boolean setting;
    private boolean saved;

    /** Opens the popup for {@code user} on {@code month}. True when something was saved. */
    public static boolean open(User user, YearMonth month) {
        if (!user.hasSalary()) {
            Notifier.error(I18n.get("salary.error.no_salary", user.getName()));
            return false;
        }
        FXMLLoader loader = WindowManager.loader(FXML_PATH);
        Parent root = WindowManager.load(loader, FXML_PATH);
        SalaryFormController form = loader.getController();
        if (!form.setUp(user, month)) {
            return false;
        }
        WindowManager.dialog(root, I18n.get("salary.form.title", user.getName())).showAndWait();
        return form.saved;
    }

    @FXML
    private void initialize() {
        InputFilters.decimal(unitsField, 6, 2);
        InputFilters.decimal(deductionsField, 12, 2);
        InputFilters.decimal(bonusField, 12, 2);
        InputFilters.maxLength(reasonField, NOTE_MAX);
        InputFilters.decimal(amountField, 12, 2);
        FormValidator.hideWhenEmpty(salaryLabel, paidLabel, grossHint, netError, dueLabel, accountBalanceLabel);
        DateUtil.apply(datePicker);
        datePicker.setValue(LocalDate.now());
        // No future dates in the calendar
        datePicker.setDayCellFactory(picker -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                setDisable(empty || date.isAfter(LocalDate.now()));
            }
        });
        SalaryController.monthConverter(monthBox);
        SalaryController.yearConverter(yearBox);
        monthBox.getItems().setAll(Month.values());
        monthBox.valueProperty().addListener((obs, old, now) -> {
            if (!setting) {
                loadMonth();
            }
        });
        yearBox.valueProperty().addListener((obs, old, now) -> {
            if (!setting) {
                loadMonth();
            }
        });
        accountBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Account a) {
                return a == null ? "" : name(a);
            }

            @Override
            public Account fromString(String text) {
                throw new UnsupportedOperationException();
            }
        });
        accountBox.valueProperty().addListener((obs, old, a) -> showAccountBalance(a));
        unitsField.textProperty().addListener((obs, old, text) -> recalc());
        deductionsField.textProperty().addListener((obs, old, text) -> recalc());
        bonusField.textProperty().addListener((obs, old, text) -> recalc());

        form = new FormValidator()
                .add(monthBox, monthError)
                .add(unitsField, unitsError)
                .add(deductionsField, deductionsError)
                .add(reasonField, reasonError)
                .add(bonusField, bonusError)
                .add(amountField, amountError)
                .add(accountBox, accountError)
                .add(datePicker, dateError);
    }

    /** Fills the popup; false (and says why) when it cannot be used, e.g. there is no account yet. */
    private boolean setUp(User user, YearMonth month) {
        this.user = user;
        SalaryType type = user.getSalaryType();
        userLabel.setText(user.getName() + " (" + SalaryController.roleText(user) + ")");
        salaryLabel.setText(I18n.get("salary.type_base", SalaryController.typeText(type),
                SalaryController.baseText(user)));
        boolean units = type != SalaryType.MONTHLY;
        unitsRow.setVisible(units);
        unitsRow.setManaged(units);
        unitsCaption.setText(I18n.get(type == SalaryType.HOURLY ? "salary.worked_hours" : "salary.worked_days"));
        try {
            List<Account> accounts = accountService.all();
            if (accounts.isEmpty()) {
                Notifier.error(I18n.get("party.error.no_account"));
                return false;
            }
            balances = accountLedger.balances(accounts);
            accountBox.getItems().setAll(accounts);
            // Cash first
            accountBox.setValue(accounts.stream()
                    .filter(a -> a.getType() != null && a.getType().getNumberRule() == AccountNumberRule.NONE)
                    .findFirst().orElse(accounts.get(0)));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load the salary popup", e);
            Notifier.error(I18n.get("msg.error"));
            return false;
        }
        setting = true;
        try {
            yearBox.getItems().setAll(SalaryController.years(month));
            yearBox.setValue(month.getYear());
            monthBox.setValue(month.getMonth());
        } finally {
            setting = false;
        }
        loadMonth();
        return true;
    }

    /** Reads the chosen month (its saved figures, paid so far, advance carried in) into the form. */
    private void loadMonth() {
        YearMonth month = chosenMonth();
        draft = null;
        if (month != null && !month.isAfter(YearMonth.now())) {
            try {
                draft = service.draft(user.getId(), month);
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "Could not load the salary month", e);
                Notifier.error(I18n.get("msg.error"));
            }
        }
        unitsField.setText(draft == null ? "" : plain(draft.workedUnits()));
        deductionsField.setText(draft == null ? "" : plain(draft.deductions()));
        bonusField.setText(draft == null ? "" : plain(draft.bonus()));
        reasonField.setText(draft == null ? "" : draft.note());
        String text = "";
        if (draft != null) {
            BigDecimal paid = draft.paid() == null ? BigDecimal.ZERO : draft.paid();
            if (paid.signum() > 0) {
                text = I18n.get("salary.paid_so_far", MoneyUtil.format(paid));
            }
            if (draft.carryIn() != null && draft.carryIn().signum() > 0) {
                String carry = I18n.get("salary.carry_in_info", MoneyUtil.format(draft.carryIn()));
                text = text.isEmpty() ? carry : text + "; " + carry;
            }
        }
        paidLabel.setText(text);
        recalc();
    }

    /** Gross, net and due from the typed values; "—" while required days / hours are missing. */
    private void recalc() {
        if (user == null) {
            return;
        }
        netError.setText("");
        SalaryType type = user.getSalaryType();
        BigDecimal units = decimal(unitsField);
        BigDecimal deductions = decimal(deductionsField);
        // The reason is a must only while something is cut
        boolean cut = deductions != null && deductions.signum() > 0;
        if (cut != reasonCaption.getStyleClass().contains("required")) {
            if (cut) {
                reasonCaption.getStyleClass().add("required");
            } else {
                reasonCaption.getStyleClass().remove("required");
            }
        }
        if (type != SalaryType.MONTHLY && units == null) {
            grossLabel.setText("—");
            grossHint.setText("");
            netLabel.setText("—");
            dueLabel.setText("");
            SalaryController.setStyle(netLabel, null);
            WindowManager.fitPopup(netLabel);
            return;
        }
        BigDecimal gross = SalaryService.gross(type, user.getBaseSalary(), units);
        BigDecimal net = SalaryService.net(gross, deductions, decimal(bonusField));
        grossLabel.setText(MoneyUtil.format(gross));
        grossHint.setText(type == SalaryType.MONTHLY ? I18n.get("salary.gross_hint.MONTHLY")
                : I18n.get("salary.gross_hint." + type.name(), SalaryController.number(units),
                MoneyUtil.format(user.getBaseSalary())));
        netLabel.setText(MoneyUtil.format(net));
        SalaryController.setStyle(netLabel, net.signum() < 0 ? PartyText.PAY_STYLE : null);
        BigDecimal left = net.subtract(paidAndCarry());
        dueLabel.setText(net.signum() < 0 ? "" : SalaryController.dueText(left.max(BigDecimal.ZERO),
                left.negate().max(BigDecimal.ZERO)));
        SalaryController.setStyle(dueLabel, SalaryController.dueStyle(left));
        WindowManager.fitPopup(netLabel);
    }

    /** Paid for the month so far plus advance carried in from earlier months. */
    private BigDecimal paidAndCarry() {
        if (draft == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal paid = draft.paid() == null ? BigDecimal.ZERO : draft.paid();
        return draft.carryIn() == null ? paid : paid.add(draft.carryIn());
    }

    private YearMonth chosenMonth() {
        Month m = monthBox.getValue();
        Integer y = yearBox.getValue();
        return m == null || y == null ? null : YearMonth.of(y, m);
    }

    @FXML
    private void onSave() {
        form.clearAll();
        netError.setText("");
        SalaryType type = user.getSalaryType();
        YearMonth month = chosenMonth();
        BigDecimal units = decimal(unitsField);
        BigDecimal deductions = decimal(deductionsField);
        BigDecimal bonus = decimal(bonusField);
        String reason = reasonField.getText() == null ? "" : reasonField.getText().trim();
        BigDecimal amount = decimal(amountField);
        Account account = accountBox.getValue();
        LocalDate date = readDate();

        if (month == null) {
            form.error(monthBox, I18n.get("msg.required_field", I18n.get("salary.month")));
        } else if (month.isAfter(YearMonth.now())) {
            form.error(monthBox, I18n.get("salary.error.future_month"));
        }
        if (type != SalaryType.MONTHLY) {
            if (units == null) {
                form.error(unitsField, I18n.get("msg.required_field", unitsCaption.getText()));
            } else if (type == SalaryType.DAILY && month != null
                    && units.compareTo(BigDecimal.valueOf(month.lengthOfMonth())) > 0) {
                form.error(unitsField, I18n.get("salary.error.days_max",
                        I18n.digits(String.valueOf(month.lengthOfMonth()))));
            }
        }
        if (deductions != null && deductions.signum() > 0 && reason.isEmpty()) {
            form.error(reasonField, I18n.get("salary.error.reason"));
        }
        BigDecimal net = type == SalaryType.MONTHLY || units != null
                ? SalaryService.net(SalaryService.gross(type, user.getBaseSalary(), units), deductions, bonus) : null;
        boolean paying = amount != null && amount.signum() > 0;
        if (amount != null && amount.signum() <= 0) {
            form.error(amountField, I18n.get("party.error.zero"));
        } else if (paying && net != null && !emergencyBox.isSelected()
                && amount.compareTo(net.subtract(paidAndCarry()).max(BigDecimal.ZERO)) > 0) {
            form.error(amountField, overNetText(net.subtract(paidAndCarry()).max(BigDecimal.ZERO)));
        } else if (paying && account != null && amount.compareTo(available(account)) > 0) {
            form.error(amountField, insufficientText(available(account)));
        }
        if (paying) {
            if (account == null) {
                form.error(accountBox, I18n.get("msg.required_field", I18n.get("salary.pay.account")));
            }
            if (date == null) {
                boolean typed = datePicker.getEditor().getText() != null && !datePicker.getEditor().getText().isBlank();
                form.error(datePicker, typed ? I18n.get("party.error.date")
                        : I18n.get("msg.required_field", I18n.get("label.date")));
            } else if (date.isAfter(LocalDate.now())) {
                form.error(datePicker, I18n.get("party.error.future_date"));
            }
        }
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (net != null && net.signum() < 0) {
            showNetError(I18n.get("salary.error.negative_net"));
            return;
        }
        String question = paying
                ? I18n.get(emergencyBox.isSelected() ? "salary.form.confirm_emergency" : "salary.form.confirm_pay",
                user.getName(), SalaryController.monthText(month), MoneyUtil.format(net), MoneyUtil.format(amount),
                name(account))
                : I18n.get("salary.form.confirm", user.getName(), SalaryController.monthText(month),
                MoneyUtil.format(net));
        if (!Dialogs.confirm(question)) {
            return;
        }

        try {
            SalaryService.Result r = service.save(
                    new MonthData(user.getId(), month, units, deductions, bonus, reason),
                    paying ? new Pay(date, amount, account.getId(), emergencyBox.isSelected()) : null);
            switch (r.status()) {
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get(paying ? "salary.pay.done" : "msg.saved"));
                }
                case NEGATIVE_NET -> showNetError(I18n.get("salary.error.negative_net"));
                case OVER_NET -> {
                    // Someone paid this month meanwhile: show the month's figures again
                    loadMonth();
                    form.error(amountField, overNetText(r.amount()));
                    form.focusFirstError();
                }
                case INSUFFICIENT_BALANCE -> {
                    // Money went out since the popup opened, or the date is earlier: show today's balance again;
                    // the error shows what was there on that date
                    balances.put(account.getId(), accountLedger.balance(account.getId()));
                    showAccountBalance(account);
                    form.error(amountField, I18n.get("msg.max_out_on_date", MoneyUtil.format(r.amount())));
                    form.focusFirstError();
                }
                case FUTURE_MONTH -> Notifier.error(I18n.get("salary.error.future_month"));
                case NO_SALARY -> Notifier.error(I18n.get("salary.error.no_salary", user.getName()));
                case NOT_FOUND -> Notifier.error(I18n.get("salary.error.not_found"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save the salary", e);
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

    private static String overNetText(BigDecimal max) {
        return max == null || max.signum() <= 0 ? I18n.get("salary.error.fully_paid")
                : I18n.get("salary.error.over_net", MoneyUtil.format(max));
    }

    private BigDecimal available(Account account) {
        return balances.getOrDefault(account.getId(), BigDecimal.ZERO).max(BigDecimal.ZERO);
    }

    private static String insufficientText(BigDecimal available) {
        return I18n.get("party.error.insufficient", MoneyUtil.format(available));
    }

    private void showAccountBalance(Account account) {
        boolean show = account != null && balances.containsKey(account.getId());
        accountBalanceLabel.setText(show
                ? I18n.get("transfer.balance_hint", MoneyUtil.format(balances.get(account.getId()))) : "");
        WindowManager.fitPopup(accountBalanceLabel);
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

    /** For an input field: plain digits, no extra zeros; empty for null or 0. */
    private static String plain(BigDecimal value) {
        return value == null || value.signum() == 0 ? "" : value.stripTrailingZeros().toPlainString();
    }

    /** The picked date; typed text counts even if the user did not press Enter. */
    private LocalDate readDate() {
        String typed = datePicker.getEditor().getText();
        if (typed == null || typed.isBlank()) {
            return null;
        }
        LocalDate parsed = DateUtil.parse(typed);
        if (parsed != null) {
            datePicker.setValue(parsed);
        }
        return parsed;
    }

    private static String name(Account a) {
        return I18n.pick(a.getName(), a.getNameEn());
    }

    private void close() {
        ((Stage) amountField.getScene().getWindow()).close();
    }
}
