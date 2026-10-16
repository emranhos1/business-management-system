package com.eh.bms.util;

import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;

/**
 * Small icons drawn from SVG paths (Material Icons, 24 x 24, Apache License 2.0), so no icon library
 * is needed. The colour comes from CSS ({@code .icon} background in main.css).
 */
public final class Icons {

    /** Book: open a ledger. */
    public static final String LEDGER =
            "M18 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 4h5v8l-2.5-1.5L6 12V4z";
    /** Pencil: edit. */
    public static final String EDIT = "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41"
            + "l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z";
    /** Two sheets: copy (add a new record filled from this one). */
    public static final String COPY = "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11"
            + "c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z";
    /** Sliders: adjust (e.g. stock adjustment). */
    public static final String ADJUST = "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7z"
            + "m14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z";
    /** Bin: remove (e.g. a line from a sale not yet saved). */
    public static final String REMOVE = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z";
    /** Arrow turning back: return goods. */
    public static final String RETURN = "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88"
            + " 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z";
    /** Arrow down: money comes in (received). */
    public static final String MONEY_IN = "M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z";
    /** Arrow up: money goes out (paid). */
    public static final String MONEY_OUT = "M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z";
    /** Circle with a line: make inactive. */
    public static final String DEACTIVATE = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4 12"
            + "c0-4.42 3.58-8 8-8 1.85 0 3.55.63 4.9 1.69L5.69 16.9C4.63 15.55 4 13.85 4 12zm8 8c-1.85 0-3.55-.63"
            + "-4.9-1.69L18.31 7.1C19.37 8.45 20 10.15 20 12c0 4.42-3.58 8-8 8z";
    /** Circle with a tick: make active again. */
    public static final String ACTIVATE =
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z";

    private static final double SIZE = 16;

    private Icons() {
    }

    /** A {@code SIZE} px icon of this path; style class {@code icon}. */
    public static Region of(String path) {
        SVGPath shape = new SVGPath();
        shape.setContent(path);
        Region icon = new Region();
        icon.setShape(shape);
        icon.setScaleShape(true);
        icon.setMinSize(SIZE, SIZE);
        icon.setPrefSize(SIZE, SIZE);
        icon.setMaxSize(SIZE, SIZE);
        icon.getStyleClass().add("icon");
        return icon;
    }
}
