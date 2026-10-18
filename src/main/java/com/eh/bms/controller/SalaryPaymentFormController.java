package com.eh.bms.controller;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.AppModule;
import com.eh.bms.model.Employee;
import com.eh.bms.model.SalaryPayment;
import com.eh.bms.service.AccountLedgerService;
import com.eh.bms.service.AccountService;
import com.eh.bms.service.SalaryService;
import com.eh.bms.service.SalaryService.MonthDue;
import com.eh.bms.service.SalaryService.PaymentData;
import com.eh.bms.service.UserSession;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
 * Salary payment popup (14.4): the salary month it is for (net, paid and due shown, or "advance" while the month
 * is not worked out; advance carried from earlier months too), amount, emergency advance, account (balance shown),
 * date (today by default, never in the future), note. A payment never goes above the month's due unless it is an
 * emergency advance (the extra counts for the next months), and money out never above what the account holds on
 * that date. Editing a payment (from the history) also offers delete.
 */
public class SalaryPaymentFormController {

    private static final Logger LOG = Logger.getLogger(SalaryPaymentFormController.class.getName());
    private static final String FXML_PATH = "/fxml/views/salary-payment-form.fxml";
    private static final int NOTE_MAX = 255;

    @FXML private Label employeeLabel;
    @FXML private Label salaryLabel;
    @FXML private ComboBox<Month> monthBox;
    @FXML private ComboBox<Integer> yearBox;
    @FXML private Label monthError;
    @FXML private Label dueLabel;
    @FXML private TextField amountField;
    @FXML private Label amountError;
    @FXML private CheckBox emergencyBox;
    @FXML private ComboBox<Account> accountBox;
    @FXML private Label accountError;
    @FXML private Label accountBalanceLabel;
    @FXML private DatePicker datePicker;
    @FXML private Label dateError;
    @FXML private TextArea noteField;
    @FXML private Label noteError;
    @FXML private Button deleteButton;
    @FXML private Button saveButton;

    private final SalaryService service = new SalaryService();
    private final AccountService accountService = new AccountService();
    private final AccountLedgerService accountLedger = new AccountLedgerService();
    private FormValidator form;

    private Employee employee;
    /** The payment being edited; null when adding. */
    private SalaryPayment payment;
    /** The chosen month's figures, without the edited payment; null while it could not be read. */
    private MonthDue monthDue;
    /** Balance of every account, read once when the form opens. */
    private Map<Long, BigDecimal> balances = new HashMap<>();
    /** True while the month and year are being set, so their listeners do not load twice. */
    private boolean setting;
    private boolean saved;

    /**
     * Opens the popup for {@code employee}: a new payment for {@code month} ({@code payment} null), or the given
     * payment to edit (its account must be loaded). True when it was saved or deleted.
     */
    public static boolean open(Employee employee, YearMonth month, SalaryPayment payment) {
        FXMLLoader loader = WindowManager.loader(FXML_PATH);
        Parent root = WindowManager.load(loader, FXML_PATH);
        SalaryPaymentFormController form = loader.getController();
        if (!form.setUp(employee, month, payment)) {
            return false;
        }
        String title = I18n.get(payment == null ? "salary.pay.title" : "salary.pay.edit_title", employee.getName());
        WindowManager.dialog(root, title).showAndWait();
        return form.saved;
    }

