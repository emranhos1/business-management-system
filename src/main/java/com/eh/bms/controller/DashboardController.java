package com.eh.bms.controller;

import com.eh.bms.service.AppConfigService;
import com.eh.bms.service.DashboardService;
import com.eh.bms.service.DashboardService.AccountBalance;
import com.eh.bms.service.DashboardService.CategoryStock;
import com.eh.bms.service.DashboardService.Period;
import com.eh.bms.service.DashboardService.Profit;
import com.eh.bms.service.DashboardService.Snapshot;
import com.eh.bms.service.StockReportService.LowStockRow;
import com.eh.bms.util.DateRangePicker;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.UnitUtil;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dashboard (Stage 15) for a date range (today at first; clearing it goes back to today): sales, purchases,
 * expenses and net cash (15.1) and net profit line by line (15.4) for those dates; money to get / to pay (15.2),
 * stock value per category (15.3), low stock (15.5) and account balances (15.6) as at the end of the last day.
 * Everything is read when the page opens (each visit loads it again), when the range changes and on refresh.
 */
public class DashboardController {

    private static final Logger LOG = Logger.getLogger(DashboardController.class.getName());
    /** Table row and header height (px) used to size the tables to their rows. */
    private static final double ROW_HEIGHT = 30;
    private static final double HEADER_HEIGHT = 34;

    @FXML private Label periodTitle;
    @FXML private DateRangePicker dateRange;
    @FXML private Label salesTitle;
    @FXML private Label salesLabel;
    @FXML private Label purchasesTitle;
    @FXML private Label purchasesLabel;
    @FXML private Label expensesTitle;
    @FXML private Label expensesLabel;
    @FXML private Label netCashTitle;
    @FXML private Label netCashLabel;
    @FXML private Label receivableTitle;
    @FXML private Label receivableLabel;
    @FXML private Label payableTitle;
    @FXML private Label payableLabel;
    @FXML private Label profitTitle;
    @FXML private Label profitSalesLabel;
    @FXML private Label profitCostLabel;
    @FXML private Label profitGrossLabel;
    @FXML private Label profitExpensesLabel;
    @FXML private Label profitSalaryLabel;
    @FXML private Label profitNetTitle;
    @FXML private Label profitNetLabel;
    @FXML private Label accountsTotalLabel;
    @FXML private StackPane accountsHolder;
    @FXML private Label stockTotalLabel;
    @FXML private StackPane stockHolder;
    @FXML private Label lowStockCountLabel;
    @FXML private StackPane lowStockHolder;

    private final DashboardService service = new DashboardService();
    private final int pageRows = new AppConfigService().dashboardRows();
    private PaginatedTable<AccountBalance> accountsTable;
    private PaginatedTable<CategoryStock> stockTable;
    private PaginatedTable<LowStockRow> lowStockTable;
    private Snapshot snapshot;

    @FXML
    private void initialize() {
        accountsTable = listTable(() -> snapshot == null ? List.of() : snapshot.accounts());
        TableView<AccountBalance> accounts = accountsTable.getTableView();
        accounts.getColumns().add(TableHelper.column("dashboard.col.account",
                r -> I18n.pick(r.account().getName(), r.account().getNameEn())));
        accounts.getColumns().add(TableHelper.column("dashboard.col.account_type",
                r -> r.account().getType() == null ? "—" : AccountFormController.typeText(r.account().getType())));
        accounts.getColumns().add(TableHelper.styledColumn("dashboard.col.balance",
                r -> MoneyUtil.format(r.balance()), r -> r.balance().signum() < 0 ? "money-out" : null));
        accountsHolder.getChildren().setAll(accountsTable);

        stockTable = listTable(() -> snapshot == null ? List.of() : snapshot.stock());
        TableView<CategoryStock> stock = stockTable.getTableView();
        stock.getColumns().add(TableHelper.column("stock.value.col.category",
                r -> ProductCategoriesController.nameText(r.category())));
        stock.getColumns().add(TableHelper.column("dashboard.col.products",
                r -> I18n.digits(String.valueOf(r.products()))));
        stock.getColumns().add(TableHelper.moneyColumn("stock.value.col.value", r -> MoneyUtil.format(r.value())));
        stockHolder.getChildren().setAll(stockTable);

        lowStockTable = listTable(() -> snapshot == null ? List.of() : snapshot.lowStock());
        TableView<LowStockRow> low = lowStockTable.getTableView();
        low.getColumns().add(TableHelper.longTextColumn("stock.low.col.name", r -> UnitUtil.productName(r.product())));
        low.getColumns().add(TableHelper.column("stock.low.col.stock", r -> UnitUtil.quantityText(r.stock(), r.product())));
        low.getColumns().add(TableHelper.column("stock.low.col.min", r -> UnitUtil.quantityText(r.minStock(), r.product())));
        low.getColumns().add(TableHelper.styledColumn("stock.low.col.shortfall",
                r -> UnitUtil.quantityText(r.shortfall(), r.product()), r -> "money-out"));
        lowStockHolder.getChildren().setAll(lowStockTable);

        // Clearing the range means today again (the dashboard always shows some dates)
        dateRange.setOnChange(() -> {
            if (dateRange.getFrom() == null) {
                onToday();
            } else {
                refresh();
            }
        });
        onToday();
    }

