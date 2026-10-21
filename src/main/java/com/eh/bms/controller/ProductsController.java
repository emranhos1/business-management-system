package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Product;
import com.eh.bms.service.ProductCategoryService;
import com.eh.bms.service.ProductService;
import com.eh.bms.service.StockLedgerService;
import com.eh.bms.service.UnitService;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.PriceUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.PermissionUtil;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.TableHelper.RowAction;
import com.eh.bms.util.TableHelper.RowTip;
import com.eh.bms.util.TableHelper.TipLine;
import com.eh.bms.util.UnitUtil;
import com.eh.bms.util.WindowManager;
import com.eh.bms.util.InputFilters;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.util.Duration;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Products page: products list on the left (8.2: add; row buttons edit and copy; delete in the edit form),
 * tabs on the right for product categories (8.1) and units (8.4), each with its own FXML + controller.
 */
public class ProductsController {

    private static final Logger LOG = Logger.getLogger(ProductsController.class.getName());
    private static final String FORM_FXML = "/fxml/views/product-form.fxml";
    /** Used when app_config has no low_stock_default (same fallback as Settings); fills a new product's form. */
    static final int LOW_STOCK_FALLBACK = 10;

    @FXML private Button addButton;
    @FXML private TextField searchField;
    /** Products list goes here. */
    @FXML private StackPane productsHolder;
    /** Controller of the included categories list (fx:id="categories"). */
    @FXML private ProductCategoriesController categoriesController;
    /** Controller of the included units list (fx:id="units"). */
    @FXML private UnitsController unitsController;

    private final ProductService service = new ProductService();
    private final ProductCategoryService categoryService = new ProductCategoryService();
    private final UnitService unitService = new UnitService();
    private final StockLedgerService stockService = new StockLedgerService();
    private PaginatedTable<Product> table;
    /** Current stock of the shown page's products (for the row tooltip). */
    private Map<Long, BigDecimal> stocks = Map.of();

    @FXML
    private void initialize() {
        PermissionUtil.applyCrud(AppModule.PRODUCT, addButton, null, null);

        table = new PaginatedTable<>(this::loadPage);
        TableView<Product> view = table.getTableView();
        // Only the main columns; hovering a row shows every detail (category, unit, prices, ...)
        view.getColumns().add(TableHelper.column("products.col.name", ProductsController::nameText));
        view.getColumns().add(TableHelper.column("products.col.sku", p -> p.getSku() == null ? "—" : p.getSku()));
        view.getColumns().add(TableHelper.column("products.col.conversion", p -> {
            String conversion = UnitUtil.conversionText(p);
            return conversion.isEmpty() ? "—" : conversion;
        }));
        view.getColumns().add(TableHelper.column("products.col.min_stock",
                p -> p.getMinStockAlert() == null ? "—" : quantityText(p.getMinStockAlert())));
        TableHelper.rowTooltip(view, this::details);
        // Row buttons: edit (delete is inside the edit form); copy opens the add form filled with that product
        boolean canEdit = UserSession.isLoggedIn() && UserSession.get().can(AppModule.PRODUCT, Action.EDIT);
        boolean canCreate = UserSession.isLoggedIn() && UserSession.get().can(AppModule.PRODUCT, Action.CREATE);
        List<RowAction<Product>> actions = new ArrayList<>();
        if (canEdit) {
            actions.add(new RowAction<>(Icons.EDIT, "button.edit", "products.form.edit_title", "icon-edit",
                    product -> openForm(product, false)));
        }
        if (canCreate) {
            actions.add(new RowAction<>(Icons.COPY, "products.copy", "products.form.copy_title", "icon-copy",
                    product -> openForm(product, true)));
        }
        // Label print needs no permission beyond seeing the page: it only reads the product
        actions.add(new RowAction<>(Icons.PRINT, "products.label", "products.label_tip", "icon-print",
                ProductLabelController::open));
        if (!actions.isEmpty()) {
            view.getColumns().add(actionsColumn(actions));
        }

        // Search by name or code while typing, after a short pause
        PauseTransition pause = new PauseTransition(Duration.millis(300));
        pause.setOnFinished(e -> table.reload());
        searchField.textProperty().addListener((obs, old, text) -> pause.playFromStart());

        productsHolder.getChildren().setAll(table);
        table.reload();

        // A renamed category or unit changes what the products list shows
        categoriesController.setOnChange(table::refresh);
        unitsController.setOnChange(table::refresh);
    }