    /** Asks, then deletes a salary payment (its money goes back to the account). True when deleted. */
    public static boolean delete(Employee employee, SalaryPayment payment) {
        if (!Dialogs.confirmDelete(I18n.get("salary.pay.confirm_delete", MoneyUtil.format(payment.getAmount()),
                employee.getName(), DateUtil.format(payment.getDate())))) {
            return false;
        }
        try {
            switch (new SalaryService().deletePayment(payment.getId())) {
                case SAVED -> {
                    Notifier.success(I18n.get("msg.deleted"));
                    return true;
                }
                case NOT_FOUND -> Notifier.error(I18n.get("salary.error.not_found"));
                default -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not delete the salary payment", e);
            Notifier.error(I18n.get("msg.error"));
        }
        return false;
    }

    @FXML
    private void initialize() {
        InputFilters.decimal(amountField, 12, 2);
        InputFilters.maxLength(noteField, NOTE_MAX);
        FormValidator.hideWhenEmpty(salaryLabel, dueLabel, accountBalanceLabel);
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
                loadDue();
            }
        });
        yearBox.valueProperty().addListener((obs, old, now) -> {
            if (!setting) {
                loadDue();
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

        form = new FormValidator()
                .add(monthBox, monthError)
                .add(amountField, amountError)
                .add(accountBox, accountError)
                .add(datePicker, dateError)
                .add(noteField, noteError);
    }

    /** Fills the form; false (and says why) when it cannot be used, e.g. there is no account yet. */
    private boolean setUp(Employee employee, YearMonth month, SalaryPayment payment) {
        this.employee = employee;
        this.payment = payment;
        employeeLabel.setText(employee.getName());
        salaryLabel.setText(I18n.get("salary.type_base", SalaryController.typeText(employee.getSalaryType()),
                SalaryController.baseText(employee)));
        saveButton.setText(I18n.get(payment == null ? "salary.pay" : "button.save"));
        try {
            List<Account> accounts = accountService.all();
            if (accounts.isEmpty()) {
                Notifier.error(I18n.get("party.error.no_account"));
                return false;
            }
            balances = accountLedger.balances(accounts);
            accountBox.getItems().setAll(accounts);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load the salary payment form", e);
            Notifier.error(I18n.get("msg.error"));
            return false;
        }

        YearMonth shown = payment == null ? month : YearMonth.of(payment.getYear(), payment.getMonth());
        setting = true;
        try {
            yearBox.getItems().setAll(SalaryController.years(shown));
            yearBox.setValue(shown.getYear());
            monthBox.setValue(shown.getMonth());
        } finally {
            setting = false;
        }

        if (payment == null) {
            // Cash first
            accountBox.setValue(accountBox.getItems().stream()
                    .filter(a -> a.getType() != null && a.getType().getNumberRule() == AccountNumberRule.NONE)
                    .findFirst().orElse(accountBox.getItems().get(0)));
        } else {
            accountBox.getItems().stream().filter(a -> a.getId().equals(payment.getAccount().getId()))
                    .findFirst().ifPresent(accountBox::setValue);
            amountField.setText(payment.getAmount().stripTrailingZeros().toPlainString());
            datePicker.setValue(payment.getDate());
            noteField.setText(payment.getNote());
            emergencyBox.setSelected(payment.isEmergency());
            boolean deletable = SalaryController.can(UserSession.Action.DELETE);
            deleteButton.setVisible(deletable);
            deleteButton.setManaged(deletable);
        }
        loadDue();
        return true;
    }

    /** Reads the chosen month's net, paid and due (the edited payment left out) and shows them. */
    private void loadDue() {
        YearMonth month = chosenMonth();
        monthDue = null;
        if (month == null) {
            dueLabel.setText("");
            return;
        }
        try {
            monthDue = service.monthDue(employee.getId(), month, payment == null ? null : payment.getId());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load the salary month", e);
            Notifier.error(I18n.get("msg.error"));
        }
        SalaryController.setStyle(dueLabel, null);
        if (monthDue == null) {
            dueLabel.setText("");
        } else if (monthDue.processed()) {
            String due = SalaryController.dueText(monthDue.due(), monthDue.advanceLeft());
            dueLabel.setText(monthDue.carryIn().signum() > 0
                    ? I18n.get("salary.pay.due_info_carry", MoneyUtil.format(monthDue.net()),
                    MoneyUtil.format(monthDue.carryIn()), MoneyUtil.format(monthDue.paid()), due)
                    : I18n.get("salary.pay.due_info", MoneyUtil.format(monthDue.net()),
                    MoneyUtil.format(monthDue.paid()), due));
            SalaryController.setStyle(dueLabel, SalaryController.dueStyle(monthDue.due()));
        } else {
            String text = I18n.get("salary.pay.advance_info", MoneyUtil.format(monthDue.paid()));
            if (monthDue.carryIn().signum() > 0) {
                text += " " + I18n.get("salary.carry_in_info", MoneyUtil.format(monthDue.carryIn()));
            }
            dueLabel.setText(text);
        }
        WindowManager.fitPopup(dueLabel);
    }

    private YearMonth chosenMonth() {
        Month m = monthBox.getValue();
        Integer y = yearBox.getValue();
        return m == null || y == null ? null : YearMonth.of(y, m);
    }

    @FXML
    private void onSave() {
        form.clearAll();
        YearMonth month = chosenMonth();
        Account account = accountBox.getValue();
        BigDecimal amount = amount();
        LocalDate date = readDate();

        if (month == null) {
            form.error(monthBox, I18n.get("msg.required_field", I18n.get("salary.month")));
        }
        if (amount == null) {
            form.error(amountField, I18n.get("msg.required_field", I18n.get("label.amount")));
        } else if (amount.signum() <= 0) {
            form.error(amountField, I18n.get("party.error.zero"));
        } else if (!emergencyBox.isSelected() && monthDue != null && monthDue.processed()
                && amount.compareTo(monthDue.due()) > 0) {
            form.error(amountField, overNetText(monthDue.due()));
        } else if (account != null && balances.containsKey(account.getId())
                && amount.compareTo(available(account)) > 0) {
            form.error(amountField, insufficientText(available(account)));
        }
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
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        String confirmKey = emergencyBox.isSelected() ? "salary.pay.confirm_emergency"
                : monthDue != null && !monthDue.processed() ? "salary.pay.confirm_advance" : "salary.pay.confirm";
        if (!Dialogs.confirm(I18n.get(confirmKey, MoneyUtil.format(amount), employee.getName(),
                SalaryController.monthText(month), name(account)))) {
            return;
        }

        try {
            SalaryService.Result r = service.savePayment(new PaymentData(payment == null ? null : payment.getId(),
                    employee.getId(), month, date, amount, account.getId(), noteField.getText(),
                    emergencyBox.isSelected()));
            switch (r.status()) {
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get(payment == null ? "salary.pay.done" : "msg.saved"));
                }
                case OVER_NET -> {
                    // Someone paid this month meanwhile: show the month's figures again
                    loadDue();
                    form.error(amountField, overNetText(r.amount()));
                    form.focusFirstError();
                }
                case INSUFFICIENT_BALANCE -> {
                    // Money went out since the form opened, or the date is earlier: show today's balance again;
                    // the error shows what was there on that date
                    balances.put(account.getId(), accountLedger.balance(account.getId()));
                    showAccountBalance(account);
                    form.error(amountField, canSeeBalances()
                            ? I18n.get("msg.max_out_on_date", MoneyUtil.format(r.amount()))
                            : I18n.get("party.error.insufficient_plain"));
                    form.focusFirstError();
                }
                case NOT_FOUND -> Notifier.error(I18n.get("salary.error.not_found"));
                default -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save the salary payment", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onDelete() {
        if (payment != null && delete(employee, payment)) {
            saved = true;
            close();
        }
    }

    @FXML
    private void onCancel() {
        close();
    }

    private static String overNetText(BigDecimal max) {
        return max == null || max.signum() <= 0 ? I18n.get("salary.error.fully_paid")
                : I18n.get("salary.error.over_net", MoneyUtil.format(max));
    }

    /** What the account can pay now: its balance, plus this payment's old amount when it was paid from it. */
    private BigDecimal available(Account account) {
        BigDecimal balance = balances.getOrDefault(account.getId(), BigDecimal.ZERO);
        if (payment != null && payment.getAccount().getId().equals(account.getId())) {
            balance = balance.add(payment.getAmount());
        }
        return balance.max(BigDecimal.ZERO);
    }

    private static boolean canSeeBalances() {
        return UserSession.isLoggedIn() && UserSession.get().can(AppModule.ACCOUNT, UserSession.Action.VIEW);
    }

    /** "Not enough money": with the amount only for users who may see account balances. */
    private static String insufficientText(BigDecimal available) {
        return canSeeBalances() ? I18n.get("party.error.insufficient", MoneyUtil.format(available))
                : I18n.get("party.error.insufficient_plain");
    }

    private void showAccountBalance(Account account) {
        // Account balances are Accounts data: only users who may view Accounts see them (not a cashier)
        boolean show = canSeeBalances() && account != null && balances.containsKey(account.getId());
        accountBalanceLabel.setText(show
                ? I18n.get("transfer.balance_hint", MoneyUtil.format(balances.get(account.getId()))) : "");
        WindowManager.fitPopup(accountBalanceLabel);
    }

    /** The typed amount, or null when empty. */
    private BigDecimal amount() {
        String text = amountField.getText() == null ? "" : amountField.getText().trim();
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
