package com.eh.bms.util;

import javafx.animation.PauseTransition;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Picks one item by typing part of it into a text field (e.g. a customer by name or mobile, a product by
 * name or code): matches show in a list under the field; click one, or press Enter for the first.
 * Typing again clears the pick until a new one is chosen. Search runs after a short pause. A click on the
 * field opens the list like a dropdown. For the arrow, put the field in a {@code picker-box}:
 * <pre>{@code
 * <StackPane styleClass="picker-box">
 *     <TextField fx:id="..."/>
 *     <Region styleClass="picker-arrow" StackPane.alignment="CENTER_RIGHT" mouseTransparent="true"/>
 * </StackPane>
 * }</pre>
 */
public class SearchPicker<T> {

    private static final Logger LOG = Logger.getLogger(SearchPicker.class.getName());
    private static final int MAX_RESULTS = 10;
    private static final Duration DELAY = Duration.millis(250);

    private final TextField field;
    private final Function<String, List<T>> search;
    private final Function<T, String> text;
    private final Function<T, String> listText;
    private final ContextMenu menu = new ContextMenu();
    private final ObjectProperty<T> value = new SimpleObjectProperty<>();
    private final PauseTransition pause = new PauseTransition(DELAY);
    private List<T> shown = List.of();
    /** True while the field text is set by a pick, so it does not search again. */
    private boolean picking;

    /**
     * @param search the items matching the typed text (at most {@value #MAX_RESULTS} are shown)
     * @param text   how an item reads in the list and in the field once picked
     */
    public SearchPicker(TextField field, Function<String, List<T>> search, Function<T, String> text) {
        this(field, search, text, text);
    }

    /** @param listText how an item reads in the list only (e.g. with its stock), when that differs from {@code text} */
    public SearchPicker(TextField field, Function<String, List<T>> search, Function<T, String> text,
                        Function<T, String> listText) {
        this.field = field;
        this.search = search;
        this.text = text;
        this.listText = listText;
        menu.setAutoHide(true);
        // Own colours: the list would otherwise take the field's pink error colours
        menu.getStyleClass().add("picker-menu");
        pause.setOnFinished(e -> showMatches());
        field.textProperty().addListener((obs, old, now) -> {
            if (picking) {
                return;
            }
            value.set(null);
            pause.playFromStart();
        });
        field.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                menu.hide();
            }
        });
        // A click opens the list like a dropdown; with an item already picked it lists everything again
        field.setOnMouseClicked(e -> {
            if (menu.isShowing()) {
                menu.hide();
            } else {
                showMatches(value.get() != null);
            }
        });
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && value.get() == null && !shown.isEmpty()) {
                pick(shown.get(0));
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE && menu.isShowing()) {
                menu.hide();
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN && !menu.isShowing()) {
                showMatches();
            }
        });
    }

    /** The picked item; null while nothing is picked. */
    public ObjectProperty<T> valueProperty() {
        return value;
    }

    public T getValue() {
        return value.get();
    }

    /** Picks an item from code (e.g. a party just added); null clears the field. */
    public void setValue(T item) {
        if (item == null) {
            picking = true;
            field.clear();
            picking = false;
            value.set(null);
            return;
        }
        pick(item);
    }

    private void pick(T item) {
        picking = true;
        field.setText(text.apply(item));
        field.positionCaret(field.getText().length());
        picking = false;
        value.set(item);
        menu.hide();
    }

    private void showMatches() {
        showMatches(false);
    }

    /** @param all list from the start (ignore the typed text) */
    private void showMatches(boolean all) {
        String typed = all || field.getText() == null ? "" : InputFilters.toAsciiDigits(field.getText().trim());
        if (!field.isFocused()) {
            return;
        }
        try {
            List<T> found = search.apply(typed);
            shown = found.size() > MAX_RESULTS ? found.subList(0, MAX_RESULTS) : found;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Search failed", e);
            shown = List.of();
        }
        menu.getItems().clear();
        if (shown.isEmpty()) {
            Label none = new Label(I18n.get("msg.no_data"));
            none.getStyleClass().add("field-hint");
            CustomMenuItem item = new CustomMenuItem(none, false);
            item.setDisable(true);
            menu.getItems().add(item);
        }
        for (T t : shown) {
            Label label = new Label(listText.apply(t));
            label.setPrefWidth(Math.max(200, field.getWidth() - 24));
            CustomMenuItem item = new CustomMenuItem(label, true);
            item.setOnAction(e -> pick(t));
            menu.getItems().add(item);
        }
        if (!menu.isShowing()) {
            menu.show(field, Side.BOTTOM, 0, 0);
        }
    }
}
