package com.eh.bms.controller;

import com.eh.bms.model.Party;
import com.eh.bms.model.PartyStatus;
import com.eh.bms.model.ProductCategory;
import com.eh.bms.service.BusinessProfileService;
import com.eh.bms.service.PartyService;
import com.eh.bms.service.ProductCategoryService;
import com.eh.bms.service.ReportService;
import com.eh.bms.service.ReportService.Period;
import com.eh.bms.service.ReportTable;
import com.eh.bms.service.ReportTable.Column;
import com.eh.bms.service.ReportTable.Kind;
import com.eh.bms.util.DateRangePicker;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.ExcelExporter;
import com.eh.bms.util.I18n;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PartyText;
import com.eh.bms.util.SearchPicker;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.UnitUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reports page (Stage 16): pick a report on the left, set its filters (only the ones it uses show), see it as a
 * table and save it as an Excel file. The table and the file hold the same {@link ReportTable}. A report loads
 * as soon as a filter changes; clearing a required date range goes back to its default.
 */
public class ReportsController {

    private static final Logger LOG = Logger.getLogger(ReportsController.class.getName());
    /** Longest range the daily cash report builds (one row for every day). */
    private static final long MAX_DAILY_DAYS = 3660;
    private static final int YEARS_BACK = 5;
    private static final int PICKER_RESULTS = 10;

    private enum Filter {
        PERIOD, RANGE, MONTH, CATEGORY, PARTY
    }

    /** The reports in the order of the list (16.2-16.12) with the filters each uses. */
    private enum Report {
        DAILY_CASH("report.daily_cash", Filter.RANGE),
        STOCK_POSITION("report.stock_position", Filter.CATEGORY),
        PARTY_LEDGER("report.party_ledger", Filter.PARTY, Filter.RANGE),
        PURCHASE_SUMMARY("report.purchase_summary", Filter.PERIOD, Filter.RANGE),
        SALES_SUMMARY("report.sales_summary", Filter.PERIOD, Filter.RANGE),
        EXPENSE_SUMMARY("report.expense_summary", Filter.PERIOD, Filter.RANGE),
        DUE_COLLECTION("report.due_collection"),
        PAYABLE("report.payable"),
        SALARY("report.salary", Filter.MONTH),
        STOCK_VALUATION("report.stock_valuation", Filter.CATEGORY),
        PROFIT_LOSS("report.profit_loss", Filter.PERIOD, Filter.RANGE),
        VAT("report.vat", Filter.MONTH),
        MOBILE_PAYMENTS("report.mobile_payments", Filter.RANGE),
        WRITE_OFFS("report.writeoffs", Filter.RANGE);

        private final String titleKey;
        private final Set<Filter> filters;

        Report(String titleKey, Filter... filters) {
            this.titleKey = titleKey;
            this.filters = filters.length == 0 ? EnumSet.noneOf(Filter.class) : EnumSet.of(filters[0], filters);
        }

        boolean uses(Filter filter) {
            return filters.contains(filter);
        }

        /** Every report with a date range needs one, except the party ledger (blank = all time). */
        boolean needsRange() {
            return uses(Filter.RANGE) && this != PARTY_LEDGER;
        }
    }

    @FXML private ListView<Report> reportList;
    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private ComboBox<Period> periodBox;
    @FXML private ComboBox<Month> monthBox;
    @FXML private ComboBox<Integer> yearBox;
    /** First item is null = all categories. */
    @FXML private ComboBox<ProductCategory> categoryBox;
    @FXML private StackPane partyBox;
    @FXML private TextField partyField;
    @FXML private DateRangePicker dateRange;
    @FXML private Button exportButton;
    @FXML private TableView<List<Object>> table;

    private final ReportService service = new ReportService();
    private final PartyService partyService = new PartyService();
    private final ProductCategoryService categoryService = new ProductCategoryService();
    private SearchPicker<Party> partyPicker;
    private Report current;
    private ReportTable shown;
    /** The totals row of the table shown (drawn bold). */
    private List<Object> totalRow;
    /** True while filters are being set by the page itself, so they do not each load the report. */
    private boolean loading;
    /** Folder of the last saved file. */
    private static Path lastFolder;

