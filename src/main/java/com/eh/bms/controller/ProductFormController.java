package com.eh.bms.controller;

import com.eh.bms.model.Product;
import com.eh.bms.model.ProductCategory;
import com.eh.bms.model.Unit;
import com.eh.bms.service.AppConfigService;
import com.eh.bms.service.ProductCategoryService;
import com.eh.bms.service.ProductService;
import com.eh.bms.service.ProductService.ProductData;
import com.eh.bms.service.UnitService;
import com.eh.bms.model.AppModule;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Add / edit product popup (8.2). Unit and secondary unit are picked from the units list (8.4).
 * The conversion row shows only when a secondary unit is chosen:
 * how many secondary units make one unit (e.g. 1 বস্তা = 50 kg). Blank minimum stock = the app default.
 */
public class ProductFormController {

    private static final Logger LOG = Logger.getLogger(ProductFormController.class.getName());
    private static final int NAME_MAX = 150;
    private static final int SKU_MAX = 50;
    /** Unit names in the current language. */
    private static final StringConverter<Unit> UNIT_CONVERTER = new StringConverter<>() {
        @Override
        public String toString(Unit unit) {
            return UnitUtil.name(unit);
        }

        @Override
        public Unit fromString(String text) {
            throw new UnsupportedOperationException();
        }
    };
    /** DECIMAL(14,3): 11 digits before the point, 3 after. */
    private static final int QTY_INT_DIGITS = 11;
    private static final int QTY_DECIMALS = 3;
    /** DECIMAL(14,2): 12 digits before the point, 2 after. */
    private static final int PRICE_INT_DIGITS = 12;
    private static final int PRICE_DECIMALS = 2;

    @FXML private Button deleteButton;
    @FXML private TextField nameBnField;
    @FXML private Label nameBnError;
    @FXML private TextField nameEnField;
    @FXML private Label nameEnError;
    @FXML private ComboBox<ProductCategory> categoryBox;
    @FXML private Label categoryError;
    @FXML private TextField skuField;
    @FXML private Label skuError;
    @FXML private ComboBox<Unit> unitBox;
    @FXML private Label unitError;
    @FXML private Label packLockedHint;
    /** First item is null = no secondary unit (shown as "None"). */
    @FXML private ComboBox<Unit> secondaryUnitBox;
    @FXML private Label secondaryUnitError;
    @FXML private HBox conversionRow;
    @FXML private TextField conversionField;
    @FXML private Label conversionError;
    @FXML private Label conversionHint;
    @FXML private Label retailCaption;
    @FXML private TextField retailField;
    @FXML private Label retailError;
    @FXML private Label wholesaleCaption;
    @FXML private TextField wholesaleField;
    @FXML private Label wholesaleError;
    @FXML private HBox retailSecondaryRow;
    @FXML private Label retailSecondaryCaption;
    @FXML private TextField retailSecondaryField;
    @FXML private Label retailSecondaryError;
    @FXML private HBox wholesaleSecondaryRow;
    @FXML private Label wholesaleSecondaryCaption;
    @FXML private TextField wholesaleSecondaryField;
    @FXML private Label wholesaleSecondaryError;
    @FXML private Label secondaryPriceHint;
    @FXML private TextField minStockField;
    @FXML private Label minStockError;
    @FXML private Label minStockHint;

    private final ProductService service = new ProductService();
    private final ProductCategoryService categoryService = new ProductCategoryService();
    private final UnitService unitService = new UnitService();
    private final AppConfigService configService = new AppConfigService();
    private FormValidator form;

    /** Null when adding. */
    private Long productId;
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.maxLength(nameBnField, NAME_MAX);
        InputFilters.maxLength(nameEnField, NAME_MAX);
        InputFilters.maxLength(skuField, SKU_MAX);
        InputFilters.decimal(conversionField, QTY_INT_DIGITS, QTY_DECIMALS);
        InputFilters.decimal(minStockField, QTY_INT_DIGITS, QTY_DECIMALS);
        for (TextField price : List.of(retailField, wholesaleField, retailSecondaryField, wholesaleSecondaryField)) {
            InputFilters.decimal(price, PRICE_INT_DIGITS, PRICE_DECIMALS);
        }

        categoryBox.getItems().setAll(categoryService.all());
        categoryBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(ProductCategory category) {
                return category == null ? "" : ProductCategoriesController.nameText(category);
            }