    /** Back to today (also the start). */
    @FXML
    private void onToday() {
        LocalDate today = LocalDate.now();
        dateRange.setRange(today, today);
    }

    /** Reads every figure again for the picked dates. */
    @FXML
    public void refresh() {
        LocalDate from = dateRange.getFrom() == null ? LocalDate.now() : dateRange.getFrom();
        LocalDate to = dateRange.getTo() == null ? from : dateRange.getTo();
        showTitles(from, to);
        try {
            snapshot = service.load(from, to);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load the dashboard", e);
            Notifier.error(I18n.get("msg.error"));
            snapshot = null;
        }
        show();
        accountsTable.reload();
        stockTable.reload();
        lowStockTable.reload();
    }

    /** "আজকের ..." for today only; otherwise the plain names, with the dates in the page title. */
    private void showTitles(LocalDate from, LocalDate to) {
        boolean today = from.equals(LocalDate.now()) && to.equals(from);
        String dates = from.equals(to) ? DateUtil.format(from) : DateUtil.format(from) + " – " + DateUtil.format(to);
        periodTitle.setText(I18n.get(today ? "dashboard.title.today" : "dashboard.title.dates", dates));
        String prefix = today ? "dashboard.today_" : "dashboard.period_";
        salesTitle.setText(I18n.get(prefix + "sales"));
        purchasesTitle.setText(I18n.get(prefix + "purchases"));
        expensesTitle.setText(I18n.get(prefix + "expenses"));
        netCashTitle.setText(I18n.get(prefix + "net_cash"));
        profitTitle.setText(I18n.get("dashboard.profit.title", dates));
    }

    private void show() {
        if (snapshot == null) {
            return;
        }
        Period t = snapshot.period();
        salesLabel.setText(MoneyUtil.format(t.sales()));
        purchasesLabel.setText(MoneyUtil.format(t.purchases()));
        expensesLabel.setText(MoneyUtil.format(t.expenses()));
        netCashLabel.setText(MoneyUtil.format(t.netCash()));
        signStyle(netCashLabel, t.netCash());

        receivableTitle.setText(I18n.get("dashboard.receivable", I18n.digits(String.valueOf(snapshot.receivableParties()))));
        receivableLabel.setText(MoneyUtil.format(snapshot.receivable()));
        payableTitle.setText(I18n.get("dashboard.payable", I18n.digits(String.valueOf(snapshot.payableParties()))));
        payableLabel.setText(MoneyUtil.format(snapshot.payable()));

        Profit p = snapshot.profit();
        profitSalesLabel.setText(MoneyUtil.format(p.sales()));
        profitCostLabel.setText("− " + MoneyUtil.format(p.costOfSales()));
        profitGrossLabel.setText(MoneyUtil.format(p.grossProfit()));
        profitExpensesLabel.setText("− " + MoneyUtil.format(p.expenses()));
        profitSalaryLabel.setText("− " + MoneyUtil.format(p.salary()));
        // A loss shows as "নিট লোকসান" with the amount without its minus sign
        profitNetTitle.setText(I18n.get(p.net().signum() < 0 ? "dashboard.profit.loss" : "dashboard.profit.net"));
        profitNetLabel.setText(MoneyUtil.format(p.net().abs()));
        signStyle(profitNetLabel, p.net());

        accountsTotalLabel.setText(I18n.get("dashboard.total", MoneyUtil.format(snapshot.accountsTotal())));
        stockTotalLabel.setText(I18n.get("dashboard.total", MoneyUtil.format(snapshot.stockValue())));
        lowStockCountLabel.setText(snapshot.lowStock().isEmpty() ? ""
                : I18n.get("stock.low.count", I18n.digits(String.valueOf(snapshot.lowStock().size()))));
    }

    /** Green when above 0, red when below. */
    private static void signStyle(Label label, BigDecimal amount) {
        label.getStyleClass().removeAll("money-in", "money-out");
        if (amount.signum() > 0) {
            label.getStyleClass().add("money-in");
        } else if (amount.signum() < 0) {
            label.getStyleClass().add("money-out");
        }
    }

    /**
     * A table over an in-memory list, {@code pageRows} rows a page (Settings: dashboard rows), exactly that many
     * rows high so the dashboard stays short.
     */
    private <T> PaginatedTable<T> listTable(Supplier<List<T>> rows) {
        PaginatedTable<T> table = new PaginatedTable<>((offset, limit) -> {
            List<T> all = rows.get();
            int from = Math.min(offset, all.size());
            int to = Math.min(from + limit, all.size());
            return new PageResult<>(all.subList(from, to), all.size());
        }, pageRows);
        TableView<T> view = table.getTableView();
        view.setFixedCellSize(ROW_HEIGHT);
        double height = HEADER_HEIGHT + pageRows * ROW_HEIGHT;
        view.setMinHeight(height);
        view.setPrefHeight(height);
        view.setMaxHeight(height);
        return table;
    }
}
