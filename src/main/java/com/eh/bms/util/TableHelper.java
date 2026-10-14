package com.eh.bms.util;

import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;

import java.util.function.Consumer;
import java.util.function.Function;

/** Shared table setup for list pages (add / edit / delete over a table). */
public final class TableHelper {

    private TableHelper() {
    }

    /** Text column with a translated title; values come from {@code value}. */
    public static <T> TableColumn<T, String> column(String titleKey, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(I18n.get(titleKey));
        c.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        c.setSortable(false);
        return c;
    }

    /** Money column: right-aligned so amounts line up. */
    public static <T> TableColumn<T, String> moneyColumn(String titleKey, Function<T, String> value) {
        TableColumn<T, String> c = column(titleKey, value);
        c.getStyleClass().add("money-column");
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
