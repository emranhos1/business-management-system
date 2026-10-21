package com.eh.bms.controller;

import com.eh.bms.model.Account;
import com.eh.bms.model.AppModule;
import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.Party;
import com.eh.bms.model.PartyStatus;
import com.eh.bms.model.PartyTradeType;
import com.eh.bms.model.Product;
import com.eh.bms.model.Unit;
import com.eh.bms.service.AccountService;
import com.eh.bms.service.PartyLedgerService;
import com.eh.bms.service.PartyService;
import com.eh.bms.service.ProductService;
import com.eh.bms.service.SaleService;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.SaleService.LineData;
import com.eh.bms.service.SaleService.PaymentData;
import com.eh.bms.service.SaleService.SaleData;
import com.eh.bms.service.SaleService.Totals;
import com.eh.bms.service.StockLedgerService;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PartyText;
import com.eh.bms.util.PriceUtil;
import com.eh.bms.util.SearchPicker;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.TableHelper.RowAction;
import com.eh.bms.util.UnitUtil;
import com.eh.bms.util.UnsavedWork;
import com.eh.bms.util.WindowManager;
import javafx.collections.FXCollections;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * New sale screen (11.1-11.4). The customer is an active party picked by name or mobile, or a walk-in customer
 * (no due allowed; with a due the screen offers to add them as a party). Each line's price comes from the
 * product (wholesale or retail by the customer, per main or secondary unit) and can be changed. Totals,
 * VAT, money received into one or more accounts and the due update as you type.
 */
public class SaleEntryController {

    private static final Logger LOG = Logger.getLogger(SaleEntryController.class.getName());
    private static final String PARTY_FORM_FXML = "/fxml/views/party-form.fxml";
    private static final int QTY_INT = 11;
    private static final int QTY_DEC = 3;
    private static final int MONEY_INT = 12;
    private static final int MONEY_DEC = 2;

    /** A line on the screen; {@code autoPrice} = the price came from the product (refilled when the customer changes). */
    private record Line(Product product, Unit unit, BigDecimal quantity, BigDecimal price, BigDecimal discount,
                        boolean autoPrice) {
        LineData data() {
            return new LineData(product.getId(), unit.getId(), quantity, price, discount);
        }

        BigDecimal total() {
            return data().total();
        }

        BigDecimal mainQuantity() {
            return SaleService.toMain(product, unit, quantity);
        }
    }

    /** One "money received" row: account and amount. */
    private final class PaymentRow {
        final ComboBox<Account> account = new ComboBox<>();
        final TextField amount = new TextField();
        final Button remove = new Button();
        final HBox node;

