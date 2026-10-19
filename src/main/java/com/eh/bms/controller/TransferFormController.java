package com.eh.bms.controller;

import com.eh.bms.model.Account;
import com.eh.bms.service.AccountLedgerService;
import com.eh.bms.service.AccountService;
import com.eh.bms.service.TransferService;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
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
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Transfer popup (7.4): from account, to account, amount, date (today by default, never in the future), note.
 * The from account cannot go below zero. Under each account its current balance is shown.
 */
public class TransferFormController {

    private static final Logger LOG = Logger.getLogger(TransferFormController.class.getName());
    private static final int NOTE_MAX = 200;

    @FXML private ComboBox<Account> fromBox;
    @FXML private Label fromError;
    @FXML private Label fromBalanceLabel;
    @FXML private ComboBox<Account> toBox;
    @FXML private Label toError;
    @FXML private Label toBalanceLabel;
    @FXML private TextField amountField;
    @FXML private Label amountError;
    @FXML private DatePicker datePicker;
    @FXML private Label dateError;
    @FXML private TextArea noteField;
    @FXML private Label noteError;

    private final AccountService accountService = new AccountService();
    private final AccountLedgerService ledgerService = new AccountLedgerService();
    private final TransferService transferService = new TransferService();
    private FormValidator form;

    /** Balance of every account, read once when the form opens. */
    private Map<Long, BigDecimal> balances = new HashMap<>();
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.decimal(amountField, 12, 2);
        InputFilters.maxLength(noteField, NOTE_MAX);
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

        try {
            var accounts = accountService.all();
            balances = ledgerService.balances(accounts);
            fromBox.getItems().setAll(accounts);
            toBox.getItems().setAll(accounts);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load accounts", e);
            Notifier.error(I18n.get("msg.error"));
        }
        fromBox.setConverter(converter());
        toBox.setConverter(converter());
        fromBox.valueProperty().addListener((obs, old, a) -> showBalance(fromBalanceLabel, a));
        toBox.valueProperty().addListener((obs, old, a) -> showBalance(toBalanceLabel, a));

        form = new FormValidator()
                .add(fromBox, fromError)
                .add(toBox, toError)
                .add(amountField, amountError)
                .add(datePicker, dateError)
                .add(noteField, noteError);
    }

    /** Pre-selects the from account (the one selected in the list). */
    public void setFrom(Account account) {
        fromBox.getItems().stream()
                .filter(a -> a.getId().equals(account.getId()))
                .findFirst()
                .ifPresent(fromBox::setValue);
    }

    /** True if a transfer was made; the accounts list then reloads. */
    public boolean isSaved() {
        return saved;
    }

    @FXML
    private void onSave() {
        form.clearAll();
        Account from = fromBox.getValue();
        Account to = toBox.getValue();
        String amountText = amountField.getText() == null ? "" : amountField.getText().trim();
        LocalDate date = readDate();
        String note = noteField.getText() == null ? "" : noteField.getText().trim();

        if (from == null) {
            form.error(fromBox, I18n.get("msg.required_field", I18n.get("transfer.from")));
        }
        if (to == null) {
            form.error(toBox, I18n.get("msg.required_field", I18n.get("transfer.to")));
        } else if (from != null && from.getId().equals(to.getId())) {
            form.error(toBox, I18n.get("transfer.error.same"));
        }

        BigDecimal amount = null;
        if (amountText.isEmpty()) {
            form.error(amountField, I18n.get("msg.required_field", I18n.get("label.amount")));
        } else {
            amount = new BigDecimal(amountText.endsWith(".") ? amountText + "0" : amountText);
            if (amount.signum() <= 0) {
                form.error(amountField, I18n.get("transfer.error.zero"));
            } else if (from != null && balances.containsKey(from.getId())
                    && amount.compareTo(balances.get(from.getId())) > 0) {
                form.error(amountField, I18n.get("transfer.error.insufficient", MoneyUtil.format(balances.get(from.getId()))));
            }
        }

        if (date == null) {
            boolean typed = datePicker.getEditor().getText() != null && !datePicker.getEditor().getText().isBlank();
            form.error(datePicker, typed ? I18n.get("transfer.error.date")
                    : I18n.get("msg.required_field", I18n.get("label.date")));
        } else if (date.isAfter(LocalDate.now())) {
            form.error(datePicker, I18n.get("transfer.error.future_date"));
        }

        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirm(I18n.get("transfer.confirm", name(from), name(to), MoneyUtil.format(amount)))) {
            return;
        }

        try {
            TransferService.Result result = transferService.transfer(from.getId(), to.getId(), amount, date, note);
            switch (result.status()) {
                case DONE -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("transfer.done"));
                }
                case SAME_ACCOUNT -> {
                    form.error(toBox, I18n.get("transfer.error.same"));
                    form.focusFirstError();
                }
                case INSUFFICIENT_BALANCE -> {
                    // Money went out since the form opened, or the date is earlier: show today's balance again;
                    // the error shows what was there on that date
                    balances.put(from.getId(), ledgerService.balance(from.getId()));
                    showBalance(fromBalanceLabel, from);
                    form.error(amountField, I18n.get("msg.max_out_on_date", MoneyUtil.format(result.available())));
                    form.focusFirstError();
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not transfer", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onCancel() {
        close();
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

    private void showBalance(Label label, Account account) {
        boolean show = account != null && balances.containsKey(account.getId());
        label.setText(show ? I18n.get("transfer.balance_hint", MoneyUtil.format(balances.get(account.getId()))) : "");
        label.setVisible(show);
        label.setManaged(show);
        WindowManager.fitPopup(label);
    }

    private static String name(Account a) {
        return I18n.pick(a.getName(), a.getNameEn());
    }

    private static StringConverter<Account> converter() {
        return new StringConverter<>() {
            @Override
            public String toString(Account a) {
                return a == null ? "" : name(a);
            }

            @Override
            public Account fromString(String text) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private void close() {
        ((Stage) amountField.getScene().getWindow()).close();
    }
}
