package com.eh.bms.util;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared table setup for list pages (add / edit / delete over a table). */
public final class TableHelper {

    private static final double TOOLTIP_WIDTH = 400;
    /** Column property: Function&lt;row, String&gt; giving the text {@link PaginatedTable} measures to size the column. */
    static final String FIT_TEXT = "bms.fitText";
    /** Column property: extra width (px) a cell needs around its text, e.g. a row button's padding and border. */
    static final String FIT_EXTRA = "bms.fitExtra";
    /** Widest a column grows to fit its text; longer text is cut with "...". */
    private static final double MAX_FIT_WIDTH = 320;
    /** Cell padding and the column line around the text. */
    private static final double CELL_PADDING = 24;
    /** Column property: the width {@link #fitColumns} worked out for it. */
    private static final String FIT_WIDTH = "bms.fitWidth";
    /** Pixels kept free at the table's right edge so rounding never pushes the last column past it. */
    private static final double EDGE_GAP = 2;

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

    /**
     * Right-aligned column whose cells each get a style class from their row (e.g. green "পাবো",
     * red "দেবো"); {@code style} may return null for no colour.
     */
    public static <T> TableColumn<T, T> styledColumn(String titleKey, Function<T, String> text,
                                                     Function<T, String> style) {
        TableColumn<T, T> c = new TableColumn<>(I18n.get(titleKey));
        c.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        c.setCellFactory(col -> new TableCell<>() {
            private String applied;

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (applied != null) {
                    getStyleClass().remove(applied);
                    applied = null;
                }
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(text.apply(item));
                applied = style.apply(item);
                if (applied != null) {
                    getStyleClass().add(applied);
                }
            }
        });
        c.setSortable(false);
        c.getStyleClass().add("money-column");
        c.getProperties().put(FIT_TEXT, text);
        return c;
    }

    /**
     * One icon button on a row. {@code labelKey} (may be null) is a short word shown next to the icon, e.g.
     * "দেখুন"; {@code tooltipKey} says what it does (shown on hover); {@code colorClass} is one of the
     * icon-button colours in main.css (e.g. "icon-view", "icon-in", "icon-out", "icon-off", "icon-on").
     */
    public record RowAction<T>(String icon, String labelKey, String tooltipKey, String colorClass, Consumer<T> action) {
        /** Icon only. */
        public RowAction(String icon, String tooltipKey, String colorClass, Consumer<T> action) {
            this(icon, null, tooltipKey, colorClass, action);
        }
    }

    /** Padding, icon and gap around a row button's word. */
    private static final double ACTION_LABEL_EXTRA = 34;

    /** Pixels per icon button in the actions column (button + gap). */
    private static final double ACTION_WIDTH = 36;

    /**
     * A column of small icon buttons on every row (e.g. ledger, money in, money out, inactive).
     * Each button shows what it does in a tooltip. Pass only the actions the user may do.
     */
    @SafeVarargs
    public static <T> TableColumn<T, T> actionsColumn(String titleKey, RowAction<T>... actions) {
        TableColumn<T, T> c = new TableColumn<>(I18n.get(titleKey));
        c.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        c.setCellFactory(col -> new TableCell<>() {
            private final HBox box = new HBox(4);

            {
                box.setAlignment(Pos.CENTER);
                for (RowAction<T> a : actions) {
                    Button b = new Button();
                    b.setGraphic(Icons.of(a.icon()));
                    b.getStyleClass().addAll("icon-button", a.colorClass());
                    if (a.labelKey() != null) {
                        b.setText(I18n.get(a.labelKey()));
                        b.getStyleClass().add("icon-text-button");
                    }
                    Tooltip tip = new Tooltip(I18n.get(a.tooltipKey()));
                    tip.setShowDelay(Duration.millis(300));
                    b.setTooltip(tip);
                    b.setOnAction(e -> {
                        T item = getItem();
                        if (item != null) {
                            getTableView().getSelectionModel().select(item);
                            a.action().accept(item);
                        }
                    });
                    box.getChildren().add(b);
                }
            }

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null ? null : box);
            }
        });
        c.setSortable(false);
        c.getStyleClass().add("button-column");
        // Sized by its buttons: the words on them plus each button's icon, padding and gap
        StringBuilder words = new StringBuilder();
        double extra = 0;
        for (RowAction<T> a : actions) {
            if (a.labelKey() == null) {
                extra += ACTION_WIDTH;
            } else {
                words.append(I18n.get(a.labelKey()));
                extra += ACTION_LABEL_EXTRA;
            }
        }
        String fitWords = words.toString();
        c.getProperties().put(FIT_TEXT, (Function<T, String>) item -> fitWords);
        c.getProperties().put(FIT_EXTRA, extra);
        return c;
    }

    /**
     * Sizes every column to its widest text (the heading or a value on the rows now shown), up to
     * {@link #MAX_FIT_WIDTH}, so short columns stay narrow and the others get room.
     * Space left over is shared out by {@link #fillWidth}. Longer values are cut with "..." (use {@link #longTextColumn}).
     */
    @SuppressWarnings("unchecked")
    public static <T> void fitColumns(TableView<T> table) {
        Font cellFont = fontOf(table, ".table-row-cell .table-cell", FontWeight.NORMAL);
        Font headerFont = fontOf(table, ".column-header .label", FontWeight.BOLD);
        for (TableColumn<T, ?> c : table.getColumns()) {
            Function<T, String> fitText = (Function<T, String>) c.getProperties().get(FIT_TEXT);
            double extra = c.getProperties().get(FIT_EXTRA) instanceof Double d ? d : 0;
            double width = textWidth(c.getText(), headerFont);
            for (T item : table.getItems()) {
                String text = fitText != null ? fitText.apply(item) : Objects.toString(c.getCellData(item), "");
                width = Math.max(width, textWidth(text, cellFont) + extra);
            }
            double fitted = Math.ceil(Math.min(width + CELL_PADDING, MAX_FIT_WIDTH));
            c.setMinWidth(fitted);
            c.getProperties().put(FIT_WIDTH, fitted);
        }
        fillWidth(table);
    }

    /**
     * Sets each column to its fitted width plus a share of the table's spare width (bigger columns get more).
     * Widths are whole pixels and add up to a little less than the visible width, so the last column never
     * runs past the table's edge (the built-in constrained policies overshoot with Windows display scaling,
     * e.g. 125%). When the columns need more than the visible width, they keep their fitted width and the
     * table scrolls sideways.
     */
    public static void fillWidth(TableView<?> table) {
        double available = Math.floor(table.getWidth() - table.snappedLeftInset() - table.snappedRightInset()
                - verticalScrollBarWidth(table)) - EDGE_GAP;
        double total = 0;
        for (TableColumn<?, ?> c : table.getColumns()) {
            total += fittedWidth(c);
        }
        double spare = total > 0 ? available - total : 0;
        for (TableColumn<?, ?> c : table.getColumns()) {
            double fitted = fittedWidth(c);
            c.setPrefWidth(spare > 0 ? Math.floor(fitted + spare * fitted / total) : fitted);
        }
    }

    private static double fittedWidth(TableColumn<?, ?> c) {
        return c.getProperties().get(FIT_WIDTH) instanceof Double d ? d : c.getPrefWidth();
    }

    /**
     * Width of the table's vertical scroll bar while it shows, else 0. The first time the bar is found, a
     * listener re-runs {@link #fillWidth} whenever it shows or hides (e.g. a page with more rows).
     */
    private static double verticalScrollBarWidth(TableView<?> table) {
        for (Node node : table.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                if (bar.getProperties().putIfAbsent(FIT_WIDTH, Boolean.TRUE) == null) {
                    bar.visibleProperty().addListener(o -> fillWidth(table));
                }
                return bar.isVisible() ? bar.getWidth() : 0;
            }
        }
        return 0;
    }

    /** Font the table's CSS gives {@code selector}; before the table is drawn, the same font as main.css. */
    private static Font fontOf(TableView<?> table, String selector, FontWeight fallbackWeight) {
        Node node = table.lookup(selector);
        if (node instanceof Labeled labeled) {
            return labeled.getFont();
        }
        return Font.font("Nirmala UI", fallbackWeight, 13);
    }

    private static double textWidth(String text, Font font) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Text measure = new Text(text);
        measure.setFont(font);
        return measure.getLayoutBounds().getWidth();
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