            @Override
            public ProductCategory fromString(String text) {
                throw new UnsupportedOperationException();
            }
        });

        List<Unit> units = unitService.all();
        unitBox.getItems().setAll(units);
        unitBox.setConverter(UNIT_CONVERTER);
        secondaryUnitBox.getItems().add(null);
        secondaryUnitBox.getItems().addAll(units);
        secondaryUnitBox.setConverter(UNIT_CONVERTER);
        // The list shows the null first item as "None", so the user can go back to no secondary unit
        secondaryUnitBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Unit unit, boolean empty) {
                super.updateItem(unit, empty);
                setText(empty ? null : unit == null ? I18n.get("products.secondary_unit.none") : UnitUtil.name(unit));
            }
        });

        // The conversion row follows the secondary unit; its hint names both units and previews the factor
        secondaryUnitBox.valueProperty().addListener((obs, old, now) -> applySecondaryUnit());
        unitBox.valueProperty().addListener((obs, old, now) -> updateConversionHint());
        conversionField.textProperty().addListener((obs, old, now) -> updateConversionHint());
        // Price labels name the units; the secondary hint shows the worked-out price per secondary unit
        retailField.textProperty().addListener((obs, old, now) -> updatePriceLabels());
        wholesaleField.textProperty().addListener((obs, old, now) -> updatePriceLabels());
        updatePriceLabels();

        // A new product starts with the app default; each product keeps its own value (0 = no alert)
        int lowStockDefault = configService.getInt(AppConfigService.LOW_STOCK_DEFAULT,
                ProductsController.LOW_STOCK_FALLBACK);
        minStockField.setText(String.valueOf(lowStockDefault));
        minStockHint.setText(I18n.get("products.min_stock.hint"));

        form = new FormValidator()
                .add(nameBnField, nameBnError)
                .add(nameEnField, nameEnError)
                .add(categoryBox, categoryError)
                .add(skuField, skuError)
                .add(unitBox, unitError)
                .add(secondaryUnitBox, secondaryUnitError)
                .add(conversionField, conversionError)
                .add(retailField, retailError)
                .add(wholesaleField, wholesaleError)
                .add(retailSecondaryField, retailSecondaryError)
                .add(wholesaleSecondaryField, wholesaleSecondaryError)
                .add(minStockField, minStockError);
    }

    /**
     * Puts the next automatic code in the code field (a new product or a copy); the user may change it. If it cannot
     * be worked out the field stays empty and the form still opens.
     */
    public void fillNextCode() {
        try {
            skuField.setText(service.nextSku());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not work out the next product code", e);
        }
    }

    /** Fills the form for editing; call before showing. Leave it uncalled to add a new product. */
    public void edit(Product product) {
        productId = product.getId();
        boolean used = !service.canDelete(productId);
        // Delete only a record nothing uses (and the user may delete)
        boolean deletable = !used && UserSession.isLoggedIn()
                && UserSession.get().can(AppModule.PRODUCT, Action.DELETE);
        deleteButton.setVisible(deletable);
        deleteButton.setManaged(deletable);
        // Stock is kept in the main unit: once used, the pack cannot change
        lockPack(used);
        nameBnField.setText(product.getName());
        nameEnField.setText(product.getNameEn());
        if (product.getCategory() != null) {
            // Select the list's own instance (same id), so the box shows it as chosen
            categoryBox.getItems().stream()
                    .filter(c -> c.getId().equals(product.getCategory().getId()))
                    .findFirst()
                    .ifPresent(categoryBox::setValue);
        }
        skuField.setText(product.getSku());
        if (product.getSku() == null || product.getSku().isBlank()) {
            fillNextCode(); // every product needs a code: an old one without it gets the next automatic code
        }
        selectUnit(unitBox, product.getUnit());
        selectUnit(secondaryUnitBox, product.getSecondaryUnit());
        conversionField.setText(plain(product.getConversionFactor()));
        minStockField.setText(plain(product.getMinStockAlert()));
        retailField.setText(plain(product.getRetailPrice()));
        wholesaleField.setText(plain(product.getWholesalePrice()));
        retailSecondaryField.setText(plain(product.getRetailPriceSecondary()));
        wholesaleSecondaryField.setText(plain(product.getWholesalePriceSecondary()));
    }

    /**
     * Fills the form with a copy of another product, to add a similar one with less typing
     * (e.g. the 25 kg bag of a 50 kg product). Saved as a new product; the code is left empty since it must be unique.
     */
    public void copyFrom(Product product) {
        edit(product);
        productId = null;
        fillNextCode();
        // A copy is a new product: nothing to delete, and its pack is free to change
        deleteButton.setVisible(false);
        deleteButton.setManaged(false);
        lockPack(false);
    }

    private void lockPack(boolean locked) {
        unitBox.setDisable(locked);
        secondaryUnitBox.setDisable(locked);
        conversionField.setDisable(locked);
        packLockedHint.setVisible(locked);
        packLockedHint.setManaged(locked);
    }

    /** True if the user saved; the list then reloads. */
    public boolean isSaved() {
        return saved;
    }

    /** Selects the box's own instance with the same id (so it shows as chosen); nothing for null. */
    private static void selectUnit(ComboBox<Unit> box, Unit unit) {
        if (unit == null) {
            return;
        }
        box.getItems().stream()
                .filter(u -> u != null && u.getId().equals(unit.getId()))
                .findFirst()
                .ifPresent(box::setValue);
    }

    /** Shows the conversion row only with a secondary unit; without one the factor is cleared. */
    private void applySecondaryUnit() {
        boolean show = secondaryUnitBox.getValue() != null;
        if (show == conversionRow.isVisible()) {
            updateConversionHint();
            return;
        }
        for (HBox row : List.of(conversionRow, retailSecondaryRow, wholesaleSecondaryRow)) {
            row.setVisible(show);
            row.setManaged(show);
        }
        if (!show) {
            conversionField.clear();
            retailSecondaryField.clear();
            wholesaleSecondaryField.clear();
        }
        updateConversionHint();
        WindowManager.fitPopup(conversionField);
    }

    /**
     * Line under the factor: the live preview ("১ বস্তা = ৫০ kg") once unit and factor are filled,
     * otherwise the question ("১ বস্তা = কত kg?"). One label, so the popup height stays the same.
     */
    private void updateConversionHint() {
        String unit = UnitUtil.name(unitBox.getValue());
        String secondaryUnit = UnitUtil.name(secondaryUnitBox.getValue());
        String factor = text(conversionField);
        // No preview for the same unit on both sides ("১ বস্তা = ৫০ বস্তা"); the save shows an error for it
        boolean sameUnit = unitBox.getValue() != null && secondaryUnitBox.getValue() != null
                && unitBox.getValue().getId().equals(secondaryUnitBox.getValue().getId());
        String preview = factor.isEmpty() || sameUnit ? "" : UnitUtil.conversionText(unit, secondaryUnit, parse(factor));
        conversionHint.setText(!preview.isEmpty() ? preview : I18n.get("products.conversion.hint",
                unit.isEmpty() ? I18n.get("products.unit") : unit, secondaryUnit));
        updatePriceLabels();
    }

    /**
     * Price labels name their unit ("খুচরা দাম (প্রতি বস্তা)"). The hint under the secondary prices shows
     * what a blank one becomes: the main price ÷ conversion (e.g. "খুচরা ৳৫০.০০, পাইকারি ৳৪৮.০০ (প্রতি কেজি)").
     */
    private void updatePriceLabels() {
        String unit = unitBox.getValue() == null ? I18n.get("products.unit") : UnitUtil.name(unitBox.getValue());
        retailCaption.setText(I18n.get("products.price.retail", unit));
        wholesaleCaption.setText(I18n.get("products.price.wholesale", unit));
        Unit secondary = secondaryUnitBox.getValue();
        if (secondary == null) {
            return;
        }
        String secondaryName = UnitUtil.name(secondary);
        retailSecondaryCaption.setText(I18n.get("products.price.retail", secondaryName));
        wholesaleSecondaryCaption.setText(I18n.get("products.price.wholesale", secondaryName));
        String factorText = text(conversionField);
        BigDecimal factor = factorText.isEmpty() ? null : parse(factorText);
        String retail = text(retailField);
        String wholesale = text(wholesaleField);
        if (factor == null || factor.signum() <= 0 || (retail.isEmpty() && wholesale.isEmpty())) {
            secondaryPriceHint.setText(I18n.get("products.price.secondary_hint_plain"));
            return;
        }
        secondaryPriceHint.setText(I18n.get("products.price.secondary_hint",
                retail.isEmpty() ? "—" : MoneyUtil.format(parse(retail).divide(factor, 2, RoundingMode.HALF_UP)),
                wholesale.isEmpty() ? "—" : MoneyUtil.format(parse(wholesale).divide(factor, 2, RoundingMode.HALF_UP)),
                secondaryName));
    }

    @FXML
    private void onSave() {
        form.clearAll();
        String nameBn = text(nameBnField);
        String nameEn = text(nameEnField);
        ProductCategory category = categoryBox.getValue();
        // Bangla digits are saved as 0-9, so the code is found whichever digits are typed in a search
        String sku = InputFilters.toAsciiDigits(text(skuField));
        Unit unit = unitBox.getValue();
        Unit secondaryUnit = secondaryUnitBox.getValue();
        String conversionText = text(conversionField);
        String minStockText = text(minStockField);

        if (nameBn.isEmpty()) {
            form.error(nameBnField, I18n.get("msg.required_field", I18n.get("products.name_bn")));
        }
        if (nameEn.isEmpty()) {
            form.error(nameEnField, I18n.get("msg.required_field", I18n.get("products.name_en")));
        }
        if (sku.isEmpty()) {
            form.error(skuField, I18n.get("msg.required_field", I18n.get("products.sku")));
        }
        if (category == null) {
            form.error(categoryBox, I18n.get("msg.required_field", I18n.get("products.category")));
        }
        if (unit == null) {
            form.error(unitBox, I18n.get("msg.required_field", I18n.get("products.unit")));
        }
        if (unit != null && secondaryUnit != null && unit.getId().equals(secondaryUnit.getId())) {
            form.error(secondaryUnitBox, I18n.get("products.error.same_unit"));
        }
        BigDecimal conversion = null;
        if (secondaryUnit != null) {
            if (conversionText.isEmpty()) {
                form.error(conversionField, I18n.get("msg.required_field", I18n.get("products.conversion")));
            } else {
                conversion = parse(conversionText);
                if (conversion.signum() <= 0) {
                    form.error(conversionField, I18n.get("products.error.conversion_positive"));
                }
            }
        }
        // The input filter allows only non-negative numbers, so any value here is >= 0; blank = 0 (no alert)
        BigDecimal minStock = minStockText.isEmpty() ? BigDecimal.ZERO : parse(minStockText);
        // Prices are optional (blank = not set); the input filter allows only numbers of 0 or more
        BigDecimal retail = price(retailField);
        BigDecimal wholesale = price(wholesaleField);
        BigDecimal retailSecondary = secondaryUnit == null ? null : price(retailSecondaryField);
        BigDecimal wholesaleSecondary = secondaryUnit == null ? null : price(wholesaleSecondaryField);
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirmSave()) {
            return;
        }

        try {
            ProductService.SaveResult result = service.save(new ProductData(productId, nameBn, nameEn,
                    category.getId(), sku, unit.getId(), secondaryUnit == null ? null : secondaryUnit.getId(),
                    conversion, minStock, retail, wholesale, retailSecondary, wholesaleSecondary));
            switch (result) {
                case DUPLICATE_NAME_BN -> {
                    form.error(nameBnField, I18n.get("products.error.duplicate"));
                    form.focusFirstError();
                }
                case DUPLICATE_NAME_EN -> {
                    form.error(nameEnField, I18n.get("products.error.duplicate"));
                    form.focusFirstError();
                }
                case SKU_REQUIRED -> {
                    form.error(skuField, I18n.get("msg.required_field", I18n.get("products.sku")));
                    form.focusFirstError();
                }
                case DUPLICATE_SKU -> {
                    form.error(skuField, I18n.get("products.error.duplicate_sku"));
                    form.focusFirstError();
                }
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.saved"));
                }
                case PACK_LOCKED -> {
                    form.error(unitBox, I18n.get("products.pack_locked"));
                    form.focusFirstError();
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save product", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onDelete() {
        if (productId == null || !Dialogs.confirmDelete(I18n.get("products.confirm_delete",
                I18n.pick(text(nameBnField), text(nameEnField))))) {
            return;
        }
        try {
            switch (service.delete(productId)) {
                case DELETED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.deleted"));
                }
                case IN_USE -> Notifier.error(I18n.get("products.error.in_use"));
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not delete product", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onCancel() {
        close();
    }

    private void close() {
        ((Stage) nameBnField.getScene().getWindow()).close();
    }

    /** Text accepted by {@link InputFilters#decimal}; "5." and "." are read as 5 and 0. */
    private static BigDecimal parse(String text) {
        String t = text.startsWith(".") ? "0" + text : text;
        return new BigDecimal(t.endsWith(".") ? t + "0" : t);
    }

    /** A typed price, or null when blank. */
    private static BigDecimal price(TextField field) {
        String t = text(field);
        return t.isEmpty() ? null : parse(t);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }
}
