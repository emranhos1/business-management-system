package com.eh.bms.controller;

import com.eh.bms.model.Product;
import com.eh.bms.util.CodeImage;
import com.eh.bms.util.CodeImage.Kind;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.NodePrinter;
import javafx.geometry.Pos;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The page of product labels (20.4): {@code copies} labels of one product, {@value #COLUMNS} on a row, each with the
 * name, the code and the retail price. Built for the A4 paper's width; each row of labels is a
 * {@link NodePrinter#ROW}, so a page is cut between rows. The same page is the preview and the print.
 */
final class ProductLabelSheet {

    static final int COLUMNS = 3;
    /** Most labels one page makes (a sheet of paper holds about 24). */
    static final int MAX_COPIES = 240;
    private static final double PAGE_WIDTH = 720;
    private static final double GAP = 8;
    private static final double LABEL_HEIGHT = 112;

    private ProductLabelSheet() {
    }

    /** Null when the code cannot be made (the caller checks {@link CodeImage#fitsBarcode} first for a barcode). */
    static VBox build(Product product, Kind kind, int copies) {
        Image code = CodeImage.create(product.getSku(), kind);
        VBox page = new VBox(GAP);
        page.getStyleClass().add("label-sheet");
        page.setPrefWidth(PAGE_WIDTH);
        page.setMinWidth(PAGE_WIDTH);
        page.setMaxWidth(PAGE_WIDTH);
        for (int i = 0; i < copies; i += COLUMNS) {
            HBox row = new HBox(GAP);
            row.getStyleClass().add(NodePrinter.ROW);
            for (int c = 0; c < COLUMNS; c++) {
                row.getChildren().add(i + c < copies ? label(product, kind, code) : spacer());
            }
            page.getChildren().add(row);
        }
        return page;
    }

    private static VBox label(Product product, Kind kind, Image code) {
        Label name = new Label(ProductsController.nameText(product));
        name.getStyleClass().add("label-name");
        name.setWrapText(true);
        name.setMaxHeight(32);

        ImageView picture = new ImageView(code);
        picture.setPreserveRatio(true);
        if (kind == Kind.QR) {
            picture.setFitHeight(56);
        } else {
            picture.setFitWidth(150);
            picture.setFitHeight(40);
        }

        Label price = new Label(product.getRetailPrice() == null ? "" : MoneyUtil.format(product.getRetailPrice()));
        price.getStyleClass().add("label-price");

        VBox cell = new VBox(3, name, picture, price);
        cell.getStyleClass().add("label-cell");
        cell.setAlignment(Pos.CENTER);
        cell.setPrefHeight(LABEL_HEIGHT);
        cell.setMinHeight(LABEL_HEIGHT);
        HBox.setHgrow(cell, Priority.ALWAYS);
        cell.setPrefWidth(0);
        cell.setMaxWidth(Double.MAX_VALUE);
        return cell;
    }

    /** An empty place in the last row, so its labels keep their size. */
    private static VBox spacer() {
        VBox empty = new VBox();
        HBox.setHgrow(empty, Priority.ALWAYS);
        empty.setPrefWidth(0);
        empty.setMaxWidth(Double.MAX_VALUE);
        return empty;
    }
}
