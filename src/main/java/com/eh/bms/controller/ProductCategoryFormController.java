package com.eh.bms.controller;

import com.eh.bms.model.ProductCategory;
import com.eh.bms.service.ProductCategoryService;
import com.eh.bms.service.ProductCategoryService.CategoryData;
import com.eh.bms.model.AppModule;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.Notifier;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Add / edit product category popup (8.1). */
public class ProductCategoryFormController {

    private static final Logger LOG = Logger.getLogger(ProductCategoryFormController.class.getName());

    @FXML private Button deleteButton;
    @FXML private TextField nameBnField;
    @FXML private Label nameBnError;
    @FXML private TextField nameEnField;
    @FXML private Label nameEnError;

    private final ProductCategoryService service = new ProductCategoryService();
    private FormValidator form;

    /** Null when adding. */
    private Long categoryId;
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.maxLength(nameBnField, 100);
        InputFilters.maxLength(nameEnField, 100);

        form = new FormValidator()
                .add(nameBnField, nameBnError)
                .add(nameEnField, nameEnError);
    }

    /** Fills the form for editing; call before showing. Leave it uncalled to add a new category. */
    public void edit(ProductCategory category) {
        categoryId = category.getId();
        // Delete only a record nothing uses (and the user may delete)
        boolean deletable = UserSession.isLoggedIn() && UserSession.get().can(AppModule.PRODUCT, Action.DELETE)
                && service.canDelete(categoryId);
        deleteButton.setVisible(deletable);
        deleteButton.setManaged(deletable);
        nameBnField.setText(category.getName());
        nameEnField.setText(category.getNameEn());
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
            form.error(nameBnField, I18n.get("msg.required_field", I18n.get("categories.name_bn")));
        }
        if (nameEn.isEmpty()) {
            form.error(nameEnField, I18n.get("msg.required_field", I18n.get("categories.name_en")));
        }
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirmSave()) {
            return;
        }

        try {
            switch (service.save(new CategoryData(categoryId, nameBn, nameEn))) {
                case DUPLICATE_NAME_BN -> {
                    form.error(nameBnField, I18n.get("categories.error.duplicate"));
                    form.focusFirstError();
                }
                case DUPLICATE_NAME_EN -> {
                    form.error(nameEnField, I18n.get("categories.error.duplicate"));
                    form.focusFirstError();
                }
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.saved"));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save product category", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onDelete() {
        if (categoryId == null || !Dialogs.confirmDelete(I18n.get("categories.confirm_delete",
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
                case IN_USE -> Notifier.error(I18n.get("categories.error.in_use"));
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not delete product category", e);
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
