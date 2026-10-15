package com.eh.bms.controller;

import com.eh.bms.model.AccountType;
import com.eh.bms.model.AppModule;
import com.eh.bms.service.AccountTypeService;
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

import java.util.logging.Level;
import java.util.logging.Logger;

/** Account types list (right side of the Accounts page): add, edit (button or double-click), delete. */
public class AccountTypesController {

    private static final Logger LOG = Logger.getLogger(AccountTypesController.class.getName());
    private static final String FORM_FXML = "/fxml/views/account-type-form.fxml";

    @FXML private Button addButton;
    @FXML private Button editButton;
    @FXML private Button deleteButton;
    @FXML private StackPane tableHolder;

    private final AccountTypeService service = new AccountTypeService();
    private PaginatedTable<AccountType> table;
    /** Runs after a type is saved or deleted (the accounts list shows type names). */
    private Runnable onChange = () -> { };

    @FXML
    private void initialize() {
        PermissionUtil.applyCrud(AppModule.ACCOUNT, addButton, editButton, deleteButton);

        table = new PaginatedTable<>(this::loadPage);
        TableView<AccountType> view = table.getTableView();
        view.getColumns().add(TableHelper.column("types.name", AccountFormController::typeText));
        view.getColumns().add(TableHelper.column("types.rule", t -> AccountTypeFormController.ruleText(t.getNumberRule())));

        TableHelper.needSelection(view, editButton, deleteButton);
        boolean canEdit = UserSession.isLoggedIn() && UserSession.get().can(AppModule.ACCOUNT, Action.EDIT);
        TableHelper.onDoubleClick(view, canEdit, this::openForm);

        tableHolder.getChildren().setAll(table);
        table.reload();
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    private PageResult<AccountType> loadPage(int offset, int limit) {
        try {
            return service.page(offset, limit);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load account types", e);
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
        AccountType selected = table.getTableView().getSelectionModel().getSelectedItem();
        if (selected != null) {
            openForm(selected);
        }
    }

    @FXML
    private void onDelete() {
        AccountType selected = table.getTableView().getSelectionModel().getSelectedItem();
        if (selected == null
                || !Dialogs.confirmDelete(I18n.get("types.confirm_delete", AccountFormController.typeText(selected)))) {
            return;
        }
        try {
            switch (service.delete(selected.getId())) {
                case DELETED -> {
                    Notifier.success(I18n.get("msg.deleted"));
                    onChange.run();
                }
                case IN_USE -> Notifier.error(I18n.get("types.error.in_use"));
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
            table.refresh();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not delete account type", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    /** Add (type = null) or edit popup; the lists reload after a save. */
    private void openForm(AccountType type) {
        FXMLLoader loader = WindowManager.loader(FORM_FXML);
        Parent root = WindowManager.load(loader, FORM_FXML);
        AccountTypeFormController form = loader.getController();
        if (type != null) {
            form.edit(type);
        }
        WindowManager.dialog(root, I18n.get(type == null ? "types.form.add_title" : "types.form.edit_title"))
                .showAndWait();
        if (form.isSaved()) {
            table.refresh();
            onChange.run();
        }
    }
}
