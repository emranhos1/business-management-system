package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Party;
import com.eh.bms.model.PartyStatus;
import com.eh.bms.service.PartyLedgerService;
import com.eh.bms.service.PartyPaymentService.Direction;
import com.eh.bms.service.PartyService;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.PartyText;
import com.eh.bms.util.PermissionUtil;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.TableHelper.RowAction;
import com.eh.bms.util.WindowManager;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parties page: active customers and suppliers (10.1) with what is due as পাবো / দেবো (10.4), search by
 * name or mobile. Each row has icon buttons: ledger (10.3), money received / paid (10.5) and inactive.
 * Inactive parties are listed below, each with a button to make it active again.
 */
public class PartiesController {

    private static final Logger LOG = Logger.getLogger(PartiesController.class.getName());
    private static final double LEDGER_WIDTH = 960;
    private static final double LEDGER_HEIGHT = 640;
    private static final String FORM_FXML = "/fxml/views/party-form.fxml";
    private static final String LEDGER_FXML = "/fxml/views/party-ledger.fxml";
    private static final String PAYMENT_FXML = "/fxml/views/party-payment-form.fxml";

    @FXML private Button addButton;
    @FXML private TextField searchField;
    @FXML private StackPane tableHolder;
    @FXML private StackPane inactiveHolder;

    private final PartyService service = new PartyService();
    private final PartyLedgerService ledgerService = new PartyLedgerService();
    private PaginatedTable<Party> table;
    private PaginatedTable<Party> inactiveTable;
    /** Current balance of the parties on the shown page. */
    private Map<Long, BigDecimal> balances = Map.of();

    @FXML
    private void initialize() {
        PermissionUtil.applyCrud(AppModule.PARTY, addButton, null, null);
        boolean canCreate = can(Action.CREATE);
        boolean canEdit = can(Action.EDIT);

        table = new PaginatedTable<>((offset, limit) -> loadPage(PartyStatus.ACTIVE, offset, limit));
        TableView<Party> view = table.getTableView();
        addInfoColumns(view);
        view.getColumns().add(TableHelper.styledColumn("party.col.balance",
                p -> PartyText.balance(balances.get(p.getId())),
                p -> PartyText.balanceStyle(balances.get(p.getId()))));
        // Money received / paid adds ledger entries (create permission); edit and inactive change the party (edit)
        List<RowAction<Party>> actions = new ArrayList<>();
        if (canEdit) {
            actions.add(new RowAction<>(Icons.EDIT, "button.edit", "party.form.edit_title", "icon-edit", this::openForm));
        }
        actions.add(new RowAction<>(Icons.LEDGER, "button.view", "party.ledger", "icon-view", this::openLedger));
        if (canCreate) {
            actions.add(new RowAction<>(Icons.MONEY_IN, "party.action.receive", "party.receive", "icon-in", p -> openPayment(p, Direction.RECEIVED)));
            actions.add(new RowAction<>(Icons.MONEY_OUT, "party.action.pay", "party.pay", "icon-out", p -> openPayment(p, Direction.PAID)));
        }
        if (canEdit) {
            actions.add(new RowAction<>(Icons.DEACTIVATE, "party.deactivate", "icon-off", this::deactivate));
        }
        view.getColumns().add(actionsColumn(actions));
        TableHelper.onDoubleClick(view, canEdit, this::openForm);

        inactiveTable = new PaginatedTable<>((offset, limit) -> loadPage(PartyStatus.INACTIVE, offset, limit));
        TableView<Party> inactiveView = inactiveTable.getTableView();
        addInfoColumns(inactiveView);
        List<RowAction<Party>> inactiveActions = new ArrayList<>();
        if (canEdit) {
            inactiveActions.add(new RowAction<>(Icons.EDIT, "button.edit", "party.form.edit_title", "icon-edit", this::openForm));
        }
        inactiveActions.add(new RowAction<>(Icons.LEDGER, "button.view", "party.ledger", "icon-view", this::openLedger));
        if (canEdit) {
            inactiveActions.add(new RowAction<>(Icons.ACTIVATE, "party.activate", "icon-on", this::activate));
        }
        inactiveView.getColumns().add(actionsColumn(inactiveActions));
        TableHelper.onDoubleClick(inactiveView, canEdit, this::openForm);

        // Search (both lists) while typing, after a short pause
        PauseTransition pause = new PauseTransition(Duration.millis(300));
        pause.setOnFinished(e -> reloadBoth());
        searchField.textProperty().addListener((obs, old, text) -> pause.playFromStart());

        tableHolder.getChildren().setAll(table);
        inactiveHolder.getChildren().setAll(inactiveTable);
        reloadBoth();
    }

