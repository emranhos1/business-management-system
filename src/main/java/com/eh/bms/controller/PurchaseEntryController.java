package com.eh.bms.controller;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.AppModule;
import com.eh.bms.model.Party;
import com.eh.bms.model.PartyStatus;
import com.eh.bms.model.Product;
import com.eh.bms.model.Unit;
import com.eh.bms.service.AccountLedgerService;
import com.eh.bms.service.AccountService;
import com.eh.bms.service.PartyLedgerService;
import com.eh.bms.service.PartyService;
import com.eh.bms.service.ProductService;
import com.eh.bms.service.PurchaseService;
import com.eh.bms.service.PurchaseService.PurchaseData;
import com.eh.bms.service.SaleService;
import com.eh.bms.service.SaleService.LineData;
import com.eh.bms.service.SaleService.PaymentData;
import com.eh.bms.service.SaleService.Totals;
import com.eh.bms.service.StockLedgerService;
import com.eh.bms.service.UserSession;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PartyText;
import com.eh.bms.util.SearchPicker;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.TableHelper.RowAction;
import com.eh.bms.util.UnitUtil;
import com.eh.bms.util.UnsavedWork;
import com.eh.bms.util.WindowManager;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * New purchase screen (12.1-12.2). The supplier is an active party picked by name or mobile, or a cash
 * purchase without a supplier (must be paid in full; with a due the screen offers to add the seller as a
 * party). Each line's cost starts from the product's last purchase price and can be changed. Totals, money
 * paid from one or more accounts and the due update as you type. No VAT on purchases (user decision).
 */
public class PurchaseEntryController {

    private static final Logger LOG = Logger.getLogger(PurchaseEntryController.class.getName());
    private static final String PARTY_FORM_FXML = "/fxml/views/party-form.fxml";
    private static final int QTY_INT = 11;
    private static final int QTY_DEC = 3;
    private static final int MONEY_INT = 12;
    private static final int MONEY_DEC = 2;

    /** A line on the screen. */
    private record Line(Product product, Unit unit, BigDecimal quantity, BigDecimal price, BigDecimal discount) {
        LineData data() {
            return new LineData(product.getId(), unit.getId(), quantity, price, discount);
        }

        BigDecimal total() {
            return data().total();
        }
    }

    /** One "money paid" row: account and amount. */
    private final class PaymentRow {
        final ComboBox<Account> account = new ComboBox<>();
        final TextField amount = new TextField();
        final Button remove = new Button();
        final VBox node;

        PaymentRow(Account selected) {
            account.getItems().setAll(accounts);
            // The account's balance next to its name: money goes out of it
            account.setConverter(new StringConverter<>() {
                @Override
                public String toString(Account a) {
                    if (a == null) {
                        return "";
                    }
                    return AccountFormController.nameWithType(a);
                }

                @Override
                public Account fromString(String s) {
                    throw new UnsupportedOperationException();
                }
            });
            account.setValue(selected);
            // An account already used in another row is not offered again
            account.setOnShowing(e -> account.getItems().setAll(accounts.stream()
                    .filter(a -> a == account.getValue() || !usedByOthers(this, a)).toList()));
            account.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(account, Priority.ALWAYS);
            InputFilters.decimal(amount, MONEY_INT, MONEY_DEC);
            amount.setPromptText("0");
            amount.setPrefWidth(130);
            // On a narrow window the account list gets narrower; the amount and the remove button keep their size
            amount.setMinWidth(Region.USE_PREF_SIZE);
            account.setMinWidth(80);
            amount.textProperty().addListener((obs, old, now) -> recalc());

            remove.setGraphic(Icons.of(Icons.REMOVE));
            remove.getStyleClass().addAll("icon-button", "icon-out");
            remove.setTooltip(new Tooltip(I18n.get("sale.remove_payment")));
            remove.setMinWidth(Region.USE_PREF_SIZE);
            // The picked account's balance, small, under the row: money goes out of it
            Label balanceHint = new Label();
            balanceHint.getStyleClass().add("field-hint");
            balanceHint.setVisible(false);
            balanceHint.setManaged(false);
            account.valueProperty().addListener((obs, old, now) -> showBalance(balanceHint, now));
            showBalance(balanceHint, selected);
            node = new VBox(2, new HBox(6, account, amount, remove), balanceHint);
            remove.setOnAction(e -> {
                payments.remove(this);
                paymentsBox.getChildren().remove(node);
                updateAddPaymentButton();
                recalc();
            });
        }

