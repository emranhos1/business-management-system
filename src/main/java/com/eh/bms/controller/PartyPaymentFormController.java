package com.eh.bms.controller;

import com.eh.bms.model.Account;
import com.eh.bms.model.AppModule;
import com.eh.bms.model.Party;
import com.eh.bms.service.AccountLedgerService;
import com.eh.bms.service.AccountService;
import com.eh.bms.service.PartyLedgerService;
import com.eh.bms.service.PartyPaymentService;
import com.eh.bms.service.PartyPaymentService.Direction;
import com.eh.bms.service.UserSession;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Money received from / paid to a party (10.5): account, amount, date (today by default, never in the
 * future), note. Shows what is due now and what it will be after this payment, in পাবো / দেবো words.
 * Paying cannot take an account below zero.
 */
public class PartyPaymentFormController {

    private static final Logger LOG = Logger.getLogger(PartyPaymentFormController.class.getName());
    private static final int NOTE_MAX = 200;

    @FXML private Label partyLabel;
    @FXML private Label dueLabel;
    @FXML private Label accountCaption;
    @FXML private ComboBox<Account> accountBox;
    @FXML private Label accountError;
    @FXML private Label accountBalanceLabel;
    @FXML private TextField amountField;
    @FXML private Label amountError;
    @FXML private Label afterLabel;
    @FXML private DatePicker datePicker;
    @FXML private Label dateError;
    @FXML private TextArea noteField;
    @FXML private Label noteError;
    @FXML private Button saveButton;

    private final AccountService accountService = new AccountService();
    private final AccountLedgerService accountLedger = new AccountLedgerService();
    private final PartyLedgerService partyLedger = new PartyLedgerService();
    private final PartyPaymentService paymentService = new PartyPaymentService();
    private FormValidator form;

    private Party party;
    private Direction direction;
    /** What the party owes now (+ পাবো, - দেবো), read when the form opens. */
    private BigDecimal due = BigDecimal.ZERO;
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
        amountField.textProperty().addListener((obs, old, text) -> showAfter());

        form = new FormValidator()
                .add(accountBox, accountError)
                .add(amountField, amountError)
                .add(datePicker, dateError)
                .add(noteField, noteError);
    }

    /**
     * Sets the party and whether money is received or paid; call before showing.
     * Returns false (and shows why) when the popup cannot be used, e.g. there is no account yet.
     */
    public boolean setUp(Party party, Direction direction) {
        this.party = party;
        this.direction = direction;
        boolean received = direction == Direction.RECEIVED;
        accountCaption.setText(I18n.get(received ? "party.receive.account" : "party.pay.account"));
        saveButton.setText(I18n.get(received ? "party.receive" : "party.pay"));
        partyLabel.setText(PartyText.nameWithPhone(party));
        try {
            List<Account> accounts = accountService.all();
            if (accounts.isEmpty()) {
                Notifier.error(I18n.get("party.error.no_account"));
                return false;
            }
            balances = accountLedger.balances(accounts);
            accountBox.getItems().setAll(accounts);
            if (accounts.size() == 1) {
                accountBox.setValue(accounts.get(0));
            }
            due = partyLedger.balance(party.getId());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load payment form", e);
            Notifier.error(I18n.get("msg.error"));
            return false;
        }
        dueLabel.setText(I18n.get("party.pay.now", PartyText.balance(due)));
        String style = PartyText.balanceStyle(due);
        if (style != null) {
            dueLabel.getStyleClass().add(style);
        }
        return true;
    }

    /** True if the payment was saved; the parties list then reloads. */
    public boolean isSaved() {
        return saved;
    }

    @FXML
    private void onSave() {
        form.clearAll();
        Account account = accountBox.getValue();
        BigDecimal amount = amount();
        LocalDate date = readDate();
        String note = noteField.getText() == null ? "" : noteField.getText().trim();
        boolean paid = direction == Direction.PAID;

        if (account == null) {
            form.error(accountBox, I18n.get("msg.required_field", accountCaption.getText()));
        }
        if (amount == null) {
            form.error(amountField, I18n.get("msg.required_field", I18n.get("label.amount")));
        } else if (amount.signum() <= 0) {
            form.error(amountField, I18n.get("party.error.zero"));
        } else if (paid && account != null && balances.containsKey(account.getId())
                && amount.compareTo(balances.get(account.getId())) > 0) {
            form.error(amountField, insufficientText(balances.get(account.getId())));
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
        String confirmKey = paid ? "party.pay.confirm" : "party.receive.confirm";
        if (!Dialogs.confirm(I18n.get(confirmKey, PartyText.name(party), MoneyUtil.format(amount), name(account)))) {
            return;
        }

        try {
            PartyPaymentService.Result result = paymentService.record(party.getId(), account.getId(), direction,
                    amount, date, note);
            switch (result.status()) {
                case DONE -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get(paid ? "party.pay.done" : "party.receive.done"));
                }
                case INSUFFICIENT_BALANCE -> {
                    // Money went out since the form opened, or the date is earlier: show today's balance again;
                    // the error shows what was there on that date
                    balances.put(account.getId(), accountLedger.balance(account.getId()));
                    showAccountBalance(account);
                    form.error(amountField, canSeeBalances()
                            ? I18n.get("msg.max_out_on_date", MoneyUtil.format(result.available()))
                            : I18n.get("party.error.insufficient_plain"));
                    form.focusFirstError();
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save party payment", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onCancel() {
        close();
    }

    /** "After this: ৳X পাবো", while an amount is typed. */
    private void showAfter() {
        BigDecimal amount = amount();
        boolean show = amount != null && amount.signum() > 0;
        if (show) {
            BigDecimal after = direction == Direction.PAID ? due.add(amount) : due.subtract(amount);
            afterLabel.setText(I18n.get("party.pay.after", PartyText.balance(after)));
        }
        if (show != afterLabel.isVisible()) {
            afterLabel.setVisible(show);
            afterLabel.setManaged(show);
            WindowManager.fitPopup(afterLabel);
        }
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
        accountBalanceLabel.setText(show ? I18n.get("transfer.balance_hint", MoneyUtil.format(balances.get(account.getId()))) : "");
        accountBalanceLabel.setVisible(show);
        accountBalanceLabel.setManaged(show);
        WindowManager.fitPopup(accountBalanceLabel);
    }

    /** The typed amount, or null when empty. */
    private BigDecimal amount() {
        String text = amountField.getText() == null ? "" : amountField.getText().trim();
        if (text.isEmpty() || text.equals(".")) {
            return null;
        }
        return new BigDecimal(text.endsWith(".") ? text + "0" : text);
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