    @FXML
    private void initialize() {
        TableHelper.setUp(table);
        TableHelper.rowStyle(table, row -> row == totalRow ? "report-total-row" : null);

        reportList.getItems().setAll(Report.values());
        reportList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Report report, boolean empty) {
                super.updateItem(report, empty);
                setText(empty || report == null ? null : I18n.get(report.titleKey));
            }
        });
        reportList.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (now != null) {
                select(now);
            }
        });

        periodBox.setConverter(converter(p -> I18n.get("report.period." + p.name())));
        monthBox.getItems().setAll(Month.values());
        monthBox.setConverter(converter(m -> m.getDisplayName(TextStyle.FULL_STANDALONE, I18n.getLocale())));
        int thisYear = LocalDate.now().getYear();
        for (int y = thisYear + 1; y >= thisYear - YEARS_BACK; y--) {
            yearBox.getItems().add(y);
        }
        yearBox.setConverter(converter(y -> I18n.digits(String.valueOf(y))));
        categoryBox.setCellFactory(list -> new CategoryCell(false));
        categoryBox.setButtonCell(new CategoryCell(true));
        try {
            categoryBox.getItems().add(null);
            categoryBox.getItems().addAll(categoryService.all());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load product categories", e);
            Notifier.error(I18n.get("msg.error"));
        }

        partyPicker = new SearchPicker<>(partyField, this::searchParties, PartyText::nameWithPhone);
        // A search typed over a picked party clears it; the report changes only when one is picked or the field is emptied
        partyPicker.valueProperty().addListener((obs, old, now) -> {
            if (now != null || partyField.getText().isBlank()) {
                load();
            }
        });
        partyField.textProperty().addListener((obs, old, now) -> {
            if (now.isBlank()) {
                load();
            }
        });

        periodBox.valueProperty().addListener((obs, old, now) -> load());
        monthBox.valueProperty().addListener((obs, old, now) -> load());
        yearBox.valueProperty().addListener((obs, old, now) -> load());
        categoryBox.valueProperty().addListener((obs, old, now) -> load());
        dateRange.setOnChange(this::load);

        reportList.getSelectionModel().selectFirst();
    }

    /** A report picked: its own filters show, with their first values, and it loads. */
    private void select(Report report) {
        current = report;
        loading = true;
        try {
            show(periodBox, report.uses(Filter.PERIOD));
            show(dateRange, report.uses(Filter.RANGE));
            show(monthBox, report.uses(Filter.MONTH));
            show(yearBox, report.uses(Filter.MONTH));
            show(categoryBox, report.uses(Filter.CATEGORY));
            show(partyBox, report.uses(Filter.PARTY));
            resetFilters();
        } finally {
            loading = false;
        }
        load();
    }

    /** Back to the first values of the picked report. */
    @FXML
    private void onClearFilters() {
        loading = true;
        try {
            resetFilters();
        } finally {
            loading = false;
        }
        load();
    }

    private void resetFilters() {
        LocalDate today = LocalDate.now();
        if (current == Report.PROFIT_LOSS) {
            periodBox.getItems().setAll(Period.MONTHLY, Period.YEARLY);
            periodBox.setValue(Period.MONTHLY);
        } else {
            periodBox.getItems().setAll(Period.values());
            periodBox.setValue(Period.DAILY);
        }
        monthBox.setValue(today.getMonth());
        yearBox.setValue(today.getYear());
        categoryBox.setValue(null);
        partyPicker.setValue(null);
        partyField.clear();
        if (current == Report.PARTY_LEDGER) {
            dateRange.clear();
        } else {
            setDefaultRange();
        }
    }

    /** This month; for profit and loss this whole year. */
    private void setDefaultRange() {
        LocalDate today = LocalDate.now();
        if (current == Report.PROFIT_LOSS) {
            dateRange.setRange(today.withDayOfYear(1), today.withDayOfYear(today.lengthOfYear()));
        } else {
            YearMonth month = YearMonth.from(today);
            dateRange.setRange(month.atDay(1), month.atEndOfMonth());
        }
    }

    /** Builds the picked report with the filters now set and shows it. */
    private void load() {
        if (loading || current == null) {
            return;
        }
        try {
            ReportTable result = build();
            if (result != null) {
                display(result);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not build the report " + current, e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    /** The picked report as a table; null when the filters are not complete (the page then fixes them). */
    private ReportTable build() {
        LocalDate from = dateRange.getFrom();
        LocalDate to = dateRange.getTo();
        if (current.needsRange() && (from == null || to == null)) {
            setDefaultRange(); // loads again through the range's change
            return null;
        }
        Long categoryId = categoryBox.getValue() == null ? null : categoryBox.getValue().getId();
        return switch (current) {
            case DAILY_CASH -> {
                if (ChronoUnit.DAYS.between(from, to) > MAX_DAILY_DAYS) {
                    Notifier.error(I18n.get("report.range_too_long"));
                    yield null;
                }
                yield service.dailyCash(from, to);
            }
            case STOCK_POSITION -> service.stockPosition(categoryId);
            case PARTY_LEDGER -> service.partyLedger(partyPicker.getValue(), from, to);
            case PURCHASE_SUMMARY -> service.purchaseSummary(periodBox.getValue(), from, to);
            case SALES_SUMMARY -> service.salesSummary(periodBox.getValue(), from, to);
            case EXPENSE_SUMMARY -> service.expenseSummary(periodBox.getValue(), from, to);
            case DUE_COLLECTION -> service.dueCollection();
            case PAYABLE -> service.payable();
            case SALARY -> monthBox.getValue() == null || yearBox.getValue() == null ? null
                    : service.salary(YearMonth.of(yearBox.getValue(), monthBox.getValue()));
            case STOCK_VALUATION -> service.stockValuation(categoryId);
            case PROFIT_LOSS -> service.profitLoss(periodBox.getValue(), from, to);
            case MOBILE_PAYMENTS -> service.mobilePayments(from, to);
            case WRITE_OFFS -> service.writeOffs(from, to);
            case VAT -> monthBox.getValue() == null || yearBox.getValue() == null ? null
                    : service.vat(YearMonth.of(yearBox.getValue(), monthBox.getValue()));
        };
    }

    /** Puts a built report into the table: one column per report column, the totals as a last bold row. */
    private void display(ReportTable report) {
        shown = report;
        titleLabel.setText(report.title());
        subtitleLabel.setText(report.subtitle());
        show(subtitleLabel, !report.subtitle().isBlank());

        table.getColumns().clear();
        for (int i = 0; i < report.columns().size(); i++) {
            Column column = report.columns().get(i);
            int index = i;
            TableColumn<List<Object>, String> c = new TableColumn<>(column.heading());
            c.setCellValueFactory(cell -> new SimpleStringProperty(
                    cellText(column.kind(), index < cell.getValue().size() ? cell.getValue().get(index) : null)));
            c.setSortable(false);
            if (column.kind() == Kind.MONEY || column.kind() == Kind.NUMBER) {
                c.getStyleClass().add("money-column");
            }
            table.getColumns().add(c);
        }
        List<List<Object>> items = new ArrayList<>(report.rows());
        totalRow = report.isEmpty() ? null : report.totals();
        if (totalRow != null) {
            items.add(totalRow);
        }
        table.getItems().setAll(items);
        TableHelper.fitColumns(table);
        exportButton.setDisable(report.isEmpty());
    }

    /** A cell as text: money with the currency symbol, dates in the chosen date format, digits by language. */
    private static String cellText(Kind kind, Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof LocalDate d) {
            return DateUtil.format(d);
        }
        if (value instanceof BigDecimal b) {
            return kind == Kind.MONEY ? MoneyUtil.format(b) : UnitUtil.number(b);
        }
        return I18n.digits(String.valueOf(value));
    }

    // ---------------------------------------------------------------- Excel (16.1)

    @FXML
    private void onExport() {
        if (shown == null || shown.isEmpty()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("report.export"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx"));
        chooser.setInitialFileName(fileName(shown.title()));
        Path folder = lastFolder != null ? lastFolder : Path.of(System.getProperty("user.home"));
        if (folder.toFile().isDirectory()) {
            chooser.setInitialDirectory(folder.toFile());
        }
        Window window = exportButton.getScene() == null ? null : exportButton.getScene().getWindow();
        File file = chooser.showSaveDialog(window);
        if (file == null) {
            return;
        }
        Path path = file.toPath();
        if (!path.getFileName().toString().toLowerCase().endsWith(".xlsx")) {
            path = path.resolveSibling(path.getFileName() + ".xlsx");
        }
        try {
            ExcelExporter.export(path, shown, new BusinessProfileService().get().getName());
            lastFolder = path.toAbsolutePath().getParent();
            Notifier.success(I18n.get("report.exported", path.toAbsolutePath()));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save the report to " + path, e);
            Notifier.error(I18n.get("report.export_failed"));
        }
    }

    /** "Sales summary 2026-10-19.xlsx": the title without characters a file name cannot hold. */
    private static String fileName(String title) {
        return title.replaceAll("[\\\\/:*?\"<>|]", "_") + " " + LocalDate.now() + ".xlsx";
    }

    // ---------------------------------------------------------------- helpers

    /** Parties by name or mobile, active ones first; inactive parties keep a ledger too. */
    private List<Party> searchParties(String text) {
        List<Party> found = new ArrayList<>(partyService.page(PartyStatus.ACTIVE, text, 0, PICKER_RESULTS).items());
        if (found.size() < PICKER_RESULTS) {
            found.addAll(partyService.page(PartyStatus.INACTIVE, text, 0, PICKER_RESULTS - found.size()).items());
        }
        return found;
    }

    /** Shows a filter or hides it with no gap left. */
    private static void show(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
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

    /** Shows a category by name in the current language, and null as "All categories". */
    private static final class CategoryCell extends ListCell<ProductCategory> {
        /** The combo box's own cell: it is "empty" when the value is null, which means all categories. */
        private final boolean buttonCell;

        CategoryCell(boolean buttonCell) {
            this.buttonCell = buttonCell;
        }

        @Override
        protected void updateItem(ProductCategory category, boolean empty) {
            super.updateItem(category, empty);
            if (empty && !buttonCell) {
                setText(null);
            } else {
                setText(category == null ? I18n.get("stock.value.all_categories")
                        : I18n.pick(category.getName(), category.getNameEn()));
            }
        }
    }
}
