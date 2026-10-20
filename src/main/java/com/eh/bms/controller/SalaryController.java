package com.eh.bms.controller;

import com.eh.bms.model.SalaryType;
import com.eh.bms.model.User;
import com.eh.bms.service.SalaryService;
import com.eh.bms.service.SalaryService.MonthSheet;
import com.eh.bms.service.SalaryService.SheetRow;
import com.eh.bms.service.UserSession;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.PartyText;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.TableHelper.RowAction;
import com.eh.bms.util.TableHelper.RowTip;
import com.eh.bms.util.TableHelper.TipLine;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Salary tab of the users page (14.3 - 14.5, N12, admin only): one month's salary for every active user with a
 * salary (and anyone with a sheet or payment that month): net (or "not worked out"), advance carried from earlier
 * months, paid and due (or advance left), with the month's totals. Each row: salary (work out and pay in one popup)
 * and history. Double-click opens the history.
 */
public class SalaryController {

    private static final Logger LOG = Logger.getLogger(SalaryController.class.getName());
    /** Years in the month pickers: this many back from this year. */
    private static final int YEARS_BACK = 5;

    @FXML private ComboBox<Month> monthBox;
    @FXML private ComboBox<Integer> yearBox;
    @FXML private Label totalNetLabel;
    @FXML private Label totalCarryLabel;
    @FXML private Label totalPaidLabel;
    @FXML private Label totalDueLabel;
    @FXML private StackPane tableHolder;

    private final SalaryService service = new SalaryService();
    private PaginatedTable<SheetRow> table;
    /** All rows of the chosen month; the table shows them one page at a time. */
    private List<SheetRow> rows = List.of();
    /** True while the month and year are being set, so their listeners do not load twice. */
    private boolean setting;

    @FXML
    private void initialize() {
        table = new PaginatedTable<>(this::loadPage);
        TableView<SheetRow> view = table.getTableView();
        view.getColumns().add(TableHelper.column("salary.col.employee", r -> r.user().getName()));
        view.getColumns().add(TableHelper.column("user.col.role", r -> roleText(r.user())));
        view.getColumns().add(TableHelper.column("salary.col.type", r -> typeText(r.user().getSalaryType())));
        view.getColumns().add(TableHelper.styledColumn("salary.col.net",
                r -> r.processed() ? MoneyUtil.format(r.sheet().getNetSalary()) : I18n.get("salary.not_processed"),
                r -> null));
        view.getColumns().add(TableHelper.moneyColumn("salary.col.carry", r -> carryText(r.carryIn())));
        view.getColumns().add(TableHelper.moneyColumn("salary.col.paid", r -> MoneyUtil.format(r.paid())));
        view.getColumns().add(TableHelper.styledColumn("salary.col.due",
                r -> r.processed() ? dueCell(r.due(), r.advanceLeft()) : "—", r -> dueStyle(r.due())));
        view.getColumns().add(TableHelper.actionsColumn("label.actions",
                new RowAction<SheetRow>(Icons.MONEY_OUT, "salary.action.salary", "salary.action.salary_tip",
                        "icon-out", r -> openSalary(r.user())),
                new RowAction<SheetRow>(Icons.LEDGER, "button.view", "salary.action.history_tip", "icon-view",
                        r -> openHistory(r.user()))));
        TableHelper.onDoubleClick(view, true, r -> openHistory(r.user()));
        TableHelper.rowTooltip(view, SalaryController::rowTip);

        monthConverter(monthBox);
        yearConverter(yearBox);
        monthBox.valueProperty().addListener((obs, old, now) -> {
            if (!setting) {
                loadRows();
            }
        });
        yearBox.valueProperty().addListener((obs, old, now) -> {
            if (!setting) {
                loadRows();
            }
        });
        tableHolder.getChildren().setAll(table);
        // Loaded when the tab opens (reload)
        setMonth(YearMonth.now());
    }

