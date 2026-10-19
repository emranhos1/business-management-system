package com.eh.bms.util;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.DatePickerSkin;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One field for a date range (e.g. a list filter): click it, pick the first day, then the last day on the
 * same calendar; the days between are marked. One click and closing the calendar picks that single day.
 * Shows "১৪-১০-২০২৬ – ১৬-১০-২০২৬". {@link #setOnChange} runs after each new range or {@link #clear()}.
 * <pre>{@code <DateRangePicker fx:id="dateRange" prefWidth="260"/>}</pre>
 */
public class DateRangePicker extends StackPane {

    private final TextField field = new TextField();
    private final DatePicker calendar = new DatePicker();
    private final Label hint = new Label();
    private final Popup popup = new Popup();
    private final VBox popupBox;
    private final ObjectProperty<LocalDate> from = new SimpleObjectProperty<>();
    private final ObjectProperty<LocalDate> to = new SimpleObjectProperty<>();
    /** First day picked, waiting for the last one; null when not picking. */
    private LocalDate start;
    private Runnable onChange = () -> { };
    /** Every day cell the calendar made (it reuses them month to month). */
    private final List<DateCell> cells = new ArrayList<>();

    public DateRangePicker() {
        getStyleClass().add("picker-box");
        field.setEditable(false);
        field.promptTextProperty().bind(I18n.bind("date_range.prompt"));
        Region arrow = new Region();
        arrow.getStyleClass().add("picker-arrow");
        arrow.setMouseTransparent(true);
        StackPane.setAlignment(arrow, Pos.CENTER_RIGHT);
        getChildren().addAll(field, arrow);

        DateUtil.apply(calendar);
        dayCells();
        Node days = new DatePickerSkin(calendar).getPopupContent();
        hint.getStyleClass().add("field-hint");
        popupBox = new VBox(6, hint, days);
        popupBox.getStyleClass().add("range-popup");
        popup.getContent().add(popupBox);
        popup.setAutoHide(true);
        // Closed after only the first day: that one day is the range
        popup.setOnHidden(e -> {
            if (start != null) {
                LocalDate day = start;
                start = null;
                set(day, day);
            }
        });

        field.setOnMouseClicked(e -> toggle());
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.DOWN) {
                toggle();
                e.consume();
            }
        });
    }

    public ReadOnlyObjectProperty<LocalDate> fromProperty() {
        return from;
    }

    public ReadOnlyObjectProperty<LocalDate> toProperty() {
        return to;
    }

    public LocalDate getFrom() {
        return from.get();
    }

    public LocalDate getTo() {
        return to.get();
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange == null ? () -> { } : onChange;
    }

    /** Shows this range (e.g. this month at first) and runs the change action. */
    public void setRange(LocalDate first, LocalDate last) {
        start = null;
        set(first, last);
    }

    /** No range (all dates). */
    public void clear() {
        start = null;
        from.set(null);
        to.set(null);
        field.clear();
        refreshCells();
        onChange.run();
    }

    private void toggle() {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        start = null;
        hint.setText(I18n.get("date_range.pick_first"));
        calendar.setValue(from.get() == null ? LocalDate.now() : from.get());
        // The popup is its own window: give it the app's styles (range colours)
        if (getScene() != null) {
            popupBox.getStylesheets().setAll(getScene().getStylesheets());
        }
        Bounds b = field.localToScreen(field.getBoundsInLocal());
        refreshCells();
        popup.show(field, b.getMinX(), b.getMaxY() + 2);
    }

    private void pick(LocalDate day) {
        if (start == null) {
            start = day;
            hint.setText(I18n.get("date_range.pick_last"));
            refreshCells();
            return;
        }
        LocalDate first = day.isBefore(start) ? day : start;
        LocalDate last = day.isBefore(start) ? start : day;
        start = null;
        set(first, last);
        popup.hide();
    }

    private void set(LocalDate first, LocalDate last) {
        from.set(first);
        to.set(last);
        field.setText(first.equals(last) ? DateUtil.format(first)
                : DateUtil.format(first) + " – " + DateUtil.format(last));
        refreshCells();
        onChange.run();
    }

    /**
     * Marks the range on the calendar's days. The calendar does not redraw its days when the range changes,
     * so every day cell is kept and recoloured here (set once, in the constructor, via {@link #dayCells()}).
     */
    private void refreshCells() {
        cells.forEach(this::mark);
    }

    /** Day cells that pick on click and are coloured by {@link #mark}. */
    private void dayCells() {
        calendar.setDayCellFactory(p -> {
            DateCell cell = new DateCell() {
                @Override
                public void updateItem(LocalDate date, boolean empty) {
                    super.updateItem(date, empty);
                    mark(this);
                }
            };
            cell.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
                if (cell.getItem() != null && !cell.isEmpty()) {
                    pick(cell.getItem());
                }
            });
            cells.add(cell);
            return cell;
        });
    }

    private void mark(DateCell cell) {
        cell.getStyleClass().removeAll("range-day", "range-edge");
        LocalDate date = cell.getItem();
        LocalDate a = start != null ? start : from.get();
        LocalDate b = start != null ? start : to.get();
        if (cell.isEmpty() || date == null || a == null || b == null) {
            return;
        }
        if (date.equals(a) || date.equals(b)) {
            cell.getStyleClass().add("range-edge");
        } else if (date.isAfter(a) && date.isBefore(b)) {
            cell.getStyleClass().add("range-day");
        }
    }
}