    /** Product name in the current language. */
    public static String nameText(Product product) {
        return I18n.pick(product.getName(), product.getNameEn());
    }

    /** A price, or "—" when not set. */
    private static String priceText(BigDecimal price) {
        return price == null ? "—" : MoneyUtil.format(price);
    }

    /** Every detail of a product for the row tooltip: the name as the title, prices as "বস্তা (খুচরা)" bold. */
    private RowTip details(Product p) {
        List<TipLine> lines = new ArrayList<>();
        lines.add(line("products.category",
                p.getCategory() == null ? "—" : ProductCategoriesController.nameText(p.getCategory())));
        lines.add(line("products.col.sku", p.getSku() == null ? "—" : p.getSku()));
        String unit = UnitUtil.name(p.getUnit());
        lines.add(line("products.unit", unit));
        String conversion = UnitUtil.conversionText(p);
        if (!conversion.isEmpty()) {
            lines.add(line("products.col.conversion", conversion));
        }
        lines.add(price("products.tip.retail", unit, p.getRetailPrice()));
        lines.add(price("products.tip.wholesale", unit, p.getWholesalePrice()));
        if (p.getSecondaryUnit() != null) {
            String secondary = UnitUtil.name(p.getSecondaryUnit());
            lines.add(price("products.tip.retail", secondary, PriceUtil.price(p, false, true)));
            lines.add(price("products.tip.wholesale", secondary, PriceUtil.price(p, true, true)));
        }
        BigDecimal stock = stocks.getOrDefault(p.getId(), BigDecimal.ZERO);
        lines.add(new TipLine(I18n.get("stock.col.current"), UnitUtil.quantityText(stock, p), true));
        lines.add(line("products.col.min_stock",
                p.getMinStockAlert() == null ? "—" : quantityText(p.getMinStockAlert())));
        return new RowTip(nameText(p), lines);
    }

    private static TipLine line(String labelKey, String value) {
        return new TipLine(I18n.get(labelKey), value, false);
    }

    /** A price line, e.g. "বস্তা (খুচরা) : ৳ ১,২০০.০০", the amount bold. */
    private static TipLine price(String labelKey, String unit, BigDecimal value) {
        return new TipLine(I18n.get(labelKey, unit), priceText(value), value != null);
    }

    /** A quantity without trailing zeros, in Bangla digits in Bangla mode (display only). */
    static String quantityText(BigDecimal value) {
        return UnitUtil.number(value);
    }

    private PageResult<Product> loadPage(int offset, int limit) {
        try {
            PageResult<Product> page = service.search(InputFilters.toAsciiDigits(searchField.getText()), offset, limit);
            List<Long> ids = page.items().stream().map(Product::getId).toList();
            stocks = ids.isEmpty() ? Map.of() : stockService.currentStocks(ids);
            return page;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load products", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
    }

    @FXML
    private void onClearSearch() {
        searchField.clear();
    }

    @FXML
    private void onAdd() {
        try {
            if (categoryService.all().isEmpty()) {
                Notifier.error(I18n.get("products.error.no_category"));
                return;
            }
            if (unitService.all().isEmpty()) {
                Notifier.error(I18n.get("products.error.no_unit"));
                return;
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load product categories or units", e);
            Notifier.error(I18n.get("msg.error"));
            return;
        }
        openForm(null, false);
    }

    @SuppressWarnings("unchecked")
    private static TableColumn<Product, Product> actionsColumn(List<RowAction<Product>> actions) {
        return TableHelper.actionsColumn("label.actions", actions.toArray(new RowAction[0]));
    }

    /**
     * Add (product = null), edit, or add-as-copy (copy = true) popup; the lists reload after a save or delete
     * (the categories and units lists show how many products use each).
     */
    private void openForm(Product product, boolean copy) {
        FXMLLoader loader = WindowManager.loader(FORM_FXML);
        Parent root = WindowManager.load(loader, FORM_FXML);
        ProductFormController form = loader.getController();
        String titleKey = "products.form.add_title";
        if (product != null && copy) {
            form.copyFrom(product);
            titleKey = "products.form.copy_title";
        } else if (product != null) {
            form.edit(product);
            titleKey = "products.form.edit_title";
        } else {
            form.fillNextCode(); // a new product: the next automatic code, changeable
        }
        WindowManager.dialog(root, I18n.get(titleKey)).showAndWait();
        if (form.isSaved()) {
            table.refresh();
            categoriesController.refresh();
            unitsController.refresh();
        }
    }
}
