package com.eh.bms.controller;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.BusinessTransaction;
import com.eh.bms.model.TransactionItem;
import com.eh.bms.service.AccountService;
import com.eh.bms.service.PurchaseService;
import com.eh.bms.service.SaleService;
import com.eh.bms.service.SaleService.SaleDetails;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.UnitUtil;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Purchase return (12.4): how much of each line goes back to the supplier now (at most what is left). The
 * value of the goods (with the bill discount shared out) is shown as you type. It is taken off what we owe
 * the supplier, and part of it may come back in money; a cash purchase without a supplier gets it all back.
 */
public class PurchaseReturnFormController {

    private static final Logger LOG = Logger.getLogger(PurchaseReturnFormController.class.getName());

    @FXML private Label customerLabel;
    @FXML private GridPane linesGrid;
    @FXML private Label linesError;
    @FXML private Label valueLabel;
    @FXML private TextField refundField;
    @FXML private Label refundError;
    @FXML private Label refundHint;
    @FXML private HBox accountRow;
    @FXML private ComboBox<Account> accountBox;
    @FXML private Label accountError;
    @FXML private DatePicker datePicker;
    @FXML private Label dateError;
    @FXML private TextArea noteField;
    @FXML private Label noteError;

    private final PurchaseService purchaseService = new PurchaseService();
    private final AccountService accountService = new AccountService();
    private FormValidator form;
    private BusinessTransaction purchase;
    /** Quantity field of each line (line id → field), in the purchase's line order. */
    private final Map<Long, TextField> qtyFields = new LinkedHashMap<>();
    private final Map<Long, TransactionItem> items = new HashMap<>();
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.decimal(refundField, 12, 2);
        InputFilters.maxLength(noteField, 150);
        DateUtil.apply(datePicker);
        datePicker.setValue(LocalDate.now());
        datePicker.setDayCellFactory(p -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                setDisable(empty || date.isAfter(LocalDate.now())
                        || (purchase != null && date.isBefore(purchase.getDate())));
            }
        });
        accountBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Account a) {
                return a == null ? "" : I18n.pick(a.getName(), a.getNameEn());
            }

            @Override
            public Account fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        refundField.textProperty().addListener((obs, old, now) -> showAccountRow());
        form = new FormValidator()
                .add(refundField, refundError)
                .add(accountBox, accountError)
                .add(datePicker, dateError)
                .add(noteField, noteError);
    }

    /** Loads the purchase; false (and says why) when nothing can go back. */
    public boolean setUp(long purchaseId) {
        SaleDetails d;
        List<Account> accounts;
        try {
            d = purchaseService.details(purchaseId);
            accounts = accountService.all();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load purchase for return", e);
            Notifier.error(I18n.get("msg.error"));
            return false;
        }
        purchase = d.sale();
        customerLabel.setText(I18n.get("sale.view.info", SalesListController.memoText(purchase),
                DateUtil.format(purchase.getDate()), PurchasesListController.supplier(purchase)));
        accountBox.getItems().setAll(accounts);
        accountBox.setValue(accounts.stream()
                .filter(a -> a.getType() != null && a.getType().getNumberRule() == AccountNumberRule.NONE)
                .findFirst().orElse(accounts.isEmpty() ? null : accounts.get(0)));

        linesGrid.addRow(0, header("sale.product"), header("purchase.return.bought"), header("sale.return.back"),
                header("purchase.return.now"));
        int row = 1;
        for (TransactionItem i : d.items()) {
            BigDecimal left = i.getQuantity().subtract(i.getReturnedQuantity());
            if (left.signum() <= 0) {
                continue;
            }
            String unit = i.getUnitRef() == null ? i.getUnit() : UnitUtil.name(i.getUnitRef());
            TextField qty = new TextField();
            qty.setPromptText("0");
            qty.setPrefWidth(110);
            InputFilters.decimal(qty, 11, 3);
            qty.textProperty().addListener((obs, old, now) -> showValue());
            qtyFields.put(i.getId(), qty);
            items.put(i.getId(), i);
            linesGrid.addRow(row++, new Label(UnitUtil.productName(i.getProduct())),
                    new Label(UnitUtil.number(i.getQuantity()) + " " + unit),
                    new Label(i.getReturnedQuantity().signum() == 0 ? "—" : UnitUtil.number(i.getReturnedQuantity())),
                    new HBox(6, qty, new Label(unit)));
        }
        if (qtyFields.isEmpty()) {
            Notifier.error(I18n.get("purchase.error.all_returned"));
            return false;
        }
        // No supplier ledger on a cash purchase, so the money comes back in full
        refundField.setDisable(purchase.getParty() == null);
        showValue();
        return true;
    }

    public boolean isSaved() {
        return saved;
    }

    private static Label header(String key) {
        Label l = new Label(I18n.get(key));
        l.getStyleClass().add("form-label");
        return l;
    }

    /** Quantities typed, by line id (only those above 0). */
    private Map<Long, BigDecimal> quantities() {
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Map.Entry<Long, TextField> e : qtyFields.entrySet()) {
            BigDecimal q = number(e.getValue());
            if (q != null && q.signum() > 0) {
                result.put(e.getKey(), q);
            }
        }
        return result;
    }

    /** Same sum as the purchase service: lines at their net price, with the bill discount shared out. */
    private BigDecimal value(Map<Long, BigDecimal> quantities) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> e : quantities.entrySet()) {
            TransactionItem i = items.get(e.getKey());
            sum = sum.add(e.getValue().multiply(i.getUnitPrice().subtract(i.getDiscountPerUnit())));
        }
        return sum.multiply(PurchaseService.billFactor(purchase)).setScale(2, RoundingMode.HALF_UP)
                .min(purchase.getGrandTotal().subtract(purchase.getReturnedAmount()));
    }

    private void showValue() {
        BigDecimal value = value(quantities());
        valueLabel.setText(I18n.get("sale.return.value", MoneyUtil.format(value)));
        if (purchase.getParty() == null) {
            refundHint.setText(I18n.get("purchase.return.refund_cash"));
            refundField.setText(value.signum() == 0 ? "" : value.stripTrailingZeros().toPlainString());
        } else {
            // What we still owe is cleared first; only the rest can come back in money. While the goods only
            // lower the due, no money can come back: the field is closed instead of offering ৳0
            BigDecimal max = SaleService.maxRefund(value, purchase.getDueAmount());
            boolean dueOnly = purchase.getDueAmount().signum() > 0 && max.signum() == 0;
            if (dueOnly) {
                refundField.clear();
                form.clear(refundField);
            }
            refundField.setDisable(dueOnly);
            refundHint.setText(dueOnly ? I18n.get("purchase.return.off_due")
                    : I18n.get("purchase.return.refund_party_max", MoneyUtil.format(max)));
        }
        showAccountRow();
    }

    /** The account row matters only when money comes back. */
    private void showAccountRow() {
        BigDecimal refund = number(refundField);
        boolean show = refund != null && refund.signum() > 0;
        if (show != accountRow.isVisible()) {
            accountRow.setVisible(show);
            accountRow.setManaged(show);
            WindowManager.fitPopup(accountRow);
        }
    }

    @FXML
    private void onSave() {
        form.clearAll();
        linesError.setText("");
        Map<Long, BigDecimal> quantities = quantities();
        boolean tooMuch = false;
        for (Map.Entry<Long, BigDecimal> e : quantities.entrySet()) {
            TransactionItem i = items.get(e.getKey());
            if (e.getValue().compareTo(i.getQuantity().subtract(i.getReturnedQuantity())) > 0) {
                tooMuch = true;
            }
        }
        if (quantities.isEmpty()) {
            linesError.setText(I18n.get("sale.error.nothing_back"));
        } else if (tooMuch) {
            linesError.setText(I18n.get("purchase.error.back_too_much"));
        }
        BigDecimal value = value(quantities);
        BigDecimal refund = number(refundField);
        BigDecimal back = refund == null ? BigDecimal.ZERO : refund;
        BigDecimal maxRefund = SaleService.maxRefund(value, purchase.getDueAmount());
        if (back.compareTo(maxRefund) > 0) {
            form.error(refundField, I18n.get("purchase.error.refund_too_much", MoneyUtil.format(maxRefund)));
        }
        Account account = accountBox.getValue();
        if (back.signum() > 0 && account == null) {
            form.error(accountBox, I18n.get("msg.required_field", I18n.get("party.receive.account")));
        }
        LocalDate date = readDate();
        if (date == null) {
            form.error(datePicker, I18n.get("party.error.date"));
        } else if (date.isAfter(LocalDate.now())) {
            form.error(datePicker, I18n.get("party.error.future_date"));
        } else if (date.isBefore(purchase.getDate())) {
            form.error(datePicker, I18n.get("purchase.error.return_before_purchase"));
        }
        if (form.hasErrors() || !linesError.getText().isEmpty()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirm(I18n.get("purchase.return.confirm", MoneyUtil.format(value), MoneyUtil.format(back)))) {
            return;
        }
        try {
            PurchaseService.Result r = purchaseService.returnGoods(purchase.getId(), quantities,
                    back.signum() > 0 ? account.getId() : null, back, date, noteField.getText());
            switch (r.status()) {
                case DONE -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("purchase.return.done", MoneyUtil.format(r.available())));
                }
                // The goods were sold meanwhile: not that much is in stock on that date
                case INSUFFICIENT_STOCK -> {
                    TransactionItem item = items.values().stream()
                            .filter(i -> i.getProduct().getId().equals(r.productId())).findFirst().orElse(null);
                    linesError.setText(item == null ? I18n.get("msg.error") : I18n.get("purchase.error.stock_line_on",
                            UnitUtil.productName(item.getProduct()),
                            UnitUtil.quantityText(r.available(), item.getProduct())));
                }
                // With a product: more goods than are left; without: more money than may come back
                case TOO_MUCH -> {
                    if (r.productId() == null) {
                        form.error(refundField, I18n.get("purchase.error.refund_too_much", MoneyUtil.format(r.available())));
                        form.focusFirstError();
                    } else {
                        linesError.setText(I18n.get("purchase.error.back_too_much"));
                    }
                }
                case INACTIVE_PARTY -> Notifier.error(I18n.get("sale.error.inactive_party"));
                case DUE_WITHOUT_PARTY -> form.error(refundField, I18n.get("purchase.return.refund_cash"));
                default -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save purchase return", e);
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

    private static BigDecimal number(TextField field) {
        String t = field.getText() == null ? "" : field.getText().trim();
        if (t.isEmpty() || t.equals(".")) {
            return null;
        }
        if (t.startsWith(".")) {
            t = "0" + t;
        }
        return new BigDecimal(t.endsWith(".") ? t + "0" : t);
    }

    private void close() {
        ((Stage) refundField.getScene().getWindow()).close();
    }
}
