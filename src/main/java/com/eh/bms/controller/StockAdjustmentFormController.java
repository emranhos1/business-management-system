package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Product;
import com.eh.bms.model.StockAdjustmentReason;
import com.eh.bms.service.StockAdjustmentService;
import com.eh.bms.service.StockLedgerService;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PermissionUtil;
import com.eh.bms.util.UnitUtil;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stock adjustment popup (9.5): reason (damage / loss / correction), increase or decrease, quantity,
 * date (today by default, never in the future) and note. Damage and loss always take stock out;
 * only a correction may add. A correction needs a note (why the count was wrong).
 * Stock cannot go below zero: the current stock is shown under the product and checked again on save.
 */
public class StockAdjustmentFormController {

    private static final Logger LOG = Logger.getLogger(StockAdjustmentFormController.class.getName());
    private static final String FXML_PATH = "/fxml/views/stock-adjustment-form.fxml";
    private static final int NOTE_MAX = 200;
    /** DECIMAL(14,3): 11 digits before the point, 3 after. */
    private static final int QTY_INT_DIGITS = 11;
    private static final int QTY_DECIMALS = 3;

    private enum Direction {
        INCREASE, DECREASE
    }

    @FXML private TextField productField;
    @FXML private Label conversionLabel;
    @FXML private Label stockLabel;
    @FXML private ComboBox<StockAdjustmentReason> reasonBox;
    @FXML private Label reasonError;
    @FXML private ComboBox<Direction> directionBox;
    @FXML private Label directionError;
    @FXML private Label directionHint;
    @FXML private TextField quantityField;
    @FXML private Label quantityError;
    @FXML private Label afterLabel;
    @FXML private DatePicker datePicker;
    @FXML private Label dateError;
    @FXML private Label noteLabel;
    @FXML private TextArea noteField;
    @FXML private Label noteError;
    @FXML private Button saveButton;

    private final StockLedgerService ledgerService = new StockLedgerService();
    private final StockAdjustmentService adjustmentService = new StockAdjustmentService();
    private FormValidator form;

    private Product product;
    /** Stock when the form opened (refreshed if a save finds less); null if it could not be read. */
    private BigDecimal currentStock;
    private boolean saved;

    /**
     * Opens the adjustment popup for one product; {@code onSaved} runs after a successful save
     * (e.g. to reload the stock list). Needs the STOCK create permission. Call on the JavaFX thread.
     */
    public static void open(Product product, Runnable onSaved) {
        if (product == null) {
            return;
        }
        if (!UserSession.isLoggedIn() || !UserSession.get().can(AppModule.STOCK, Action.CREATE)) {
            Notifier.error(I18n.get("msg.no_permission"));
            return;
        }
        FXMLLoader loader = WindowManager.loader(FXML_PATH);
        Parent root = WindowManager.load(loader, FXML_PATH);
        StockAdjustmentFormController form = loader.getController();
        form.setProduct(product);
        WindowManager.dialog(root, I18n.get("stock.adjust.title")).showAndWait();
        if (form.saved && onSaved != null) {
            onSaved.run();
        }
    }

    @FXML
    private void initialize() {
        PermissionUtil.require(saveButton, AppModule.STOCK, Action.CREATE);
        InputFilters.decimal(quantityField, QTY_INT_DIGITS, QTY_DECIMALS);
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

        reasonBox.getItems().setAll(StockAdjustmentReason.values());
        reasonBox.setConverter(converter(r -> I18n.get("stock.reason." + r.name())));
        directionBox.getItems().setAll(Direction.values());
        directionBox.setConverter(converter(d -> I18n.get(d == Direction.INCREASE
                ? "stock.adjust.direction.increase" : "stock.adjust.direction.decrease")));

        reasonBox.valueProperty().addListener((obs, old, reason) -> applyReason(reason));
        directionBox.valueProperty().addListener((obs, old, now) -> updateAfter());
        quantityField.textProperty().addListener((obs, old, now) -> updateAfter());
        applyReason(null);

        form = new FormValidator()
                .add(reasonBox, reasonError)
                .add(directionBox, directionError)
                .add(quantityField, quantityError)
                .add(datePicker, dateError)
                .add(noteField, noteError);
    }

    /** Shows the product (name + conversion, so same-name pack sizes can be told apart) and its current stock. */
    private void setProduct(Product product) {
        this.product = product;
        productField.setText(I18n.pick(product.getName(), product.getNameEn()));
        String conversion = UnitUtil.conversionText(product);
        conversionLabel.setText(conversion);
        conversionLabel.setVisible(!conversion.isEmpty());
        conversionLabel.setManaged(!conversion.isEmpty());
        try {
            currentStock = ledgerService.currentStock(product.getId());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not read current stock", e);
            Notifier.error(I18n.get("msg.error"));
            currentStock = null;
        }
        showStock();
    }

