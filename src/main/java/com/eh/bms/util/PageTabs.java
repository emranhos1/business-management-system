package com.eh.bms.util;

import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * Tabs that fill a whole page (e.g. Sales: new sale / sales list). A plain TabPane does not pass its open
 * tab's minimum height up, so on a short window the page is squeezed and cut off instead of scrolling;
 * this one does, so the page ScrollPane (main.fxml) scrolls.
 */
public class PageTabs extends TabPane {

    public PageTabs() {
        getSelectionModel().selectedItemProperty().addListener(o -> requestLayout());
    }

    @Override
    protected double computeMinHeight(double width) {
        Tab tab = getSelectionModel().getSelectedItem();
        Node content = tab == null ? null : tab.getContent();
        if (content == null) {
            return super.computeMinHeight(width);
        }
        Node header = lookup(".tab-header-area");
        double headerHeight = header == null ? 0 : header.prefHeight(-1);
        return snappedTopInset() + headerHeight + content.minHeight(width) + snappedBottomInset();
    }
}
