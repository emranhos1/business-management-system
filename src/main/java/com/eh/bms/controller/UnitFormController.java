package com.eh.bms.controller;

import com.eh.bms.model.Unit;
import com.eh.bms.service.UnitService;
import com.eh.bms.service.UnitService.UnitData;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.Notifier;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Add / edit unit popup (8.4). */
public class UnitFormController {

    private static final Logger LOG = Logger.getLogger(UnitFormController.class.getName());
    private static final int NAME_MAX = 30;

    @FXML private TextField nameBnField;
    @FXML private Label nameBnError;
    @FXML private TextField nameEnField;
    @FXML private Label nameEnError;

    private final UnitService service = new UnitService();
    private FormValidator form;

    /** Null when adding. */
    private Long unitId;
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.maxLength(nameBnField, NAME_MAX);
        InputFilters.maxLength(nameEnField, NAME_MAX);

        form = new FormValidator()
                .add(nameBnField, nameBnError)
                .add(nameEnField, nameEnError);
    }

    /** Fills the form for editing; call before showing. Leave it uncalled to add a new unit. */
    public void edit(Unit unit) {
        unitId = unit.getId();
        nameBnField.setText(unit.getNameBn());
        nameEnField.setText(unit.getNameEn());
    }

    public boolean isSaved() {
        return saved;
    }

    @FXML
    private void onSave() {
        form.clearAll();
        String nameBn = text(nameBnField);
        String nameEn = text(nameEnField);

        if (nameBn.isEmpty()) {
            form.error(nameBnField, I18n.get("msg.required_field", I18n.get("units.name_bn")));
        }
        if (nameEn.isEmpty()) {
            form.error(nameEnField, I18n.get("msg.required_field", I18n.get("units.name_en")));
        }
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirmSave()) {
            return;
        }

        try {
            switch (service.save(new UnitData(unitId, nameBn, nameEn))) {
                case DUPLICATE_NAME_BN -> {
                    form.error(nameBnField, I18n.get("units.error.duplicate"));
                    form.focusFirstError();
                }
                case DUPLICATE_NAME_EN -> {
                    form.error(nameEnField, I18n.get("units.error.duplicate"));
                    form.focusFirstError();
                }
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.saved"));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save unit", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onCancel() {
        close();
    }

    private void close() {
        ((Stage) nameBnField.getScene().getWindow()).close();
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }
}