        BigDecimal value() {
            return amountOf(amount);
        }
    }

    @FXML private TextField partyField;
    @FXML private CheckBox cashCheck;
    @FXML private Label partyInfo;
    @FXML private HBox sellerBox;
    @FXML private TextField sellerNameField;
    @FXML private TextField sellerPhoneField;
    @FXML private Label partyError;
    @FXML private DatePicker datePicker;
    @FXML private Label dateError;
    @FXML private TextField supplierMemoField;
    @FXML private TextField productField;
    @FXML private ComboBox<Unit> unitBox;
    @FXML private TextField qtyField;
    @FXML private TextField priceField;
    @FXML private TextField discountField;
    @FXML private Label stockHint;
    @FXML private Label linePreview;
    @FXML private Label lineError;
    @FXML private TableView<Line> itemsTable;
    @FXML private Label lineDiscountRowLabel;
    @FXML private Label lineDiscountLabel;
    @FXML private Label subtotalLabel;
    @FXML private TextField billDiscountField;
    @FXML private Label grandLabel;
    @FXML private Label billDiscountError;
    @FXML private VBox paymentsBox;
    @FXML private Button addPaymentButton;
    @FXML private Label paymentError;
    @FXML private Label extraHint;
    @FXML private Label paidLabel;
    @FXML private Label dueLabel;
    @FXML private TextArea noteField;

    private final PartyService partyService = new PartyService();
    private final PartyLedgerService partyLedger = new PartyLedgerService();
    private final ProductService productService = new ProductService();
    private final StockLedgerService stockService = new StockLedgerService();
    private final AccountService accountService = new AccountService();
    private final AccountLedgerService accountLedger = new AccountLedgerService();
    private final PurchaseService purchaseService = new PurchaseService();

    private final ObservableList<Line> lines = FXCollections.observableArrayList();
    private final List<PaymentRow> payments = new ArrayList<>();
    private List<Account> accounts = List.of();
    /** Balance of each account (by id), read when the screen opens and after each purchase. */
    private Map<Long, BigDecimal> balances = new HashMap<>();
    /** Stock of the products the product search last found (shown next to each name in the list). */
    private Map<Long, BigDecimal> listStocks = Map.of();
    private SearchPicker<Party> partyPicker;
    private SearchPicker<Product> productPicker;
    /** The cost last filled in from the product; a typed cost is the user's own. */
    private BigDecimal filledPrice;
    /** Cost of one main unit suggested for the picked product (last purchase); null when none. */
    private BigDecimal suggestedCost;
    private FormValidator form;
    private Runnable onSaved = () -> { };

