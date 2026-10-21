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
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

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
     * A column that shows a quantity with a small "−" and "+" button around it (e.g. the lines of a sale), so the
     * quantity can be changed by one without opening the line. {@code canMinus} greys the "−" out (e.g. at 1).
     */
    public static <T> TableColumn<T, T> stepperColumn(String titleKey, Function<T, String> text, Consumer<T> minus,
                                                      Consumer<T> plus, Predicate<T> canMinus) {
        TableColumn<T, T> c = new TableColumn<>(I18n.get(titleKey));
        c.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        c.setCellFactory(col -> new TableCell<>() {
            private final Button less = stepButton("−");
            private final Button more = stepButton("+");
            private final Label label = new Label();
            private final HBox box = new HBox(6, less, label, more);

            {
                box.setAlignment(Pos.CENTER_RIGHT);
                less.setOnAction(e -> {
                    T item = getItem();
                    if (item != null) {
                        minus.accept(item);
                    }
                });
                more.setOnAction(e -> {
                    T item = getItem();
                    if (item != null) {
                        plus.accept(item);
                    }
                });
            }

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                label.setText(text.apply(item));
                less.setDisable(!canMinus.test(item));
                setGraphic(box);
            }
        });
        c.setSortable(false);
        c.getStyleClass().add("money-column");
        fitBy(c, text, 2 * 28 + 12);
        return c;
    }

    private static Button stepButton(String sign) {
        Button b = new Button(sign);
        b.getStyleClass().add("step-button");
        b.setFocusTraversable(false);
        return b;
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
    public record RowAction<T>(String icon, String labelKey, String tooltipKey, String colorClass, Consumer<T> action,
                               Predicate<T> visibleWhen, Predicate<T> enabledWhen) {
        public RowAction(String icon, String labelKey, String tooltipKey, String colorClass, Consumer<T> action) {
            this(icon, labelKey, tooltipKey, colorClass, action, null, null);
        }

        /** Icon only. */
        public RowAction(String icon, String tooltipKey, String colorClass, Consumer<T> action) {
            this(icon, null, tooltipKey, colorClass, action, null, null);
        }

        /** Icon only, shown on the rows where {@code visibleWhen} is true. */
        public RowAction(String icon, String tooltipKey, String colorClass, Consumer<T> action,
                         Predicate<T> visibleWhen) {
            this(icon, null, tooltipKey, colorClass, action, visibleWhen, null);
        }

        /** The same button, shown only on the rows where {@code visible} is true. */
        public RowAction<T> visibleWhen(Predicate<T> visible) {
            return new RowAction<>(icon, labelKey, tooltipKey, colorClass, action, visible, enabledWhen);
        }

        /** The same button, greyed out (disabled) on the rows where {@code enabled} is false. */
        public RowAction<T> enabledWhen(Predicate<T> enabled) {
            return new RowAction<>(icon, labelKey, tooltipKey, colorClass, action, visibleWhen, enabled);
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
            private final java.util.List<Button> buttons = new java.util.ArrayList<>();

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
                    buttons.add(b);
                }
            }

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (!empty && item != null) {
                    for (int i = 0; i < actions.length; i++) {
                        boolean show = actions[i].visibleWhen() == null || actions[i].visibleWhen().test(item);
                        buttons.get(i).setVisible(show);
                        buttons.get(i).setManaged(show);
                        buttons.get(i).setDisable(actions[i].enabledWhen() != null && !actions[i].enabledWhen().test(item));
                    }
                }
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
        fitBy(c, item -> fitWords, extra);
        return c;
    }

    /**
     * For a column whose cells hold an input (ComboBox, text field) instead of plain text: {@link #fitColumns}
     * sizes it by {@code text} for each row plus {@code extra} px for the input's own padding and arrow.
     */
    public static <T> void fitBy(TableColumn<T, ?> column, Function<T, String> text, double extra) {
        column.getProperties().put(FIT_TEXT, text);
        column.getProperties().put(FIT_EXTRA, extra);
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
        fillWidth(table, table.getColumns(), () -> fillWidth(table));
    }

    /** {@link #fillWidth(TableView)} for any table kind; {@code again} re-runs it when the scroll bar shows or hides. */
    private static void fillWidth(Control table, List<? extends TableColumnBase<?, ?>> columns, Runnable again) {
        double available = Math.floor(table.getWidth() - table.snappedLeftInset() - table.snappedRightInset()
                - verticalScrollBarWidth(table, again)) - EDGE_GAP;
        double total = 0;
        for (TableColumnBase<?, ?> c : columns) {
            total += fittedWidth(c);
        }
        double spare = total > 0 ? available - total : 0;
        for (TableColumnBase<?, ?> c : columns) {
            double fitted = fittedWidth(c);
            c.setPrefWidth(spare > 0 ? Math.floor(fitted + spare * fitted / total) : fitted);
        }
    }

    /** Width the column needs; before {@link #fitColumns} ran, its first width (kept, so filling never makes it grow for good). */
    private static double fittedWidth(TableColumnBase<?, ?> c) {
        c.getProperties().putIfAbsent(FIT_WIDTH, c.getPrefWidth());
        return c.getProperties().get(FIT_WIDTH) instanceof Double d ? d : c.getPrefWidth();
    }

    /**
     * Width of the table's vertical scroll bar while it shows, else 0. The first time the bar is found, a
     * listener runs {@code again} (fills the width again) whenever it shows or hides (e.g. a page with more rows).
     */
    private static double verticalScrollBarWidth(Control table, Runnable again) {
        for (Node node : table.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                if (bar.getProperties().putIfAbsent(FIT_WIDTH, Boolean.TRUE) == null) {
                    bar.visibleProperty().addListener(o -> again.run());
                }
                return bar.isVisible() ? bar.getWidth() : 0;
            }
        }
        return 0;
    }

    /** Font the table's CSS gives {@code selector}; before the table is drawn, the same font as main.css. */
    private static Font fontOf(Control table, String selector, FontWeight fallbackWeight) {
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
            Node value;
            if (line.value().length() > LONG_TIP_VALUE) {
                // A long value wraps as a Text at a fixed width: a wrapping Label is measured without a width
                // inside the tooltip, which made the tooltip as tall as the screen
                Text text = new Text(line.value());
                text.setWrappingWidth(TOOLTIP_WIDTH);
                text.getStyleClass().add("tip-long-value");
                value = text;
            } else {
                Label shortValue = new Label(line.value());
                shortValue.getStyleClass().add(line.bold() ? "tip-amount" : "tip-value");
                value = shortValue;
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

    // ---- Grouped tables (TreeTableView): a group row with its rows under it, e.g. expenses by category ----

    /** Style class of a group row (a row with rows under it): bold on a light background (main.css). */
    private static final String GROUP_ROW = "group-row";
    /** Space the tree column gives each level, and the expand arrow before the text. */
    private static final double TREE_INDENT = 10;
    private static final double TREE_ARROW = 18;

    /**
     * What every grouped table gets: "no data" text, column widths that fit their text and follow the table's
     * width (call {@link #fitColumns(TreeTableView)} after its rows change), group rows bold on a light background
     * that open / close on a click, and the row tooltip {@code tip} gives (null = no tooltip for that row).
     */
    public static <T> void setUp(TreeTableView<T> tree, Function<T, RowTip> tip) {
        Label empty = new Label();
        empty.textProperty().bind(I18n.bind("msg.no_data"));
        tree.setPlaceholder(empty);
        tree.setShowRoot(false);
        tree.setColumnResizePolicy(TreeTableView.UNCONSTRAINED_RESIZE_POLICY);
        tree.widthProperty().addListener(o -> fillWidth(tree));
        tree.setMinWidth(0);
        tree.setRowFactory(tv -> {
            Tooltip tooltip = new Tooltip();
            tooltip.setShowDelay(Duration.millis(400));
            tooltip.setShowDuration(Duration.INDEFINITE);
            TreeTableRow<T> row = new TreeTableRow<>() {
                @Override
                protected void updateItem(T item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().remove(GROUP_ROW);
                    if (empty || item == null) {
                        setTooltip(null);
                        return;
                    }
                    if (getTreeItem() != null && !getTreeItem().isLeaf()) {
                        getStyleClass().add(GROUP_ROW);
                    }
                    RowTip t = tip == null ? null : tip.apply(item);
                    Tooltip shown = null;
                    if (t != null && (t.title() != null || !t.lines().isEmpty())) {
                        tooltip.setGraphic(tipNode(t));
                        shown = tooltip;
                    }
                    if (getProperties().containsKey(HIDDEN_ROW_TIP)) {
                        // The mouse is on the buttons column: keep it aside until the mouse leaves
                        getProperties().put(HIDDEN_ROW_TIP, shown);
                    } else {
                        setTooltip(shown);
                    }
                }
            };
            // A press anywhere on a group row (the arrow too) opens / closes it once. It is handled here and
            // consumed, so the arrow's own toggle never runs as well (two toggles = nothing seems to happen).
            row.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                TreeItem<T> item = row.getTreeItem();
                if (e.getButton() == MouseButton.PRIMARY && item != null && !item.isLeaf()) {
                    item.setExpanded(!item.isExpanded());
                    e.consume();
                }
            });
            return row;
        });
    }

    /** Text column of a grouped table with a translated title. */
    public static <T> TreeTableColumn<T, String> treeColumn(String titleKey, Function<T, String> value) {
        TreeTableColumn<T, String> c = new TreeTableColumn<>(I18n.get(titleKey));
        c.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue() == null || cell.getValue().getValue() == null ? "" : value.apply(cell.getValue().getValue())));
        c.setSortable(false);
        return c;
    }

    /** Money column of a grouped table: right-aligned so amounts line up. */
    public static <T> TreeTableColumn<T, String> treeMoneyColumn(String titleKey, Function<T, String> value) {
        TreeTableColumn<T, String> c = treeColumn(titleKey, value);
        c.getStyleClass().add("money-column");
        return c;
    }

    /**
     * Icon buttons on the rows of a grouped table for which {@code shown} is true (e.g. edit on an expense,
     * none on its group row); same look and tooltips as {@link #actionsColumn}.
     */
    @SafeVarargs
    public static <T> TreeTableColumn<T, T> treeActionsColumn(String titleKey, Predicate<T> shown, RowAction<T>... actions) {
        TreeTableColumn<T, T> c = new TreeTableColumn<>(I18n.get(titleKey));
        c.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue() == null ? null : cell.getValue().getValue()));
        c.setCellFactory(col -> new TreeTableCell<>() {
            private final HBox box = new HBox(4);

            {
                setOnMouseEntered(e -> {
                    TreeTableRow<T> row = getTableRow();
                    if (row != null && row.getTooltip() != null) {
                        row.getProperties().put(HIDDEN_ROW_TIP, row.getTooltip());
                        row.setTooltip(null);
                    }
                });
                setOnMouseExited(e -> {
                    TreeTableRow<T> row = getTableRow();
                    if (row != null && row.getProperties().containsKey(HIDDEN_ROW_TIP)) {
                        Object tip = row.getProperties().remove(HIDDEN_ROW_TIP);
                        row.setTooltip(tip instanceof Tooltip t ? t : null);
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
                            a.action().accept(item);
                        }
                    });
                    box.getChildren().add(b);
                }
            }

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null || !shown.test(item) ? null : box);
            }
        });
        c.setSortable(false);
        c.getStyleClass().add("button-column");
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
     * {@link #fitColumns(TableView)} for a grouped table: every row counts, closed groups too (so widths do not
     * jump when a group opens); group rows are measured bold, and the first column gets room for the arrow and
     * the indent.
     */
    @SuppressWarnings("unchecked")
    public static <T> void fitColumns(TreeTableView<T> tree) {
        Font cellFont = fontOf(tree, ".tree-table-row-cell .tree-table-cell", FontWeight.NORMAL);
        Font boldFont = Font.font(cellFont.getFamily(), FontWeight.BOLD, cellFont.getSize());
        Font headerFont = fontOf(tree, ".column-header .label", FontWeight.BOLD);
        List<TreeItem<T>> items = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        if (tree.getRoot() != null) {
            collect(tree.getRoot().getChildren(), 0, items, depths);
        }
        boolean first = true;
        for (TreeTableColumn<T, ?> c : tree.getColumns()) {
            Function<T, String> fitText = (Function<T, String>) c.getProperties().get(FIT_TEXT);
            double extra = c.getProperties().get(FIT_EXTRA) instanceof Double d ? d : 0;
            double width = textWidth(c.getText(), headerFont);
            for (int i = 0; i < items.size(); i++) {
                TreeItem<T> item = items.get(i);
                String text = fitText != null ? fitText.apply(item.getValue()) : Objects.toString(c.getCellData(item), "");
                double w = textWidth(text, item.isLeaf() ? cellFont : boldFont) + extra;
                if (first) {
                    w += TREE_ARROW + TREE_INDENT * depths.get(i);
                }
                width = Math.max(width, w);
            }
            double fitted = Math.ceil(Math.min(width + CELL_PADDING, MAX_FIT_WIDTH));
            c.setMinWidth(fitted);
            c.getProperties().put(FIT_WIDTH, fitted);
            first = false;
        }
        fillWidth(tree);
    }

    private static <T> void collect(List<TreeItem<T>> children, int depth, List<TreeItem<T>> items, List<Integer> depths) {
        for (TreeItem<T> child : children) {
            items.add(child);
            depths.add(depth);
            collect(child.getChildren(), depth + 1, items, depths);
        }
    }

    /** {@link #fillWidth(TableView)} for a grouped table. */
    public static void fillWidth(TreeTableView<?> tree) {
        fillWidth(tree, tree.getColumns(), () -> fillWidth(tree));
    }
}
