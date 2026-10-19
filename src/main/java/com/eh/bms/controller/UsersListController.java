package com.eh.bms.controller;

import com.eh.bms.model.User;
import com.eh.bms.model.UserStatus;
import com.eh.bms.service.UserService;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.TableHelper.RowAction;
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

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Users list (17.1, 17.2): active users with edit and deactivate buttons on each row; inactive users in a second
 * table below, with edit and activate. Search by name or username while typing. Delete is in the edit form.
 */
public class UsersListController {

    private static final Logger LOG = Logger.getLogger(UsersListController.class.getName());
    private static final String FORM_FXML = "/fxml/views/user-form.fxml";

    @FXML private TextField searchField;
    @FXML private Button addButton;
    @FXML private StackPane tableHolder;
    @FXML private StackPane inactiveHolder;

    private final UserService service = new UserService();
    private PaginatedTable<User> table;
    private PaginatedTable<User> inactiveTable;

    @FXML
    private void initialize() {
        table = new PaginatedTable<>((offset, limit) -> loadPage(UserStatus.ACTIVE, offset, limit));
        addInfoColumns(table.getTableView());
        table.getTableView().getColumns().add(actionsColumn(
                new RowAction<User>(Icons.EDIT, "button.edit", "user.form.edit_title", "icon-edit", this::openForm),
                new RowAction<User>(Icons.DEACTIVATE, "user.deactivate", "icon-off", this::deactivate)));

        inactiveTable = new PaginatedTable<>((offset, limit) -> loadPage(UserStatus.INACTIVE, offset, limit));
        addInfoColumns(inactiveTable.getTableView());
        inactiveTable.getTableView().getColumns().add(actionsColumn(
                new RowAction<User>(Icons.EDIT, "button.edit", "user.form.edit_title", "icon-edit", this::openForm),
                new RowAction<User>(Icons.ACTIVATE, "user.activate", "icon-on", this::activate)));

        // Search (both lists) while typing, after a short pause
        PauseTransition pause = new PauseTransition(Duration.millis(300));
        pause.setOnFinished(e -> reload());
        searchField.textProperty().addListener((obs, old, text) -> pause.playFromStart());

        tableHolder.getChildren().setAll(table);
        inactiveHolder.getChildren().setAll(inactiveTable);
        reload();
    }

    /** Loads both lists again. */
    public void reload() {
        table.reload();
        inactiveTable.reload();
    }

    private static void addInfoColumns(TableView<User> view) {
        view.getColumns().add(TableHelper.column("user.col.name", User::getName));
        view.getColumns().add(TableHelper.column("user.col.username", User::getUsername));
        view.getColumns().add(TableHelper.column("user.col.role", u -> I18n.get("role." + u.getRole().name())));
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
        searchField.clear();
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
            switch (service.setStatus(user.getId(), status)) {
                case DONE -> Notifier.success(I18n.get(doneKey));
                case SELF -> Notifier.error(I18n.get("user.error.self"));
                case LAST_ADMIN -> Notifier.error(I18n.get("user.error.last_admin"));
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
