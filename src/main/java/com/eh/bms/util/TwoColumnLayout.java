package com.eh.bms.util;

import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;

/**
 * Page with a main part on the left (62%) and a side part on the right (38%), e.g. Accounts + Account types.
 * When the page is narrower than {@link #STACK_BELOW}, the side part moves under the main part; each part
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

    public TwoColumnLayout() {
        setHgap(GAP);
        setVgap(GAP);
        widthProperty().addListener(o -> arrange());
        getChildren().addListener((ListChangeListener<Node>) change -> {
            stacked = null;
            arrange();
        });
    }

    private void arrange() {
        boolean stack = getWidth() > 0 && getWidth() < STACK_BELOW;
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
            getColumnConstraints().addAll(column(62), column(38));
            getRowConstraints().add(row());
        }
        for (int i = 0; i < getChildren().size(); i++) {
            Node child = getChildren().get(i);
            GridPane.setConstraints(child, stack ? 0 : i, stack ? i : 0);
            if (child instanceof Region region) {
                region.setMinHeight(stack ? PART_MIN_HEIGHT : Region.USE_COMPUTED_SIZE);
            }
        }
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
