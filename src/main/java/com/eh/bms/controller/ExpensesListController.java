package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Expense;
import com.eh.bms.service.ExpenseService;
import com.eh.bms.service.ExpenseService.Group;
import com.eh.bms.service.ExpenseService.GroupedExpenses;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.DateRangePicker;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PermissionUtil;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.TableHelper.RowAction;
import com.eh.bms.util.TableHelper.RowTip;
import com.eh.bms.util.TableHelper.TipLine;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableView;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Expenses grouped by category (13.2-13.4): pick a month + year (fills the date range with that month) or any date
 * range (this month at first); five tiles on top (total and
 * count of those dates, today's total, the daily average up to today, the category spent most); one row per category (total and share, largest first) with
 * its expenses under it, newest first. A click on a category row (or its arrow) opens or closes it. "New expense"
 * opens the form; the edit button on an expense edits it (delete is in the edit form).
 */
public class ExpensesListController {

    private static final Logger LOG = Logger.getLogger(ExpensesListController.class.getName());
    private static final String FORM_FXML = "/fxml/views/expense-form.fxml";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** One row of the tree: a category with its total ({@code expense} null), or one of its expenses. */
    private record Line(Group group, Expense expense) {
        boolean isGroup() {
            return expense == null;
        }
    }

    @FXML private ComboBox<Month> monthBox;
    @FXML private ComboBox<Integer> yearBox;
    @FXML private DateRangePicker dateRange;
    @FXML private Button addButton;
    @FXML private Label totalLabel;
    @FXML private Label countLabel;
    @FXML private Label todayLabel;
    @FXML private Label averageLabel;
    @FXML private Label topNameLabel;
    @FXML private Label topAmountLabel;
    @FXML private TreeTableView<Line> tree;

    private final ExpenseService service = new ExpenseService();
    /** Total of what the filters show, for each category's share. */
    private BigDecimal total = BigDecimal.ZERO;
    /** True while the month / year boxes follow the date range, so they do not set the range again. */
    private boolean syncing;
    /** Years in the year box before this year. */
    private static final int YEARS_BACK = 5;

    @FXML
    private void initialize() {
        PermissionUtil.applyCrud(AppModule.EXPENSE, addButton, null, null);

        TableHelper.setUp(tree, this::rowTip);
        tree.getColumns().add(TableHelper.treeColumn("expense.category_date", l -> l.isGroup()
                ? ExpenseCategoriesController.nameText(l.group().category())
                : DateUtil.format(l.expense().getDate())));
        tree.getColumns().add(TableHelper.treeMoneyColumn("label.amount",
                l -> MoneyUtil.format(l.isGroup() ? l.group().total() : l.expense().getAmount())));
        tree.getColumns().add(TableHelper.treeMoneyColumn("expense.summary.share",
                l -> l.isGroup() ? shareText(l.group().total()) : ""));
        tree.getColumns().add(TableHelper.treeColumn("expense.account",
                l -> l.isGroup() ? "" : I18n.pick(l.expense().getAccount().getName(), l.expense().getAccount().getNameEn())));
        tree.getColumns().add(TableHelper.treeColumn("label.note",
                l -> l.isGroup() || l.expense().getNote() == null ? "" : l.expense().getNote()));
        if (UserSession.isLoggedIn() && UserSession.get().can(AppModule.EXPENSE, Action.EDIT)) {
            tree.getColumns().add(TableHelper.treeActionsColumn("label.actions", l -> !l.isGroup(),
                    new RowAction<Line>(Icons.EDIT, "button.edit", "expense.edit_title", "icon-edit",
                            l -> openForm(l.expense()))));
        }
        tree.setRoot(new TreeItem<>());

        // Month + year fill the date range with that month; the date range can also be picked freely
        monthBox.getItems().setAll(Month.values());
        monthBox.setConverter(converter(m -> m.getDisplayName(TextStyle.FULL_STANDALONE, I18n.getLocale())));
        int thisYear = LocalDate.now().getYear();
        for (int y = thisYear + 1; y >= thisYear - YEARS_BACK; y--) {
            yearBox.getItems().add(y);
        }
        yearBox.setConverter(converter(y -> I18n.digits(String.valueOf(y))));
        monthBox.valueProperty().addListener((obs, old, now) -> onMonthPicked());
        yearBox.valueProperty().addListener((obs, old, now) -> onMonthPicked());
        dateRange.setOnChange(() -> {
            syncMonth();
            load();
        });
        showThisMonth();
    }

    /** A month and year picked: the date range becomes that whole month. */
    private void onMonthPicked() {
        if (syncing || monthBox.getValue() == null || yearBox.getValue() == null) {
            return;
        }
        YearMonth month = YearMonth.of(yearBox.getValue(), monthBox.getValue());
        dateRange.setRange(month.atDay(1), month.atEndOfMonth());
    }

    /**
     * Follows the date range: the year box shows the first date's year (so picking only a month works next), the
     * month box shows the month only when the dates are exactly one whole month, else it is empty.
     */
    private void syncMonth() {
        LocalDate from = dateRange.getFrom();
        LocalDate to = dateRange.getTo();
        boolean wholeMonth = from != null && to != null && from.getDayOfMonth() == 1
                && to.equals(YearMonth.from(from).atEndOfMonth());
        syncing = true;
        try {
            monthBox.setValue(wholeMonth ? from.getMonth() : null);
            if (from != null) {
                if (!yearBox.getItems().contains(from.getYear())) {
                    yearBox.getItems().add(from.getYear());
                }
                yearBox.setValue(from.getYear());
            }
        } finally {
            syncing = false;
        }
    }

    private static <T> StringConverter<T> converter(Function<T, String> text) {
        return new StringConverter<>() {
            @Override
            public String toString(T item) {
                return item == null ? "" : text.apply(item);
            }

            @Override
            public T fromString(String s) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** Loads the chosen dates again (after a category change or a saved expense). */
    public void reload() {
        load();
    }

    /** Back to this month. */
    @FXML
    private void onClearFilters() {
        showThisMonth();
    }

    /** This month's first to last day (also loads it). */
    private void showThisMonth() {
        YearMonth month = YearMonth.now();
        dateRange.setRange(month.atDay(1), month.atEndOfMonth());
    }

    @FXML
    private void onAdd() {
        openForm(null);
    }

    /** Add (expense = null) or edit popup; the tree and totals reload after a save or delete. */
    private void openForm(Expense expense) {
        FXMLLoader loader = WindowManager.loader(FORM_FXML);
        Parent root = WindowManager.load(loader, FORM_FXML);
        ExpenseFormController form = loader.getController();
        if (!form.setUp(expense)) {
            return;
        }
        WindowManager.dialog(root, I18n.get(expense == null ? "expense.add_title" : "expense.edit_title")).showAndWait();
        if (form.isSaved()) {
            reload();
        }
    }

    /** Today's total, whatever dates are chosen. */
    private void showToday() {
        try {
            LocalDate today = LocalDate.now();
            todayLabel.setText(MoneyUtil.format(service.groups(today, today).total()));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load today's expenses", e);
            todayLabel.setText("—");
        }
    }

    /**
     * Total per day over the chosen dates, counted up to today (days still to come are not counted, so this
     * month's average is not watered down by the days left).
     */
    private BigDecimal dailyAverage() {
        LocalDate from = dateRange.getFrom();
        LocalDate to = dateRange.getTo();
        if (from == null || to == null) {
            return BigDecimal.ZERO;
        }
        LocalDate last = to.isAfter(LocalDate.now()) ? LocalDate.now() : to;
        long days = ChronoUnit.DAYS.between(from, last) + 1;
        return days <= 0 ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
    }

    /** Loads the chosen dates into the tiles and the tree; categories the user closed stay closed. */
    private void load() {
        GroupedExpenses data;
        try {
            data = service.groups(dateRange.getFrom(), dateRange.getTo());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load expenses", e);
            Notifier.error(I18n.get("msg.error"));
            data = new GroupedExpenses(List.of(), BigDecimal.ZERO, 0);
        }
        total = data.total();
        totalLabel.setText(MoneyUtil.format(total));
        countLabel.setText(I18n.digits(String.valueOf(data.count())));
        showToday();
        averageLabel.setText(MoneyUtil.format(dailyAverage()));
        if (data.groups().isEmpty()) {
            topNameLabel.setText("—");
            topAmountLabel.setText("");
        } else {
            Group top = data.groups().get(0);
            topNameLabel.setText(ExpenseCategoriesController.nameText(top.category()));
            topAmountLabel.setText(MoneyUtil.format(top.total()));
        }

        Set<Long> closed = new HashSet<>();
        for (TreeItem<Line> item : tree.getRoot().getChildren()) {
            if (!item.isExpanded()) {
                closed.add(item.getValue().group().category().getId());
            }
        }
        List<TreeItem<Line>> groups = new ArrayList<>();
        for (Group g : data.groups()) {
            TreeItem<Line> item = new TreeItem<>(new Line(g, null));
            for (Expense e : g.expenses()) {
                item.getChildren().add(new TreeItem<>(new Line(g, e)));
            }
            item.setExpanded(!closed.contains(g.category().getId()));
            groups.add(item);
        }
        tree.getRoot().getChildren().setAll(groups);
        tree.getRoot().setExpanded(true);
        TableHelper.fitColumns(tree);
    }

    /** The category's part of the total shown, e.g. "২৫.৫%". */
    private String shareText(BigDecimal amount) {
        if (total.signum() == 0) {
            return "—";
        }
        return I18n.digits(amount.multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP).toPlainString()) + "%";
    }

    /** An expense's full data on hover; a category row has none. */
    private RowTip rowTip(Line l) {
        if (l.isGroup()) {
            return null;
        }
        Expense e = l.expense();
        List<TipLine> lines = new ArrayList<>();
        lines.add(new TipLine(I18n.get("label.date"), DateUtil.format(e.getDate()), false));
        lines.add(new TipLine(I18n.get("label.amount"), MoneyUtil.format(e.getAmount()), true));
        lines.add(new TipLine(I18n.get("expense.account"),
                I18n.pick(e.getAccount().getName(), e.getAccount().getNameEn()), false));
        if (e.getNote() != null) {
            lines.add(new TipLine(I18n.get("label.note"), e.getNote(), false));
        }
        return new RowTip(ExpenseCategoriesController.nameText(e.getCategory()), lines);
    }
}