        PaymentRow(Account selected) {
            account.getItems().setAll(accounts);
            account.setConverter(new StringConverter<>() {
                @Override
                public String toString(Account a) {
                    return a == null ? "" : AccountFormController.nameWithType(a);
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
            amount.textProperty().addListener((obs, old, now) -> {
                if (!filling) {
                    fullMode = false; // typed by hand: it stays as typed
                }
                recalc();
            });

            remove.setGraphic(Icons.of(Icons.REMOVE));
            remove.getStyleClass().addAll("icon-button", "icon-out");
            remove.setTooltip(new javafx.scene.control.Tooltip(I18n.get("sale.remove_payment")));
            remove.setMinWidth(Region.USE_PREF_SIZE);
            node = new HBox(6, account, amount, remove);
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
    @FXML private CheckBox walkInCheck;
    @FXML private Label partyInfo;
    @FXML private HBox walkInBox;
    @FXML private TextField walkInNameField;
    @FXML private TextField walkInPhoneField;
    @FXML private Label partyError;
    @FXML private DatePicker datePicker;
    @FXML private Label dateError;
    @FXML private ComboBox<PartyTradeType> priceTypeBox;
    @FXML private TextField productField;
    @FXML private ComboBox<Unit> unitBox;
    @FXML private TextField qtyField;
    @FXML private Label priceCaption;
    @FXML private TextField priceField;
    @FXML private TextField discountField;
    @FXML private Label stockHint;
    @FXML private Label lineError;
    @FXML private TableView<Line> itemsTable;
    @FXML private Label subtotalLabel;
    @FXML private TextField billDiscountField;
    @FXML private CheckBox vatCheck;
    /** True after "Full amount" until an amount is typed by hand: the first row then follows the bill. */
    private boolean fullMode;
    /** True while the page itself sets an amount (so it does not count as typed). */
    private boolean filling;
    @FXML private Label vatLabel;
    @FXML private Label grandLabel;
    @FXML private Label billDiscountError;
    @FXML private VBox paymentsBox;
    @FXML private Button addPaymentButton;
    @FXML private Label paymentError;
    @FXML private Label extraHint;
    @FXML private Label linePreview;
    @FXML private Label lineDiscountRowLabel;
    @FXML private Label lineDiscountLabel;
    @FXML private Label paidLabel;
    @FXML private Label dueLabel;
    @FXML private TextArea noteField;

    private final PartyService partyService = new PartyService();
    private final PartyLedgerService partyLedger = new PartyLedgerService();
    private final ProductService productService = new ProductService();
    private final StockLedgerService stockService = new StockLedgerService();
    /** Stock of the products the product search last found (shown next to each name in the list). */
    private Map<Long, BigDecimal> listStocks = Map.of();
    private final AccountService accountService = new AccountService();
    private final SaleService saleService = new SaleService();

    private final ObservableList<Line> lines = FXCollections.observableArrayList();
    private final List<PaymentRow> payments = new ArrayList<>();
    private List<Account> accounts = List.of();
    private SearchPicker<Party> partyPicker;
    private SearchPicker<Product> productPicker;
    private BigDecimal vatPercent = BigDecimal.ZERO;
    /** The price last filled in from the product; a typed price is the user's own. */
    private BigDecimal filledPrice;
    /** Red box + message under the customer, mobile and date fields; focus goes to the first error. */
    private FormValidator form;
    private Runnable onSaved = () -> { };

    @FXML
    private void initialize() {
        InputFilters.decimal(qtyField, QTY_INT, QTY_DEC);
        InputFilters.decimal(priceField, MONEY_INT, MONEY_DEC);
        InputFilters.decimal(discountField, MONEY_INT, MONEY_DEC);
        InputFilters.decimal(billDiscountField, MONEY_INT, MONEY_DEC);
        InputFilters.digits(walkInPhoneField, 11);
        InputFilters.maxLength(walkInNameField, 100);
        InputFilters.maxLength(noteField, 150);
        form = new FormValidator()
                .add(partyField, partyError)
                .add(walkInPhoneField, partyError)
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

        priceTypeBox.getItems().setAll(PartyTradeType.values());
        priceTypeBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(PartyTradeType t) {
                return PartyText.trade(t);
            }

            @Override
            public PartyTradeType fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        priceTypeBox.setValue(PartyTradeType.RETAIL);
        priceTypeBox.valueProperty().addListener((obs, old, now) -> repriceLines());

        partyPicker = new SearchPicker<>(partyField,
                text -> partyService.page(PartyStatus.ACTIVE, text, 0, 10).items(), PartyText::nameWithPhone);
        partyPicker.valueProperty().addListener((obs, old, party) -> onPartyChanged());
        walkInCheck.selectedProperty().addListener((obs, old, walkIn) -> {
            walkInBox.setVisible(walkIn);
            walkInBox.setManaged(walkIn);
            partyField.setDisable(walkIn);
            if (walkIn) {
                partyPicker.setValue(null);
            }
            onPartyChanged();
        });

        productPicker = new SearchPicker<>(productField,
                this::searchProducts, UnitUtil::productName,
                p -> UnitUtil.productName(p) + " " + I18n.get("sale.stock_in_list",
                        UnitUtil.quantityText(listStocks.getOrDefault(p.getId(), BigDecimal.ZERO)
                                .subtract(inList(p, null)), p)));
        productPicker.valueProperty().addListener((obs, old, product) -> onProductPicked(product));
        // A barcode scanner types the code and Enter (20.5)
        productField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER && onScan()) {
                e.consume();
            }
        });
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
        itemsTable.getColumns().add(TableHelper.moneyColumn("sale.price", l -> MoneyUtil.format(l.price())));
        itemsTable.getColumns().add(TableHelper.moneyColumn("sale.discount_unit",
                l -> l.discount().signum() == 0 ? "—" : MoneyUtil.format(l.discount())));
        itemsTable.getColumns().add(TableHelper.moneyColumn("sale.line_total", l -> MoneyUtil.format(l.total())));
        itemsTable.getColumns().add(TableHelper.actionsColumn("label.actions",
                new RowAction<>(Icons.EDIT, "button.edit", "sale.edit_line", "icon-edit", this::editLine),
                new RowAction<>(Icons.REMOVE, "sale.remove_line", "icon-out", this::removeLine)));
        lines.addListener((javafx.collections.ListChangeListener<Line>) c -> {
            TableHelper.fitColumns(itemsTable);
            followBill();
        });
        TableHelper.fitColumns(itemsTable);
        // Before the language switches (the page reloads): offer to finish this sale first
        UnsavedWork.register(itemsTable, this::hasUnsavedSale, this::confirmSale);

        billDiscountField.textProperty().addListener((obs, old, now) -> followBill());
        vatCheck.selectedProperty().addListener((obs, old, now) -> followBill());

        try {
            accounts = accountService.all();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load accounts", e);
            Notifier.error(I18n.get("msg.error"));
        }
        loadVat();
        addPaymentRow(defaultAccount());
        recalc();
    }

    /** VAT % from Settings; the VAT tick shows only when it is above 0, unticked (tick it to add VAT). */
    private void loadVat() {
        try {
            vatPercent = saleService.vatPercent();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load VAT", e);
            Notifier.error(I18n.get("msg.error"));
        }
        boolean hasVat = vatPercent.signum() > 0;
        vatCheck.setText(I18n.get("sale.vat", UnitUtil.number(vatPercent)));
        vatCheck.setSelected(false);
        vatCheck.setVisible(hasVat);
        vatLabel.setVisible(hasVat);
    }

    /** Runs after a sale is saved (the sales list reloads). */
    public void setOnSaved(Runnable onSaved) {
        this.onSaved = onSaved;
    }

    // ---------------------------------------------------------------- customer

    private boolean isWalkIn() {
        return walkInCheck.isSelected();
    }

    /** Prices for this sale: wholesale or retail as chosen (it starts from the party's own type). */
    private boolean wholesale() {
        return priceTypeBox.getValue() == PartyTradeType.WHOLESALE;
    }

    private void onPartyChanged() {
        form.clear(partyField);
        Party p = isWalkIn() ? null : partyPicker.getValue();
        if (p == null) {
            partyInfo.setVisible(false);
            partyInfo.setManaged(false);
        } else {
            String info = "";
            try {
                info = I18n.get("sale.customer.info", PartyText.balance(partyLedger.balance(p.getId())));
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "Could not read party balance", e);
            }
            partyInfo.setText(info);
            partyInfo.setVisible(!info.isEmpty());
            partyInfo.setManaged(!info.isEmpty());
        }
        // Wholesale / retail is chosen on each sale (price type), not stored on the party
        recalc();
    }

