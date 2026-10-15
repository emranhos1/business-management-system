package com.eh.bms.controller;

import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.AccountType;
import com.eh.bms.service.AccountTypeService;
import com.eh.bms.service.AccountTypeService.TypeData;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.Notifier;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Add / edit account type popup (7.1). Names and number rule can always be changed. */
public class AccountTypeFormController {

    private static final Logger LOG = Logger.getLogger(AccountTypeFormController.class.getName());

    @FXML private TextField nameBnField;
    @FXML private Label nameBnError;
    @FXML private TextField nameEnField;
    @FXML private Label nameEnError;
    @FXML private ComboBox<AccountNumberRule> ruleBox;
    @FXML private Label ruleError;

    private final AccountTypeService service = new AccountTypeService();
    private FormValidator form;

    /** Null when adding. */
    private Long typeId;
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.maxLength(nameBnField, 100);
        InputFilters.maxLength(nameEnField, 100);

        ruleBox.getItems().setAll(AccountNumberRule.values());
        ruleBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(AccountNumberRule rule) {
                return rule == null ? "" : ruleText(rule);
            }

            @Override
            public AccountNumberRule fromString(String text) {
                throw new UnsupportedOperationException();
            }
        });

        form = new FormValidator()
                .add(nameBnField, nameBnError)
                .add(nameEnField, nameEnError)
                .add(ruleBox, ruleError);
    }

    /** Fills the form for editing; call before showing. Leave it uncalled to add a new type. */
    public void edit(AccountType type) {
        typeId = type.getId();
        nameBnField.setText(type.getNameBn());
        nameEnField.setText(type.getNameEn());
        ruleBox.setValue(type.getNumberRule());
    }

    public boolean isSaved() {
        return saved;
    }

    /** Number rule in plain words, e.g. "মোবাইল নম্বর (১১ সংখ্যা)". */
    public static String ruleText(AccountNumberRule rule) {
        return I18n.get("types.rule." + rule.name());
    }

    @FXML
    private void onSave() {
        form.clearAll();
        String nameBn = text(nameBnField);
        String nameEn = text(nameEnField);
        AccountNumberRule rule = ruleBox.getValue();

        if (nameBn.isEmpty()) {
            form.error(nameBnField, I18n.get("msg.required_field", I18n.get("types.name_bn")));
        }
        if (nameEn.isEmpty()) {
            form.error(nameEnField, I18n.get("msg.required_field", I18n.get("types.name_en")));
        }
        if (rule == null) {
            form.error(ruleBox, I18n.get("msg.required_field", I18n.get("types.rule")));
        }
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirmSave()) {
            return;
        }

        try {
            switch (service.save(new TypeData(typeId, nameBn, nameEn, rule))) {
                case DUPLICATE_NAME_BN -> {
                    form.error(nameBnField, I18n.get("types.error.duplicate"));
                    form.focusFirstError();
                }
                case DUPLICATE_NAME_EN -> {
                    form.error(nameEnField, I18n.get("types.error.duplicate"));
                    form.focusFirstError();
                }
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.saved"));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save account type", e);
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
