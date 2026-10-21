package com.eh.bms.controller;

import com.eh.bms.model.User;
import com.eh.bms.model.UserStatus;
import com.eh.bms.service.SalaryService;
import com.eh.bms.service.SalaryService.MonthSheet;
import com.eh.bms.service.SalaryService.SheetRow;
import com.eh.bms.service.UserService;
import com.eh.bms.service.UserSession;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.time.Month;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Users list (17.1, 17.2, N12: every user is staff): registrations waiting for approval, with approve and reject
 * (N1, shown only while there are any); active users with edit, salary (work out and pay), salary history and
 * deactivate on each row; inactive users are in the same list, last, and keep only the activate button. Search by name, username, mobile or NID
 * while typing. Delete is in the edit form. Double-click opens the salary history.
 */
public class UsersListController {

    private static final Logger LOG = Logger.getLogger(UsersListController.class.getName());
    private static final String FORM_FXML = "/fxml/views/user-form.fxml";
    private static final String APPROVE_FXML = "/fxml/views/approve-user-form.fxml";

    @FXML private TextField searchField;
    @FXML private Button addButton;
    @FXML private StackPane tableHolder;
    @FXML private VBox pendingBox;
    @FXML private StackPane pendingHolder;
    @FXML private ComboBox<Month> monthBox;
    @FXML private ComboBox<Integer> yearBox;
    @FXML private Label totalNetLabel;
    @FXML private Label totalCarryLabel;
    @FXML private Label totalPaidLabel;
    @FXML private Label totalDueLabel;
    @FXML private HBox listsRow;
    @FXML private VBox activeBox;

    private final UserService service = new UserService();
    private final SalaryService salaryService = new SalaryService();
    /** The chosen month's salary row of each user who has one (users without a salary are not in it). */
    private Map<Long, SheetRow> sheetRows = Map.of();
    /** True while the month and year are being set, so their listeners do not load twice. */
    private boolean setting;
    private PaginatedTable<User> table;
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

        table = new PaginatedTable<>((offset, limit) -> loadPage(null, offset, limit));
        addInfoColumns(table.getTableView());
        table.getTableView().getColumns().add(actionsColumn(
                new RowAction<User>(Icons.EDIT, "button.edit", "user.form.edit_title", "icon-edit", this::openForm)
                        .enabledWhen(UsersListController::isActive),
                new RowAction<User>(Icons.MONEY_OUT, "salary.action.salary", "salary.action.salary_tip", "icon-out",
                        this::openSalary).enabledWhen(UsersListController::isActive),
                new RowAction<User>(Icons.LEDGER, "button.view", "salary.action.history_tip", "icon-view",
                        this::openHistory).enabledWhen(UsersListController::isActive),
                // Nobody can switch their own login off (the service refuses it too): greyed out on their own row
                new RowAction<User>(Icons.DEACTIVATE, "user.deactivate", "icon-off", this::deactivate,
                        UsersListController::isActive).enabledWhen(u -> u.getId() != UserSession.get().getUserId()),
                new RowAction<User>(Icons.ACTIVATE, "user.activate", "icon-on", this::activate,
                        u -> !isActive(u))));
        // An inactive user keeps only the switch-on button; the rest of its row is greyed out
        TableHelper.onDoubleClick(table.getTableView(), true, u -> {
            if (isActive(u)) {
                openHistory(u);
            }
        });

        // Search while typing, after a short pause
        PauseTransition pause = new PauseTransition(Duration.millis(300));
        pause.setOnFinished(e -> reload());
        searchField.textProperty().addListener((obs, old, text) -> pause.playFromStart());

