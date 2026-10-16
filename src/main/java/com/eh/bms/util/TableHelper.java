package com.eh.bms.util;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
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

import java.util.ArrayList;
import java.util.List;
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
    /** Table properties read by the rows: the double-click action and the tooltip text. */
    private static final String ROW_DOUBLE_CLICK = "bms.rowDoubleClick";
    private static final String ROW_TOOLTIP = "bms.rowTooltip";
    private static final String ROW_STYLE = "bms.rowStyle";
    /** Row property: the row tooltip, set aside while the mouse is on the buttons column. */
    private static final String HIDDEN_ROW_TIP = "bms.hiddenRowTip";
    /** Tooltip values longer than this (characters) wrap at {@link #TOOLTIP_WIDTH}. */
    private static final int LONG_TIP_VALUE = 50;

    private TableHelper() {
    }

    /**
     * What every table gets (PaginatedTable calls it; call it for a plain table, then {@link #fitColumns} after
     * its rows change): "no data" text, column widths that fit their text and follow the table's width (never
     * past its edge), and the row tooltip.
     */
    public static void setUp(TableView<?> table) {
        Label empty = new Label();
        empty.textProperty().bind(I18n.bind("msg.no_data"));
        table.setPlaceholder(empty);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.widthProperty().addListener(o -> fillWidth(table));
        // Otherwise the table's min width follows its columns, which fillWidth stretched on a wide window,
        // so it would never get narrower again; narrower than its columns, it scrolls sideways instead
        table.setMinWidth(0);
        installRows(table);
    }

    /** Text column with a translated title; values come from {@code value}. */
    public static <T> TableColumn<T, String> column(String titleKey, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(I18n.get(titleKey));
        c.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        c.setSortable(false);
        return c;
    }

    /**
     * Text column whose text can be long (e.g. ledger details, address). The cell cuts it with "...";
     * the row tooltip (every table has one) shows it in full.
     */
    public static <T> TableColumn<T, String> longTextColumn(String titleKey, Function<T, String> value) {
        return column(titleKey, value);
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
                // Over the buttons column only each button's own tooltip shows, not the row's full details
                setOnMouseEntered(e -> {
                    TableRow<T> row = getTableRow();
                    if (row != null && row.getTooltip() != null) {
                        row.getProperties().put(HIDDEN_ROW_TIP, row.getTooltip());
                        row.setTooltip(null);
                    }
                });
                setOnMouseExited(e -> {
                    TableRow<T> row = getTableRow();
                    if (row != null && row.getProperties().remove(HIDDEN_ROW_TIP) instanceof Tooltip tip) {
                        row.setTooltip(tip);
                    }
                });
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

    /** Width the column needs; before {@link #fitColumns} ran, its first width (kept, so filling never makes it grow for good). */
    private static double fittedWidth(TableColumn<?, ?> c) {
        c.getProperties().putIfAbsent(FIT_WIDTH, c.getPrefWidth());
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

    /** One line of a row tooltip: "label : value"; {@code bold} for amounts. */
    public record TipLine(String label, String value, boolean bold) {
    }

    /** A row tooltip: a big title (e.g. the product name), then its lines. */
    public record RowTip(String title, List<TipLine> lines) {
    }

    /**
     * The default row tooltip: the first column's value as the title, then "heading : value" for the other
     * columns (button columns left out; money columns bold).
     */
    @SuppressWarnings("unchecked")
    static <T> RowTip columnsTip(TableView<T> table, T item) {
        String title = null;
        List<TipLine> lines = new ArrayList<>();
        for (TableColumn<T, ?> c : table.getColumns()) {
            if (c.getStyleClass().contains("button-column")) {
                continue;
            }
            Function<T, String> fitText = (Function<T, String>) c.getProperties().get(FIT_TEXT);
            String value = fitText != null ? fitText.apply(item) : Objects.toString(c.getCellData(item), "");
            if (value == null || value.isBlank()) {
                continue;
            }
            if (title == null) {
                title = value;
            } else {
                lines.add(new TipLine(c.getText(), value, c.getStyleClass().contains("money-column")));
            }
        }
        return new RowTip(title, lines);
    }

    /** Lays a tooltip out: big title on top, then label and value columns (main.css "row-tip"). */
    private static Node tipNode(RowTip tip) {
        VBox box = new VBox(6);
        box.getStyleClass().add("row-tip");
        if (tip.title() != null) {
            Label title = new Label(tip.title());
            title.getStyleClass().add("tip-title");
            box.getChildren().add(title);
        }
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(3);
        int r = 0;
        for (TipLine line : tip.lines()) {
            Label label = new Label(line.label());
            label.getStyleClass().add("tip-label");
            Label value = new Label(line.value());
            value.getStyleClass().add(line.bold() ? "tip-amount" : "tip-value");
            // Only a long value wraps, and then at a fixed width: a wrapping label with no width makes the
            // tooltip as tall as the screen
            if (line.value().length() > LONG_TIP_VALUE) {
                value.setWrapText(true);
                value.setPrefWidth(TOOLTIP_WIDTH);
                value.setMinHeight(Region.USE_PREF_SIZE);
            }
            grid.addRow(r++, label, new Label(":"), value);
        }
        box.getChildren().add(grid);
        return box;
    }

    /** Edit and delete buttons work only while a row is selected. */
    public static void needSelection(TableView<?> table, Button... buttons) {
        for (Button b : buttons) {
            b.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        }
    }

    /** Double-click on a row runs {@code action} with that row (e.g. open the edit form), if allowed. */
    public static <T> void onDoubleClick(TableView<T> table, boolean allowed, Consumer<T> action) {
        if (allowed) {
            table.getProperties().put(ROW_DOUBLE_CLICK, action);
        }
        installRows(table);
    }

    /**
     * Hovering a row shows {@code text} for it in a tooltip, e.g. every detail of a product when the table
     * shows only a few columns. Without this, the tooltip lists every column ({@link #columnsTip}).
     */
    public static <T> void rowTooltip(TableView<T> table, Function<T, RowTip> text) {
        table.getProperties().put(ROW_TOOLTIP, text);
        installRows(table);
    }

    /** Gives a row a style class (e.g. a red row below minimum stock); {@code style} may return null. */
    public static <T> void rowStyle(TableView<T> table, Function<T, String> style) {
        table.getProperties().put(ROW_STYLE, style);
        installRows(table);
    }

    /**
     * The rows every table gets (PaginatedTable calls it): a tooltip with the row's full data, shown while the
     * mouse stays on the row; the double-click action and row style if set.
     */
    @SuppressWarnings("unchecked")
    static <T> void installRows(TableView<T> table) {
        table.setRowFactory(tv -> {
            Tooltip tooltip = new Tooltip();
            tooltip.setShowDelay(Duration.millis(400));
            // Stays while the mouse is on the row (JavaFX would hide it after 5 s); goes when it leaves
            tooltip.setShowDuration(Duration.INDEFINITE);
            TableRow<T> row = new TableRow<>() {
                private String styled;

                @Override
                protected void updateItem(T item, boolean empty) {
                    super.updateItem(item, empty);
                    if (styled != null) {
                        getStyleClass().remove(styled);
                        styled = null;
                    }
                    if (empty || item == null) {
                        setTooltip(null);
                        return;
                    }
                    Function<T, String> style = (Function<T, String>) table.getProperties().get(ROW_STYLE);
                    styled = style == null ? null : style.apply(item);
                    if (styled != null) {
                        getStyleClass().add(styled);
                    }
                    Function<T, RowTip> text = (Function<T, RowTip>) table.getProperties().get(ROW_TOOLTIP);
                    RowTip tip = text != null ? text.apply(item) : columnsTip(table, item);
                    if (tip == null || (tip.title() == null && tip.lines().isEmpty())) {
                        setTooltip(null);
                    } else {
                        tooltip.setGraphic(tipNode(tip));
                        if (getProperties().containsKey(HIDDEN_ROW_TIP)) {
                            // The mouse is on the buttons column: keep it aside until the mouse leaves
                            getProperties().put(HIDDEN_ROW_TIP, tooltip);
                        } else {
                            setTooltip(tooltip);
                        }
                    }
                }
            };
            row.setOnMouseClicked(e -> {
                Consumer<T> action = (Consumer<T>) table.getProperties().get(ROW_DOUBLE_CLICK);
                if (action != null && e.getClickCount() == 2 && !row.isEmpty()) {
                    action.accept(row.getItem());
                }
            });
            return row;
        });
    }
}
