package com.eh.bms.controller;

import com.eh.bms.model.Product;
import com.eh.bms.model.ProductCategory;
import com.eh.bms.model.Unit;
import com.eh.bms.service.AppConfigService;
import com.eh.bms.service.ProductCategoryService;
import com.eh.bms.service.ProductService;
import com.eh.bms.service.ProductService.ProductData;
import com.eh.bms.service.UnitService;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.UnitUtil;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.math.BigDecimal;
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
    /** First item is null = no secondary unit (shown as "None"). */
    @FXML private ComboBox<Unit> secondaryUnitBox;
    @FXML private Label secondaryUnitError;
    @FXML private HBox conversionRow;
    @FXML private TextField conversionField;
    @FXML private Label conversionError;
    @FXML private Label conversionHint;
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
                .add(minStockField, minStockError);
    }

    /** Fills the form for editing; call before showing. Leave it uncalled to add a new product. */
    public void edit(Product product) {
        productId = product.getId();
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
        selectUnit(unitBox, product.getUnit());
        selectUnit(secondaryUnitBox, product.getSecondaryUnit());
        conversionField.setText(plain(product.getConversionFactor()));
        minStockField.setText(plain(product.getMinStockAlert()));
    }

    /**
     * Fills the form with a copy of another product, to add a similar one with less typing
     * (e.g. the 25 kg bag of a 50 kg product). Saved as a new product; the code is left empty since it must be unique.
     */
    public void copyFrom(Product product) {
        edit(product);
        productId = null;
        skuField.clear();
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
        conversionRow.setVisible(show);
        conversionRow.setManaged(show);
        if (!show) {
            conversionField.clear();
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
    }

    @FXML
    private void onSave() {
        form.clearAll();
        String nameBn = text(nameBnField);
        String nameEn = text(nameEnField);
        ProductCategory category = categoryBox.getValue();
        String sku = text(skuField);
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
                    conversion, minStock));
            switch (result) {
                case DUPLICATE_NAME_BN -> {
                    form.error(nameBnField, I18n.get("products.error.duplicate"));
                    form.focusFirstError();
                }
                case DUPLICATE_NAME_EN -> {
                    form.error(nameEnField, I18n.get("products.error.duplicate"));
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
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save product", e);
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

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }
}
