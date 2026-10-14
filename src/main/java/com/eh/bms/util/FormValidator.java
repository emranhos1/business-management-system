package com.eh.bms.util;

import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shows form errors: the wrong field turns red and its message appears in the label under it.
 * All errors of a form are shown together; editing a field clears its own error.
 * <pre>
 * FormValidator v = new FormValidator().add(nameField, nameError).add(phoneField, phoneError);
 * v.clearAll();
 * if (name.isEmpty()) v.error(nameField, I18n.get("msg.required_field", ...));
 * if (v.hasErrors()) { v.focusFirstError(); Notifier.error(I18n.get("msg.form_has_errors")); return; }
 * </pre>
 * In FXML put each field and its error label in a VBox; give the label {@code styleClass="field-error"}.
 */
public final class FormValidator {

    private static final String INVALID = "field-invalid";

    /** Field → its error label, in form order. */
    private final Map<Control, Label> fields = new LinkedHashMap<>();
    private final Set<Control> invalid = new LinkedHashSet<>();

    public FormValidator add(Control field, Label errorLabel) {
        fields.put(field, errorLabel);
        hide(errorLabel);
        if (field instanceof TextInputControl text) {
            text.textProperty().addListener((obs, old, now) -> clear(field));
        } else if (field instanceof ComboBoxBase<?> box) {
            box.valueProperty().addListener((obs, old, now) -> clear(field));
        }
        return this;
    }

    public void error(Control field, String message) {
        Label label = fields.get(field);
        if (label == null) {
            throw new IllegalArgumentException("Field not added to the validator");
        }
        if (!field.getStyleClass().contains(INVALID)) {
            field.getStyleClass().add(INVALID);
        }
        label.setText(message);
        label.setVisible(true);
        label.setManaged(true);
        invalid.add(field);
        fitWindow(field);
    }

    public void clear(Control field) {
        boolean wasInvalid = invalid.remove(field);
        field.getStyleClass().remove(INVALID);
        Label label = fields.get(field);
        if (label != null) {
            hide(label);
        }
        if (wasInvalid) {
            fitWindow(field);
        }
    }

    /** A popup (WindowManager.dialog) grows/shrinks with its error lines, so its buttons never get cut off. */
    private static void fitWindow(Control field) {
        WindowManager.fitPopup(field);
    }

    public void clearAll() {
        fields.keySet().forEach(this::clear);
    }

    public boolean hasErrors() {
        return !invalid.isEmpty();
    }

    /** Moves the cursor to the first wrong field (top of the form first). */
    public void focusFirstError() {
        fields.keySet().stream().filter(invalid::contains).findFirst().ifPresent(Control::requestFocus);
    }

    private static void hide(Label label) {
        label.setText("");
        label.setVisible(false);
        label.setManaged(false);
    }
}