    /** The line being typed, worked out before it is added: "এই লাইনে: ৳X (ছাড় ৳Y)". */
    private void showLinePreview() {
        BigDecimal qty = amountOf(qtyField);
        BigDecimal price = amountOf(priceField);
        if (qty == null || price == null || qty.signum() <= 0) {
            linePreview.setText("");
            return;
        }
        BigDecimal discount = amountOf(discountField);
        BigDecimal disc = discount == null ? BigDecimal.ZERO : discount;
        BigDecimal total = qty.multiply(price.subtract(disc)).setScale(2, java.math.RoundingMode.HALF_UP);
        String text = I18n.get("sale.line_preview", MoneyUtil.format(total));
        if (disc.signum() > 0) {
            text += " " + I18n.get("sale.line_preview_discount",
                    MoneyUtil.format(qty.multiply(disc).setScale(2, java.math.RoundingMode.HALF_UP)));
        }
        linePreview.setText(text);
    }

    /** Prices that came from the product follow the chosen wholesale / retail type; typed prices stay. */
    private void repriceLines() {
        boolean wholesale = wholesale();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (l.autoPrice()) {
                BigDecimal price = PriceUtil.price(l.product(), wholesale, isSecondary(l.product(), l.unit()));
                if (price != null) {
                    lines.set(i, new Line(l.product(), l.unit(), l.quantity(), price, l.discount().min(price), true));
                }
            }
        }
        fillPrice();
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
        if (product == null) {
            stockHint.setText("");
            return;
        }
        unitBox.getItems().add(product.getUnit());
        if (product.getSecondaryUnit() != null) {
            unitBox.getItems().add(product.getSecondaryUnit());
        }
        unitBox.setValue(product.getUnit());
        fillPrice();
        try {
            BigDecimal stock = stockService.currentStock(product.getId()).subtract(inList(product, null));
            stockHint.setText(I18n.get("sale.stock_hint", UnitUtil.quantityText(stock, product)));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not read stock", e);
            stockHint.setText("");
        }
        qtyField.requestFocus();
    }

