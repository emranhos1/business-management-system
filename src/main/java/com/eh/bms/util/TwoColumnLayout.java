package com.eh.bms.util;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;

import java.util.List;

/**
 * Page with a main part on the left (62% by default, {@link #setMainPercent}) and a side part on the right,
 * e.g. Accounts + Account types; Settings uses 50/50 for its two equal cards.
 * When the page is narrower than {@link #STACK_BELOW}, or a part would get less than its own minimum width,
 * the side part moves under the main part; each part
 * then keeps at least {@link #PART_MIN_HEIGHT} so its table stays usable, and the page scrolls (main.fxml
 * puts every page in a ScrollPane).
 * <pre>{@code
 * <TwoColumnLayout fx:controller="...">
 *     <VBox> main part </VBox>
 *     <fx:include source="side-part.fxml"/>
 * </TwoColumnLayout>
 * }</pre>
 */
public class TwoColumnLayout extends GridPane {

    /** Page width (px) below which the two parts stack. */
    private static final double STACK_BELOW = 1000;
    /** Height (px) each part keeps while stacked. */
    private static final double PART_MIN_HEIGHT = 450;
    private static final double GAP = 24;

    /** Null until the first arrange, so it always runs once. */
    private Boolean stacked;
    /** Width share (%) of the main part side by side; the side part gets the rest. */
    private double mainPercent = 62;

    public TwoColumnLayout() {
        setHgap(GAP);
        setVgap(GAP);
        widthProperty().addListener(o -> arrange());
        getChildren().addListener((ListChangeListener<Node>) change -> {
            stacked = null;
            arrange();
        });
    }

    public double getMainPercent() {
        return mainPercent;
    }

    /** FXML: {@code <TwoColumnLayout mainPercent="50">} for two equal parts. */
    public void setMainPercent(double mainPercent) {
        this.mainPercent = mainPercent;
        stacked = null;
        arrange();
    }

    private void arrange() {
        boolean stack = getWidth() > 0 && (getWidth() < STACK_BELOW || !partsFitSideBySide());
        if (stacked != null && stacked == stack) {
            return;
        }
        stacked = stack;

        getColumnConstraints().clear();
        getRowConstraints().clear();
        if (stack) {
            getColumnConstraints().add(column(100));
            getRowConstraints().addAll(row(), row());
        } else {
            getColumnConstraints().addAll(column(mainPercent), column(100 - mainPercent));
            getRowConstraints().add(row());
        }
        for (int i = 0; i < getChildren().size(); i++) {
            GridPane.setConstraints(getChildren().get(i), stack ? 0 : i, stack ? i : 0);
        }
        // The new minimum height must reach the page's ScrollPane. This runs inside a layout pass, where
        // JavaFX does not pass a layout request up to parents that are already marked, so their remembered
        // (side-by-side) minimum stays and the page cannot scroll to the side part. Ask every parent again
        // after this pass.
        Platform.runLater(() -> {
            for (Parent p = this; p != null; p = p.getParent()) {
                p.requestLayout();
            }
        });
    }

    /** True if each part gets at least its own minimum width in its share of the current width. */
    private boolean partsFitSideBySide() {
        List<Node> parts = getManagedChildren();
        double room = getWidth() - snappedLeftInset() - snappedRightInset() - Math.max(0, parts.size() - 1) * getHgap();
        double[] shares = {mainPercent / 100, (100 - mainPercent) / 100};
        for (int i = 0; i < parts.size() && i < shares.length; i++) {
            if (parts.get(i).minWidth(-1) > room * shares[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The parts can always stack, so the page never has to be wider than its widest part. (Side by side, the
     * minimum would be both parts together: a page narrower than that would be pushed wider than the window,
     * cutting off the side part, instead of stacking.)
     */
    @Override
    protected double computeMinWidth(double height) {
        double width = 0;
        for (Node part : getManagedChildren()) {
            width = Math.max(width, part.minWidth(-1));
        }
        return snappedLeftInset() + width + snappedRightInset();
    }

    /**
     * Stacked, each part needs at least {@link #PART_MIN_HEIGHT} or its own content's minimum, whichever is
     * more (a fixed minimum would cut off a tall part, e.g. the sale totals, instead of letting the page scroll).
     */
    @Override
    protected double computeMinHeight(double width) {
        if (!Boolean.TRUE.equals(stacked)) {
            return super.computeMinHeight(width);
        }
        double height = snappedTopInset() + snappedBottomInset();
        List<Node> parts = getManagedChildren();
        for (Node part : parts) {
            height += Math.max(PART_MIN_HEIGHT, part.minHeight(-1));
        }
        return height + Math.max(0, parts.size() - 1) * getVgap();
    }

    private static ColumnConstraints column(double percent) {
        ColumnConstraints c = new ColumnConstraints();
        c.setPercentWidth(percent);
        return c;
    }

    private static RowConstraints row() {
        RowConstraints r = new RowConstraints();
        r.setVgrow(Priority.ALWAYS);
        return r;
    }
}
