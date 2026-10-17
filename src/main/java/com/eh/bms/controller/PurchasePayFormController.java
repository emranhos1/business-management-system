package com.eh.bms.controller;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.BusinessTransaction;
import com.eh.bms.service.AccountLedgerService;
import com.eh.bms.service.AccountService;
import com.eh.bms.service.PurchaseService;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
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
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Payment on a purchase's due: money paid out of an account (cash first, balance shown), at most the due and
 * never more than the account holds on that date. The supplier's ledger and the purchase's paid / due change
 * with it.
 */
public class PurchasePayFormController {

    private static final Logger LOG = Logger.getLogger(PurchasePayFormController.class.getName());

    @FXML private Label customerLabel;
    @FXML private Label dueLabel;
    @FXML private ComboBox<Account> accountBox;
    @FXML private Label accountError;
    @FXML private TextField amountField;
    @FXML private Label amountError;
    @FXML private DatePicker datePicker;
    @FXML private Label dateError;
    @FXML private TextArea noteField;
    @FXML private Label noteError;

    private final AccountService accountService = new AccountService();
    private final AccountLedgerService accountLedger = new AccountLedgerService();
    private final PurchaseService purchaseService = new PurchaseService();
    private FormValidator form;
    private BusinessTransaction purchase;
    private Map<Long, BigDecimal> balances = Map.of();
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.decimal(amountField, 12, 2);
        InputFilters.maxLength(noteField, 150);
        DateUtil.apply(datePicker);
        datePicker.setValue(LocalDate.now());
        datePicker.setDayCellFactory(p -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                // Only from the purchase's date up to today
                setDisable(empty || date.isAfter(LocalDate.now())
                        || (purchase != null && date.isBefore(purchase.getDate())));
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
                .add(accountBox, accountError)
                .add(amountField, amountError)
                .add(datePicker, dateError)
                .add(noteField, noteError);
    }

    /** False (and says why) when it cannot be used, e.g. no account yet. */
    public boolean setUp(BusinessTransaction purchase) {
        this.purchase = purchase;
        try {
            List<Account> accounts = accountService.all();
            if (accounts.isEmpty()) {
                Notifier.error(I18n.get("party.error.no_account"));
                return false;
            }
            balances = accountLedger.balances(accounts);
            accountBox.getItems().setAll(accounts);
            accountBox.setValue(accounts.stream()
                    .filter(a -> a.getType() != null && a.getType().getNumberRule() == AccountNumberRule.NONE)
                    .findFirst().orElse(accounts.get(0)));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load accounts", e);
            Notifier.error(I18n.get("msg.error"));
            return false;
        }
        customerLabel.setText(PurchasesListController.supplier(purchase));
        dueLabel.setText(I18n.get("purchase.pay.due", MoneyUtil.format(purchase.getDueAmount())));
        amountField.setText(purchase.getDueAmount().stripTrailingZeros().toPlainString());
        return true;
    }

    public boolean isSaved() {
        return saved;
    }

    @FXML
    private void onSave() {
        form.clearAll();
        Account account = accountBox.getValue();
        String text = amountField.getText() == null ? "" : amountField.getText().trim();
        BigDecimal amount = text.isEmpty() || text.equals(".") ? null
                : new BigDecimal(text.endsWith(".") ? text + "0" : text.startsWith(".") ? "0" + text : text);
        LocalDate date = readDate();
        if (account == null) {
            form.error(accountBox, I18n.get("msg.required_field", I18n.get("party.pay.account")));
        }
        if (amount == null) {
            form.error(amountField, I18n.get("msg.required_field", I18n.get("label.amount")));
        } else if (amount.signum() <= 0) {
            form.error(amountField, I18n.get("party.error.zero"));
        } else if (amount.compareTo(purchase.getDueAmount()) > 0) {
            form.error(amountField, I18n.get("purchase.error.more_than_due", MoneyUtil.format(purchase.getDueAmount())));
        }
        if (date == null) {
            form.error(datePicker, I18n.get("party.error.date"));
        } else if (date.isAfter(LocalDate.now())) {
            form.error(datePicker, I18n.get("party.error.future_date"));
        } else if (date.isBefore(purchase.getDate())) {
            form.error(datePicker, I18n.get("purchase.error.pay_before_purchase"));
        }
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirm(I18n.get("purchase.pay.confirm", MoneyUtil.format(amount),
                I18n.pick(account.getName(), account.getNameEn())))) {
            return;
        }
        try {
            PurchaseService.Result r = purchaseService.payDue(purchase.getId(), account.getId(), amount, date,
                    noteField.getText());
            switch (r.status()) {
                case DONE -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("party.pay.done"));
                }
                case TOO_MUCH -> {
                    form.error(amountField, I18n.get("purchase.error.more_than_due", MoneyUtil.format(r.available())));
                    form.focusFirstError();
                }
                case INSUFFICIENT_BALANCE -> {
                    form.error(amountField, I18n.get("msg.max_out_on_date", MoneyUtil.format(r.available())));
                    form.focusFirstError();
                }
                case INACTIVE_PARTY -> Notifier.error(I18n.get("sale.error.inactive_party"));
                default -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save purchase payment", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onCancel() {
        close();
    }

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