        tableHolder.getChildren().setAll(table);
        pendingHolder.getChildren().setAll(pendingTable);
        listsRow.widthProperty().addListener((obs, old, w) -> sizeLists());
        pendingBox.managedProperty().addListener((obs, old, shown) -> sizeLists());
        SalaryText.monthConverter(monthBox);
        SalaryText.yearConverter(yearBox);
        monthBox.valueProperty().addListener((obs, old, now) -> {
            if (!setting) {
                reload();
            }
        });
        yearBox.valueProperty().addListener((obs, old, now) -> {
            if (!setting) {
                reload();
            }
        });
        setMonth(YearMonth.now());
        reload();
    }

    private static boolean isActive(User u) {
        return u.getStatus() == UserStatus.ACTIVE;
    }

    /** Registrations waiting take a third of the width while there are any; the users list takes the rest. */
    private void sizeLists() {
        boolean pending = pendingBox.isManaged();
        double free = listsRow.getWidth() - (pending ? listsRow.getSpacing() : 0);
        double pendingWidth = pending ? free / 3 : 0;
        pendingBox.setPrefWidth(pendingWidth);
        activeBox.setPrefWidth(free - pendingWidth);
    }

    /** Loads the month's salary and both lists again. */
    public void reload() {
        loadSheet();
        pendingTable.reload();
        table.reload();
    }

    /** The chosen month, or this month when none. */
    private YearMonth chosenMonth() {
        Month m = monthBox.getValue();
        Integer y = yearBox.getValue();
        return m == null || y == null ? YearMonth.now() : YearMonth.of(y, m);
    }

    private void setMonth(YearMonth month) {
        setting = true;
        try {
            yearBox.getItems().setAll(SalaryText.years(month));
            yearBox.setValue(month.getYear());
            monthBox.getItems().setAll(Month.values());
            monthBox.setValue(month.getMonth());
        } finally {
            setting = false;
        }
    }

    /** The month's salary of every user with one, and the four totals above the table. */
    private void loadSheet() {
        YearMonth month = chosenMonth();
        MonthSheet sheet;
        try {
            sheet = salaryService.monthSheet(month);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load the salary sheet", e);
            Notifier.error(I18n.get("msg.error"));
            sheet = new MonthSheet(month, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Map<Long, SheetRow> byUser = new HashMap<>();
        for (SheetRow r : sheet.rows()) {
            byUser.put(r.user().getId(), r);
        }
        sheetRows = byUser;
        totalNetLabel.setText(MoneyUtil.format(sheet.totalNet()));
        totalCarryLabel.setText(MoneyUtil.format(sheet.totalCarry()));
        totalPaidLabel.setText(MoneyUtil.format(sheet.totalPaid()));
        totalDueLabel.setText(MoneyUtil.format(sheet.totalDue()));
        SalaryText.setStyle(totalDueLabel, SalaryText.dueStyle(sheet.totalDue()));
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

    private void addInfoColumns(TableView<User> view) {
        view.getColumns().add(TableHelper.column("user.col.name", User::getName));
        view.getColumns().add(TableHelper.column("user.col.role", SalaryText::roleText));
        view.getColumns().add(TableHelper.column("party.phone", u -> orDash(u.getPhone())));
        view.getColumns().add(TableHelper.moneyColumn("salary.base", SalaryText::baseText));
        view.getColumns().add(TableHelper.styledColumn("salary.col.net", u -> netText(sheetRows.get(u.getId())), u -> null));
        view.getColumns().add(TableHelper.moneyColumn("salary.col.paid", u -> paidText(sheetRows.get(u.getId()))));
        view.getColumns().add(TableHelper.styledColumn("salary.col.due", u -> dueText(sheetRows.get(u.getId())),
                u -> dueStyle(sheetRows.get(u.getId()))));
        view.getColumns().add(TableHelper.column("label.status", u -> I18n.get("label.status." + u.getStatus().name())));
        TableHelper.rowTooltip(view, this::details);
    }

    // The month's salary cells; a user with no salary (or none this month) shows a dash
    private static String netText(SheetRow r) {
        return r == null ? "—" : r.processed() ? MoneyUtil.format(r.sheet().getNetSalary()) : I18n.get("salary.not_processed");
    }

    private static String paidText(SheetRow r) {
        return r == null ? "—" : MoneyUtil.format(r.paid());
    }

    private static String dueText(SheetRow r) {
        return r == null || !r.processed() ? "—" : SalaryText.dueCell(r.due(), r.advanceLeft());
    }

    private static String dueStyle(SheetRow r) {
        return r == null ? null : SalaryText.dueStyle(r.due());
    }

    /** Row tooltip: every detail, including those the table leaves out. */
    private RowTip details(User u) {
        List<TipLine> lines = new ArrayList<>();
        lines.add(line("user.col.username", u.getUsername()));
        lines.add(line("user.col.role", SalaryText.roleText(u)));
        lines.add(line("party.phone", orDash(u.getPhone())));
        lines.add(line("employee.nid", orDash(u.getNid())));
        lines.add(line("label.address", orDash(u.getAddress())));
        lines.add(line("employee.join_date", u.getJoinDate() == null ? "—" : DateUtil.format(u.getJoinDate())));
        lines.add(line("employee.salary_type", SalaryText.typeText(u.getSalaryType())));
        lines.add(new TipLine(I18n.get("salary.base"), SalaryText.baseText(u), true));
        SheetRow r = sheetRows.get(u.getId());
        if (r != null) {
            lines.add(new TipLine(SalaryText.monthText(chosenMonth()), "", false));
            if (r.processed()) {
                lines.add(new TipLine(I18n.get("salary.gross"), MoneyUtil.format(r.sheet().getGrossSalary()), true));
                lines.add(new TipLine(I18n.get("salary.deductions"), MoneyUtil.format(r.sheet().getDeductions()), true));
                if (r.sheet().getNote() != null) {
                    lines.add(new TipLine(I18n.get("salary.reason"), r.sheet().getNote(), false));
                }
                lines.add(new TipLine(I18n.get("salary.bonus"), MoneyUtil.format(r.sheet().getBonus()), true));
            }
            lines.add(new TipLine(I18n.get("salary.col.carry"), SalaryText.carryText(r.carryIn()), true));
        }
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
        if (SalaryFormController.open(user, chosenMonth())) {
            reload();
        }
    }

    private void openHistory(User user) {
        SalaryHistoryController.open(user);
        // A payment may have been deleted there
        reload();
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
        setMonth(YearMonth.now());
        searchField.clear();
        reload();
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
