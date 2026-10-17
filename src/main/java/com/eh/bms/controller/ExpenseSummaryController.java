package com.eh.bms.controller;

import com.eh.bms.model.ExpenseCategoryType;
import com.eh.bms.service.ExpenseService;
import com.eh.bms.service.ExpenseService.Summary;
import com.eh.bms.service.ExpenseService.SummaryRow;
import com.eh.bms.util.I18n;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.TableHelper;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.EnumMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Monthly expense summary (13.4): pick a month and year (this month at first); shows the month's total, the total
 * per category type, and each category's total and share, largest first.
 */
public class ExpenseSummaryController {

    private static final Logger LOG = Logger.getLogger(ExpenseSummaryController.class.getName());
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @FXML private ComboBox<Month> monthBox;
    @FXML private ComboBox<Integer> yearBox;
    @FXML private Label totalLabel;
    @FXML private Label operationalTitle;
    @FXML private Label operationalLabel;
    @FXML private Label overheadTitle;
    @FXML private Label overheadLabel;
    @FXML private Label otherTitle;
    @FXML private Label otherLabel;
    @FXML private StackPane tableHolder;

    private final ExpenseService service = new ExpenseService();
    private PaginatedTable<SummaryRow> table;
    /** All rows of the chosen month; the table shows them one page at a time. */
    private List<SummaryRow> rows = List.of();
    private BigDecimal total = BigDecimal.ZERO;
    /** True while the month and year are being set, so their listeners do not load twice. */
    private boolean setting;

    @FXML
    private void initialize() {
        operationalTitle.setText(ExpenseCategoriesController.typeText(ExpenseCategoryType.OPERATIONAL));
        overheadTitle.setText(ExpenseCategoriesController.typeText(ExpenseCategoryType.OVERHEAD));
        otherTitle.setText(ExpenseCategoriesController.typeText(ExpenseCategoryType.OTHER));

        table = new PaginatedTable<>(this::loadPage);
        TableView<SummaryRow> view = table.getTableView();
        view.getColumns().add(TableHelper.column("expense.category", r -> ExpenseCategoriesController.nameText(r.category())));
        view.getColumns().add(TableHelper.column("expense.category.type",
                r -> ExpenseCategoriesController.typeText(r.category().getType())));
        view.getColumns().add(TableHelper.moneyColumn("label.amount", r -> MoneyUtil.format(r.total())));
        view.getColumns().add(TableHelper.moneyColumn("expense.summary.share", this::shareText));

        monthBox.getItems().setAll(Month.values());
        monthBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Month m) {
                return m == null ? "" : m.getDisplayName(TextStyle.FULL_STANDALONE, I18n.getLocale());
            }

            @Override
            public Month fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        yearBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer y) {
                return y == null ? "" : I18n.digits(String.valueOf(y));
            }

            @Override
            public Integer fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
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
        showMonth(YearMonth.now());
    }

    /** Loads the chosen month again (expenses change on the list tab); also adds a new oldest year if any. */
    @FXML
    public void refresh() {
        Month month = monthBox.getValue();
        Integer year = yearBox.getValue();
        showMonth(month == null || year == null ? YearMonth.now() : YearMonth.of(year, month));
    }

    /** Back to this month. */
    @FXML
    private void onClearFilter() {
        showMonth(YearMonth.now());
    }

    /** Fills the years (oldest expense's year to this year), selects {@code month} and loads it. */
    private void showMonth(YearMonth month) {
        setting = true;
        try {
            int first;
            try {
                first = service.firstYear();
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "Could not load the first expense year", e);
                first = LocalDate.now().getYear();
            }
            yearBox.getItems().clear();
            for (int y = LocalDate.now().getYear(); y >= Math.min(first, month.getYear()); y--) {
                yearBox.getItems().add(y);
            }
            yearBox.setValue(month.getYear());
            monthBox.setValue(month.getMonth());
        } finally {
            setting = false;
        }
        loadRows();
    }

    private void loadRows() {
        Month month = monthBox.getValue();
        Integer year = yearBox.getValue();
        if (month == null || year == null) {
            return;
        }
        Summary summary;
        try {
            summary = service.monthSummary(YearMonth.of(year, month));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load the expense summary", e);
            Notifier.error(I18n.get("msg.error"));
            summary = new Summary(List.of(), BigDecimal.ZERO, new EnumMap<>(ExpenseCategoryType.class));
        }
        rows = summary.rows();
        total = summary.total();
        totalLabel.setText(MoneyUtil.format(total));
        operationalLabel.setText(MoneyUtil.format(summary.byType().getOrDefault(ExpenseCategoryType.OPERATIONAL, BigDecimal.ZERO)));
        overheadLabel.setText(MoneyUtil.format(summary.byType().getOrDefault(ExpenseCategoryType.OVERHEAD, BigDecimal.ZERO)));
        otherLabel.setText(MoneyUtil.format(summary.byType().getOrDefault(ExpenseCategoryType.OTHER, BigDecimal.ZERO)));
        table.reload();
    }

    /** The category's part of the month's total, e.g. "২৫.৫%". */
    private String shareText(SummaryRow r) {
        if (total.signum() == 0) {
            return "—";
        }
        BigDecimal share = r.total().multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP);
        return I18n.digits(share.toPlainString()) + "%";
    }

    private PageResult<SummaryRow> loadPage(int offset, int limit) {
        int from = Math.min(offset, rows.size());
        int to = Math.min(from + limit, rows.size());
        return new PageResult<>(rows.subList(from, to), rows.size());
    }
}
