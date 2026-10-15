package com.eh.bms.controller;

import com.eh.bms.model.ProductCategory;
import com.eh.bms.service.ProductCategoryService;
import com.eh.bms.service.StockReportService;
import com.eh.bms.service.StockReportService.Valuation;
import com.eh.bms.service.StockReportService.ValueRow;
import com.eh.bms.util.I18n;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.UnitUtil;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;

import java.math.BigDecimal;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stock value (9.7, a tab of the Stock page): each product in stock with stock x weighted average cost,
 * and the grand total on top. Products with no stock are left out. Optional category filter.
 */
public class StockValuationController {

    private static final Logger LOG = Logger.getLogger(StockValuationController.class.getName());

    /** First item is null = all categories. */
    @FXML private ComboBox<ProductCategory> categoryBox;
    @FXML private Label totalLabel;
    @FXML private Label countLabel;
    @FXML private StackPane tableHolder;

    private final StockReportService service = new StockReportService();
    private final ProductCategoryService categoryService = new ProductCategoryService();
    private PaginatedTable<ValueRow> table;
    /** All rows of the current filter; the table shows them one page at a time. */
    private List<ValueRow> rows = List.of();
    /** True while the category list is being filled, so the filter listener does not load rows. */
    private boolean fillingCategories;

    @FXML
    private void initialize() {
        table = new PaginatedTable<>(this::loadPage);
        TableView<ValueRow> view = table.getTableView();
        view.getColumns().add(TableHelper.column("stock.value.col.name", r -> UnitUtil.productName(r.product())));
        view.getColumns().add(TableHelper.column("stock.value.col.category",
                r -> r.product().getCategory() == null ? "—"
                        : ProductCategoriesController.nameText(r.product().getCategory())));
        view.getColumns().add(TableHelper.column("stock.value.col.stock",
                r -> UnitUtil.quantityText(r.stock(), r.product())));
        view.getColumns().add(TableHelper.moneyColumn("stock.value.col.avg_cost",
                r -> MoneyUtil.format(r.averageCost())));
        view.getColumns().add(TableHelper.moneyColumn("stock.value.col.value", r -> MoneyUtil.format(r.value())));

        categoryBox.setCellFactory(list -> new CategoryCell(false));
        categoryBox.setButtonCell(new CategoryCell(true));
        categoryBox.valueProperty().addListener((obs, old, now) -> {
            if (!fillingCategories) {
                loadRows();
            }
        });
        loadCategories(null);

        tableHolder.getChildren().setAll(table);
        loadRows();
    }

    /** Reloads categories and values (stock changes on the other tabs); keeps the chosen category if it still exists. */
    @FXML
    public void refresh() {
        Long chosen = categoryBox.getValue() == null ? null : categoryBox.getValue().getId();
        loadCategories(chosen);
        loadRows();
    }

    /** Fills the category list ("All categories" first) and selects {@code chosenId} if it still exists. */
    private void loadCategories(Long chosenId) {
        fillingCategories = true;
        try {
            categoryBox.setValue(null);
            categoryBox.getItems().clear();
            categoryBox.getItems().add(null);
            categoryBox.getItems().addAll(categoryService.all());
            if (chosenId != null) {
                categoryBox.getItems().stream()
                        .filter(c -> c != null && chosenId.equals(c.getId()))
                        .findFirst()
                        .ifPresent(categoryBox::setValue);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load product categories", e);
            Notifier.error(I18n.get("msg.error"));
        } finally {
            fillingCategories = false;
        }
    }

    private void loadRows() {
        ProductCategory category = categoryBox.getValue();
        Valuation valuation;
        try {
            valuation = service.valuation(category == null ? null : category.getId());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load stock value", e);
            Notifier.error(I18n.get("msg.error"));
            valuation = new Valuation(List.of(), BigDecimal.ZERO);
        }
        rows = valuation.rows();
        totalLabel.setText(MoneyUtil.format(valuation.total()));
        countLabel.setText(I18n.digits(String.valueOf(rows.size())));
        table.reload();
    }

    private PageResult<ValueRow> loadPage(int offset, int limit) {
        int from = Math.min(offset, rows.size());
        int to = Math.min(from + limit, rows.size());
        return new PageResult<>(rows.subList(from, to), rows.size());
    }

    /** Shows a category by name in the current language, and null as "All categories". */
    private static final class CategoryCell extends ListCell<ProductCategory> {
        /** The combo box's own cell: it is "empty" when the value is null, which means all categories. */
        private final boolean buttonCell;

        CategoryCell(boolean buttonCell) {
            this.buttonCell = buttonCell;
        }

        @Override
        protected void updateItem(ProductCategory category, boolean empty) {
            super.updateItem(category, empty);
            if (empty) {
                setText(buttonCell ? I18n.get("stock.value.all_categories") : null);
            } else {
                setText(category == null ? I18n.get("stock.value.all_categories")
                        : ProductCategoriesController.nameText(category));
            }
        }
    }
}
