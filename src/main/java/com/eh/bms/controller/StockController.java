package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Product;
import com.eh.bms.service.ProductService;
import com.eh.bms.service.StockLedgerService;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.TableHelper.RowAction;
import com.eh.bms.util.UnitUtil;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stock page, "Current stock" tab (9.3): products with their current stock from the stock ledger,
 * searchable and paginated. Rows below their minimum stock are marked. Each row opens its stock
 * ledger (9.4) and adjusts its stock (9.5) with its row buttons. The low stock (9.6) and
 * stock value (9.7) tabs are included FXML files with their own controllers.
 */
public class StockController {

    private static final Logger LOG = Logger.getLogger(StockController.class.getName());
    /** Marks a row whose stock is below the product's minimum stock. */
    private static final String LOW_ROW = "low-stock-row";
    private static final int SEARCH_MAX = 100;
    /** Wait after the last key before searching, so typing does not query on every key. */
    private static final Duration SEARCH_DELAY = Duration.millis(300);

    @FXML private TextField searchField;
    @FXML private StackPane tableHolder;

    private final StockLedgerService stockService = new StockLedgerService();
    private final ProductService productService = new ProductService();
    private PaginatedTable<Product> table;
    /** Current stock of the products on the shown page (one query per page). */
    private Map<Long, BigDecimal> stocks = Map.of();

    @FXML
    private void initialize() {
        InputFilters.maxLength(searchField, SEARCH_MAX);

        table = new PaginatedTable<>(this::loadPage);
        TableView<Product> view = table.getTableView();
        view.getColumns().add(TableHelper.column("products.name", UnitUtil::productName));
        view.getColumns().add(TableHelper.column("products.category",
                p -> p.getCategory() == null ? "—" : ProductCategoriesController.nameText(p.getCategory())));
        view.getColumns().add(TableHelper.column("products.unit", p -> UnitUtil.name(p.getUnit())));
        view.getColumns().add(stockColumn());
        view.getColumns().add(TableHelper.moneyColumn("products.col.min_stock",
                p -> hasMinimum(p) ? UnitUtil.number(p.getMinStockAlert()) : "—"));
        boolean canView = UserSession.isLoggedIn() && UserSession.get().can(AppModule.STOCK, Action.VIEW);
        // An adjustment adds stock ledger entries, so it needs the create permission
        boolean canAdjust = UserSession.isLoggedIn() && UserSession.get().can(AppModule.STOCK, Action.CREATE);
        List<RowAction<Product>> actions = new ArrayList<>();
        if (canView) {
            actions.add(new RowAction<>(Icons.LEDGER, "button.view", "stock.ledger", "icon-view", StockLedgerController::open));
        }
        if (canAdjust) {
            actions.add(new RowAction<>(Icons.ADJUST, "stock.action.adjust", "stock.adjust", "icon-edit",
                    p -> StockAdjustmentFormController.open(p, this::reload)));
        }
        if (!actions.isEmpty()) {
            view.getColumns().add(actionsColumn(actions));
        }

        // Marks low rows; double-click opens the product's stock ledger (rows keep the shared tooltip)
        TableHelper.rowStyle(view, p -> isLow(p) ? LOW_ROW : null);
        TableHelper.onDoubleClick(view, canView, StockLedgerController::open);

        PauseTransition searchPause = new PauseTransition(SEARCH_DELAY);
        searchPause.setOnFinished(e -> table.reload());
        searchField.textProperty().addListener((obs, old, text) -> searchPause.playFromStart());

        tableHolder.getChildren().setAll(table);
        table.reload();
    }

    /** Current stock in both units ("২ বস্তা (১০০ কেজি)"), right-aligned; red when below the minimum. */
    private TableColumn<Product, String> stockColumn() {
        TableColumn<Product, String> c = TableHelper.moneyColumn("stock.col.current",
                p -> UnitUtil.quantityText(stockOf(p), p));
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                getStyleClass().remove("money-out");
                Product p = getTableRow() == null ? null : getTableRow().getItem();
                if (!empty && p != null && isLow(p)) {
                    getStyleClass().add("money-out");
                }
            }
        });
        return c;
    }

    private BigDecimal stockOf(Product p) {
        return stocks.getOrDefault(p.getId(), BigDecimal.ZERO);
    }

    private static boolean hasMinimum(Product p) {
        return p.getMinStockAlert() != null && p.getMinStockAlert().signum() > 0;
    }

    /** Below its minimum stock; a minimum of 0 (or none) means no alert. */
    private boolean isLow(Product p) {
        return hasMinimum(p) && stockOf(p).compareTo(p.getMinStockAlert()) < 0;
    }

    private PageResult<Product> loadPage(int offset, int limit) {
        try {
            PageResult<Product> page = productService.search(searchField.getText(), offset, limit);
            List<Long> ids = page.items().stream().map(Product::getId).toList();
            stocks = ids.isEmpty() ? Map.of() : stockService.currentStocks(ids);
            return page;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load current stock", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static TableColumn<Product, Product> actionsColumn(List<RowAction<Product>> actions) {
        return TableHelper.actionsColumn("label.actions", actions.toArray(new RowAction[0]));
    }

    /** Reloads the shown page (e.g. after a stock adjustment). */
    public void reload() {
        table.refresh();
    }
}
