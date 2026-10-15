package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Unit;
import com.eh.bms.service.UnitService;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.PermissionUtil;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.UnitUtil;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Units list ("Units" tab on the right of the Products page, 8.4): add, edit (button or double-click), delete. */
public class UnitsController {

    private static final Logger LOG = Logger.getLogger(UnitsController.class.getName());
    private static final String FORM_FXML = "/fxml/views/unit-form.fxml";

    @FXML private Button addButton;
    @FXML private Button editButton;
    @FXML private Button deleteButton;
    @FXML private StackPane tableHolder;

    private final UnitService service = new UnitService();
    private PaginatedTable<Unit> table;
    /** Number of products using each unit on the shown page. */
    private Map<Long, Long> productCounts = Map.of();
    /** Runs after a unit is saved or deleted (the products list shows unit names). */
    private Runnable onChange = () -> { };

    @FXML
    private void initialize() {
        PermissionUtil.applyCrud(AppModule.PRODUCT, addButton, editButton, deleteButton);

        table = new PaginatedTable<>(this::loadPage);
        TableView<Unit> view = table.getTableView();
        view.getColumns().add(TableHelper.column("units.name", UnitUtil::name));
        view.getColumns().add(TableHelper.column("units.col.products",
                u -> I18n.digits(String.valueOf(productCounts.getOrDefault(u.getId(), 0L)))));

        TableHelper.needSelection(view, editButton, deleteButton);
        boolean canEdit = UserSession.isLoggedIn() && UserSession.get().can(AppModule.PRODUCT, Action.EDIT);
        TableHelper.onDoubleClick(view, canEdit, this::openForm);

        tableHolder.getChildren().setAll(table);
        table.reload();
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Reloads the shown page (e.g. after a product is saved or deleted, so the product counts are current). */
    public void refresh() {
        table.refresh();
    }

    private PageResult<Unit> loadPage(int offset, int limit) {
        try {
            PageResult<Unit> page = service.page(offset, limit);
            productCounts = service.productCounts(page.items());
            return page;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load units", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
    }

    @FXML
    private void onAdd() {
        openForm(null);
    }

    @FXML
    private void onEdit() {
        Unit selected = table.getTableView().getSelectionModel().getSelectedItem();
        if (selected != null) {
            openForm(selected);
        }
    }

    @FXML
    private void onDelete() {
        Unit selected = table.getTableView().getSelectionModel().getSelectedItem();
        if (selected == null
                || !Dialogs.confirmDelete(I18n.get("units.confirm_delete", UnitUtil.name(selected)))) {
            return;
        }
        try {
            switch (service.delete(selected.getId())) {
                case DELETED -> {
                    Notifier.success(I18n.get("msg.deleted"));
                    onChange.run();
                }
                case IN_USE -> Notifier.error(I18n.get("units.error.in_use"));
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
            table.refresh();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not delete unit", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    /** Add (unit = null) or edit popup; the lists reload after a save. */
    private void openForm(Unit unit) {
        FXMLLoader loader = WindowManager.loader(FORM_FXML);
        Parent root = WindowManager.load(loader, FORM_FXML);
        UnitFormController form = loader.getController();
        if (unit != null) {
            form.edit(unit);
        }
        WindowManager.dialog(root, I18n.get(unit == null ? "units.form.add_title" : "units.form.edit_title"))
                .showAndWait();
        if (form.isSaved()) {
            table.refresh();
            onChange.run();
        }
    }
}
