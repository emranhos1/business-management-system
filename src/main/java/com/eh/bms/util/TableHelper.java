package com.eh.bms.util;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.Button;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

import java.util.function.Consumer;
import java.util.function.Function;

/** Shared table setup for list pages (add / edit / delete over a table). */
public final class TableHelper {

    private static final double TOOLTIP_WIDTH = 400;

    private TableHelper() {
    }

    /** Text column with a translated title; values come from {@code value}. */
    public static <T> TableColumn<T, String> column(String titleKey, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(I18n.get(titleKey));
        c.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        c.setSortable(false);
        return c;
    }

    /** Text column whose full text shows in a tooltip on hover (e.g. ledger details, cut off when long). */
    public static <T> TableColumn<T, String> longTextColumn(String titleKey, Function<T, String> value) {
        TableColumn<T, String> c = column(titleKey, value);
        c.setCellFactory(col -> new TableCell<>() {
            private final Tooltip tooltip = new Tooltip();

            {
                tooltip.setWrapText(true);
                tooltip.setMaxWidth(TOOLTIP_WIDTH);
                tooltip.setShowDelay(Duration.millis(300));
            }

            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                tooltip.setText(text);
                setTooltip(empty || text == null || text.isBlank() ? null : tooltip);
            }
        });
        return c;
    }

    /** Money column: right-aligned so amounts line up. */
    public static <T> TableColumn<T, String> moneyColumn(String titleKey, Function<T, String> value) {
        TableColumn<T, String> c = column(titleKey, value);
        c.getStyleClass().add("money-column");
        return c;
    }

    /** A small button on every row (e.g. Copy); clicking it runs {@code action} with that row. */
    public static <T> TableColumn<T, T> buttonColumn(String titleKey, String buttonKey, Consumer<T> action) {
        TableColumn<T, T> c = new TableColumn<>(I18n.get(titleKey));
        c.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        c.setCellFactory(col -> new TableCell<>() {
            private final Button button = new Button(I18n.get(buttonKey));

            {
                button.getStyleClass().add("row-button");
                button.setOnAction(e -> {
                    T item = getItem();
                    if (item != null) {
                        action.accept(item);
                    }
                });
            }

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null ? null : button);
            }
        });
        c.setSortable(false);
        c.getStyleClass().add("button-column");
        return c;
    }

    /** Edit and delete buttons work only while a row is selected. */
    public static void needSelection(TableView<?> table, Button... buttons) {
        for (Button b : buttons) {
            b.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        }
    }

    /** Double-click on a row runs {@code action} with that row (e.g. open the edit form), if allowed. */
    public static <T> void onDoubleClick(TableView<T> table, boolean allowed, Consumer<T> action) {
        table.setRowFactory(tv -> {
            TableRow<T> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (allowed && e.getClickCount() == 2 && !row.isEmpty()) {
                    action.accept(row.getItem());
                }
            });
            return row;
        });
    }
}
