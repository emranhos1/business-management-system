package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.UserRole;
import com.eh.bms.service.PermissionService;
import com.eh.bms.service.PermissionService.Row;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.UnsavedWork;
import javafx.fxml.FXML;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Permission editor (17.3): pick a role (manager or cashier; the admin can always do everything), tick what it may
 * do in each section (view, add, edit, delete), save. Adding, editing or deleting needs view, so ticking one of
 * them ticks view and clearing view clears them. The dashboard and reports are view only. Changes reach a user
 * at the next login. Switching role or language with unsaved ticks asks first.
 */
public class PermissionsController {

    private static final Logger LOG = Logger.getLogger(PermissionsController.class.getName());
    /** Sections where only viewing means anything. */
    private static final List<AppModule> VIEW_ONLY = List.of(AppModule.DASHBOARD, AppModule.REPORT);
    private static final int VIEW = 0;
    private static final int CREATE = 1;
    private static final int EDIT = 2;
    private static final int DELETE = 3;

    @FXML private VBox root;
    @FXML private ComboBox<UserRole> roleBox;
    @FXML private Button saveButton;
    @FXML private GridPane grid;

    private final PermissionService service = new PermissionService();
    /** The four boxes of each section: view, add, edit, delete. */
    private final Map<AppModule, CheckBox[]> boxes = new EnumMap<>(AppModule.class);
    /** What was loaded or last saved, to tell whether anything changed. */
    private List<Row> loaded = List.of();
    /** The role whose permissions are shown (the box may already show another while asking about unsaved ticks). */
    private UserRole shownRole;
    /** True while the page sets the ticks itself, so the rules between them do not run. */
    private boolean filling;
    private boolean switching;

    @FXML
    private void initialize() {
        roleBox.getItems().setAll(UserRole.MANAGER, UserRole.CASHIER, UserRole.EMPLOYEE);
        roleBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(UserRole role) {
                return role == null ? "" : I18n.get("role." + role.name());
            }

            @Override
            public UserRole fromString(String text) {
                throw new UnsupportedOperationException();
            }
        });
        buildGrid();
        roleBox.valueProperty().addListener((obs, old, now) -> onRolePicked(old, now));
        UnsavedWork.register(root, this::hasChanges, this::save);
        roleBox.setValue(UserRole.MANAGER);
    }

    /** The heading row and one row of four boxes per section. */
    private void buildGrid() {
        grid.add(heading("permission.module"), 0, 0);
        grid.add(heading("permission.view"), 1, 0);
        grid.add(heading("permission.create"), 2, 0);
        grid.add(heading("permission.edit"), 3, 0);
        grid.add(heading("permission.delete"), 4, 0);
        int row = 1;
        for (AppModule module : PermissionService.editableModules()) {
            Label name = new Label(moduleText(module));
            name.setMinWidth(180);
            grid.add(name, 0, row);
            CheckBox[] row4 = new CheckBox[4];
            for (int i = 0; i < 4; i++) {
                CheckBox box = new CheckBox();
                row4[i] = box;
                GridPane.setHalignment(box, HPos.CENTER);
                grid.add(box, i + 1, row);
            }
            if (VIEW_ONLY.contains(module)) {
                // Nothing to add, edit or delete here: the other three boxes only follow "view" (they cannot be ticked)
                for (int i = CREATE; i <= DELETE; i++) {
                    row4[i].setDisable(true);
                }
                row4[VIEW].selectedProperty().addListener((obs, old, on) -> {
                    if (!filling) {
                        for (int i = CREATE; i <= DELETE; i++) {
                            row4[i].setSelected(on);
                        }
                    }
                });
            } else {
                // Add / edit / delete need view; no view means none of them
                row4[VIEW].selectedProperty().addListener((obs, old, on) -> {
                    if (!filling && !on) {
                        for (int i = CREATE; i <= DELETE; i++) {
                            row4[i].setSelected(false);
                        }
                    }
                });
                for (int i = CREATE; i <= DELETE; i++) {
                    row4[i].selectedProperty().addListener((obs, old, on) -> {
                        if (!filling && on) {
                            row4[VIEW].setSelected(true);
                        }
                    });
                }
            }
            boxes.put(module, row4);
            row++;
        }
    }

    private static Label heading(String key) {
        Label l = new Label(I18n.get(key));
        l.getStyleClass().add("form-label");
        l.setAlignment(Pos.CENTER);
        return l;
    }

    /** Section name as in the menu. */
    static String moduleText(AppModule module) {
        return I18n.get(switch (module) {
            case DASHBOARD -> "menu.dashboard";
            case PRODUCT -> "menu.products";
            case STOCK -> "menu.stock";
            case PARTY -> "menu.parties";
            case SALE -> "menu.sales";
            case PURCHASE -> "menu.purchases";
            case EXPENSE -> "menu.expenses";
            case ACCOUNT -> "menu.accounts";
            case REPORT -> "menu.reports";
            case SETTINGS -> "menu.settings";
            case USER -> "menu.users";
        });
    }

    /** Another role picked: with unsaved ticks, ask first (save, drop them, or stay on the old role). */
    private void onRolePicked(UserRole old, UserRole now) {
        if (switching || now == null) {
            return;
        }
        if (shownRole != null && hasChanges()) {
            switch (Dialogs.askSave(I18n.get("msg.unsaved_question"))) {
                case SAVE -> {
                    if (!save()) {
                        revert(old);
                        return;
                    }
                }
                case DISCARD -> {
                    // go on without saving
                }
                case CANCEL -> {
                    revert(old);
                    return;
                }
            }
        }
        load(now);
    }

    private void revert(UserRole role) {
        switching = true;
        try {
            roleBox.setValue(role);
        } finally {
            switching = false;
        }
    }

    private void load(UserRole role) {
        try {
            // A view-only section shows its other three boxes the same as "view", whatever an old row says
            loaded = service.forRole(role).stream()
                    .map(r -> VIEW_ONLY.contains(r.module())
                            ? new Row(r.module(), r.view(), r.view(), r.view(), r.view()) : r)
                    .toList();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load permissions", e);
            Notifier.error(I18n.get("msg.error"));
            return;
        }
        shownRole = role;
        filling = true;
        try {
            for (Row r : loaded) {
                CheckBox[] row4 = boxes.get(r.module());
                row4[VIEW].setSelected(r.view());
                row4[CREATE].setSelected(r.create());
                row4[EDIT].setSelected(r.edit());
                row4[DELETE].setSelected(r.delete());
            }
        } finally {
            filling = false;
        }
    }

    /** The ticks as rows. */
    private List<Row> current() {
        List<Row> rows = new ArrayList<>();
        for (AppModule module : PermissionService.editableModules()) {
            CheckBox[] b = boxes.get(module);
            rows.add(new Row(module, b[VIEW].isSelected(), b[CREATE].isSelected(), b[EDIT].isSelected(),
                    b[DELETE].isSelected()));
        }
        return rows;
    }

    private boolean hasChanges() {
        return shownRole != null && !current().equals(loaded);
    }

    @FXML
    private void onSave() {
        save();
    }

    /** Saves the ticks of the shown role after a Yes / No; true only if they were saved. */
    private boolean save() {
        if (shownRole == null || !Dialogs.confirmSave()) {
            return false;
        }
        try {
            service.save(shownRole, current());
            loaded = current();
            Notifier.success(I18n.get("msg.saved"));
            return true;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save permissions", e);
            Notifier.error(I18n.get("msg.error"));
            return false;
        }
    }
}