    /** Fills the price from the product for the chosen unit and customer, unless the user typed their own. */
    private void fillPrice() {
        Product product = productPicker.getValue();
        Unit unit = unitBox.getValue();
        priceCaption.setText(I18n.get(wholesale() ? "sale.price.wholesale" : "sale.price.retail"));
        if (product == null || unit == null) {
            return;
        }
        BigDecimal typed = amountOf(priceField);
        if (typed != null && filledPrice != null && typed.compareTo(filledPrice) != 0) {
            return;
        }
        BigDecimal price = PriceUtil.price(product, wholesale(), isSecondary(product, unit));
        filledPrice = price;
        priceField.setText(price == null ? "" : price.stripTrailingZeros().toPlainString());
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
        if (price == null) {
            lineError.setText(I18n.get("msg.required_field", I18n.get("sale.price")));
            priceField.requestFocus();
            return;
        }
        BigDecimal disc = discount == null ? BigDecimal.ZERO : discount;
        if (disc.compareTo(price) > 0) {
            lineError.setText(I18n.get("sale.error.discount"));
            discountField.requestFocus();
            return;
        }
        // Stock left after the lines already on this sale
        BigDecimal mainQty = SaleService.toMain(product, unit, qty);
        try {
            BigDecimal left = stockService.currentStock(product.getId()).subtract(inList(product, null));
            if (mainQty.compareTo(left) > 0) {
                lineError.setText(I18n.get("sale.error.stock", UnitUtil.quantityText(left.max(BigDecimal.ZERO), product)));
                qtyField.requestFocus();
                return;
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not read stock", e);
            Notifier.error(I18n.get("msg.error"));
            return;
        }
        boolean auto = filledPrice != null && price.compareTo(filledPrice) == 0;
        // The same product in the same unit at the same price and discount is one line: the quantity is added
        Line same = lines.stream()
                .filter(l -> l.product().getId().equals(product.getId()) && l.unit().getId().equals(unit.getId())
                        && l.price().compareTo(price) == 0 && l.discount().compareTo(disc) == 0)
                .findFirst().orElse(null);
        if (same == null) {
            lines.add(new Line(product, unit, qty, price, disc, auto));
        } else {
            lines.set(lines.indexOf(same),
                    new Line(product, unit, same.quantity().add(qty), price, disc, same.autoPrice() && auto));
        }
        clearEntry();
        productField.requestFocus();
    }

    // ---------------------------------------------------------------- barcode scanner (20.5)

    /**
     * A scanner types the product's code and presses Enter in the product field. A code that is exactly a product's
     * SKU adds that product at once: 1 in its main unit at the price of the chosen type; scanning it again adds one
     * more to the same line. Returns true when the Enter was a scan (anything else is an ordinary search).
     */
    private boolean onScan() {
        String code = InputFilters.toAsciiDigits(productField.getText() == null ? "" : productField.getText()).trim();
        if (code.isEmpty()) {
            return false;
        }
        Product product;
        try {
            product = productService.findBySku(code).orElse(null);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not look up the scanned code", e);
            return false;
        }
        if (product == null) {
            return false;
        }
        lineError.setText("");
        Unit unit = product.getUnit();
        BigDecimal price = PriceUtil.price(product, wholesale(), false);
        if (unit == null || price == null) {
            // No price set: pick the product the usual way, the price is typed
            productPicker.setValue(product);
            return true;
        }
        Line same = lines.stream()
                .filter(l -> l.product().getId().equals(product.getId()) && l.unit().getId().equals(unit.getId())
                        && l.autoPrice() && l.discount().signum() == 0)
                .findFirst().orElse(null);
        BigDecimal qty = same == null ? BigDecimal.ONE : same.quantity().add(BigDecimal.ONE);
        try {
            BigDecimal left = stockService.currentStock(product.getId()).subtract(inList(product, same));
            if (SaleService.toMain(product, unit, qty).compareTo(left) > 0) {
                lineError.setText(I18n.get("sale.error.stock", UnitUtil.quantityText(left.max(BigDecimal.ZERO), product)));
                clearEntry();
                productField.requestFocus();
                return true;
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not read stock", e);
            Notifier.error(I18n.get("msg.error"));
            return true;
        }
        Line line = new Line(product, unit, qty, price, BigDecimal.ZERO, true);
        if (same == null) {
            lines.add(line);
        } else {
            lines.set(lines.indexOf(same), line);
        }
        clearEntry();
        productField.requestFocus();
        return true;
    }

    /** The − / + button of a line: its quantity changes by {@code step}; one more must still be in stock. */
    private void stepLine(Line line, BigDecimal step) {
        lineError.setText("");
        BigDecimal qty = line.quantity().add(step);
        if (qty.signum() <= 0) {
            return;
        }
        if (step.signum() > 0) {
            try {
                BigDecimal left = stockService.currentStock(line.product().getId()).subtract(inList(line.product(), line));
                if (SaleService.toMain(line.product(), line.unit(), qty).compareTo(left) > 0) {
                    lineError.setText(I18n.get("sale.error.stock",
                            UnitUtil.quantityText(left.max(BigDecimal.ZERO), line.product())));
                    return;
                }
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "Could not read stock", e);
                Notifier.error(I18n.get("msg.error"));
                return;
            }
        }
        int i = lines.indexOf(line);
        if (i >= 0) {
            lines.set(i, new Line(line.product(), line.unit(), qty, line.price(), line.discount(), line.autoPrice()));
        }
    }

    /** Puts the line back into the entry fields to change it. */
    private void editLine(Line line) {
        lines.remove(line);
        productPicker.setValue(line.product());
        unitBox.setValue(line.unit());
        qtyField.setText(line.quantity().stripTrailingZeros().toPlainString());
        priceField.setText(line.price().stripTrailingZeros().toPlainString());
        filledPrice = line.autoPrice() ? line.price() : null;
        discountField.setText(line.discount().signum() == 0 ? "" : line.discount().stripTrailingZeros().toPlainString());
        qtyField.requestFocus();
    }

    private void removeLine(Line line) {
        lines.remove(line);
    }

    private void clearEntry() {
        productPicker.setValue(null);
        unitBox.getItems().clear();
        qtyField.clear();
        priceField.clear();
        discountField.clear();
        stockHint.setText("");
        filledPrice = null;
    }

    /** Main-unit quantity of this product already on the sale (leaving out {@code skip}). */
    private BigDecimal inList(Product product, Line skip) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Line l : lines) {
            if (l != skip && l.product().getId().equals(product.getId())) {
                sum = sum.add(l.mainQuantity());
            }
        }
        return sum;
    }

    private static boolean isSecondary(Product p, Unit unit) {
        return p.getSecondaryUnit() != null && p.getSecondaryUnit().getId().equals(unit.getId())
                && (p.getUnit() == null || !p.getUnit().getId().equals(unit.getId()));
    }

    // ---------------------------------------------------------------- money

    private Account defaultAccount() {
        // Cash first: the most common way to be paid
        return accounts.stream()
                .filter(a -> a.getType() != null && a.getType().getNumberRule() == AccountNumberRule.NONE)
                .findFirst()
                .orElse(accounts.isEmpty() ? null : accounts.get(0));
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

    /** True if a row other than {@code row} already has this account. */
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
        fillFull();
        fullMode = true;
    }

    /** First row = the bill minus the other rows. */
    private void fillFull() {
        Totals t = totals();
        BigDecimal others = BigDecimal.ZERO;
        for (int i = 1; i < payments.size(); i++) {
            BigDecimal v = payments.get(i).value();
            others = others.add(v == null ? BigDecimal.ZERO : v);
        }
        BigDecimal rest = t.grandTotal().subtract(others).max(BigDecimal.ZERO);
        filling = true;
        try {
            payments.get(0).amount.setText(rest.stripTrailingZeros().toPlainString());
        } finally {
            filling = false;
        }
    }

    /** The bill changed (VAT, discount, items): an amount filled by "Full amount" follows it. */
    private void followBill() {
        if (fullMode && !payments.isEmpty()) {
            fillFull();
        }
        recalc();
    }

    private Totals totals() {
        List<LineData> data = lines.stream().map(Line::data).toList();
        return SaleService.totals(data, amountOf(billDiscountField),
                vatCheck.isSelected() ? vatPercent : BigDecimal.ZERO);
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
        vatLabel.setText(MoneyUtil.format(t.vat()));
        grandLabel.setText(MoneyUtil.format(t.grandTotal()));
        BigDecimal paid = paid();
        BigDecimal due = t.grandTotal().subtract(paid);
        paidLabel.setText(MoneyUtil.format(paid));
        dueLabel.setText(MoneyUtil.format(due.max(BigDecimal.ZERO)));
        dueLabel.getStyleClass().remove("money-out");
        if (due.signum() > 0) {
            dueLabel.getStyleClass().add("money-out");
        }
        // More than the bill: a party's extra goes to its ledger; a walk-in customer cannot overpay
        boolean extra = due.signum() < 0;
        boolean party = !isWalkIn() && partyPicker.getValue() != null;
        paymentError.setText(extra && !party ? I18n.get("sale.error.overpaid", MoneyUtil.format(due.negate())) : "");
        extraHint.setText(extra && party ? I18n.get("sale.extra_to_ledger", MoneyUtil.format(due.negate())) : "");
        // Discount given on the lines (already taken off each line's total)
        BigDecimal lineDiscount = BigDecimal.ZERO;
        for (Line l : lines) {
            lineDiscount = lineDiscount.add(l.quantity().multiply(l.discount()));
        }
        boolean hasLineDiscount = lineDiscount.signum() > 0;
        lineDiscountLabel.setText(MoneyUtil.format(lineDiscount.setScale(2, java.math.RoundingMode.HALF_UP)));
        lineDiscountRowLabel.setVisible(hasLineDiscount);
        lineDiscountRowLabel.setManaged(hasLineDiscount);
        lineDiscountLabel.setVisible(hasLineDiscount);
        lineDiscountLabel.setManaged(hasLineDiscount);
        BigDecimal discount = amountOf(billDiscountField);
        billDiscountError.setText(discount != null && discount.compareTo(t.subtotal().add(t.vat())) > 0
                ? I18n.get("sale.error.bill_discount") : "");
    }

    // ---------------------------------------------------------------- confirm

    @FXML
    private void onConfirm() {
        confirmSale();
    }

    /** Something typed that a reload would lose: item lines, a line being entered, or money received. */
    private boolean hasUnsavedSale() {
        return !lines.isEmpty() || productPicker.getValue() != null || !blank(qtyField)
                || payments.stream().anyMatch(r -> r.value() != null && r.value().signum() > 0);
    }

    private static boolean blank(TextField field) {
        return field.getText() == null || field.getText().isBlank();
    }

    /** Checks and saves the sale; true only if it was saved. */
    private boolean confirmSale() {
        form.clearAll();
        lineError.setText("");
        boolean ok = true;
        if (lines.isEmpty()) {
            lineError.setText(I18n.get("sale.error.no_lines"));
            ok = false;
        }
        if (!isWalkIn() && partyPicker.getValue() == null) {
            form.error(partyField, I18n.get("sale.error.no_customer"));
            ok = false;
        }
        String walkInPhone = walkInPhoneField.getText() == null ? "" : walkInPhoneField.getText().trim();
        if (isWalkIn() && !walkInPhone.isEmpty() && walkInPhone.length() != 11) {
            form.error(walkInPhoneField, I18n.get("msg.invalid_mobile"));
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
        for (PaymentRow r : payments) {
            BigDecimal v = r.value();
            if (v == null || v.signum() == 0) {
                continue;
            }
            if (r.account.getValue() == null) {
                paymentError.setText(I18n.get("sale.error.no_account"));
                ok = false;
                break;
            }
            paymentData.add(new PaymentData(r.account.getValue().getId(), v));
        }
        BigDecimal paid = paid();
        BigDecimal due = t.grandTotal().subtract(paid);
        if (due.signum() < 0 && isWalkIn()) {
            ok = false;
        }
        if (!ok) {
            // Shows the live checks' messages too (bill discount, overpaid)
            recalc();
            form.focusFirstError();
            return false;
        }

        // No money typed (often forgotten): ask before selling it all on credit (N11)
        if (paid.signum() == 0 && t.grandTotal().signum() > 0
                && !Dialogs.confirm(I18n.get("sale.no_money.question", MoneyUtil.format(t.grandTotal())))) {
            if (!payments.isEmpty()) {
                payments.get(0).amount.requestFocus();
            }
            return false;
        }

        // A walk-in customer cannot owe: offer to add them as a party (name and mobile filled in)
        if (due.signum() > 0 && isWalkIn()) {
            // Adding a party needs its own permission; without it the walk-in must pay in full
            if (!(UserSession.isLoggedIn() && UserSession.get().can(AppModule.PARTY, UserSession.Action.CREATE))) {
                paymentError.setText(I18n.get("sale.error.walk_in_due", MoneyUtil.format(due)));
                return false;
            }
            if (!Dialogs.confirm(I18n.get("sale.walk_in.due_question", MoneyUtil.format(due)))) {
                return false;
            }
            Optional<Party> added = addWalkInAsParty();
            if (added.isEmpty()) {
                return false;
            }
            walkInCheck.setSelected(false);
            partyPicker.setValue(added.get());
        }

        String question = I18n.get("sale.confirm.question", MoneyUtil.format(t.grandTotal()),
                MoneyUtil.format(paid), MoneyUtil.format(due.max(BigDecimal.ZERO)));
        if (due.signum() < 0) {
            question = I18n.get("sale.extra_to_ledger", MoneyUtil.format(due.negate())) + "\n" + question;
        }
        if (!Dialogs.confirm(question)) {
            return false;
        }
        Party party = isWalkIn() ? null : partyPicker.getValue();
        SaleData data = new SaleData(party == null ? null : party.getId(),
                isWalkIn() ? walkInNameField.getText() : null, isWalkIn() ? walkInPhone : null, date,
                lines.stream().map(Line::data).toList(), amountOf(billDiscountField),
                vatCheck.isSelected() ? vatPercent : BigDecimal.ZERO,
                paymentData, noteField.getText());
        try {
            SaleService.Result result = saleService.confirm(data);
            switch (result.status()) {
                case DONE -> {
                    Notifier.success(I18n.get("sale.done", I18n.digits(String.valueOf(result.memoNo()))));
                    reset();
                    onSaved.run();
                    offerMemo(result.memoNo());
                    return true;
                }
                case INSUFFICIENT_STOCK -> {
                    Line line = lines.stream().filter(l -> l.product().getId().equals(result.productId()))
                            .findFirst().orElse(null);
                    lineError.setText(line == null ? I18n.get("msg.error") : I18n.get("sale.error.stock_line_on",
                            UnitUtil.productName(line.product()), UnitUtil.quantityText(result.available(), line.product())));
                }
                case DUE_WITHOUT_PARTY -> form.error(partyField, I18n.get("sale.error.no_customer"));
                case OVERPAID -> paymentError.setText(I18n.get("sale.error.overpaid", MoneyUtil.format(due.negate())));
                case INACTIVE_PARTY -> form.error(partyField, I18n.get("sale.error.inactive_party"));
                case VAT_CHANGED -> {
                    // Settings changed meanwhile: show the new VAT (keeping the user's tick) and let the user
                    // check the total again
                    boolean ticked = vatCheck.isSelected();
                    loadVat();
                    vatCheck.setSelected(ticked && vatPercent.signum() > 0);
                    recalc();
                    paymentError.setText(I18n.get("sale.error.vat_changed"));
                }
                default -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save sale", e);
            Notifier.error(I18n.get("msg.error"));
        }
        return false;
    }

    /** After a sale: "Print the memo?"; Yes opens its preview (N6). */
    private void offerMemo(long memoNo) {
        if (!Dialogs.confirm(I18n.get("memo.ask_print", I18n.digits(String.valueOf(memoNo))))) {
            return;
        }
        try {
            saleService.idOfMemo(memoNo).ifPresent(MemoViewController::open);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not open memo " + memoNo, e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    /** Opens the new-party form with the walk-in name and mobile; the party, once saved. */
    private Optional<Party> addWalkInAsParty() {
        FXMLLoader loader = WindowManager.loader(PARTY_FORM_FXML);
        Parent root = WindowManager.load(loader, PARTY_FORM_FXML);
        PartyFormController form = loader.getController();
        form.prefill(walkInNameField.getText(), walkInPhoneField.getText());
        WindowManager.dialog(root, I18n.get("party.form.add_title")).showAndWait();
        if (!form.isSaved()) {
            return Optional.empty();
        }
        try {
            return partyService.findByPhone(form.savedPhone());
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

    /** Empty screen for the next sale (date and VAT setting kept). */
    private void reset() {
        fullMode = false;
        lines.clear();
        clearEntry();
        walkInCheck.setSelected(false);
        partyPicker.setValue(null);
        priceTypeBox.setValue(PartyTradeType.RETAIL);
        walkInNameField.clear();
        walkInPhoneField.clear();
        billDiscountField.clear();
        noteField.clear();
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
