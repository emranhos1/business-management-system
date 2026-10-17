package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Expense;
import com.eh.bms.model.ExpenseCategory;
import com.eh.bms.repository.ExpenseRepository.Filter;
import com.eh.bms.service.ExpenseCategoryService;
import com.eh.bms.service.ExpenseService;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.DateRangePicker;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.PermissionUtil;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.TableHelper.RowAction;
import com.eh.bms.util.WindowManager;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Expenses list (13.2, 13.3): newest first; search by note, dates and category, with the total and count of what
 * the filters find on top. "New expense" opens the form; the row button edits (delete is in the edit form).
 */
public class ExpensesListController {

    private static final Logger LOG = Logger.getLogger(ExpensesListController.class.getName());
    private static final String FORM_FXML = "/fxml/views/expense-form.fxml";

    @FXML private Label totalLabel;
    @FXML private Label countLabel;
    @FXML private TextField searchField;
    @FXML private DateRangePicker dateRange;
    /** First item is null = all categories. */
    @FXML private ComboBox<ExpenseCategory> categoryBox;
    @FXML private Button addButton;
    @FXML private StackPane tableHolder;

    private final ExpenseService service = new ExpenseService();
    private final ExpenseCategoryService categoryService = new ExpenseCategoryService();
    private PaginatedTable<Expense> table;
    /** True while the category list is being filled, so the filter listener does not search. */
    private boolean fillingCategories;

    @FXML
    private void initialize() {
        PermissionUtil.applyCrud(AppModule.EXPENSE, addButton, null, null);

        table = new PaginatedTable<>(this::loadPage);
        TableView<Expense> view = table.getTableView();
        view.getColumns().add(TableHelper.column("label.date", e -> DateUtil.format(e.getDate())));
        view.getColumns().add(TableHelper.column("expense.category", e -> ExpenseCategoriesController.nameText(e.getCategory())));
        view.getColumns().add(TableHelper.moneyColumn("label.amount", e -> MoneyUtil.format(e.getAmount())));
        view.getColumns().add(TableHelper.column("expense.account",
                e -> I18n.pick(e.getAccount().getName(), e.getAccount().getNameEn())));
        view.getColumns().add(TableHelper.longTextColumn("label.note", e -> e.getNote() == null ? "—" : e.getNote()));
        if (UserSession.isLoggedIn() && UserSession.get().can(AppModule.EXPENSE, Action.EDIT)) {
            view.getColumns().add(TableHelper.actionsColumn("label.actions",
                    new RowAction<>(Icons.EDIT, "button.edit", "expense.edit_title", "icon-edit", this::openForm)));
        }

        categoryBox.setCellFactory(list -> new CategoryCell(false));
        categoryBox.setButtonCell(new CategoryCell(true));
        categoryBox.valueProperty().addListener((obs, old, now) -> {
            if (!fillingCategories) {
                reload();
            }
        });
        loadCategories(null);

        // Any filter change searches again, after a short pause while typing
        PauseTransition pause = new PauseTransition(Duration.millis(300));
        pause.setOnFinished(e -> reload());
        searchField.textProperty().addListener((obs, old, now) -> pause.playFromStart());
        dateRange.setOnChange(this::reload);

        tableHolder.getChildren().setAll(table);
        reload();
    }

    /** Reloads from page 1, with the total (e.g. when the tab opens). */
    public void reload() {
        table.reload();
        showTotal();
    }

    /** Fills the category filter again (categories changed on their tab); keeps the chosen one if it still exists. */
    public void reloadCategories() {
        Long chosen = categoryBox.getValue() == null ? null : categoryBox.getValue().getId();
        loadCategories(chosen);
        reload();
    }

    /** Empties every filter (search, dates, category) and shows all expenses again. */
    @FXML
    private void onClearFilters() {
        searchField.clear();
        categoryBox.setValue(null);
        dateRange.clear();
    }

    @FXML
    private void onAdd() {
        openForm(null);
    }

    /** Add (expense = null) or edit popup; the list and total reload after a save or delete. */
    private void openForm(Expense expense) {
        FXMLLoader loader = WindowManager.loader(FORM_FXML);
        Parent root = WindowManager.load(loader, FORM_FXML);
        ExpenseFormController form = loader.getController();
        if (!form.setUp(expense)) {
            return;
        }
        WindowManager.dialog(root, I18n.get(expense == null ? "expense.add_title" : "expense.edit_title")).showAndWait();
        if (form.isSaved()) {
            table.refresh();
            showTotal();
        }
    }

    private Filter filter() {
        ExpenseCategory category = categoryBox.getValue();
        return new Filter(searchField.getText(), dateRange.getFrom(), dateRange.getTo(),
                category == null ? null : category.getId());
    }

    private PageResult<Expense> loadPage(int offset, int limit) {
        try {
            PageResult<Expense> page = service.page(filter(), offset, limit);
            countLabel.setText(I18n.digits(String.valueOf(page.total())));
            return page;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load expenses", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
    }

    private void showTotal() {
        try {
            totalLabel.setText(MoneyUtil.format(service.total(filter())));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load expense total", e);
            totalLabel.setText(MoneyUtil.format(BigDecimal.ZERO));
        }
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
            LOG.log(Level.SEVERE, "Could not load expense categories", e);
            Notifier.error(I18n.get("msg.error"));
        } finally {
            fillingCategories = false;
        }
    }

    /** Shows a category by name in the current language, and null as "All categories". */
    private static final class CategoryCell extends ListCell<ExpenseCategory> {
        /** The combo box's own cell: it is "empty" when the value is null, which means all categories. */
        private final boolean buttonCell;

        CategoryCell(boolean buttonCell) {
            this.buttonCell = buttonCell;
        }

        @Override
        protected void updateItem(ExpenseCategory category, boolean empty) {
            super.updateItem(category, empty);
            if (empty) {
                setText(buttonCell ? I18n.get("expense.all_categories") : null);
            } else {
                setText(category == null ? I18n.get("expense.all_categories")
                        : ExpenseCategoriesController.nameText(category));
            }
        }
    }
}
