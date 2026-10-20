package com.eh.bms.util;

import javafx.geometry.Bounds;
import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.Paper;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Prints a page made on screen (e.g. a sale memo, N6) on a {@link MemoPaper}: the printer dialog first, then the
 * page scaled to the paper's printable width. A page taller than the paper goes on more sheets, cut only under a
 * {@link #ROW} node (a row is never cut in half). Call on the JavaFX thread.
 */
public final class NodePrinter {

    private static final Logger LOG = Logger.getLogger(NodePrinter.class.getName());

    /** Style class of the rows a page may be cut under. */
    public static final String ROW = "print-row";
    /** 1 mm in points (1/72 inch). */
    private static final double MM = 72 / 25.4;
    /** A roll paper the printer offers: 75-85 mm wide and long (a short card of that width is no roll). */
    private static final double ROLL_MIN_MM = 75;
    private static final double ROLL_MAX_MM = 85;
    private static final double ROLL_MIN_LENGTH_MM = 200;
    private static final double ROLL_WIDTH_MM = 80;
    /** No roll (e.g. a PDF printer): a narrow paper (card) this wide, the memo then goes on several sheets. */
    private static final double CARD_MIN_MM = 55;
    private static final double CARD_MAX_MM = 90;
    /** Printed width of an 80 mm roll; also the memo's width on a normal paper when no roll is found. */
    private static final double ROLL_PRINT_MM = 72;

    public enum Status {
        DONE, CANCELLED, NO_PRINTER, FAILED
    }

    private NodePrinter() {
    }

    /** {@code page}: a new node (not shown anywhere), laid out here at its own width with the app stylesheet. */
    public static Status print(Region page, MemoPaper paper, Window owner) {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            return Status.NO_PRINTER;
        }
        try {
            if (!job.showPrintDialog(owner)) {
                job.cancelJob();
                return Status.CANCELLED;
            }
            PageLayout layout = layout(job, paper);
            job.getJobSettings().setPageLayout(layout);

            // Off-screen scene: CSS and layout at the page's own width
            Scene scene = WindowManager.scene(page);
            double width = paper.width();
            page.applyCss();
            double height = page.prefHeight(width);
            page.resize(width, height);
            page.layout();
            List<Double> cuts = cuts(page);

            // A roll memo stays 72 mm wide even on a wider paper (a normal printer with no roll)
            double printWidth = paper.isRoll() ? Math.min(layout.getPrintableWidth(), ROLL_PRINT_MM * MM)
                    : layout.getPrintableWidth();
            double scale = printWidth / width;
            double pageHeight = layout.getPrintableHeight() / scale;
            double start = 0;
            while (start < height - 0.5) {
                double end = start + pageHeight;
                if (end < height) {
                    end = lastCut(cuts, start, end);
                }
                page.getTransforms().setAll(new Scale(scale, scale), new Translate(0, -start));
                page.setClip(new Rectangle(0, start, width, end - start));
                if (!job.printPage(layout, page)) {
                    job.cancelJob();
                    return Status.FAILED;
                }
                start = end;
            }
            scene.setRoot(new Region());
            return job.endJob() ? Status.DONE : Status.FAILED;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not print", e);
            job.cancelJob();
            return Status.FAILED;
        }
    }

    /**
     * The paper's page on the chosen printer. A4 / A5 when the printer has it; the roll as {@link #rollPaper}.
     * Otherwise the paper picked in the printer dialog.
     */
    private static PageLayout layout(PrinterJob job, MemoPaper memoPaper) {
        Printer printer = job.getPrinter();
        Paper paper = memoPaper.isRoll() ? rollPaper(printer) : memoPaper.paper();
        if (paper == null || !printer.getPrinterAttributes().getSupportedPapers().contains(paper)) {
            return job.getJobSettings().getPageLayout();
        }
        Printer.MarginType margins = memoPaper.isRoll() ? Printer.MarginType.HARDWARE_MINIMUM : Printer.MarginType.DEFAULT;
        return printer.createPageLayout(paper, PageOrientation.PORTRAIT, margins);
    }

    /**
     * The longest 80 mm roll paper, so the memo comes out in one piece. A printer with no roll (e.g. PDF): the
     * narrow paper nearest 80 mm wide (user decision: narrow, on several sheets). Null when it has neither.
     * JavaFX cannot make a paper of the memo's own length: it turns any size into the nearest standard one.
     */
    private static Paper rollPaper(Printer printer) {
        Paper roll = null;
        Paper card = null;
        for (Paper p : printer.getPrinterAttributes().getSupportedPapers()) {
            double mm = p.getWidth() / MM;
            if (mm >= ROLL_MIN_MM && mm <= ROLL_MAX_MM && p.getHeight() / MM >= ROLL_MIN_LENGTH_MM
                    && (roll == null || p.getHeight() > roll.getHeight())) {
                roll = p;
            }
            if (mm >= CARD_MIN_MM && mm <= CARD_MAX_MM
                    && (card == null || Math.abs(mm - ROLL_WIDTH_MM) < Math.abs(card.getWidth() / MM - ROLL_WIDTH_MM))) {
                card = p;
            }
        }
        return roll != null ? roll : card;
    }

    /** Bottom edge of every row, top to bottom. */
    private static List<Double> cuts(Region page) {
        List<Double> cuts = new ArrayList<>();
        for (Node row : page.lookupAll("." + ROW)) {
            Bounds b = page.sceneToLocal(row.localToScene(row.getBoundsInLocal()));
            cuts.add(b.getMaxY());
        }
        cuts.sort(Double::compare);
        return cuts;
    }

    /** The lowest row edge on this sheet; the sheet's own end when no row ends on it. */
    private static double lastCut(List<Double> cuts, double start, double end) {
        double best = end;
        for (double c : cuts) {
            if (c > start + 1 && c <= end) {
                best = c;
            }
        }
        return best;
    }
}
