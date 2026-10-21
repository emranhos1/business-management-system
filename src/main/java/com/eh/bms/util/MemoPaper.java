package com.eh.bms.util;

import javafx.print.Paper;

/**
 * Paper a sale memo is printed on (N6, Settings {@code memo_paper}). {@code width} is the memo's width on screen
 * (px); printing scales it to the paper's printable width.
 */
public enum MemoPaper {
    A4(720, Paper.A4),
    A5(520, Paper.A5),
    /** Thermal roll, 80 mm wide (POS printer). Its paper comes from the printer's own list (see NodePrinter). */
    ROLL_80(300, null);

    public static final MemoPaper DEFAULT = A5;

    private final double width;
    private final Paper paper;

    MemoPaper(double width, Paper paper) {
        this.width = width;
        this.paper = paper;
    }

    public double width() {
        return width;
    }

    /** The paper to print on; null for the roll, whose size the printer itself knows. */
    public Paper paper() {
        return paper;
    }

    public boolean isRoll() {
        return this == ROLL_80;
    }

    /** The saved name to a paper; a missing or unknown name gives the default. */
    public static MemoPaper of(String name) {
        if (name != null) {
            for (MemoPaper p : values()) {
                if (p.name().equalsIgnoreCase(name.trim())) {
                    return p;
                }
            }
        }
        return DEFAULT;
    }
}