    private static void addInfoColumns(TableView<Party> view) {
        view.getColumns().add(TableHelper.column("party.name", PartyText::name));
        view.getColumns().add(TableHelper.column("party.phone", Party::getPhone));
        view.getColumns().add(TableHelper.column("party.type", p -> PartyText.type(p.getType())));
        view.getColumns().add(TableHelper.column("party.trade", p -> PartyText.trade(p.getPartyType())));
        view.getColumns().add(TableHelper.longTextColumn("label.address",
                p -> p.getAddress() == null ? "—" : p.getAddress()));
    }

    @SuppressWarnings("unchecked")
    private static TableColumn<Party, Party> actionsColumn(List<RowAction<Party>> actions) {
        return TableHelper.actionsColumn("label.actions", actions.toArray(new RowAction[0]));
    }

    private PageResult<Party> loadPage(PartyStatus status, int offset, int limit) {
        try {
            PageResult<Party> page = service.page(status, searchField.getText(), offset, limit);
            if (status == PartyStatus.ACTIVE) {
                balances = ledgerService.balances(page.items());
            }
            return page;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load parties", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
    }

    private void reloadBoth() {
        table.reload();
        inactiveTable.reload();
    }

    @FXML
    private void onAdd() {
        openForm(null);
    }

    private void deactivate(Party party) {
        if (!Dialogs.confirm(I18n.get("party.confirm_deactivate", PartyText.name(party)))) {
            return;
        }
        changeStatus(party, PartyStatus.INACTIVE, "party.deactivated");
    }

    private void activate(Party party) {
        if (!Dialogs.confirm(I18n.get("party.confirm_activate", PartyText.name(party)))) {
            return;
        }
        changeStatus(party, PartyStatus.ACTIVE, "party.activated");
    }

    private void changeStatus(Party party, PartyStatus status, String doneKey) {
        try {
            switch (service.setStatus(party.getId(), status)) {
                case DONE -> Notifier.success(I18n.get(doneKey));
                case HAS_BALANCE -> Notifier.error(I18n.get("party.error.has_balance"));
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
            table.refresh();
            inactiveTable.refresh();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not change party status", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    /** Ledger of a party (10.3), in its own resizable window. */
    private void openLedger(Party party) {
        FXMLLoader loader = WindowManager.loader(LEDGER_FXML);
        Parent root = WindowManager.load(loader, LEDGER_FXML);
        PartyLedgerController ledger = loader.getController();
        ledger.show(party);
        Stage stage = WindowManager.dialog(root, I18n.get("party.ledger.title", PartyText.nameWithPhone(party)));
        // A list window: resizable and a fixed starting size, not a form that fits its content
        stage.getProperties().remove(WindowManager.AUTO_SIZE);
        stage.setResizable(true);
        stage.setWidth(LEDGER_WIDTH);
        stage.setHeight(LEDGER_HEIGHT);
        stage.showAndWait();
    }

    /** Money received / paid popup (10.5); balances reload after a save. */
    private void openPayment(Party party, Direction direction) {
        FXMLLoader loader = WindowManager.loader(PAYMENT_FXML);
        Parent root = WindowManager.load(loader, PAYMENT_FXML);
        PartyPaymentFormController form = loader.getController();
        if (!form.setUp(party, direction)) {
            return;
        }
        String titleKey = direction == Direction.RECEIVED ? "party.receive.title" : "party.pay.title";
        WindowManager.dialog(root, I18n.get(titleKey, PartyText.name(party))).showAndWait();
        if (form.isSaved()) {
            table.refresh();
        }
    }

    /** Add (party = null) or edit popup; the list reloads after a save. */
    private void openForm(Party party) {
        FXMLLoader loader = WindowManager.loader(FORM_FXML);
        Parent root = WindowManager.load(loader, FORM_FXML);
        PartyFormController form = loader.getController();
        if (party != null) {
            form.edit(party);
        }
        WindowManager.dialog(root, I18n.get(party == null ? "party.form.add_title" : "party.form.edit_title"))
                .showAndWait();
        // Saved or deleted; the party may be in either list
        if (form.isSaved()) {
            table.refresh();
            inactiveTable.refresh();
        }
    }

    private static boolean can(Action action) {
        return UserSession.isLoggedIn() && UserSession.get().can(AppModule.PARTY, action);
    }
}
