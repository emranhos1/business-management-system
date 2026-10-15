package com.eh.bms.controller;

import com.eh.bms.service.StockReportService;
import com.eh.bms.service.StockReportService.LowStockRow;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.UnitUtil;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Low stock list (9.6, a tab of the Stock page): products whose stock is below their minimum stock,
 * biggest shortfall first. Products with minimum stock 0 have no alert and never show here.
 */
public class LowStockController {

    private static final Logger LOG = Logger.getLogger(LowStockController.class.getName());

    @FXML private Label countLabel;
    @FXML private StackPane tableHolder;

    private final StockReportService service = new StockReportService();
    private PaginatedTable<LowStockRow> table;
    /** All low-stock rows; the table shows them one page at a time. */
    private List<LowStockRow> rows = List.of();

    @FXML
    private void initialize() {
        table = new PaginatedTable<>(this::loadPage);
        TableView<LowStockRow> view = table.getTableView();
        view.getColumns().add(TableHelper.column("stock.low.col.name", r -> UnitUtil.productName(r.product())));
        view.getColumns().add(TableHelper.column("stock.low.col.category",
                r -> r.product().getCategory() == null ? "—"
                        : ProductCategoriesController.nameText(r.product().getCategory())));
        view.getColumns().add(TableHelper.column("stock.low.col.stock",
                r -> UnitUtil.quantityText(r.stock(), r.product())));
        view.getColumns().add(TableHelper.column("stock.low.col.min",
                r -> UnitUtil.quantityText(r.minStock(), r.product())));
        view.getColumns().add(TableHelper.column("stock.low.col.shortfall",
                r -> UnitUtil.quantityText(r.shortfall(), r.product())));

        tableHolder.getChildren().setAll(table);
        refresh();
    }

    /** Reloads the list (stock changes on the other tabs). */
    @FXML
    public void refresh() {
        try {
            rows = service.lowStock();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load low stock list", e);
            Notifier.error(I18n.get("msg.error"));
            rows = List.of();
        }
        countLabel.setText(I18n.get("stock.low.count", I18n.digits(String.valueOf(rows.size()))));
        table.reload();
    }

    private PageResult<LowStockRow> loadPage(int offset, int limit) {
        int from = Math.min(offset, rows.size());
        int to = Math.min(from + limit, rows.size());
        return new PageResult<>(rows.subList(from, to), rows.size());
    }
}
