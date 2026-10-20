package com.eh.bms.controller;

import com.eh.bms.model.User;
import com.eh.bms.model.UserStatus;
import com.eh.bms.service.UserService;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.TableHelper.RowAction;
import com.eh.bms.util.TableHelper.RowTip;
import com.eh.bms.util.TableHelper.TipLine;
import com.eh.bms.util.WindowManager;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Users list (17.1, 17.2, N12: every user is staff): registrations waiting for approval, with approve and reject
 * (N1, shown only while there are any); active users with edit, salary (work out and pay), salary history and
 * deactivate on each row; inactive users with edit, history and activate. Search by name, username, mobile or NID
 * while typing. Delete is in the edit form. Double-click opens the salary history.
 */
public class UsersListController {

    private static final Logger LOG = Logger.getLogger(UsersListController.class.getName());
    private static final String FORM_FXML = "/fxml/views/user-form.fxml";
    private static final String APPROVE_FXML = "/fxml/views/approve-user-form.fxml";

    @FXML private TextField searchField;
    @FXML private Button addButton;
    @FXML private StackPane tableHolder;
    @FXML private StackPane inactiveHolder;
    @FXML private VBox pendingBox;
    @FXML private StackPane pendingHolder;
    @FXML private HBox listsRow;
    @FXML private VBox activeBox;
    @FXML private VBox inactiveBox;

    private final UserService service = new UserService();
    private PaginatedTable<User> table;
    private PaginatedTable<User> inactiveTable;
    private PaginatedTable<User> pendingTable;

    @FXML
    private void initialize() {
        // N1: no role column, the admin picks the role when approving
        pendingTable = new PaginatedTable<>((offset, limit) -> {
            PageResult<User> page = loadPage(UserStatus.PENDING, offset, limit);
            pendingBox.setVisible(page.total() > 0);
            pendingBox.setManaged(page.total() > 0);
            return page;
        });
        pendingTable.getTableView().getColumns().add(TableHelper.column("user.col.name", User::getName));
        pendingTable.getTableView().getColumns().add(TableHelper.column("user.col.username", User::getUsername));
        pendingTable.getTableView().getColumns().add(actionsColumn(
                new RowAction<User>(Icons.ACTIVATE, "user.approve", "icon-on", this::approve),
                new RowAction<User>(Icons.REMOVE, "user.reject", "icon-out", this::reject)));

        table = new PaginatedTable<>((offset, limit) -> loadPage(UserStatus.ACTIVE, offset, limit));
        addInfoColumns(table.getTableView());
        table.getTableView().getColumns().add(actionsColumn(
                new RowAction<User>(Icons.EDIT, "button.edit", "user.form.edit_title", "icon-edit", this::openForm),
                new RowAction<User>(Icons.MONEY_OUT, "salary.action.salary_tip", "icon-out", this::openSalary),
                new RowAction<User>(Icons.LEDGER, "salary.action.history_tip", "icon-view", this::openHistory),
                new RowAction<User>(Icons.DEACTIVATE, "user.deactivate", "icon-off", this::deactivate)));
        TableHelper.onDoubleClick(table.getTableView(), true, this::openHistory);

        inactiveTable = new PaginatedTable<>((offset, limit) -> loadPage(UserStatus.INACTIVE, offset, limit));
        addInfoColumns(inactiveTable.getTableView());
        inactiveTable.getTableView().getColumns().add(actionsColumn(
                new RowAction<User>(Icons.EDIT, "button.edit", "user.form.edit_title", "icon-edit", this::openForm),
                new RowAction<User>(Icons.LEDGER, "salary.action.history_tip", "icon-view", this::openHistory),
                new RowAction<User>(Icons.ACTIVATE, "user.activate", "icon-on", this::activate)));
        TableHelper.onDoubleClick(inactiveTable.getTableView(), true, this::openHistory);

        // Search (both lists) while typing, after a short pause
        PauseTransition pause = new PauseTransition(Duration.millis(300));
        pause.setOnFinished(e -> reload());
        searchField.textProperty().addListener((obs, old, text) -> pause.playFromStart());

        tableHolder.getChildren().setAll(table);
        inactiveHolder.getChildren().setAll(inactiveTable);
        pendingHolder.getChildren().setAll(pendingTable);
        listsRow.widthProperty().addListener((obs, old, w) -> sizeLists());
        pendingBox.managedProperty().addListener((obs, old, shown) -> sizeLists());
        reload();
    }

    /** Active 60 %, inactive 40 %; while registrations wait, their list takes a third and the rest is split so. */
    private void sizeLists() {
        boolean pending = pendingBox.isManaged();
        double free = listsRow.getWidth() - listsRow.getSpacing() * (pending ? 2 : 1);
        double pendingWidth = pending ? free / 3 : 0;
        double rest = free - pendingWidth;
        pendingBox.setPrefWidth(pendingWidth);
        activeBox.setPrefWidth(rest * 0.6);
        inactiveBox.setPrefWidth(rest * 0.4);
    }

