package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Product;
import com.eh.bms.service.ProductCategoryService;
import com.eh.bms.service.ProductService;
import com.eh.bms.service.UnitService;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.PermissionUtil;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.TableHelper.RowAction;
import com.eh.bms.util.UnitUtil;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
    /** Products list goes here. */
    @FXML private StackPane productsHolder;
    /** Controller of the included categories list (fx:id="categories"). */
    @FXML private ProductCategoriesController categoriesController;
    /** Controller of the included units list (fx:id="units"). */
    @FXML private UnitsController unitsController;

    private final ProductService service = new ProductService();
    private final ProductCategoryService categoryService = new ProductCategoryService();
    private final UnitService unitService = new UnitService();
    private PaginatedTable<Product> table;

    @FXML
    private void initialize() {
        PermissionUtil.applyCrud(AppModule.PRODUCT, addButton, null, null);

        table = new PaginatedTable<>(this::loadPage);
        TableView<Product> view = table.getTableView();
        view.getColumns().add(TableHelper.column("products.col.name", ProductsController::nameText));
        view.getColumns().add(TableHelper.column("products.category",
                p -> p.getCategory() == null ? "—" : ProductCategoriesController.nameText(p.getCategory())));
        view.getColumns().add(TableHelper.column("products.col.sku", p -> p.getSku() == null ? "—" : p.getSku()));
        view.getColumns().add(TableHelper.column("products.unit", p -> UnitUtil.name(p.getUnit())));
        view.getColumns().add(TableHelper.column("products.col.conversion", p -> {
            String conversion = UnitUtil.conversionText(p);
            return conversion.isEmpty() ? "—" : conversion;
        }));
        view.getColumns().add(TableHelper.column("products.col.min_stock",
                p -> p.getMinStockAlert() == null ? "—" : quantityText(p.getMinStockAlert())));
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
        if (!actions.isEmpty()) {
            view.getColumns().add(actionsColumn(actions));
        }
        TableHelper.onDoubleClick(view, canEdit, product -> openForm(product, false));

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

    /** A quantity without trailing zeros, in Bangla digits in Bangla mode (display only). */
    static String quantityText(BigDecimal value) {
        return UnitUtil.number(value);
    }

    private PageResult<Product> loadPage(int offset, int limit) {
        try {
            return service.page(offset, limit);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load products", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
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
        }
        WindowManager.dialog(root, I18n.get(titleKey)).showAndWait();
        if (form.isSaved()) {
            table.refresh();
            categoriesController.refresh();
            unitsController.refresh();
        }
    }
}
