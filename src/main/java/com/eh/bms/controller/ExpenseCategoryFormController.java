package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.ExpenseCategory;
import com.eh.bms.model.ExpenseCategoryType;
import com.eh.bms.service.ExpenseCategoryService;
import com.eh.bms.service.ExpenseCategoryService.CategoryData;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.Notifier;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Add / edit expense category popup (13.1). Names and type can always be changed. */
public class ExpenseCategoryFormController {

    private static final Logger LOG = Logger.getLogger(ExpenseCategoryFormController.class.getName());

    @FXML private TextField nameBnField;
    @FXML private Label nameBnError;
    @FXML private TextField nameEnField;
    @FXML private Label nameEnError;
    @FXML private ComboBox<ExpenseCategoryType> typeBox;
    @FXML private Label typeError;
    @FXML private Button deleteButton;

    private final ExpenseCategoryService service = new ExpenseCategoryService();
    private FormValidator form;

    /** Null when adding. */
    private Long categoryId;
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.maxLength(nameBnField, 100);
        InputFilters.maxLength(nameEnField, 100);

        typeBox.getItems().setAll(ExpenseCategoryType.values());
        typeBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(ExpenseCategoryType type) {
                return type == null ? "" : ExpenseCategoriesController.typeText(type);
            }

            @Override
            public ExpenseCategoryType fromString(String text) {
                throw new UnsupportedOperationException();
            }
        });

        form = new FormValidator()
                .add(nameBnField, nameBnError)
                .add(nameEnField, nameEnError)
                .add(typeBox, typeError);
    }

    /** Fills the form for editing; call before showing. Leave it uncalled to add a new category. */
    public void edit(ExpenseCategory category) {
        categoryId = category.getId();
        nameBnField.setText(category.getName());
        nameEnField.setText(category.getNameEn());
        typeBox.setValue(category.getType());
        // Delete only a category no expense uses (and the user may delete)
        boolean deletable = UserSession.isLoggedIn() && UserSession.get().can(AppModule.EXPENSE, Action.DELETE)
                && service.canDelete(categoryId);
        deleteButton.setVisible(deletable);
        deleteButton.setManaged(deletable);
    }

    public boolean isSaved() {
        return saved;
    }

    @FXML
    private void onSave() {
        form.clearAll();
        String nameBn = text(nameBnField);
        String nameEn = text(nameEnField);
        ExpenseCategoryType type = typeBox.getValue();

        if (nameBn.isEmpty()) {
            form.error(nameBnField, I18n.get("msg.required_field", I18n.get("expense.category.name_bn")));
        }
        if (nameEn.isEmpty()) {
            form.error(nameEnField, I18n.get("msg.required_field", I18n.get("expense.category.name_en")));
        }
        if (type == null) {
            form.error(typeBox, I18n.get("msg.required_field", I18n.get("expense.category.type")));
        }
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirmSave()) {
            return;
        }

        try {
            switch (service.save(new CategoryData(categoryId, nameBn, nameEn, type))) {
                case DUPLICATE_NAME -> {
                    form.error(nameBnField, I18n.get("expense.category.error.duplicate"));
                    form.focusFirstError();
                }
                case DUPLICATE_NAME_EN -> {
                    form.error(nameEnField, I18n.get("expense.category.error.duplicate"));
                    form.focusFirstError();
                }
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.saved"));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save expense category", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onDelete() {
        if (categoryId == null || !Dialogs.confirmDelete(I18n.get("expense.category.confirm_delete",
                I18n.pick(text(nameBnField), text(nameEnField))))) {
            return;
        }
        try {
            switch (service.delete(categoryId)) {
                case DELETED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.deleted"));
                }
                case IN_USE -> Notifier.error(I18n.get("expense.category.error.in_use"));
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not delete expense category", e);
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