    /** Loads all lists again. */
    public void reload() {
        pendingTable.reload();
        table.reload();
        inactiveTable.reload();
    }

    /** Opens the approve popup, where the admin picks the role. */
    private void approve(User user) {
        FXMLLoader loader = WindowManager.loader(APPROVE_FXML);
        Parent root = WindowManager.load(loader, APPROVE_FXML);
        ApproveUserFormController form = loader.getController();
        form.setUp(user);
        WindowManager.dialog(root, I18n.get("user.approve_title")).showAndWait();
        if (form.isSaved()) {
            reload();
        }
    }

    /** Rejecting deletes the registration, so the username is free again. */
    private void reject(User user) {
        if (!Dialogs.confirmDelete(I18n.get("user.confirm_reject", user.getName()))) {
            return;
        }
        try {
            switch (service.reject(user.getId())) {
                case DELETED -> Notifier.success(I18n.get("user.rejected"));
                case IN_USE, SELF, LAST_ADMIN, NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
            reload();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not reject user", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    private static void addInfoColumns(TableView<User> view) {
        view.getColumns().add(TableHelper.column("user.col.name", User::getName));
        view.getColumns().add(TableHelper.column("user.col.role", SalaryController::roleText));
        view.getColumns().add(TableHelper.column("party.phone", u -> orDash(u.getPhone())));
        view.getColumns().add(TableHelper.moneyColumn("salary.base", SalaryController::baseText));
        TableHelper.rowTooltip(view, UsersListController::details);
    }

    /** Row tooltip: every detail, including those the table leaves out. */
    private static RowTip details(User u) {
        List<TipLine> lines = new ArrayList<>();
        lines.add(line("user.col.username", u.getUsername()));
        lines.add(line("user.col.role", SalaryController.roleText(u)));
        lines.add(line("party.phone", orDash(u.getPhone())));
        lines.add(line("employee.nid", orDash(u.getNid())));
        lines.add(line("label.address", orDash(u.getAddress())));
        lines.add(line("employee.join_date", u.getJoinDate() == null ? "—" : DateUtil.format(u.getJoinDate())));
        lines.add(line("employee.salary_type", SalaryController.typeText(u.getSalaryType())));
        lines.add(new TipLine(I18n.get("salary.base"), SalaryController.baseText(u), true));
        return new RowTip(u.getName(), lines);
    }

    private static TipLine line(String labelKey, String value) {
        return new TipLine(I18n.get(labelKey), value, false);
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    /** Work out and pay this month's salary (any month can be picked in the popup). */
    private void openSalary(User user) {
        if (SalaryFormController.open(user, YearMonth.now())) {
            reload();
        }
    }

    private void openHistory(User user) {
        SalaryHistoryController.open(user);
    }

    @SafeVarargs
    private static TableColumn<User, User> actionsColumn(RowAction<User>... actions) {
        return TableHelper.actionsColumn("label.actions", actions);
    }

    private PageResult<User> loadPage(UserStatus status, int offset, int limit) {
        try {
            return service.page(status, searchField.getText(), offset, limit);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load users", e);
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
        openForm(null);
    }

    private void deactivate(User user) {
        if (!Dialogs.confirm(I18n.get("user.confirm_deactivate", user.getName()))) {
            return;
        }
        changeStatus(user, UserStatus.INACTIVE, "user.deactivated");
    }

    private void activate(User user) {
        if (!Dialogs.confirm(I18n.get("user.confirm_activate", user.getName()))) {
            return;
        }
        changeStatus(user, UserStatus.ACTIVE, "user.activated");
    }

    private void changeStatus(User user, UserStatus status, String doneKey) {
        try {
            UserService.StatusOutcome r = service.setStatus(user.getId(), status);
            switch (r.status()) {
                case DONE -> Notifier.success(I18n.get(doneKey));
                case SELF -> Notifier.error(I18n.get("user.error.self"));
                case LAST_ADMIN -> Notifier.error(I18n.get("user.error.last_admin"));
                case HAS_DUE -> Notifier.error(I18n.get("employee.error.has_due", MoneyUtil.format(r.amount())));
                case HAS_ADVANCE -> Notifier.error(I18n.get("employee.error.has_advance", MoneyUtil.format(r.amount())));
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
            reload();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not change user status", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    /** Add (user = null) or edit popup; both lists reload after a save or delete. */
    private void openForm(User user) {
        FXMLLoader loader = WindowManager.loader(FORM_FXML);
        Parent root = WindowManager.load(loader, FORM_FXML);
        UserFormController form = loader.getController();
        if (user != null) {
            form.edit(user);
        }
        WindowManager.dialog(root, I18n.get(user == null ? "user.form.add_title" : "user.form.edit_title"))
                .showAndWait();
        if (form.isSaved()) {
            reload();
        }
    }
}
