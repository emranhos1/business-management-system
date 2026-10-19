package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.ExpenseCategory;
import com.eh.bms.service.ExpenseCategoryService;
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
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Expense categories (13.1, beside the list on the Expenses page): add; edit by the row button (no double-click: there is
 * no view); delete is in the edit form (only for a category no expense uses).
 */
public class ExpenseCategoriesController {

    private static final Logger LOG = Logger.getLogger(ExpenseCategoriesController.class.getName());
    private static final String FORM_FXML = "/fxml/views/expense-category-form.fxml";

    @FXML private Button addButton;
    @FXML private StackPane tableHolder;

    private final ExpenseCategoryService service = new ExpenseCategoryService();
    private PaginatedTable<ExpenseCategory> table;
    /** Runs after a category is saved or deleted (the expense filters list the categories). */
    private Runnable onChange = () -> { };

    @FXML
    private void initialize() {
        PermissionUtil.applyCrud(AppModule.EXPENSE, addButton, null, null);

        table = new PaginatedTable<>(this::loadPage);
        TableView<ExpenseCategory> view = table.getTableView();
        view.getColumns().add(TableHelper.column("expense.category.name", ExpenseCategoriesController::nameText));

        if (UserSession.isLoggedIn() && UserSession.get().can(AppModule.EXPENSE, Action.EDIT)) {
            view.getColumns().add(TableHelper.actionsColumn("label.actions",
                    new RowAction<>(Icons.EDIT, "button.edit", "expense.category.edit_title", "icon-edit", this::openForm)));
        }

        tableHolder.getChildren().setAll(table);
        table.reload();
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Category name in the current language. */
    public static String nameText(ExpenseCategory c) {
        return I18n.pick(c.getName(), c.getNameEn());
    }

    private PageResult<ExpenseCategory> loadPage(int offset, int limit) {
        try {
            return service.page(offset, limit);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load expense categories", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
    }

    @FXML
    private void onAdd() {
        openForm(null);
    }

    /** Add (category = null) or edit popup; the list reloads after a save or delete. */
    private void openForm(ExpenseCategory category) {
        FXMLLoader loader = WindowManager.loader(FORM_FXML);
        Parent root = WindowManager.load(loader, FORM_FXML);
        ExpenseCategoryFormController form = loader.getController();
        if (category != null) {
            form.edit(category);
        }
        WindowManager.dialog(root, I18n.get(category == null ? "expense.category.add_title" : "expense.category.edit_title"))
                .showAndWait();
        if (form.isSaved()) {
            table.refresh();
            onChange.run();
        }
    }
}
