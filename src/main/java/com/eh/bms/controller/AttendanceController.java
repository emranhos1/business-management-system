package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Attendance;
import com.eh.bms.model.AttendanceStatus;
import com.eh.bms.model.Employee;
import com.eh.bms.model.SalaryType;
import com.eh.bms.service.AppConfigService;
import com.eh.bms.service.AttendanceService;
import com.eh.bms.service.AttendanceService.DayRow;
import com.eh.bms.service.AttendanceService.Entry;
import com.eh.bms.service.AttendanceService.MonthSummary;
import com.eh.bms.service.AttendanceService.Result;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.TableHelper.RowTip;
import com.eh.bms.util.TableHelper.TipLine;
import com.eh.bms.util.TimePicker;
import com.eh.bms.util.UnsavedWork;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Daily attendance (14.2): one day's sheet with every employee, saved all at once. Present is saved
 * automatically for every work day ({@link AttendanceService#autoFill}, run when a day loads), so the admin only
 * changes the exceptions (user decision). A row still without attendance starts as present, or as none on a weekly
 * off day. In / out time is optional, but needed for hourly employees when present or half day (their pay is
 * worked out from the hours). Attendance can still be given on weekly off days. The month view shows each employee's counts for a month.
 * Saving needs CREATE or EDIT on the Employees module (marking attendance adds rows and changes them).
 */
public class AttendanceController {

    private static final Logger LOG = Logger.getLogger(AttendanceController.class.getName());
    private static final String OFF_DAY = "attendance-off-day";
    private static final String INVALID = "field-invalid";
    private static final int NOTE_MAX = 255;
    private static final int YEARS_BACK = 5;
    /** "9:30", "09:30AM", "9AM", "21:30" (spaces and dots already removed). */
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2})(?::(\\d{2}))?(AM|PM)?$");

    /** What the status list offers: the four statuses, or no attendance (an existing row is removed). */
    private enum Mark {
        PRESENT(AttendanceStatus.PRESENT),
        HALF_DAY(AttendanceStatus.HALF_DAY),
        LEAVE(AttendanceStatus.LEAVE),
        ABSENT(AttendanceStatus.ABSENT),
        NONE(null);

        final AttendanceStatus status;

        Mark(AttendanceStatus status) {
            this.status = status;
        }

        static Mark of(AttendanceStatus status) {
            for (Mark m : values()) {
                if (m.status == status) {
                    return m;
                }
            }
            return NONE;
        }

        /** In / out times are kept only for days the employee came. */
        boolean keepsTime() {
            return this == PRESENT || this == HALF_DAY;
        }

        String text() {
            return I18n.get("attendance.status." + name());
        }
    }

    /** One employee's line on the day sheet; the cells edit its properties. */
    private static final class Row {
        final Employee employee;
        /** The row saved for that day, or null. */
        final Attendance saved;
        final ObjectProperty<Mark> mark = new SimpleObjectProperty<>();
        final StringProperty in = new SimpleStringProperty("");
        final StringProperty out = new SimpleStringProperty("");
        final StringProperty note = new SimpleStringProperty("");
        /** Wrong in / out time found on Save (red box until the field is changed). */
        final BooleanProperty inBad = new SimpleBooleanProperty();
        final BooleanProperty outBad = new SimpleBooleanProperty();

        Row(Employee employee, Attendance saved, Mark start) {
            this.employee = employee;
            this.saved = saved;
            mark.set(start);
            if (saved != null) {
                in.set(timeText(saved.getInTime()));
                out.set(timeText(saved.getOutTime()));
                note.set(saved.getNote() == null ? "" : saved.getNote());
            }
            // Change listeners (an invalidation listener would fire only once until the value is read)
            in.addListener((obs, old, now) -> inBad.set(false));
            out.addListener((obs, old, now) -> outBad.set(false));
            mark.addListener((obs, old, now) -> {
                inBad.set(false);
                outBad.set(false);
            });
        }

        boolean hourly() {
            return employee.getSalaryType() == SalaryType.HOURLY;
        }

        String state() {
            return employee.getId() + ":" + mark.get() + ":" + in.get().trim() + ":" + out.get().trim() + ":"
                    + note.get().trim();
        }
    }

    @FXML private VBox root;
    @FXML private VBox dayView;
    @FXML private DatePicker datePicker;
    @FXML private Label dayLabel;
    @FXML private Button saveButton;
    @FXML private Label notSavedHint;
    @FXML private TableView<Row> dayTable;
    @FXML private Label errorLabel;
    @FXML private VBox monthView;
    @FXML private ComboBox<Month> monthBox;
    @FXML private ComboBox<Integer> yearBox;
    @FXML private StackPane monthTableHolder;

    private final AttendanceService service = new AttendanceService();
    private final AppConfigService configService = new AppConfigService();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private PaginatedTable<MonthSummary> monthTable;
    private List<MonthSummary> monthRows = List.of();
    private Set<DayOfWeek> offDays = EnumSet.noneOf(DayOfWeek.class);
    /** The day the sheet shows (the picker may briefly differ while the user is asked to save). */
    private LocalDate loadedDate;
    /** The sheet as last loaded or saved; a difference means unsaved changes. */
    private String snapshot = "";
    private boolean canSave;
    /** True while the controller sets the picker / month boxes itself, so their listeners do nothing. */
    private boolean setting;

    @FXML
    private void initialize() {
        canSave = UserSession.isLoggedIn()
                && (UserSession.get().can(AppModule.EMPLOYEE, Action.CREATE)
                || UserSession.get().can(AppModule.EMPLOYEE, Action.EDIT));
        saveButton.setVisible(canSave);
        saveButton.setManaged(canSave);
        FormValidator.hideWhenEmpty(notSavedHint, errorLabel);

        DateUtil.apply(datePicker);
        datePicker.setDayCellFactory(p -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                getStyleClass().remove(OFF_DAY);
                setDisable(empty || date == null || date.isAfter(LocalDate.now()));
                if (!empty && date != null && offDays.contains(date.getDayOfWeek())) {
                    getStyleClass().add(OFF_DAY);
                }
            }
        });
        datePicker.valueProperty().addListener((obs, old, date) -> onDateChanged(date));

        setUpDayTable();
        setUpMonthView();

        // Before the language switches (the page reloads), offer to save the day
        UnsavedWork.register(root, this::hasUnsaved, this::save);
    }

    /** Loads the sheet again (the Employees page calls it when the tab opens); unsaved edits are kept. */
    public void reload() {
        if (hasUnsaved()) {
            return;
        }
        loadOffDays();
        LocalDate date = loadedDate == null ? LocalDate.now() : loadedDate;
        if (monthView.isVisible()) {
            loadMonth();
        }
        showDate(date);
    }

    // ---------------------------------------------------------------- day sheet

    private void setUpDayTable() {
        TableHelper.setUp(dayTable);
        dayTable.setItems(rows);
        dayTable.getColumns().add(TableHelper.column("label.name", r -> r.employee.getName()));
        dayTable.getColumns().add(TableHelper.column("attendance.col.designation", r -> designation(r.employee)));
        dayTable.getColumns().add(TableHelper.column("employee.salary_type",
                r -> I18n.get("employee.salary_type." + r.employee.getSalaryType().name())));

        TableColumn<Row, Row> status = rowColumn("attendance.col.status", MarkCell::new);
        String widest = "";
        for (Mark m : Mark.values()) {
            widest = m.text().length() > widest.length() ? m.text() : widest;
        }
        String widestMark = widest;
        TableHelper.fitBy(status, r -> widestMark, 44.0);
        dayTable.getColumns().add(status);

        TableColumn<Row, Row> in = rowColumn("attendance.col.in", () -> new TimeCell(r -> r.in, r -> r.inBad, r -> LocalTime.of(9, 0)));
        TableHelper.fitBy(in, r -> timeExample(), 56.0);
        dayTable.getColumns().add(in);

        TableColumn<Row, Row> out = rowColumn("attendance.col.out", () -> new TimeCell(r -> r.out, r -> r.outBad, AttendanceController::outStart));
        TableHelper.fitBy(out, r -> timeExample(), 56.0);
        dayTable.getColumns().add(out);

        TableColumn<Row, Row> note = rowColumn("label.note", () -> new TextCell(r -> r.note));
        TableHelper.fitBy(note, r -> r.note.get(), 140.0);
        dayTable.getColumns().add(note);

        // The inputs change on the row itself, so the tooltip shows what does not: the employee and what is saved
        TableHelper.rowTooltip(dayTable, r -> {
            List<TipLine> lines = new ArrayList<>();
            String designation = designation(r.employee);
            if (!designation.isEmpty()) {
                lines.add(new TipLine(I18n.get("attendance.col.designation"), designation, false));
            }
            lines.add(new TipLine(I18n.get("attendance.tip.saved"),
                    r.saved == null ? "—" : Mark.of(r.saved.getStatus()).text(), false));
            return new RowTip(r.employee.getName(), lines);
        });
    }

    private static TableColumn<Row, Row> rowColumn(String titleKey,
                                                   java.util.function.Supplier<TableCell<Row, Row>> cell) {
        TableColumn<Row, Row> c = new TableColumn<>(I18n.get(titleKey));
        c.setCellValueFactory(v -> new SimpleObjectProperty<>(v.getValue()));
        c.setCellFactory(col -> cell.get());
        c.setSortable(false);
        return c;
    }

    private void onDateChanged(LocalDate date) {
        if (setting) {
            return;
        }
        if (date == null || date.isAfter(LocalDate.now())) {
            if (date != null) {
                Notifier.error(I18n.get("attendance.error.future"));
            }
            setPicker(loadedDate);
            return;
        }
        if (date.equals(loadedDate)) {
            return;
        }
        if (hasUnsaved()) {
            switch (Dialogs.askSave(I18n.get("msg.unsaved_question"))) {
                case SAVE -> {
                    if (!save()) {
                        setPicker(loadedDate);
                        return;
                    }
                }
                case DISCARD -> {
                    // load the new day without saving
                }
                case CANCEL -> {
                    setPicker(loadedDate);
                    return;
                }
            }
        }
        showDate(date);
    }

    private void setPicker(LocalDate date) {
        setting = true;
        try {
            datePicker.setValue(date);
        } finally {
            setting = false;
        }
    }

    /** Loads the day's sheet into the table. */
    private void showDate(LocalDate date) {
        loadedDate = date;
        setPicker(date);
        showDayName();
        errorLabel.setText("");
        if (!date.isAfter(LocalDate.now())) {
            try {
                // Auto attendance up to this day (only days from attendance_auto_from on)
                service.autoFill(date);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Auto attendance failed", e);
            }
        }
        List<DayRow> day;
        try {
            day = service.loadDay(date);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load attendance", e);
            Notifier.error(I18n.get("msg.error"));
            day = List.of();
        }
        List<Row> loaded = new ArrayList<>();
        for (DayRow d : day) {
            Attendance a = d.attendance();
            Mark start;
            if (a != null) {
                start = Mark.of(a.getStatus());
            } else {
                // Not joined yet that day, or a weekly off day: nothing; everyone else starts as present
                LocalDate joined = d.employee().getJoinDate();
                start = joined != null && joined.isAfter(date) || offDays.contains(date.getDayOfWeek())
                        ? Mark.NONE : Mark.PRESENT;
            }
            loaded.add(new Row(d.employee(), a, start));
        }
        rows.setAll(loaded);
        TableHelper.fitColumns(dayTable);
        snapshot = state();
        showNotSaved();
    }

    /** "Friday", or "Friday · weekly off" in orange. */
    private void showDayName() {
        if (loadedDate == null) {
            dayLabel.setText("");
            return;
        }
        DayOfWeek dow = loadedDate.getDayOfWeek();
        String name = dow.getDisplayName(TextStyle.FULL, I18n.getLocale());
        boolean off = offDays.contains(dow);
        dayLabel.setText(off ? I18n.get("attendance.weekly_off", name) : name);
        dayLabel.getStyleClass().remove(OFF_DAY);
        if (off) {
            dayLabel.getStyleClass().add(OFF_DAY);
        }
    }

    /** How many marked employees have nothing saved for the day yet. */
    private void showNotSaved() {
        long count = rows.stream().filter(r -> r.saved == null && r.mark.get() != Mark.NONE).count();
        notSavedHint.setText(count == 0 ? "" : I18n.get("attendance.not_saved", I18n.digits(String.valueOf(count))));
    }

    private void loadOffDays() {
        try {
            offDays = configService.weeklyOffDays();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load weekly off days", e);
            offDays = EnumSet.noneOf(DayOfWeek.class);
        }
    }

    @FXML
    private void onSave() {
        save();
    }

    /** True if the day was saved (false: errors shown, the user said No, or it failed). */
    private boolean save() {
        if (!canSave || loadedDate == null) {
            return false;
        }
        errorLabel.setText("");
        List<String> errors = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        int firstBad = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            r.inBad.set(false);
            r.outBad.set(false);
            Mark mark = r.mark.get() == null ? Mark.NONE : r.mark.get();
            LocalTime in = null;
            LocalTime out = null;
            if (mark.keepsTime()) {
                String inText = r.in.get().trim();
                String outText = r.out.get().trim();
                in = parseTime(inText, null);
                out = parseTime(outText, in);
                String name = r.employee.getName();
                boolean bad = false;
                if (!inText.isEmpty() && in == null) {
                    r.inBad.set(true);
                }
                if (!outText.isEmpty() && out == null) {
                    r.outBad.set(true);
                }
                if (r.inBad.get() || r.outBad.get()) {
                    errors.add(I18n.get("attendance.error.time", name, timeExample()));
                    bad = true;
                } else if (r.hourly() && (in == null || out == null)) {
                    r.inBad.set(in == null);
                    r.outBad.set(out == null);
                    errors.add(I18n.get("attendance.error.time_needed", name));
                    bad = true;
                } else if (in != null && out != null && !out.isAfter(in)) {
                    r.outBad.set(true);
                    errors.add(I18n.get("attendance.error.out_before_in", name));
                    bad = true;
                }
                if (bad && firstBad < 0) {
                    firstBad = i;
                }
            }
            if (mark != Mark.NONE || r.saved != null) {
                entries.add(new Entry(r.employee.getId(), mark.status, in, out, r.note.get()));
            }
        }
        if (!errors.isEmpty()) {
            errorLabel.setText(String.join("\n", errors));
            dayTable.scrollTo(firstBad);
            Notifier.error(I18n.get("msg.form_has_errors"));
            return false;
        }
        if (!Dialogs.confirmSave()) {
            return false;
        }
        Result result;
        try {
            result = service.saveDay(loadedDate, entries);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save attendance", e);
            Notifier.error(I18n.get("msg.error"));
            return false;
        }
        switch (result.status()) {
            case SAVED -> {
                Notifier.success(I18n.get("msg.saved"));
                showDate(loadedDate);
                return true;
            }
            case FUTURE_DATE -> Notifier.error(I18n.get("attendance.error.future"));
            case BAD_TIME -> {
                Row bad = rowOf(result.employeeId());
                if (bad != null) {
                    bad.outBad.set(true);
                    dayTable.scrollTo(bad);
                    Notifier.error(I18n.get("attendance.error.out_before_in", bad.employee.getName()));
                } else {
                    Notifier.error(I18n.get("msg.error"));
                }
            }
            case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
        }
        return false;
    }

    private Row rowOf(Long employeeId) {
        if (employeeId == null) {
            return null;
        }
        return rows.stream().filter(r -> employeeId.equals(r.employee.getId())).findFirst().orElse(null);
    }

    private boolean hasUnsaved() {
        return canSave && loadedDate != null && !state().equals(snapshot);
    }

    private String state() {
        return rows.stream().map(Row::state).collect(Collectors.joining("|"));
    }

    /** Status drop-down on each row. */
    private final class MarkCell extends TableCell<Row, Row> {
        private final ComboBox<Mark> box = new ComboBox<>(FXCollections.observableArrayList(Mark.values()));
        private Row bound;

        MarkCell() {
            box.setConverter(new StringConverter<>() {
                @Override
                public String toString(Mark m) {
                    return m == null ? "" : m.text();
                }

                @Override
                public Mark fromString(String s) {
                    throw new UnsupportedOperationException();
                }
            });
            box.setMaxWidth(Double.MAX_VALUE);
            box.setDisable(!canSave);
            box.valueProperty().addListener((obs, old, now) -> {
                if (bound != null) {
                    showNotSaved();
                }
            });
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            if (bound != null) {
                box.valueProperty().unbindBidirectional(bound.mark);
                bound = null;
            }
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            box.valueProperty().bindBidirectional(item.mark);
            bound = item;
            setGraphic(box);
        }
    }

    /** The note input on each row. */
    private final class TextCell extends TableCell<Row, Row> {
        private final TextField field = new TextField();
        private final Function<Row, StringProperty> text;
        private Row bound;

        TextCell(Function<Row, StringProperty> text) {
            this.text = text;
            field.setMaxWidth(Double.MAX_VALUE);
            InputFilters.maxLength(field, NOTE_MAX);
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            if (bound != null) {
                field.textProperty().unbindBidirectional(text.apply(bound));
                bound = null;
            }
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            field.textProperty().bindBidirectional(text.apply(item));
            field.setDisable(!canSave);
            bound = item;
            setGraphic(field);
        }
    }

    /**
     * In / out time on each row, picked on a clock ({@link TimePicker}), never typed. Enabled only when present or
     * half day. The row keeps the time as text in the time format (what {@link #parseTime} reads on save).
     */
    private final class TimeCell extends TableCell<Row, Row> {
        private final TimePicker picker = new TimePicker();
        private final Function<Row, StringProperty> text;
        private final Function<Row, BooleanProperty> bad;
        private final ChangeListener<Boolean> badListener = (obs, old, now) -> showBad(now);
        private Row bound;

        TimeCell(Function<Row, StringProperty> text, Function<Row, BooleanProperty> bad,
                 Function<Row, LocalTime> start) {
            this.text = text;
            this.bad = bad;
            picker.setPrompt(I18n.get("time.pick"));
            picker.setStartTime(() -> bound == null ? LocalTime.of(9, 0) : start.apply(bound));
            picker.valueProperty().addListener((obs, old, now) -> {
                if (bound != null) {
                    text.apply(bound).set(TimePicker.text(now));
                    bad.apply(bound).set(false);
                }
            });
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            if (bound != null) {
                bad.apply(bound).removeListener(badListener);
                picker.disableProperty().unbind();
                bound = null;
            }
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            picker.setValue(parseTime(text.apply(item).get(), null));
            picker.disableProperty().bind(Bindings.createBooleanBinding(
                    () -> !canSave || item.mark.get() == null || !item.mark.get().keepsTime(), item.mark));
            bad.apply(item).addListener(badListener);
            showBad(bad.apply(item).get());
            bound = item;
            setGraphic(picker);
        }

        private void showBad(boolean isBad) {
            picker.getStyleClass().remove(INVALID);
            if (isBad) {
                picker.getStyleClass().add(INVALID);
            }
        }
    }

    /** The out time's clock starts at 5:00 PM, or an hour after the in time when that is later. */
    private static LocalTime outStart(Row r) {
        LocalTime in = parseTime(r.in.get(), null);
        LocalTime five = LocalTime.of(17, 0);
        return in != null && !in.isBefore(five) ? in.plusHours(1) : five;
    }

    // ---------------------------------------------------------------- month view

    private void setUpMonthView() {
        monthTable = new PaginatedTable<>(this::loadMonthPage);
        TableView<MonthSummary> view = monthTable.getTableView();
        view.getColumns().add(TableHelper.column("label.name", s -> s.employee().getName()));
        view.getColumns().add(TableHelper.column("attendance.col.designation", s -> designation(s.employee())));
        view.getColumns().add(TableHelper.moneyColumn("attendance.status.PRESENT", s -> count(s.present())));
        view.getColumns().add(TableHelper.moneyColumn("attendance.status.HALF_DAY", s -> count(s.halfDay())));
        view.getColumns().add(TableHelper.moneyColumn("attendance.status.LEAVE", s -> count(s.leave())));
        view.getColumns().add(TableHelper.moneyColumn("attendance.status.ABSENT", s -> count(s.absent())));
        view.getColumns().add(TableHelper.moneyColumn("attendance.col.worked", s -> number(s.workedDays())));
        view.getColumns().add(TableHelper.moneyColumn("attendance.col.hours", s -> number(s.hours())));
        monthTableHolder.getChildren().setAll(monthTable);

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
                loadMonth();
            }
        });
        yearBox.valueProperty().addListener((obs, old, now) -> {
            if (!setting) {
                loadMonth();
            }
        });
    }

    /** Shows the month of the chosen day. */
    @FXML
    private void onShowMonth() {
        YearMonth month = YearMonth.from(loadedDate == null ? LocalDate.now() : loadedDate);
        setting = true;
        try {
            int thisYear = LocalDate.now().getYear();
            yearBox.getItems().clear();
            for (int y = thisYear; y >= Math.min(thisYear - YEARS_BACK, month.getYear()); y--) {
                yearBox.getItems().add(y);
            }
            yearBox.setValue(month.getYear());
            monthBox.setValue(month.getMonth());
        } finally {
            setting = false;
        }
        showView(false);
        loadMonth();
    }

    @FXML
    private void onShowDay() {
        showView(true);
    }

    private void showView(boolean day) {
        dayView.setVisible(day);
        dayView.setManaged(day);
        monthView.setVisible(!day);
        monthView.setManaged(!day);
    }

    private void loadMonth() {
        Month month = monthBox.getValue();
        Integer year = yearBox.getValue();
        if (month == null || year == null) {
            return;
        }
        try {
            monthRows = service.monthSummary(YearMonth.of(year, month));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load the attendance month", e);
            Notifier.error(I18n.get("msg.error"));
            monthRows = List.of();
        }
        monthTable.reload();
    }

    private PageResult<MonthSummary> loadMonthPage(int offset, int limit) {
        int from = Math.min(offset, monthRows.size());
        int to = Math.min(from + limit, monthRows.size());
        return new PageResult<>(monthRows.subList(from, to), monthRows.size());
    }

    // ---------------------------------------------------------------- helpers

    private static String designation(Employee e) {
        return e.getDesignation() == null ? "" : e.getDesignation();
    }

    private static String count(int n) {
        return n == 0 ? "—" : I18n.digits(String.valueOf(n));
    }

    private static String number(BigDecimal n) {
        return n == null || n.signum() == 0 ? "—" : I18n.digits(n.stripTrailingZeros().toPlainString());
    }

    /** Time in the {@code time_format} setting without seconds, e.g. "09:30 AM" or "09:30". */
    private static String timeText(LocalTime t) {
        return t == null ? "" : I18n.digits(DateTimeFormatter.ofPattern(DateUtil.timePattern(), Locale.ENGLISH).format(t));
    }

    /** 9:00 AM (or 09:00 in 24-hour mode), as a hint of how to type a time. */
    private static String timeExample() {
        return timeText(LocalTime.of(9, 0));
    }

    /**
     * Reads a typed time: "9:30 AM", "09:30pm", "9 PM", "9.30", "21:30" (Bangla digits too). In 12-hour mode an hour
     * from 1 to 12 without AM/PM is read as AM (12 as noon); when {@code after} is given (the in time, for the out
     * time) and that is not later than it, it is read as PM ("9:00" in, "5:30" out = 5:30 PM). The field then shows
     * AM/PM, so the user sees how it was read. Null for blank or wrong text.
     */
    static LocalTime parseTime(String text, LocalTime after) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = InputFilters.toAsciiDigits(text).toUpperCase(Locale.ROOT)
                .replace("A.M.", "AM").replace("P.M.", "PM").replace('.', ':').replaceAll("\\s+", "");
        Matcher m = TIME.matcher(s);
        if (!m.matches()) {
            return null;
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        String half = m.group(3);
        if (minute > 59) {
            return null;
        }
        if (half != null) {
            if (hour < 1 || hour > 12) {
                return null;
            }
            hour = hour % 12 + ("PM".equals(half) ? 12 : 0);
        } else {
            if (hour > 23) {
                return null;
            }
            if (DateUtil.TIME_12.equals(DateUtil.timePattern()) && hour >= 1 && hour <= 12) {
                LocalTime morning = LocalTime.of(hour, minute);
                return hour < 12 && after != null && !morning.isAfter(after) ? morning.plusHours(12) : morning;
            }
        }
        return LocalTime.of(hour, minute);
    }
}
