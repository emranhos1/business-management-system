package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.User;
import com.eh.bms.service.AuditLogService;
import com.eh.bms.service.AuditLogService.Row;
import com.eh.bms.service.UserService;
import com.eh.bms.util.DateRangePicker;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.TableHelper;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.layout.StackPane;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Audit log (17.4): who changed what and when, newest first (this month at first). Filters: dates, user and
 * section. The first item of the user and section lists is "all". Old and new values are long, so the table cuts
 * them and the row tooltip shows them in full.
 */
public class AuditLogController {

    private static final Logger LOG = Logger.getLogger(AuditLogController.class.getName());

    @FXML private DateRangePicker dateRange;
    /** First item is null = all users. */
    @FXML private ComboBox<User> userBox;
    /** First item is null = all sections. */
    @FXML private ComboBox<String> moduleBox;
    @FXML private StackPane tableHolder;

    private final AuditLogService service = new AuditLogService();
    private final UserService userService = new UserService();
    private PaginatedTable<Row> table;
    /** True while the filter lists are being filled, so they do not each load the table. */
    private boolean filling;

    @FXML
    private void initialize() {
        table = new PaginatedTable<>(this::loadPage);
        var view = table.getTableView();
        view.getColumns().add(TableHelper.column("audit.col.time", r -> DateUtil.format(r.time().toLocalDate()) + " "
                + DateUtil.formatTime(r.time().toLocalTime())));
        view.getColumns().add(TableHelper.column("audit.col.user", r -> r.user() == null ? I18n.get("audit.system") : r.user()));
        view.getColumns().add(TableHelper.column("audit.col.action", r -> actionText(r.action())));
        view.getColumns().add(TableHelper.column("audit.col.module", r -> moduleText(r.module())));
        view.getColumns().add(TableHelper.column("audit.col.record",
                r -> r.referenceId() == null ? "—" : I18n.digits(String.valueOf(r.referenceId()))));
        view.getColumns().add(TableHelper.longTextColumn("audit.col.old", r -> r.oldValue() == null ? "—" : r.oldValue()));
        view.getColumns().add(TableHelper.longTextColumn("audit.col.new", r -> r.newValue() == null ? "—" : r.newValue()));
        tableHolder.getChildren().setAll(table);

        userBox.setCellFactory(list -> new AllCell<>(u -> u.getName() + " (" + u.getUsername() + ")",
                "audit.all_users", false));
        userBox.setButtonCell(new AllCell<>(u -> u.getName() + " (" + u.getUsername() + ")", "audit.all_users", true));
        moduleBox.setCellFactory(list -> new AllCell<>(AuditLogController::moduleText, "audit.all_modules", false));
        moduleBox.setButtonCell(new AllCell<>(AuditLogController::moduleText, "audit.all_modules", true));

        dateRange.setOnChange(this::onFilterChanged);
        userBox.valueProperty().addListener((obs, old, now) -> onFilterChanged());
        moduleBox.valueProperty().addListener((obs, old, now) -> onFilterChanged());
        fillLists();
        showThisMonth();
    }

    /** Loads the log again, with the filter lists refreshed (new users or sections may have appeared). */
    public void reload() {
        fillLists();
        table.refresh();
    }

    @FXML
    private void onClearFilters() {
        filling = true;
        try {
            userBox.setValue(null);
            moduleBox.setValue(null);
        } finally {
            filling = false;
        }
        showThisMonth();
    }

    /** This month's first to last day (also loads it). */
    private void showThisMonth() {
        YearMonth month = YearMonth.now();
        dateRange.setRange(month.atDay(1), month.atEndOfMonth());
    }

    private void onFilterChanged() {
        if (!filling) {
            table.reload();
        }
    }

    /** Fills the user and section lists ("all" first), keeping what was picked if it is still there. */
    private void fillLists() {
        Long userId = userBox.getValue() == null ? null : userBox.getValue().getId();
        String module = moduleBox.getValue();
        filling = true;
        try {
            List<User> users = userService.all();
            userBox.getItems().clear();
            userBox.getItems().add(null);
            userBox.getItems().addAll(users);
            userBox.setValue(users.stream().filter(u -> u.getId().equals(userId)).findFirst().orElse(null));
            List<String> modules = service.modules();
            moduleBox.getItems().clear();
            moduleBox.getItems().add(null);
            moduleBox.getItems().addAll(modules);
            moduleBox.setValue(modules.contains(module) ? module : null);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load the audit log filters", e);
            Notifier.error(I18n.get("msg.error"));
        } finally {
            filling = false;
        }
    }

    private PageResult<Row> loadPage(int offset, int limit) {
        try {
            LocalDate from = dateRange.getFrom();
            LocalDate to = dateRange.getTo();
            Long userId = userBox.getValue() == null ? null : userBox.getValue().getId();
            return service.page(from, to, userId, moduleBox.getValue(), offset, limit);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load the audit log", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
    }

    private static String actionText(String action) {
        String key = "audit.action." + action;
        return I18n.getBundle().containsKey(key) ? I18n.get(key) : action;
    }

    /** A section by its menu name, a record kind by its own name; anything else as stored. */
    static String moduleText(String module) {
        try {
            return PermissionsController.moduleText(AppModule.valueOf(module));
        } catch (IllegalArgumentException e) {
            String key = "audit.record." + module;
            return I18n.getBundle().containsKey(key) ? I18n.get(key) : module;
        }
    }

    /** A list cell showing an item, or {@code allKey} for the null "all" item. */
    private static final class AllCell<T> extends ListCell<T> {
        private final Function<T, String> text;
        private final String allKey;
        /** The combo box's own cell: it is "empty" when the value is null, which means "all". */
        private final boolean buttonCell;

        AllCell(Function<T, String> text, String allKey, boolean buttonCell) {
            this.text = text;
            this.allKey = allKey;
            this.buttonCell = buttonCell;
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            if (empty && !buttonCell) {
                setText(null);
            } else {
                setText(item == null ? I18n.get(allKey) : text.apply(item));
            }
        }
    }
}
