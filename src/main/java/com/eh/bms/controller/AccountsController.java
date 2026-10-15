package com.eh.bms.controller;

import com.eh.bms.model.Account;
import com.eh.bms.model.AppModule;
import com.eh.bms.service.AccountLedgerService;
import com.eh.bms.service.AccountService;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
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
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Accounts list (left side of the Accounts page): add, edit (button or double-click), delete,
 * current balance (7.3), ledger (7.2) and transfer (7.4). Row buttons: edit, ledger; delete is in the edit form.
 */
public class AccountsController {

    private static final Logger LOG = Logger.getLogger(AccountsController.class.getName());
    private static final double LEDGER_WIDTH = 960;
    private static final double LEDGER_HEIGHT = 640;
    private static final String FORM_FXML = "/fxml/views/account-form.fxml";
    private static final String LEDGER_FXML = "/fxml/views/account-ledger.fxml";
    private static final String TRANSFER_FXML = "/fxml/views/transfer-form.fxml";

    @FXML private Button addButton;
    @FXML private Button transferButton;
    @FXML private StackPane tableHolder;
    /** Controller of the included account types list (fx:id="types"). */
    @FXML private AccountTypesController typesController;

    private final AccountService service = new AccountService();
    private final AccountLedgerService ledgerService = new AccountLedgerService();
    private PaginatedTable<Account> table;
    /** Current balance of the accounts on the shown page (7.3). */
    private Map<Long, BigDecimal> balances = Map.of();

    @FXML
    private void initialize() {
        PermissionUtil.applyCrud(AppModule.ACCOUNT, addButton, null, null);
        // A transfer adds ledger entries, so it needs the create permission
        PermissionUtil.require(transferButton, AppModule.ACCOUNT, Action.CREATE);

        table = new PaginatedTable<>(this::loadPage);
        TableView<Account> view = table.getTableView();
        view.getColumns().add(TableHelper.column("accounts.name", a -> I18n.pick(a.getName(), a.getNameEn())));
        view.getColumns().add(TableHelper.column("accounts.type",
                a -> a.getType() == null ? "—" : AccountFormController.typeText(a.getType())));
        view.getColumns().add(TableHelper.column("accounts.number",
                a -> a.getAccountNumber() == null ? "—" : a.getAccountNumber()));
        view.getColumns().add(TableHelper.moneyColumn("accounts.col.balance",
                a -> MoneyUtil.format(balances.get(a.getId()))));

        boolean canEdit = UserSession.isLoggedIn() && UserSession.get().can(AppModule.ACCOUNT, Action.EDIT);
        // Row buttons: edit (delete is inside the edit form), view ledger
        List<RowAction<Account>> actions = new ArrayList<>();
        if (canEdit) {
            actions.add(new RowAction<>(Icons.EDIT, "button.edit", "accounts.form.edit_title", "icon-edit", this::openForm));
        }
        actions.add(new RowAction<>(Icons.LEDGER, "button.view", "accounts.ledger", "icon-view", this::openLedger));
        view.getColumns().add(actionsColumn(actions));
        TableHelper.onDoubleClick(view, canEdit, this::openForm);

        tableHolder.getChildren().setAll(table);
        table.reload();

        // A renamed or deleted type changes what the accounts list shows
        typesController.setOnChange(table::refresh);
    }

    private PageResult<Account> loadPage(int offset, int limit) {
        try {
            PageResult<Account> page = service.page(offset, limit);
            balances = ledgerService.balances(page.items());
            return page;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load accounts", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
    }

    @FXML
    private void onAdd() {
        openForm(null);
    }

    @SuppressWarnings("unchecked")
    private static TableColumn<Account, Account> actionsColumn(List<RowAction<Account>> actions) {
        return TableHelper.actionsColumn("label.actions", actions.toArray(new RowAction[0]));
    }

    /** Ledger of an account (7.2), in its own resizable window. */
    private void openLedger(Account selected) {
        FXMLLoader loader = WindowManager.loader(LEDGER_FXML);
        Parent root = WindowManager.load(loader, LEDGER_FXML);
        AccountLedgerController ledger = loader.getController();
        ledger.show(selected);
        Stage stage = WindowManager.dialog(root,
                I18n.get("ledger.title", I18n.pick(selected.getName(), selected.getNameEn())));
        // A list window: resizable and a fixed starting size, not a form that fits its content
        stage.getProperties().remove(WindowManager.AUTO_SIZE);
        stage.setResizable(true);
        stage.setWidth(LEDGER_WIDTH);
        stage.setHeight(LEDGER_HEIGHT);
        stage.showAndWait();
    }

    /** Transfer popup (7.4); balances reload after a transfer. */
    @FXML
    private void onTransfer() {
        if (service.all().size() < 2) {
            Notifier.error(I18n.get("transfer.need_two"));
            return;
        }
        FXMLLoader loader = WindowManager.loader(TRANSFER_FXML);
        Parent root = WindowManager.load(loader, TRANSFER_FXML);
        TransferFormController form = loader.getController();
        Account selected = table.getTableView().getSelectionModel().getSelectedItem();
        if (selected != null) {
            form.setFrom(selected);
        }
        WindowManager.dialog(root, I18n.get("transfer.title")).showAndWait();
        if (form.isSaved()) {
            table.refresh();
        }
    }

    /** Add (account = null) or edit popup; the list reloads after a save. */
    private void openForm(Account account) {
        FXMLLoader loader = WindowManager.loader(FORM_FXML);
        Parent root = WindowManager.load(loader, FORM_FXML);
        AccountFormController form = loader.getController();
        if (account != null) {
            form.edit(account);
        }
        WindowManager.dialog(root, I18n.get(account == null ? "accounts.form.add_title" : "accounts.form.edit_title"))
                .showAndWait();
        if (form.isSaved()) {
            table.refresh();
        }
    }
}