    /**
     * Damage and loss always take stock out, so the direction is fixed to Decrease;
     * a correction may go either way and needs a note.
     */
    private void applyReason(StockAdjustmentReason reason) {
        boolean correction = reason == StockAdjustmentReason.CORRECTION;
        boolean fixed = reason != null && !correction;
        if (fixed) {
            directionBox.setValue(Direction.DECREASE);
        }
        directionBox.setDisable(fixed);
        directionHint.setText(fixed ? I18n.get("stock.adjust.direction.fixed_hint") : "");
        directionHint.setVisible(fixed);
        directionHint.setManaged(fixed);

        if (correction) {
            if (!noteLabel.getStyleClass().contains("required")) {
                noteLabel.getStyleClass().add("required");
            }
        } else {
            noteLabel.getStyleClass().remove("required");
        }
        WindowManager.fitPopup(directionHint);
    }

    private void showStock() {
        stockLabel.setText(currentStock == null ? ""
                : I18n.get("stock.adjust.current_hint", UnitUtil.quantityText(currentStock, product)));
        updateAfter();
    }

    /** "Stock after: ..." under the quantity, once direction and quantity are filled. */
    private void updateAfter() {
        BigDecimal signed = signedQuantity();
        boolean show = currentStock != null && signed != null && signed.signum() != 0;
        afterLabel.setText(show
                ? I18n.get("stock.adjust.after_hint", UnitUtil.quantityText(currentStock.add(signed), product))
                : "");
        if (afterLabel.isVisible() != show) {
            afterLabel.setVisible(show);
            afterLabel.setManaged(show);
            WindowManager.fitPopup(afterLabel);
        }
    }

    /** The quantity with its sign from the direction; null while either is missing. */
    private BigDecimal signedQuantity() {
        String text = text(quantityField.getText());
        Direction direction = directionBox.getValue();
        if (text.isEmpty() || direction == null) {
            return null;
        }
        BigDecimal qty = parse(text);
        return direction == Direction.DECREASE ? qty.negate() : qty;
    }

    @FXML
    private void onSave() {
        form.clearAll();
        StockAdjustmentReason reason = reasonBox.getValue();
        Direction direction = directionBox.getValue();
        String qtyText = text(quantityField.getText());
        LocalDate date = readDate();
        String note = text(noteField.getText());

        if (reason == null) {
            form.error(reasonBox, I18n.get("msg.required_field", I18n.get("stock.adjust.reason")));
        }
        if (direction == null) {
            form.error(directionBox, I18n.get("msg.required_field", I18n.get("stock.adjust.direction")));
        } else if (reason != null && reason != StockAdjustmentReason.CORRECTION && direction == Direction.INCREASE) {
            form.error(directionBox, I18n.get("stock.adjust.direction.fixed_hint"));
        }

        BigDecimal qty = null;
        if (qtyText.isEmpty()) {
            form.error(quantityField, I18n.get("msg.required_field", I18n.get("label.quantity")));
        } else {
            qty = parse(qtyText);
            if (qty.signum() <= 0) {
                form.error(quantityField, I18n.get("stock.adjust.error.zero"));
            } else if (direction == Direction.DECREASE && currentStock != null && qty.compareTo(currentStock) > 0) {
                form.error(quantityField, insufficientText(currentStock));
            }
        }

        if (date == null) {
            boolean typed = datePicker.getEditor().getText() != null && !datePicker.getEditor().getText().isBlank();
            form.error(datePicker, typed ? I18n.get("stock.adjust.error.date")
                    : I18n.get("msg.required_field", I18n.get("label.date")));
        } else if (date.isAfter(LocalDate.now())) {
            form.error(datePicker, I18n.get("stock.adjust.error.future_date"));
        }

        if (reason == StockAdjustmentReason.CORRECTION && note.isEmpty()) {
            form.error(noteField, I18n.get("stock.adjust.error.note_required"));
        }

        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirmSave()) {
            return;
        }

        BigDecimal signed = direction == Direction.DECREASE ? qty.negate() : qty;
        try {
            StockLedgerService.Result result = adjustmentService.adjust(product.getId(), date, reason, signed, note);
            switch (result.status()) {
                case DONE -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("stock.adjust.done"));
                }
                case INSUFFICIENT_STOCK -> {
                    // Stock went out since the form opened, or the date is earlier: show today's stock again;
                    // the error shows what was there on that date
                    currentStock = ledgerService.currentStock(product.getId());
                    showStock();
                    form.error(quantityField, I18n.get("stock.adjust.error.insufficient_on",
                            UnitUtil.quantityText(result.available(), product)));
                    form.focusFirstError();
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save stock adjustment", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onCancel() {
        close();
    }

    private String insufficientText(BigDecimal available) {
        return I18n.get("stock.adjust.error.insufficient",
                UnitUtil.quantityText(available == null ? BigDecimal.ZERO : available, product));
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
        ((Stage) quantityField.getScene().getWindow()).close();
    }

    /** Text accepted by {@link InputFilters#decimal}; "5." and "." are read as 5 and 0. */
    private static BigDecimal parse(String text) {
        String t = text.startsWith(".") ? "0" + text : text;
        return new BigDecimal(t.endsWith(".") ? t + "0" : t);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> label) {
        return new StringConverter<>() {
            @Override
            public String toString(T item) {
                return item == null ? "" : label.apply(item);
            }

            @Override
            public T fromString(String text) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
