package com.eh.bms.controller;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.AppModule;
import com.eh.bms.model.Expense;
import com.eh.bms.model.ExpenseCategory;
import com.eh.bms.service.AccountLedgerService;
import com.eh.bms.service.AccountService;
import com.eh.bms.service.ExpenseCategoryService;
import com.eh.bms.service.ExpenseService;
import com.eh.bms.service.ExpenseService.ExpenseData;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
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
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Add / edit expense popup (13.2): category, amount, the account it is paid from (cash first, balance shown),
 * date (today by default, never in the future) and note. The amount cannot be more than the account holds.
 * When editing, users who may delete also get the delete button.
 */
public class ExpenseFormController {

    private static final Logger LOG = Logger.getLogger(ExpenseFormController.class.getName());
    private static final int NOTE_MAX = 255;

    @FXML private ComboBox<ExpenseCategory> categoryBox;
    @FXML private Label categoryError;
    @FXML private TextField amountField;
    @FXML private Label amountError;
    @FXML private ComboBox<Account> accountBox;
    @FXML private Label accountError;
    @FXML private DatePicker datePicker;
    @FXML private Label dateError;
    @FXML private TextArea noteField;
    @FXML private Label noteError;
    @FXML private Button deleteButton;

    private final ExpenseService service = new ExpenseService();
    private final ExpenseCategoryService categoryService = new ExpenseCategoryService();
    private final AccountService accountService = new AccountService();
    private final AccountLedgerService accountLedger = new AccountLedgerService();
    private FormValidator form;
    private Map<Long, BigDecimal> balances = Map.of();
    /** The expense being edited; null when adding. */
    private Expense expense;
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.decimal(amountField, 12, 2);
        InputFilters.maxLength(noteField, NOTE_MAX);
        DateUtil.apply(datePicker);
        datePicker.setValue(LocalDate.now());
        // No future dates in the calendar
        datePicker.setDayCellFactory(p -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                setDisable(empty || date.isAfter(LocalDate.now()));
            }
        });
        categoryBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(ExpenseCategory c) {
                return c == null ? "" : ExpenseCategoriesController.nameText(c);
            }

            @Override
            public ExpenseCategory fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        accountBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Account a) {
                if (a == null) {
                    return "";
                }
                BigDecimal balance = balances.get(a.getId());
                String name = I18n.pick(a.getName(), a.getNameEn());
                return balance == null ? name : I18n.get("purchase.account_balance", name, MoneyUtil.format(balance));
            }

            @Override
            public Account fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        form = new FormValidator()
                .add(categoryBox, categoryError)
                .add(amountField, amountError)
                .add(accountBox, accountError)
                .add(datePicker, dateError)
                .add(noteField, noteError);
    }

    /**
     * Fills the lists, and the form when {@code expense} is given (edit). False (and says why) when the form
     * cannot be used, e.g. no account or no category yet.
     */
    public boolean setUp(Expense expense) {
        this.expense = expense;
        try {
            List<Account> accounts = accountService.all();
            if (accounts.isEmpty()) {
                Notifier.error(I18n.get("party.error.no_account"));
                return false;
            }
            List<ExpenseCategory> categories = categoryService.all();
            if (categories.isEmpty()) {
                Notifier.error(I18n.get("expense.error.no_category"));
                return false;
            }
            balances = accountLedger.balances(accounts);
            accountBox.getItems().setAll(accounts);
            categoryBox.getItems().setAll(categories);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load expense form lists", e);
            Notifier.error(I18n.get("msg.error"));
            return false;
        }

        if (expense == null) {
            // Cash first
            accountBox.setValue(accountBox.getItems().stream()
                    .filter(a -> a.getType() != null && a.getType().getNumberRule() == AccountNumberRule.NONE)
                    .findFirst().orElse(accountBox.getItems().get(0)));
            return true;
        }
        categoryBox.getItems().stream().filter(c -> c.getId().equals(expense.getCategory().getId()))
                .findFirst().ifPresent(categoryBox::setValue);
        accountBox.getItems().stream().filter(a -> a.getId().equals(expense.getAccount().getId()))
                .findFirst().ifPresent(accountBox::setValue);
        amountField.setText(expense.getAmount().stripTrailingZeros().toPlainString());
        datePicker.setValue(expense.getDate());
        noteField.setText(expense.getNote());
        boolean deletable = UserSession.isLoggedIn() && UserSession.get().can(AppModule.EXPENSE, Action.DELETE);
        deleteButton.setVisible(deletable);
        deleteButton.setManaged(deletable);
        return true;
    }

    public boolean isSaved() {
        return saved;
    }

    @FXML
    private void onSave() {
        form.clearAll();
        ExpenseCategory category = categoryBox.getValue();
        Account account = accountBox.getValue();
        String text = amountField.getText() == null ? "" : amountField.getText().trim();
        BigDecimal amount = text.isEmpty() || text.equals(".") ? null
                : new BigDecimal(text.endsWith(".") ? text + "0" : text.startsWith(".") ? "0" + text : text);
        LocalDate date = readDate();

        if (category == null) {
            form.error(categoryBox, I18n.get("msg.required_field", I18n.get("expense.category")));
        }
        if (amount == null) {
            form.error(amountField, I18n.get("msg.required_field", I18n.get("label.amount")));
        } else if (amount.signum() <= 0) {
            form.error(amountField, I18n.get("party.error.zero"));
        } else if (account != null && amount.compareTo(available(account)) > 0) {
            form.error(amountField, I18n.get("expense.error.insufficient", MoneyUtil.format(available(account))));
        }
        if (account == null) {
            form.error(accountBox, I18n.get("msg.required_field", I18n.get("expense.account")));
        }
        if (date == null) {
            form.error(datePicker, I18n.get("party.error.date"));
        } else if (date.isAfter(LocalDate.now())) {
            form.error(datePicker, I18n.get("party.error.future_date"));
        }
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirm(I18n.get("expense.confirm", MoneyUtil.format(amount),
                ExpenseCategoriesController.nameText(category), I18n.pick(account.getName(), account.getNameEn())))) {
            return;
        }

        try {
            ExpenseService.Result r = service.save(new ExpenseData(expense == null ? null : expense.getId(),
                    category.getId(), account.getId(), amount, date, noteField.getText()));
            switch (r.status()) {
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.saved"));
                }
                case INSUFFICIENT_BALANCE -> {
                    form.error(amountField, I18n.get("msg.max_out_on_date", MoneyUtil.format(r.available())));
                    form.focusFirstError();
                }
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save expense", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onDelete() {
        if (expense == null || !Dialogs.confirmDelete(I18n.get("expense.confirm_delete",
                MoneyUtil.format(expense.getAmount()), ExpenseCategoriesController.nameText(expense.getCategory())))) {
            return;
        }
        try {
            switch (service.delete(expense.getId())) {
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.deleted"));
                }
                default -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not delete expense", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onCancel() {
        close();
    }

    /** What the account can pay now: its balance, plus this expense's old amount when it was paid from it. */
    private BigDecimal available(Account account) {
        BigDecimal balance = balances.getOrDefault(account.getId(), BigDecimal.ZERO);
        if (expense != null && expense.getAccount().getId().equals(account.getId())) {
            balance = balance.add(expense.getAmount());
        }
        return balance.max(BigDecimal.ZERO);
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

    private void close() {
        ((Stage) amountField.getScene().getWindow()).close();
    }
}
