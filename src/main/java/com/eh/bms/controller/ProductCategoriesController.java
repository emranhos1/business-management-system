package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.ProductCategory;
import com.eh.bms.service.ProductCategoryService;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.PermissionUtil;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Product categories list (right side of the Products page, 8.1): add, edit (button or double-click), delete. */
public class ProductCategoriesController {

    private static final Logger LOG = Logger.getLogger(ProductCategoriesController.class.getName());
    private static final String FORM_FXML = "/fxml/views/product-category-form.fxml";

    @FXML private Button addButton;
    @FXML private Button editButton;
    @FXML private Button deleteButton;
    @FXML private StackPane tableHolder;

    private final ProductCategoryService service = new ProductCategoryService();
    private PaginatedTable<ProductCategory> table;
    /** Number of products in each category on the shown page. */
    private Map<Long, Long> productCounts = Map.of();
    /** Runs after a category is saved or deleted (the products list shows category names). */
    private Runnable onChange = () -> { };

    @FXML
    private void initialize() {
        PermissionUtil.applyCrud(AppModule.PRODUCT, addButton, editButton, deleteButton);

        table = new PaginatedTable<>(this::loadPage);
        TableView<ProductCategory> view = table.getTableView();
        view.getColumns().add(TableHelper.column("categories.name", ProductCategoriesController::nameText));
        view.getColumns().add(TableHelper.column("categories.col.products",
                c -> I18n.digits(String.valueOf(productCounts.getOrDefault(c.getId(), 0L)))));

        TableHelper.needSelection(view, editButton, deleteButton);
        boolean canEdit = UserSession.isLoggedIn() && UserSession.get().can(AppModule.PRODUCT, Action.EDIT);
        TableHelper.onDoubleClick(view, canEdit, this::openForm);

        tableHolder.getChildren().setAll(table);
        table.reload();
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Reloads the shown page (e.g. after a product is added or deleted, so the product counts are current). */
    public void refresh() {
        table.refresh();
    }

    /** Category name in the current language. */
    public static String nameText(ProductCategory category) {
        return I18n.pick(category.getName(), category.getNameEn());
    }

    private PageResult<ProductCategory> loadPage(int offset, int limit) {
        try {
            PageResult<ProductCategory> page = service.page(offset, limit);
            productCounts = service.productCounts(page.items());
            return page;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load product categories", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
    }

    @FXML
    private void onAdd() {
        openForm(null);
    }

    @FXML
    private void onEdit() {
        ProductCategory selected = table.getTableView().getSelectionModel().getSelectedItem();
        if (selected != null) {
            openForm(selected);
        }
    }

    @FXML
    private void onDelete() {
        ProductCategory selected = table.getTableView().getSelectionModel().getSelectedItem();
        if (selected == null
                || !Dialogs.confirmDelete(I18n.get("categories.confirm_delete", nameText(selected)))) {
            return;
        }
        try {
            switch (service.delete(selected.getId())) {
                case DELETED -> {
                    Notifier.success(I18n.get("msg.deleted"));
                    onChange.run();
                }
                case IN_USE -> Notifier.error(I18n.get("categories.error.in_use"));
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
            table.refresh();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not delete product category", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    /** Add (category = null) or edit popup; the lists reload after a save. */
    private void openForm(ProductCategory category) {
        FXMLLoader loader = WindowManager.loader(FORM_FXML);
        Parent root = WindowManager.load(loader, FORM_FXML);
        ProductCategoryFormController form = loader.getController();
        if (category != null) {
            form.edit(category);
        }
        WindowManager.dialog(root,
                        I18n.get(category == null ? "categories.form.add_title" : "categories.form.edit_title"))
                .showAndWait();
        if (form.isSaved()) {
            table.refresh();
            onChange.run();
        }
    }
}