    /** Loads the chosen month again (the users page calls it when this tab opens). */
    @FXML
    public void reload() {
        loadRows();
    }

    /** Back to this month. */
    @FXML
    private void onClearFilter() {
        setMonth(YearMonth.now());
        loadRows();
    }

    private void setMonth(YearMonth month) {
        setting = true;
        try {
            yearBox.getItems().setAll(years(month));
            yearBox.setValue(month.getYear());
            monthBox.getItems().setAll(Month.values());
            monthBox.setValue(month.getMonth());
        } finally {
            setting = false;
        }
    }

    /** The chosen month, or this month when none. */
    private YearMonth chosenMonth() {
        Month m = monthBox.getValue();
        Integer y = yearBox.getValue();
        return m == null || y == null ? YearMonth.now() : YearMonth.of(y, m);
    }

    private void loadRows() {
        YearMonth month = chosenMonth();
        MonthSheet sheet;
        try {
            sheet = service.monthSheet(month);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load the salary sheet", e);
            Notifier.error(I18n.get("msg.error"));
            sheet = new MonthSheet(month, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        rows = sheet.rows();
        totalNetLabel.setText(MoneyUtil.format(sheet.totalNet()));
        totalCarryLabel.setText(MoneyUtil.format(sheet.totalCarry()));
        totalPaidLabel.setText(MoneyUtil.format(sheet.totalPaid()));
        totalDueLabel.setText(MoneyUtil.format(sheet.totalDue()));
        setStyle(totalDueLabel, dueStyle(sheet.totalDue()));
        table.reload();
    }

    private PageResult<SheetRow> loadPage(int offset, int limit) {
        int from = Math.min(offset, rows.size());
        int to = Math.min(from + limit, rows.size());
        return new PageResult<>(rows.subList(from, to), rows.size());
    }

    private void openSalary(User user) {
        if (SalaryFormController.open(user, chosenMonth())) {
            loadRows();
        }
    }

    private void openHistory(User user) {
        SalaryHistoryController.open(user);
        // A payment may have been deleted there
        loadRows();
    }

    // ---------------------------------------------------------------- shared texts (salary popup, history, users)

    /** Advance carried from earlier months, or "—". */
    static String carryText(BigDecimal carryIn) {
        return carryIn == null || carryIn.signum() == 0 ? "—" : MoneyUtil.format(carryIn);
    }

    /** A processed month's due for a table cell: the amount, or "অগ্রিম বাকি ৳X" when paid ahead. */
    static String dueCell(BigDecimal due, BigDecimal advanceLeft) {
        return advanceLeft != null && advanceLeft.signum() > 0
                ? I18n.get("salary.advance_left", MoneyUtil.format(advanceLeft)) : MoneyUtil.format(due);
    }

    /** "বাকি ৳X", or "অগ্রিম বাকি ৳X" when paid ahead. */
    static String dueText(BigDecimal due, BigDecimal advanceLeft) {
        return advanceLeft != null && advanceLeft.signum() > 0
                ? I18n.get("salary.advance_left", MoneyUtil.format(advanceLeft))
                : I18n.get("salary.due_text", MoneyUtil.format(due));
    }

    private static RowTip rowTip(SheetRow r) {
        User u = r.user();
        List<TipLine> lines = new ArrayList<>();
        lines.add(new TipLine(I18n.get("user.col.role"), roleText(u), false));
        lines.add(new TipLine(I18n.get("salary.col.type"), typeText(u.getSalaryType()), false));
        lines.add(new TipLine(I18n.get("salary.base"), baseText(u), true));
        if (r.processed()) {
            lines.add(new TipLine(I18n.get("salary.gross"), MoneyUtil.format(r.sheet().getGrossSalary()), true));
            lines.add(new TipLine(I18n.get("salary.deductions"), MoneyUtil.format(r.sheet().getDeductions()), true));
            if (r.sheet().getNote() != null) {
                lines.add(new TipLine(I18n.get("salary.reason"), r.sheet().getNote(), false));
            }
            lines.add(new TipLine(I18n.get("salary.bonus"), MoneyUtil.format(r.sheet().getBonus()), true));
            lines.add(new TipLine(I18n.get("salary.net"), MoneyUtil.format(r.sheet().getNetSalary()), true));
            lines.add(new TipLine(I18n.get("salary.col.carry"), carryText(r.carryIn()), true));
            lines.add(new TipLine(I18n.get("salary.col.paid"), MoneyUtil.format(r.paid()), true));
            lines.add(new TipLine(I18n.get("salary.col.due"), dueCell(r.due(), r.advanceLeft()), true));
        } else {
            lines.add(new TipLine(I18n.get("salary.col.net"), I18n.get("salary.not_processed"), false));
            lines.add(new TipLine(I18n.get("salary.col.carry"), carryText(r.carryIn()), true));
            lines.add(new TipLine(I18n.get("salary.advance"), MoneyUtil.format(r.paid()), true));
        }
        return new RowTip(u.getName(), lines);
    }

    /** Red while something is still due. */
    static String dueStyle(BigDecimal due) {
        return due != null && due.signum() > 0 ? PartyText.PAY_STYLE : null;
    }

    /** Puts {@code style} (may be null) on the label in place of the due colour it had. */
    static void setStyle(Label label, String style) {
        label.getStyleClass().remove(PartyText.PAY_STYLE);
        if (style != null) {
            label.getStyleClass().add(style);
        }
    }

    /** "অ্যাডমিন" / "ক্যাশিয়ার" ... */
    static String roleText(User u) {
        return u.getRole() == null ? "—" : I18n.get("role." + u.getRole().name());
    }

    /** "মাসিক" / "দৈনিক" / "ঘণ্টা ভিত্তিক"; "—" when not set yet. */
    static String typeText(SalaryType type) {
        return type == null ? "—" : I18n.get("salary.type." + type.name());
    }

    /** Label of the salary amount by type: per month / per day / per hour. */
    static String baseSalaryKey(SalaryType type) {
        return type == null ? "employee.base_salary" : "employee.base_salary." + type.name();
    }

    /** Salary with its period, e.g. "৳ 500.00 / দিন"; "—" when not set yet. */
    static String baseText(User u) {
        return u.hasSalary() ? I18n.get("salary.base." + u.getSalaryType().name(), MoneyUtil.format(u.getBaseSalary()))
                : "—";
    }

    /** e.g. "অক্টোবর ২০২৬". */
    static String monthText(YearMonth month) {
        return month == null ? "" : monthName(month.getMonth()) + " " + I18n.digits(String.valueOf(month.getYear()));
    }

    static String monthName(Month m) {
        return m.getDisplayName(TextStyle.FULL_STANDALONE, I18n.getLocale());
    }

    /** A count or amount of days / hours without extra zeros, e.g. "২২.৫". */
    static String number(BigDecimal value) {
        return value == null ? "" : I18n.digits(value.stripTrailingZeros().toPlainString());
    }

    static void monthConverter(ComboBox<Month> box) {
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(Month m) {
                return m == null ? "" : monthName(m);
            }

            @Override
            public Month fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
    }

    static void yearConverter(ComboBox<Integer> box) {
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer y) {
                return y == null ? "" : I18n.digits(String.valueOf(y));
            }

            @Override
            public Integer fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
    }

    /** This year down to {@link #YEARS_BACK} years ago, plus {@code month}'s year if it is older. */
    static List<Integer> years(YearMonth month) {
        int now = YearMonth.now().getYear();
        List<Integer> years = new ArrayList<>();
        for (int y = now; y >= Math.min(now - YEARS_BACK, month.getYear()); y--) {
            years.add(y);
        }
        return years;
    }

    /** Salary is on the users page: the admin's alone. */
    static boolean isAdmin() {
        return UserSession.isLoggedIn() && UserSession.get().isAdmin();
    }
}
