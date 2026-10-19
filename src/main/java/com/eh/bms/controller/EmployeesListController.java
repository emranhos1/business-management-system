package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Employee;
import com.eh.bms.model.EmployeeStatus;
import com.eh.bms.model.SalaryType;
import com.eh.bms.service.EmployeeService;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.PermissionUtil;
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
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Employees list tab (14.1): active employees, search by name, mobile, NID or designation. Each row has icon
 * buttons: edit (delete is inside the edit form), salary history and inactive. Inactive employees are listed
 * below, each with a button to make it active again. Double-click opens the salary history.
 */
public class EmployeesListController {

    private static final Logger LOG = Logger.getLogger(EmployeesListController.class.getName());
    private static final String FORM_FXML = "/fxml/views/employee-form.fxml";

    @FXML private Button addButton;
    @FXML private TextField searchField;
    @FXML private StackPane tableHolder;
    @FXML private StackPane inactiveHolder;

    private final EmployeeService service = new EmployeeService();
    private PaginatedTable<Employee> table;
    private PaginatedTable<Employee> inactiveTable;

    @FXML
    private void initialize() {
        PermissionUtil.applyCrud(AppModule.EMPLOYEE, addButton, null, null);
        boolean canEdit = UserSession.isLoggedIn() && UserSession.get().can(AppModule.EMPLOYEE, Action.EDIT);

        table = new PaginatedTable<>((offset, limit) -> loadPage(EmployeeStatus.ACTIVE, offset, limit));
        TableView<Employee> view = table.getTableView();
        addInfoColumns(view);
        List<RowAction<Employee>> actions = new ArrayList<>();
        if (canEdit) {
            actions.add(new RowAction<>(Icons.EDIT, "button.edit", "employee.form.edit_title", "icon-edit", this::openForm));
        }
        actions.add(new RowAction<>(Icons.LEDGER, "button.view", "employee.salary_history", "icon-view", this::openHistory));
        if (canEdit) {
            actions.add(new RowAction<>(Icons.DEACTIVATE, "party.deactivate", "icon-off", this::deactivate));
        }
        view.getColumns().add(actionsColumn(actions));
        TableHelper.rowTooltip(view, EmployeesListController::details);
        TableHelper.onDoubleClick(view, true, this::openHistory);

        inactiveTable = new PaginatedTable<>((offset, limit) -> loadPage(EmployeeStatus.INACTIVE, offset, limit));
        TableView<Employee> inactiveView = inactiveTable.getTableView();
        addInfoColumns(inactiveView);
        List<RowAction<Employee>> inactiveActions = new ArrayList<>();
        if (canEdit) {
            inactiveActions.add(new RowAction<>(Icons.EDIT, "button.edit", "employee.form.edit_title", "icon-edit", this::openForm));
        }
        inactiveActions.add(new RowAction<>(Icons.LEDGER, "button.view", "employee.salary_history", "icon-view", this::openHistory));
        if (canEdit) {
            inactiveActions.add(new RowAction<>(Icons.ACTIVATE, "party.activate", "icon-on", this::activate));
        }
        inactiveView.getColumns().add(actionsColumn(inactiveActions));
        TableHelper.rowTooltip(inactiveView, EmployeesListController::details);
        TableHelper.onDoubleClick(inactiveView, true, this::openHistory);

        // Search (both lists) while typing, after a short pause
        PauseTransition pause = new PauseTransition(Duration.millis(300));
        pause.setOnFinished(e -> reload());
        searchField.textProperty().addListener((obs, old, text) -> pause.playFromStart());

        tableHolder.getChildren().setAll(table);
        inactiveHolder.getChildren().setAll(inactiveTable);
        reload();
    }

    /** Reloads both lists from page 1 (e.g. when the tab opens). */
    public void reload() {
        table.reload();
        inactiveTable.reload();
    }

