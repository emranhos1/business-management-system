package com.eh.bms.controller;

import com.eh.bms.model.Employee;
import com.eh.bms.model.SalaryPayment;
import com.eh.bms.service.SalaryService;
import com.eh.bms.service.SalaryService.History;
import com.eh.bms.service.SalaryService.MonthRow;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.TableHelper.RowAction;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One employee's salary history (14.5): total net, paid, due and advance left; each month (newest first) with
 * gross, deductions, bonus, net, advance carried in, paid and due (or advance left); every payment (newest first), each with an edit button for users who
 * may do that (delete sits in the payment form).
 */
public class SalaryHistoryController {

    private static final Logger LOG = Logger.getLogger(SalaryHistoryController.class.getName());
    private static final String FXML_PATH = "/fxml/views/salary-history.fxml";
    private static final double WIDTH = 1000;
    private static final double HEIGHT = 720;

    @FXML private Label totalNetLabel;
    @FXML private Label totalPaidLabel;
    @FXML private Label totalDueLabel;
    @FXML private Label totalAdvanceLabel;
    @FXML private StackPane monthsHolder;
    @FXML private StackPane paymentsHolder;

    private final SalaryService service = new SalaryService();
    private PaginatedTable<MonthRow> monthsTable;
    private PaginatedTable<SalaryPayment> paymentsTable;
    private List<MonthRow> months = List.of();
    private List<SalaryPayment> payments = List.of();
    private Employee employee;

    /** Opens the employee's salary history in a list popup. */
    public static void open(Employee employee) {
        FXMLLoader loader = WindowManager.loader(FXML_PATH);
        Parent root = WindowManager.load(loader, FXML_PATH);
        SalaryHistoryController history = loader.getController();
        history.show(employee);
        Stage stage = WindowManager.dialog(root, I18n.get("salary.history.title", employee.getName()));
        // A list window: a fixed size (capped to the screen), not a form that fits its content
        WindowManager.sizeListWindow(stage, WIDTH, HEIGHT);
        stage.showAndWait();
    }

    @FXML
    private void initialize() {
        monthsTable = new PaginatedTable<>((offset, limit) -> slice(months, offset, limit));
        TableView<MonthRow> mv = monthsTable.getTableView();
        mv.getColumns().add(TableHelper.column("salary.month", r -> SalaryController.monthText(r.month())));
        mv.getColumns().add(TableHelper.moneyColumn("salary.gross", r -> MoneyUtil.format(r.gross())));
        mv.getColumns().add(TableHelper.moneyColumn("salary.deductions", r -> MoneyUtil.format(r.deductions())));
        mv.getColumns().add(TableHelper.moneyColumn("salary.bonus", r -> MoneyUtil.format(r.bonus())));
        mv.getColumns().add(TableHelper.styledColumn("salary.col.net",
                r -> r.processed() ? MoneyUtil.format(r.net()) : I18n.get("salary.not_processed"), r -> null));
        mv.getColumns().add(TableHelper.moneyColumn("salary.col.carry", r -> SalaryController.carryText(r.carryIn())));
        mv.getColumns().add(TableHelper.moneyColumn("salary.col.paid", r -> MoneyUtil.format(r.paid())));
        mv.getColumns().add(TableHelper.styledColumn("salary.col.due",
                r -> r.processed() ? SalaryController.dueCell(r.due(), r.advanceLeft()) : "—",
                r -> SalaryController.dueStyle(r.due())));
        monthsHolder.getChildren().setAll(monthsTable);

        paymentsTable = new PaginatedTable<>((offset, limit) -> slice(payments, offset, limit));
        TableView<SalaryPayment> pv = paymentsTable.getTableView();
        pv.getColumns().add(TableHelper.column("label.date", p -> DateUtil.format(p.getDate())));
        pv.getColumns().add(TableHelper.column("salary.month",
                p -> SalaryController.monthText(YearMonth.of(p.getYear(), p.getMonth()))));
        pv.getColumns().add(TableHelper.column("salary.pay.account",
                p -> I18n.pick(p.getAccount().getName(), p.getAccount().getNameEn())));
        pv.getColumns().add(TableHelper.moneyColumn("label.amount", p -> MoneyUtil.format(p.getAmount())));
        pv.getColumns().add(TableHelper.column("salary.pay.emergency",
                p -> p.isEmergency() ? I18n.get("button.yes") : "—"));
        pv.getColumns().add(TableHelper.longTextColumn("label.note", p -> p.getNote() == null ? "" : p.getNote()));
        List<RowAction<SalaryPayment>> actions = new ArrayList<>();
        if (SalaryController.can(Action.EDIT)) {
            actions.add(new RowAction<>(Icons.EDIT, "button.edit", "salary.pay.edit_tip", "icon-edit", this::edit));
        }
        if (!actions.isEmpty()) {
            pv.getColumns().add(actionsColumn(actions));
        }
        paymentsHolder.getChildren().setAll(paymentsTable);
    }

    @SuppressWarnings("unchecked")
    private static TableColumn<SalaryPayment, SalaryPayment> actionsColumn(List<RowAction<SalaryPayment>> actions) {
        return TableHelper.actionsColumn("label.actions", actions.toArray(new RowAction[0]));
    }

    /** Loads the employee's history; call before showing. */
    private void show(Employee employee) {
        this.employee = employee;
        load();
    }

    private void load() {
        try {
            History h = service.history(employee.getId());
            months = h.months();
            payments = h.payments();
            totalNetLabel.setText(MoneyUtil.format(h.totalNet()));
            totalPaidLabel.setText(MoneyUtil.format(h.totalPaid()));
            totalDueLabel.setText(MoneyUtil.format(h.totalDue()));
            SalaryController.setStyle(totalDueLabel, SalaryController.dueStyle(h.totalDue()));
            totalAdvanceLabel.setText(MoneyUtil.format(h.totalAdvance()));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load the salary history", e);
            Notifier.error(I18n.get("msg.error"));
            months = List.of();
            payments = List.of();
            for (Label l : List.of(totalNetLabel, totalPaidLabel, totalDueLabel, totalAdvanceLabel)) {
                l.setText(MoneyUtil.format(BigDecimal.ZERO));
            }
        }
        monthsTable.reload();
        paymentsTable.reload();
    }

    private void edit(SalaryPayment p) {
        if (SalaryPaymentFormController.open(employee, YearMonth.of(p.getYear(), p.getMonth()), p)) {
            load();
        }
    }

    private static <T> PageResult<T> slice(List<T> all, int offset, int limit) {
        int from = Math.min(offset, all.size());
        int to = Math.min(from + limit, all.size());
        return new PageResult<>(all.subList(from, to), all.size());
    }

    @FXML
    private void onClose() {
        ((Stage) monthsHolder.getScene().getWindow()).close();
    }
}
