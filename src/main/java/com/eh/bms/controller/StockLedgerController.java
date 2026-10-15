package com.eh.bms.controller;

import com.eh.bms.model.Product;
import com.eh.bms.model.StockAdjustmentReason;
import com.eh.bms.model.StockLedgerType;
import com.eh.bms.service.StockLedgerService;
import com.eh.bms.service.StockLedgerService.StockRow;
import com.eh.bms.service.StockLedgerService.Summary;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.UnitUtil;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One product's stock ledger (9.4), read-only: stock in / stock out and the stock left after each line.
 * Newest entries first. The figures on top show the current stock, the average cost and the totals in and out.
 * Quantities are in the product's unit (with the secondary unit in brackets when it has one).
 */
public class StockLedgerController {

    private static final Logger LOG = Logger.getLogger(StockLedgerController.class.getName());
    private static final String FXML_PATH = "/fxml/views/stock-ledger.fxml";
    private static final double WINDOW_WIDTH = 1040;
    private static final double WINDOW_HEIGHT = 640;
    private static final double DETAILS_WIDTH = 300;

    @FXML private Label currentStockLabel;
    @FXML private Label averageCostLabel;
    @FXML private Label totalInLabel;
    @FXML private Label totalOutLabel;
    @FXML private StackPane tableHolder;

    private final StockLedgerService service = new StockLedgerService();
    private PaginatedTable<StockRow> table;
    private Product product;

    /**
     * Opens the product's stock ledger window over the main window. The title shows the product name and its
     * conversion, so same-name products in other pack sizes can be told apart. The product's units must be loaded.
     */
    public static void open(Product product) {
        if (product == null || product.getId() == null) {
            return;
        }
        FXMLLoader loader = WindowManager.loader(FXML_PATH);
        Parent root = WindowManager.load(loader, FXML_PATH);
        StockLedgerController ledger = loader.getController();
        ledger.show(product);
        Stage stage = WindowManager.dialog(root, I18n.get("stock.ledger.title", UnitUtil.productName(product)));
        // A list window: resizable and a fixed starting size, not a form that fits its content
        stage.getProperties().remove(WindowManager.AUTO_SIZE);
        stage.setResizable(true);
        stage.setWidth(WINDOW_WIDTH);
        stage.setHeight(WINDOW_HEIGHT);
        stage.showAndWait();
    }

    @FXML
    private void initialize() {
        table = new PaginatedTable<>(this::loadPage);
        TableView<StockRow> view = table.getTableView();
        view.getColumns().add(TableHelper.column("label.date", r -> DateUtil.format(r.date())));
        TableColumn<StockRow, String> details = TableHelper.longTextColumn("stock.ledger.col.details", StockLedgerController::details);
        details.setPrefWidth(DETAILS_WIDTH);
        view.getColumns().add(details);
        view.getColumns().add(styled(TableHelper.moneyColumn("stock.ledger.col.in", r -> quantity(r.quantityIn())), "money-in"));
        view.getColumns().add(styled(TableHelper.moneyColumn("stock.ledger.col.out", r -> quantity(r.quantityOut())), "money-out"));
        view.getColumns().add(TableHelper.moneyColumn("stock.ledger.col.unit_price", r -> MoneyUtil.format(r.unitPrice())));
        view.getColumns().add(TableHelper.moneyColumn("stock.ledger.col.balance", r -> quantity(r.balance())));
        tableHolder.getChildren().setAll(table);
    }

    /** Loads the product's figures and first page; call before showing. */
    private void show(Product p) {
        product = p;
        try {
            Summary s = service.summary(p.getId());
            currentStockLabel.setText(quantity(s.currentStock()));
            averageCostLabel.setText(MoneyUtil.format(s.averageCost()));
            totalInLabel.setText(quantity(s.totalIn()));
            totalOutLabel.setText(quantity(s.totalOut()));
            if (s.currentStock() != null && s.currentStock().signum() < 0) {
                currentStockLabel.getStyleClass().add("money-out");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load stock ledger summary", e);
            Notifier.error(I18n.get("msg.error"));
        }
        table.reload();
    }

    private PageResult<StockRow> loadPage(int offset, int limit) {
        if (product == null) {
            return PageResult.empty();
        }
        try {
            return service.page(product.getId(), offset, limit);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load stock ledger", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
    }

    /** Quantity in the product's unit(s); "" for none (the empty side of in/out). */
    private String quantity(BigDecimal qty) {
        return qty == null || product == null ? "" : UnitUtil.quantityText(qty, product);
    }

    /** What the line is (type, and the reason for an adjustment) in the current language, plus the note. */
    static String details(StockRow r) {
        List<String> parts = new ArrayList<>();
        StockLedgerType type = r.type();
        if (type != null) {
            parts.add(label("stock.type.", type.name()));
        }
        StockAdjustmentReason reason = r.reason();
        if (reason != null) {
            parts.add(label("stock.reason.", reason.name()));
        }
        String note = r.note();
        if (note != null && !note.isBlank()) {
            parts.add(note.trim());
        }
        return parts.isEmpty() ? "—" : String.join(" — ", parts);
    }

    /** Translated label for an enum constant; the constant's name when no key exists yet (a new type). */
    private static String label(String prefix, String name) {
        String key = prefix + name;
        return I18n.getBundle().containsKey(key) ? I18n.get(key) : name;
    }

    private static <T> TableColumn<T, String> styled(TableColumn<T, String> column, String styleClass) {
        column.getStyleClass().add(styleClass);
        return column;
    }

    @FXML
    private void onClose() {
        ((Stage) tableHolder.getScene().getWindow()).close();
    }
}
