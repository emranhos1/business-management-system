package com.eh.bms.controller;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.BusinessTransaction;
import com.eh.bms.model.TransactionItem;
import com.eh.bms.service.AccountService;
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
 * Sales return (11.7): how much of each line comes back now (at most what is left). The value of the goods
 * (with the sale's discount and VAT shared out) is shown as you type. A party's value goes to its ledger, and
 * part of it may be paid back from an account; a walk-in customer gets it all back in money.
 */
public class SaleReturnFormController {

    private static final Logger LOG = Logger.getLogger(SaleReturnFormController.class.getName());

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

    private final SaleService saleService = new SaleService();
    private final AccountService accountService = new AccountService();
    private FormValidator form;
    private BusinessTransaction sale;
    /** Quantity field of each line (line id → field), in the sale's line order. */
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
                setDisable(empty || date.isAfter(LocalDate.now()));
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

    /** Loads the sale; false (and says why) when nothing can come back. */
    public boolean setUp(long saleId) {
        SaleDetails d;
        List<Account> accounts;
        try {
            d = saleService.details(saleId);
            accounts = accountService.all();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load sale for return", e);
            Notifier.error(I18n.get("msg.error"));
            return false;
        }
        sale = d.sale();
        customerLabel.setText(I18n.get("sale.view.info", SalesListController.memoText(sale),
                DateUtil.format(sale.getDate()), SalesListController.customer(sale)));
        accountBox.getItems().setAll(accounts);
        accountBox.setValue(accounts.stream()
                .filter(a -> a.getType() != null && a.getType().getNumberRule() == AccountNumberRule.NONE)
                .findFirst().orElse(accounts.isEmpty() ? null : accounts.get(0)));

        linesGrid.addRow(0, header("sale.product"), header("sale.return.sold"), header("sale.return.back"),
                header("sale.return.now"));
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
            Notifier.error(I18n.get("sale.error.all_returned"));
            return false;
        }
        // A walk-in customer has no ledger, so the money is paid back in full
        refundHint.setText(I18n.get(sale.getParty() == null ? "sale.return.refund_walk_in" : "sale.return.refund_party"));
        refundField.setDisable(sale.getParty() == null);
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

    /** Quantities typed, by line id (only those above 0); null when one is more than what is left. */
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

    /** Same sum as the sale service: lines at their net price, with the sale's discount and VAT shared out. */
    private BigDecimal value(Map<Long, BigDecimal> quantities) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> e : quantities.entrySet()) {
            TransactionItem i = items.get(e.getKey());
            sum = sum.add(e.getValue().multiply(i.getUnitPrice().subtract(i.getDiscountPerUnit())));
        }
        BigDecimal factor = sale.getSubtotal().signum() == 0 ? BigDecimal.ONE
                : sale.getGrandTotal().divide(sale.getSubtotal(), 10, RoundingMode.HALF_UP);
        return sum.multiply(factor).setScale(2, RoundingMode.HALF_UP)
                .min(sale.getGrandTotal().subtract(sale.getReturnedAmount()));
    }

    private void showValue() {
        BigDecimal value = value(quantities());
        valueLabel.setText(I18n.get("sale.return.value", MoneyUtil.format(value)));
        if (sale.getParty() == null) {
            refundField.setText(value.signum() == 0 ? "" : value.stripTrailingZeros().toPlainString());
        }
        showAccountRow();
    }

    /** The account row matters only when money is paid back. */
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
            linesError.setText(I18n.get("sale.error.back_too_much"));
        }
        BigDecimal value = value(quantities);
        BigDecimal refund = number(refundField);
        BigDecimal back = refund == null ? BigDecimal.ZERO : refund;
        if (back.compareTo(value) > 0) {
            form.error(refundField, I18n.get("sale.error.refund_too_much", MoneyUtil.format(value)));
        }
        Account account = accountBox.getValue();
        if (back.signum() > 0 && account == null) {
            form.error(accountBox, I18n.get("msg.required_field", I18n.get("party.pay.account")));
        }
        LocalDate date = readDate();
        if (date == null) {
            form.error(datePicker, I18n.get("party.error.date"));
        } else if (date.isAfter(LocalDate.now())) {
            form.error(datePicker, I18n.get("party.error.future_date"));
        } else if (date.isBefore(sale.getDate())) {
            form.error(datePicker, I18n.get("sale.error.return_before_sale"));
        }
        if (form.hasErrors() || !linesError.getText().isEmpty()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirm(I18n.get("sale.return.confirm", MoneyUtil.format(value), MoneyUtil.format(back)))) {
            return;
        }
        try {
            SaleService.Result r = saleService.returnGoods(sale.getId(), quantities,
                    back.signum() > 0 ? account.getId() : null, back, date, noteField.getText());
            switch (r.status()) {
                case DONE -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("sale.return.done", MoneyUtil.format(r.available())));
                }
                case INSUFFICIENT_BALANCE -> {
                    form.error(refundField, I18n.get("party.error.insufficient", MoneyUtil.format(r.available())));
                    form.focusFirstError();
                }
                case TOO_MUCH -> linesError.setText(I18n.get("sale.error.back_too_much"));
                case DUE_WITHOUT_PARTY -> form.error(refundField,
                        I18n.get("sale.return.refund_walk_in"));
                default -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save sales return", e);
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