    private static void addInfoColumns(TableView<Employee> view) {
        view.getColumns().add(TableHelper.column("label.name", Employee::getName));
        view.getColumns().add(TableHelper.column("party.phone", e -> orDash(e.getPhone())));
        view.getColumns().add(TableHelper.column("employee.designation", e -> orDash(e.getDesignation())));
        view.getColumns().add(TableHelper.column("employee.salary_type", e -> salaryTypeText(e.getSalaryType())));
        view.getColumns().add(TableHelper.moneyColumn("employee.base_salary", e -> MoneyUtil.format(e.getBaseSalary())));
        view.getColumns().add(TableHelper.column("employee.join_date",
                e -> e.getJoinDate() == null ? "—" : DateUtil.format(e.getJoinDate())));
    }

    /** Row tooltip: every detail, including NID and address that the table leaves out. */
    private static RowTip details(Employee e) {
        List<TipLine> lines = new ArrayList<>();
        lines.add(line("party.phone", orDash(e.getPhone())));
        lines.add(line("employee.nid", orDash(e.getNid())));
        lines.add(line("label.address", orDash(e.getAddress())));
        lines.add(line("employee.designation", orDash(e.getDesignation())));
        lines.add(line("employee.join_date", e.getJoinDate() == null ? "—" : DateUtil.format(e.getJoinDate())));
        lines.add(line("employee.salary_type", salaryTypeText(e.getSalaryType())));
        lines.add(new TipLine(I18n.get(baseSalaryKey(e.getSalaryType())), MoneyUtil.format(e.getBaseSalary()), true));
        return new RowTip(e.getName(), lines);
    }

    private static TipLine line(String labelKey, String value) {
        return new TipLine(I18n.get(labelKey), value, false);
    }

    /** "মাসিক" / "Monthly" etc. */
    static String salaryTypeText(SalaryType type) {
        return type == null ? "—" : I18n.get("employee.salary_type." + type.name());
    }

    /** Label of the base salary by type: per month / per day / per hour. */
    static String baseSalaryKey(SalaryType type) {
        return type == null ? "employee.base_salary" : "employee.base_salary." + type.name();
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    @SuppressWarnings("unchecked")
    private static TableColumn<Employee, Employee> actionsColumn(List<RowAction<Employee>> actions) {
        return TableHelper.actionsColumn("label.actions", actions.toArray(new RowAction[0]));
    }

    private PageResult<Employee> loadPage(EmployeeStatus status, int offset, int limit) {
        try {
            return service.page(status, searchField.getText(), offset, limit);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load employees", e);
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

    private void openHistory(Employee employee) {
        SalaryHistoryController.open(employee);
    }

    private void deactivate(Employee employee) {
        if (!Dialogs.confirm(I18n.get("employee.confirm_deactivate", employee.getName()))) {
            return;
        }
        changeStatus(employee, EmployeeStatus.INACTIVE, "employee.deactivated");
    }

    private void activate(Employee employee) {
        if (!Dialogs.confirm(I18n.get("party.confirm_activate", employee.getName()))) {
            return;
        }
        changeStatus(employee, EmployeeStatus.ACTIVE, "employee.activated");
    }

    private void changeStatus(Employee employee, EmployeeStatus status, String doneKey) {
        try {
            EmployeeService.StatusOutcome r = service.setStatus(employee.getId(), status);
            switch (r.status()) {
                case DONE -> Notifier.success(I18n.get(doneKey));
                case HAS_DUE -> Notifier.error(I18n.get("employee.error.has_due", MoneyUtil.format(r.amount())));
                case HAS_ADVANCE -> Notifier.error(I18n.get("employee.error.has_advance",
                        MoneyUtil.format(r.amount())));
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
            table.refresh();
            inactiveTable.refresh();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not change employee status", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    /** Add (employee = null) or edit popup; both lists reload after a save or delete. */
    private void openForm(Employee employee) {
        FXMLLoader loader = WindowManager.loader(FORM_FXML);
        Parent root = WindowManager.load(loader, FORM_FXML);
        EmployeeFormController form = loader.getController();
        if (employee != null) {
            form.edit(employee);
        }
        WindowManager.dialog(root, I18n.get(employee == null ? "employee.form.add_title" : "employee.form.edit_title"))
                .showAndWait();
        if (form.isSaved()) {
            table.refresh();
            inactiveTable.refresh();
        }
    }
}
