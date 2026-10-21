package com.eh.bms.controller;

import com.eh.bms.model.Product;
import com.eh.bms.util.CodeImage;
import com.eh.bms.util.CodeImage.Kind;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.MemoPaper;
import com.eh.bms.util.NodePrinter;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.util.StringConverter;

/**
 * Product label print (20.4): the product's SKU as a barcode or a QR code, a number of copies, a preview of the
 * sheet, then Print (A4 paper, three labels on a row). A product with no SKU gets no label.
 */
public class ProductLabelController {

    private static final String FXML = "/fxml/views/product-label.fxml";
    private static final int COPIES_DIGITS = 3;

    @FXML private ComboBox<Kind> kindBox;
    @FXML private TextField copiesField;
    @FXML private Label errorLabel;
    @FXML private ScrollPane previewPane;

    private Product product;

    /** Opens the label window of a product; says so instead when it has no SKU. */
    public static void open(Product product) {
        if (product.getSku() == null || product.getSku().isBlank()) {
            Notifier.error(I18n.get("label.no_sku"));
            return;
        }
        FXMLLoader loader = WindowManager.loader(FXML);
        Parent root = WindowManager.load(loader, FXML);
        ProductLabelController controller = loader.getController();
        controller.product = product;
        controller.show();
        Stage stage = WindowManager.dialog(root, I18n.get("label.title", ProductsController.nameText(product)));
        WindowManager.sizeListWindow(stage, 820, 640);
        stage.showAndWait();
    }

    @FXML
    private void initialize() {
        InputFilters.digits(copiesField, COPIES_DIGITS);
        kindBox.getItems().setAll(Kind.values());
        kindBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Kind k) {
                return k == null ? "" : I18n.get("label.kind." + k.name());
            }

            @Override
            public Kind fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        kindBox.setValue(Kind.BARCODE);
        copiesField.setText("1");
        kindBox.valueProperty().addListener((obs, old, now) -> refresh());
        copiesField.textProperty().addListener((obs, old, now) -> refresh());
    }

    private void show() {
        refresh();
    }

    /** The label count typed, 1..{@value ProductLabelSheet#MAX_COPIES}; 0 when empty or too big. */
    private int copies() {
        String text = copiesField.getText();
        if (text == null || text.isBlank()) {
            return 0;
        }
        int n = Integer.parseInt(text);
        return n < 1 || n > ProductLabelSheet.MAX_COPIES ? 0 : n;
    }

    /** Checks the choices; the message under them, or "" when the labels can be made. */
    private String problem() {
        if (kindBox.getValue() == Kind.BARCODE && !CodeImage.fitsBarcode(product.getSku())) {
            return I18n.get("label.bad_sku");
        }
        if (copies() == 0) {
            return I18n.get("label.bad_copies", ProductLabelSheet.MAX_COPIES);
        }
        return "";
    }

    /** Draws the sheet again for the kind and the number of copies now chosen. */
    private void refresh() {
        if (product == null) {
            return;
        }
        String problem = problem();
        errorLabel.setText(problem);
        errorLabel.setVisible(!problem.isEmpty());
        errorLabel.setManaged(!problem.isEmpty());
        previewPane.setContent(problem.isEmpty()
                ? ProductLabelSheet.build(product, kindBox.getValue(), copies()) : null);
    }

    @FXML
    private void onPrint() {
        if (!problem().isEmpty()) {
            return;
        }
        // A fresh copy for the printer, so the preview is never scaled or cut
        NodePrinter.Status status = NodePrinter.print(
                ProductLabelSheet.build(product, kindBox.getValue(), copies()), MemoPaper.A4, window());
        switch (status) {
            case DONE -> {
                Notifier.success(I18n.get("label.printed"));
                window().close();
            }
            case NO_PRINTER -> Notifier.error(I18n.get("memo.error.no_printer"));
            case FAILED -> Notifier.error(I18n.get("memo.error.print"));
            case CANCELLED -> {
                // The user closed the printer dialog; the window stays open
            }
        }
    }

    @FXML
    private void onClose() {
        window().close();
    }

    private Stage window() {
        return (Stage) previewPane.getScene().getWindow();
    }
}