    @FXML
    private void initialize() {
        InputFilters.decimal(qtyField, QTY_INT, QTY_DEC);
        InputFilters.decimal(priceField, MONEY_INT, MONEY_DEC);
        InputFilters.decimal(discountField, MONEY_INT, MONEY_DEC);
        InputFilters.decimal(billDiscountField, MONEY_INT, MONEY_DEC);
        InputFilters.digits(sellerPhoneField, 11);
        InputFilters.maxLength(sellerNameField, 100);
        InputFilters.maxLength(supplierMemoField, 50);
        InputFilters.maxLength(noteField, 150);
        form = new FormValidator()
                .add(partyField, partyError)
                .add(sellerPhoneField, partyError)
                .add(datePicker, dateError);
        FormValidator.hideWhenEmpty(stockHint, linePreview, lineError, billDiscountError, paymentError, extraHint);
        DateUtil.apply(datePicker);
        datePicker.setValue(LocalDate.now());
        datePicker.setDayCellFactory(p -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                setDisable(empty || date.isAfter(LocalDate.now()));
            }
        });

        partyPicker = new SearchPicker<>(partyField,
                text -> partyService.page(PartyStatus.ACTIVE, text, 0, 10).items(), PartyText::nameWithPhone);
        partyPicker.valueProperty().addListener((obs, old, party) -> onPartyChanged());
        cashCheck.selectedProperty().addListener((obs, old, cash) -> {
            sellerBox.setVisible(cash);
            sellerBox.setManaged(cash);
            partyField.setDisable(cash);
            if (cash) {
                partyPicker.setValue(null);
            }
            onPartyChanged();
        });

        productPicker = new SearchPicker<>(productField, this::searchProducts, UnitUtil::productName,
                p -> UnitUtil.productName(p) + " " + I18n.get("sale.stock_in_list",
                        UnitUtil.quantityText(listStocks.getOrDefault(p.getId(), BigDecimal.ZERO), p)));
        productPicker.valueProperty().addListener((obs, old, product) -> onProductPicked(product));
        unitBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Unit u) {
                return UnitUtil.name(u);
            }

            @Override
            public Unit fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        unitBox.valueProperty().addListener((obs, old, unit) -> fillPrice());
        qtyField.textProperty().addListener(o -> showLinePreview());
        priceField.textProperty().addListener(o -> showLinePreview());
        discountField.textProperty().addListener(o -> showLinePreview());

        TableHelper.setUp(itemsTable);
        itemsTable.setItems(lines);
        itemsTable.getColumns().add(TableHelper.column("sale.product", l -> UnitUtil.productName(l.product())));
        // − / + around the quantity: one more or one less without opening the line
        itemsTable.getColumns().add(TableHelper.stepperColumn("sale.quantity",
                l -> UnitUtil.number(l.quantity()) + " " + UnitUtil.name(l.unit()), l -> stepLine(l, BigDecimal.ONE.negate()),
                l -> stepLine(l, BigDecimal.ONE), l -> l.quantity().compareTo(BigDecimal.ONE) > 0));
        itemsTable.getColumns().add(TableHelper.moneyColumn("purchase.price", l -> MoneyUtil.format(l.price())));
        itemsTable.getColumns().add(TableHelper.moneyColumn("sale.discount_unit",
                l -> l.discount().signum() == 0 ? "—" : MoneyUtil.format(l.discount())));
        itemsTable.getColumns().add(TableHelper.moneyColumn("sale.line_total", l -> MoneyUtil.format(l.total())));
        itemsTable.getColumns().add(TableHelper.actionsColumn("label.actions",
                new RowAction<>(Icons.EDIT, "button.edit", "sale.edit_line", "icon-edit", this::editLine),
                new RowAction<>(Icons.REMOVE, "sale.remove_line", "icon-out", lines::remove)));
        lines.addListener((ListChangeListener<Line>) c -> {
            TableHelper.fitColumns(itemsTable);
            recalc();
        });
        TableHelper.fitColumns(itemsTable);
        // Before the language switches (the page reloads): offer to finish this purchase first
        UnsavedWork.register(itemsTable, this::hasUnsavedPurchase, this::confirmPurchase);

        billDiscountField.textProperty().addListener((obs, old, now) -> recalc());

        loadAccounts();
        addPaymentRow(defaultAccount());
        recalc();
    }

    /** Runs after a purchase is saved (the purchases list reloads). */
    public void setOnSaved(Runnable onSaved) {
        this.onSaved = onSaved;
    }

    private void loadAccounts() {
        try {
            accounts = accountService.all();
            balances = accountLedger.balances(accounts);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load accounts", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    // ---------------------------------------------------------------- supplier

    private boolean isCash() {
        return cashCheck.isSelected();
    }

    private void onPartyChanged() {
        form.clear(partyField);
        Party p = isCash() ? null : partyPicker.getValue();
        String info = "";
        if (p != null) {
            try {
                info = I18n.get("sale.customer.info", PartyText.balance(partyLedger.balance(p.getId())));
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "Could not read party balance", e);
            }
        }
        partyInfo.setText(info);
        partyInfo.setVisible(!info.isEmpty());
        partyInfo.setManaged(!info.isEmpty());
        recalc();
    }

    // ---------------------------------------------------------------- line entry

    /** Products matching the typed text, with their stock read for the list. */
    private List<Product> searchProducts(String text) {
        List<Product> found = productService.search(text, 0, 10).items();
        List<Long> ids = found.stream().map(Product::getId).toList();
        listStocks = ids.isEmpty() ? Map.of() : stockService.currentStocks(ids);
        return found;
    }

    private void onProductPicked(Product product) {
        lineError.setText("");
        unitBox.getItems().clear();
        suggestedCost = null;
        if (product == null) {
            stockHint.setText("");
            return;
        }
        // A price typed for the product picked before does not carry over to this one
        priceField.clear();
        filledPrice = null;
        try {
            suggestedCost = purchaseService.suggestedCost(product.getId());
            String hint = I18n.get("sale.stock_hint",
                    UnitUtil.quantityText(stockService.currentStock(product.getId()), product));
            if (suggestedCost != null) {
                hint += " • " + I18n.get("purchase.last_price", MoneyUtil.format(suggestedCost),
                        UnitUtil.name(product.getUnit()));
            }
            stockHint.setText(hint);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not read stock", e);
            stockHint.setText("");
        }
        unitBox.getItems().add(product.getUnit());
        if (product.getSecondaryUnit() != null) {
            unitBox.getItems().add(product.getSecondaryUnit());
        }
        unitBox.setValue(product.getUnit());
        fillPrice();
        qtyField.requestFocus();
    }

    /** Fills the cost from the last purchase for the chosen unit, unless the user typed their own. */
    private void fillPrice() {
        Product product = productPicker.getValue();
        Unit unit = unitBox.getValue();
        if (product == null || unit == null) {
            return;
        }
        BigDecimal typed = amountOf(priceField);
        if (typed != null && (filledPrice == null || typed.compareTo(filledPrice) != 0)) {
            return;
        }
        BigDecimal price = suggestedCost;
        if (price != null && isSecondary(product, unit) && product.getConversionFactor() != null
                && product.getConversionFactor().signum() > 0) {
            price = price.divide(product.getConversionFactor(), MONEY_DEC, RoundingMode.HALF_UP);
        }
        price = price == null ? null : price.setScale(MONEY_DEC, RoundingMode.HALF_UP);
        filledPrice = price;
        priceField.setText(price == null ? "" : price.stripTrailingZeros().toPlainString());
    }

    /** The line being typed, worked out before it is added. */
    private void showLinePreview() {
        BigDecimal qty = amountOf(qtyField);
        BigDecimal price = amountOf(priceField);
        if (qty == null || price == null || qty.signum() <= 0) {
            linePreview.setText("");
            return;
        }
        BigDecimal discount = amountOf(discountField);
        BigDecimal disc = discount == null ? BigDecimal.ZERO : discount;
        BigDecimal total = qty.multiply(price.subtract(disc)).setScale(2, RoundingMode.HALF_UP);
        String text = I18n.get("sale.line_preview", MoneyUtil.format(total));
        if (disc.signum() > 0) {
            text += " " + I18n.get("sale.line_preview_discount",
                    MoneyUtil.format(qty.multiply(disc).setScale(2, RoundingMode.HALF_UP)));
        }
        linePreview.setText(text);
    }

    @FXML
    private void onAddLine() {
        lineError.setText("");
        Product product = productPicker.getValue();
        Unit unit = unitBox.getValue();
        BigDecimal qty = amountOf(qtyField);
        BigDecimal price = amountOf(priceField);
        BigDecimal discount = amountOf(discountField);
        if (product == null) {
            lineError.setText(I18n.get("msg.required_field", I18n.get("sale.product")));
            productField.requestFocus();
            return;
        }
        if (unit == null) {
            lineError.setText(I18n.get("msg.required_field", I18n.get("sale.unit")));
            return;
        }
        if (qty == null || qty.signum() <= 0) {
            lineError.setText(I18n.get("sale.error.quantity"));
            qtyField.requestFocus();
            return;
        }
        // Stock is kept in the main unit to 3 decimals: a tiny secondary-unit quantity would become 0
        if (SaleService.toMain(product, unit, qty).signum() <= 0) {
            lineError.setText(I18n.get("purchase.error.too_small"));
            qtyField.requestFocus();
            return;
        }
        if (price == null) {
            lineError.setText(I18n.get("msg.required_field", I18n.get("purchase.price")));
            priceField.requestFocus();
            return;
        }
        BigDecimal disc = discount == null ? BigDecimal.ZERO : discount;
        if (disc.compareTo(price) > 0) {
            lineError.setText(I18n.get("sale.error.discount"));
            discountField.requestFocus();
            return;
        }
        lines.add(new Line(product, unit, qty, price, disc));
        clearEntry();
        productField.requestFocus();
    }

    /** The − / + button of a line: its quantity changes by {@code step}. */
    private void stepLine(Line line, BigDecimal step) {
        BigDecimal qty = line.quantity().add(step);
        int i = lines.indexOf(line);
        if (qty.signum() > 0 && i >= 0) {
            lines.set(i, new Line(line.product(), line.unit(), qty, line.price(), line.discount()));
        }
    }

    /** Puts the line back into the entry fields to change it. */
    private void editLine(Line line) {
        lines.remove(line);
        productPicker.setValue(line.product());
        unitBox.setValue(line.unit());
        qtyField.setText(line.quantity().stripTrailingZeros().toPlainString());
        priceField.setText(line.price().stripTrailingZeros().toPlainString());
        filledPrice = null;
        discountField.setText(line.discount().signum() == 0 ? "" : line.discount().stripTrailingZeros().toPlainString());
        qtyField.requestFocus();
    }

    private void clearEntry() {
        productPicker.setValue(null);
        unitBox.getItems().clear();
        qtyField.clear();
        priceField.clear();
        discountField.clear();
        stockHint.setText("");
        filledPrice = null;
        suggestedCost = null;
    }

    private static boolean isSecondary(Product p, Unit unit) {
        return p.getSecondaryUnit() != null && p.getSecondaryUnit().getId().equals(unit.getId())
                && (p.getUnit() == null || !p.getUnit().getId().equals(unit.getId()));
    }

    // ---------------------------------------------------------------- money

    private Account defaultAccount() {
        // Cash first: the most common way to pay
        return accounts.stream()
                .filter(a -> a.getType() != null && a.getType().getNumberRule() == AccountNumberRule.NONE)
                .findFirst()
                .orElse(accounts.isEmpty() ? null : accounts.get(0));
    }

    /** Small line under a payment row: the balance of the picked account (hidden while none is picked). */
    private void showBalance(Label hint, Account account) {
        BigDecimal balance = account == null ? null : balances.get(account.getId());
        hint.setText(balance == null ? "" : I18n.get("purchase.balance_hint", MoneyUtil.format(balance)));
        hint.setVisible(balance != null);
        hint.setManaged(balance != null);
    }

    private void addPaymentRow(Account account) {
        PaymentRow row = new PaymentRow(account);
        payments.add(row);
        paymentsBox.getChildren().add(row.node);
        updateAddPaymentButton();
    }

    /** One row per account at most: a new row gets the first account not used yet. */
    @FXML
    private void onAddPayment() {
        accounts.stream().filter(a -> !usedByOthers(null, a)).findFirst().ifPresent(this::addPaymentRow);
    }

    private boolean usedByOthers(PaymentRow row, Account a) {
        return payments.stream().anyMatch(r -> r != row && r.account.getValue() != null
                && r.account.getValue().getId().equals(a.getId()));
    }

    private void updateAddPaymentButton() {
        addPaymentButton.setDisable(payments.size() >= accounts.size());
        // At least one account always stays: the only row has no remove button
        for (PaymentRow r : payments) {
            r.remove.setVisible(payments.size() > 1);
            r.remove.setManaged(payments.size() > 1);
        }
    }

    /** Puts what is still due into the first row (e.g. the whole bill in cash). */
    @FXML
    private void onFullAmount() {
        if (payments.isEmpty()) {
            addPaymentRow(defaultAccount());
        }
        BigDecimal others = BigDecimal.ZERO;
        for (int i = 1; i < payments.size(); i++) {
            BigDecimal v = payments.get(i).value();
            others = others.add(v == null ? BigDecimal.ZERO : v);
        }
        BigDecimal rest = totals().grandTotal().subtract(others).max(BigDecimal.ZERO);
        payments.get(0).amount.setText(rest.stripTrailingZeros().toPlainString());
    }

    private Totals totals() {
        return PurchaseService.totals(lines.stream().map(Line::data).toList(), amountOf(billDiscountField));
    }

    private BigDecimal paid() {
        BigDecimal sum = BigDecimal.ZERO;
        for (PaymentRow r : payments) {
            BigDecimal v = r.value();
            sum = sum.add(v == null ? BigDecimal.ZERO : v);
        }
        return sum;
    }

    /** Totals, paid and due as you type. */
    private void recalc() {
        Totals t = totals();
        subtotalLabel.setText(MoneyUtil.format(t.subtotal()));
        grandLabel.setText(MoneyUtil.format(t.grandTotal()));
        BigDecimal paid = paid();
        BigDecimal due = t.grandTotal().subtract(paid);
        paidLabel.setText(MoneyUtil.format(paid));
        dueLabel.setText(MoneyUtil.format(due.max(BigDecimal.ZERO)));
        dueLabel.getStyleClass().remove("money-out");
        if (due.signum() > 0) {
            dueLabel.getStyleClass().add("money-out");
        }
        // More than the bill: a supplier's extra goes to its ledger; a cash purchase cannot overpay
        boolean extra = due.signum() < 0;
        boolean party = !isCash() && partyPicker.getValue() != null;
        paymentError.setText(extra && !party ? I18n.get("purchase.error.overpaid", MoneyUtil.format(due.negate())) : "");
        extraHint.setText(extra && party ? I18n.get("purchase.extra_to_ledger", MoneyUtil.format(due.negate())) : "");
        BigDecimal lineDiscount = BigDecimal.ZERO;
        for (Line l : lines) {
            lineDiscount = lineDiscount.add(l.quantity().multiply(l.discount()));
        }
        boolean hasLineDiscount = lineDiscount.signum() > 0;
        lineDiscountLabel.setText(MoneyUtil.format(lineDiscount.setScale(2, RoundingMode.HALF_UP)));
        lineDiscountRowLabel.setVisible(hasLineDiscount);
        lineDiscountRowLabel.setManaged(hasLineDiscount);
        lineDiscountLabel.setVisible(hasLineDiscount);
        lineDiscountLabel.setManaged(hasLineDiscount);
        BigDecimal discount = amountOf(billDiscountField);
        billDiscountError.setText(discount != null && discount.compareTo(t.subtotal()) > 0
                ? I18n.get("sale.error.bill_discount") : "");
    }

    // ---------------------------------------------------------------- confirm

    @FXML
    private void onConfirm() {
        confirmPurchase();
    }

    /** Something typed that a reload would lose: item lines, a line being entered, or money paid. */
    private boolean hasUnsavedPurchase() {
        return !lines.isEmpty() || productPicker.getValue() != null || !blank(qtyField)
                || payments.stream().anyMatch(r -> r.value() != null && r.value().signum() > 0);
    }

    private static boolean blank(TextField field) {
        return field.getText() == null || field.getText().isBlank();
    }

    /** Checks and saves the purchase; true only if it was saved. */
    private boolean confirmPurchase() {
        form.clearAll();
        lineError.setText("");
        paymentError.setText("");
        boolean ok = true;
        if (lines.isEmpty()) {
            lineError.setText(I18n.get("sale.error.no_lines"));
            ok = false;
        }
        if (!isCash() && partyPicker.getValue() == null) {
            form.error(partyField, I18n.get("purchase.error.no_supplier"));
            ok = false;
        }
        String sellerPhone = sellerPhoneField.getText() == null ? "" : sellerPhoneField.getText().trim();
        if (isCash() && !sellerPhone.isEmpty() && sellerPhone.length() != 11) {
            form.error(sellerPhoneField, I18n.get("msg.invalid_mobile"));
            ok = false;
        }
        LocalDate date = readDate();
        if (date == null) {
            form.error(datePicker, I18n.get("party.error.date"));
            ok = false;
        } else if (date.isAfter(LocalDate.now())) {
            form.error(datePicker, I18n.get("party.error.future_date"));
            ok = false;
        }
        Totals t = totals();
        if (t.discount().compareTo(t.subtotal()) > 0) {
            ok = false;
        }
        List<PaymentData> paymentData = new ArrayList<>();
        String moneyError = null;
        for (PaymentRow r : payments) {
            BigDecimal v = r.value();
            if (v == null || v.signum() == 0) {
                continue;
            }
            if (r.account.getValue() == null) {
                moneyError = I18n.get("purchase.error.no_account");
                ok = false;
                break;
            }
            // More than the account holds now (a past date is checked again on save)
            Account a = r.account.getValue();
            BigDecimal balance = balances.get(a.getId());
            if (balance != null && v.compareTo(balance) > 0) {
                moneyError = I18n.get("purchase.error.balance", I18n.pick(a.getName(), a.getNameEn()),
                        MoneyUtil.format(balance.max(BigDecimal.ZERO)));
                ok = false;
                break;
            }
            paymentData.add(new PaymentData(a.getId(), v));
        }
        BigDecimal paid = paid();
        BigDecimal due = t.grandTotal().subtract(paid);
        if (due.signum() < 0 && isCash()) {
            ok = false;
        }
        if (!ok) {
            // Shows the live checks' messages too (bill discount, overpaid); a money row's error comes after them
            recalc();
            if (moneyError != null) {
                paymentError.setText(moneyError);
            }
            form.focusFirstError();
            return false;
        }

        // A cash purchase cannot stay due: offer to add the seller as a party (name and mobile filled in)
        if (due.signum() > 0 && isCash()) {
            if (!(UserSession.isLoggedIn() && UserSession.get().can(AppModule.PARTY, UserSession.Action.CREATE))) {
                paymentError.setText(I18n.get("purchase.error.cash_due", MoneyUtil.format(due)));
                return false;
            }
            if (!Dialogs.confirm(I18n.get("purchase.cash.due_question", MoneyUtil.format(due)))) {
                return false;
            }
            Optional<Party> added = addSellerAsParty();
            if (added.isEmpty()) {
                return false;
            }
            cashCheck.setSelected(false);
            partyPicker.setValue(added.get());
        }

        String question = I18n.get("purchase.confirm.question", MoneyUtil.format(t.grandTotal()),
                MoneyUtil.format(paid), MoneyUtil.format(due.max(BigDecimal.ZERO)));
        if (due.signum() < 0) {
            question = I18n.get("purchase.extra_to_ledger", MoneyUtil.format(due.negate())) + "\n" + question;
        }
        if (!Dialogs.confirm(question)) {
            return false;
        }
        Party party = isCash() ? null : partyPicker.getValue();
        PurchaseData data = new PurchaseData(party == null ? null : party.getId(),
                isCash() ? sellerNameField.getText() : null, isCash() ? sellerPhone : null,
                supplierMemoField.getText(), date, lines.stream().map(Line::data).toList(),
                amountOf(billDiscountField), paymentData, noteField.getText());
        try {
            PurchaseService.Result result = purchaseService.confirm(data);
            switch (result.status()) {
                case DONE -> {
                    Notifier.success(I18n.get("purchase.done", I18n.digits(String.valueOf(result.memoNo()))));
                    reset();
                    onSaved.run();
                    return true;
                }
                case INSUFFICIENT_BALANCE -> {
                    Account account = accounts.stream().filter(a -> a.getId().equals(result.accountId()))
                            .findFirst().orElse(null);
                    paymentError.setText(account == null ? I18n.get("msg.error")
                            : I18n.get("purchase.error.balance", I18n.pick(account.getName(), account.getNameEn()),
                            MoneyUtil.format(result.available())));
                    loadAccounts();
                }
                case DUE_WITHOUT_PARTY -> form.error(partyField, I18n.get("purchase.error.no_supplier"));
                case OVERPAID -> paymentError.setText(I18n.get("purchase.error.overpaid", MoneyUtil.format(due.negate())));
                case INACTIVE_PARTY -> form.error(partyField, I18n.get("sale.error.inactive_party"));
                default -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save purchase", e);
            Notifier.error(I18n.get("msg.error"));
        }
        return false;
    }

    /** Opens the new-party form with the seller's name and mobile; the party, once saved. */
    private Optional<Party> addSellerAsParty() {
        FXMLLoader loader = WindowManager.loader(PARTY_FORM_FXML);
        Parent root = WindowManager.load(loader, PARTY_FORM_FXML);
        PartyFormController partyForm = loader.getController();
        partyForm.prefill(sellerNameField.getText(), sellerPhoneField.getText());
        WindowManager.dialog(root, I18n.get("party.form.add_title")).showAndWait();
        if (!partyForm.isSaved()) {
            return Optional.empty();
        }
        try {
            return partyService.findByPhone(partyForm.savedPhone());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not read the new party", e);
            Notifier.error(I18n.get("msg.error"));
            return Optional.empty();
        }
    }

    @FXML
    private void onClear() {
        if (lines.isEmpty() || Dialogs.confirm(I18n.get("sale.clear.question"))) {
            reset();
        }
    }

    /** Empty screen for the next purchase (date kept); account balances read again. */
    private void reset() {
        lines.clear();
        clearEntry();
        cashCheck.setSelected(false);
        partyPicker.setValue(null);
        sellerNameField.clear();
        sellerPhoneField.clear();
        supplierMemoField.clear();
        billDiscountField.clear();
        noteField.clear();
        loadAccounts();
        payments.clear();
        paymentsBox.getChildren().clear();
        addPaymentRow(defaultAccount());
        form.clearAll();
        lineError.setText("");
        paymentError.setText("");
        recalc();
        partyField.requestFocus();
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


    /** The "−" next to the quantity: one less, never to 0 or below. */
    @FXML
    private void onQtyMinus() {
        BigDecimal qty = amountOf(qtyField);
        if (qty != null && qty.subtract(BigDecimal.ONE).signum() > 0) {
            qtyField.setText(qty.subtract(BigDecimal.ONE).stripTrailingZeros().toPlainString());
        }
    }

    /** The "+" next to the quantity: one more (an empty field becomes 1). */
    @FXML
    private void onQtyPlus() {
        BigDecimal qty = amountOf(qtyField);
        qtyField.setText((qty == null ? BigDecimal.ONE : qty.add(BigDecimal.ONE)).stripTrailingZeros().toPlainString());
    }
    /** The number typed (accepted by InputFilters.decimal), or null when empty. */
    private static BigDecimal amountOf(TextField field) {
        String t = field.getText() == null ? "" : field.getText().trim();
        if (t.isEmpty() || t.equals(".")) {
            return null;
        }
        if (t.startsWith(".")) {
            t = "0" + t;
        }
        return new BigDecimal(t.endsWith(".") ? t + "0" : t);
    }
}
