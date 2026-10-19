package com.eh.bms.util;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.ScrollPane;
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
    /** Space (px) kept above / below a field scrolled into sight (its label, its error line). */
    private static final double LABEL_ROOM = 40;
    private static final double ERROR_ROOM = 30;

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

    /** Moves the cursor to the first wrong field (top of the form first) and scrolls it into sight. */
    public void focusFirstError() {
        fields.keySet().stream().filter(invalid::contains).findFirst().ifPresent(field -> {
            field.requestFocus();
            // After the error lines are laid out, so the field's position is final
            Platform.runLater(() -> scrollIntoView(field));
        });
    }

    /** Scrolls every scroll pane around the node (page, popup) so the node and its error line show. */
    private static void scrollIntoView(Node node) {
        for (Node p = node.getParent(); p != null; p = p.getParent()) {
            if (p instanceof ScrollPane scroll && scroll.getContent() != null) {
                Node content = scroll.getContent();
                Bounds inContent = content.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
                double extra = content.getBoundsInLocal().getHeight() - scroll.getViewportBounds().getHeight();
                if (inContent == null || extra <= 0) {
                    continue;
                }
                double top = scroll.getVvalue() * extra;
                double viewHeight = scroll.getViewportBounds().getHeight();
                // Room above for the label, below for the error line
                double want = top;
                if (inContent.getMinY() - LABEL_ROOM < top) {
                    want = inContent.getMinY() - LABEL_ROOM;
                } else if (inContent.getMaxY() + ERROR_ROOM > top + viewHeight) {
                    want = inContent.getMaxY() + ERROR_ROOM - viewHeight;
                }
                scroll.setVvalue(Math.max(0, Math.min(1, want / extra)));
            }
        }
    }

    /**
     * For a hint or error label whose text is set directly (not through a validator): it takes no space
     * while its text is empty, so no blank gap stays under the field.
     */
    public static void hideWhenEmpty(Label... labels) {
        for (Label label : labels) {
            label.visibleProperty().bind(label.textProperty().isNotEmpty());
            label.managedProperty().bind(label.textProperty().isNotEmpty());
        }
    }

    private static void hide(Label label) {
        label.setText("");
        label.setVisible(false);
        label.setManaged(false);
    }
}
