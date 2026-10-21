package com.eh.bms.controller;

import com.eh.bms.model.BusinessProfile;
import com.eh.bms.model.BusinessTransaction;
import com.eh.bms.model.Party;
import com.eh.bms.model.TransactionItem;
import com.eh.bms.model.TransactionPayment;
import com.eh.bms.service.SaleService.Memo;
import com.eh.bms.util.AppPaths;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.MemoPaper;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.NodePrinter;
import com.eh.bms.util.PartyText;
import com.eh.bms.util.UnitUtil;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A sale memo (N6) as a page for the screen and the printer: shop, memo no. and date, customer, goods, totals,
 * money received, the party's earlier and present balance, thanks and signatures. A4 / A5 show the goods as a
 * table; the 80 mm roll shows each line on two rows. Texts in the app's language.
 */
final class SaleMemo {

    private static final double LOGO_PAGE = 64;
    private static final double LOGO_ROLL = 44;
    private static final double COL_NO = 26;
    private static final double COL_QTY = 90;
    private static final double COL_MONEY = 84;
    private static final double TOTALS_WIDTH = 280;

    private SaleMemo() {
    }

    static VBox build(Memo memo, MemoPaper paper) {
        BusinessTransaction sale = memo.details().sale();
        VBox page = new VBox();
        page.getStyleClass().addAll("memo", paper.isRoll() ? "memo-roll" : "memo-page");
        page.setMinWidth(paper.width());
        page.setPrefWidth(paper.width());
        page.setMaxWidth(paper.width());

        row(page, header(memo.profile(), paper));
        Label title = new Label(I18n.get("memo.title"));
        title.getStyleClass().add("memo-title");
        HBox titleBox = new HBox(title);
        titleBox.setAlignment(Pos.CENTER);
        row(page, titleBox);
        row(page, spread(I18n.get("memo.no", SalesListController.memoText(sale)),
                I18n.get("memo.date", DateUtil.format(sale.getDate()))));
        customer(page, sale);
        row(page, line());

        boolean discount = memo.details().items().stream().anyMatch(i -> i.getDiscountPerUnit().signum() > 0);
        if (paper.isRoll()) {
            for (TransactionItem item : memo.details().items()) {
                rollItem(page, item);
            }
        } else {
            row(page, itemRow(true, discount, "#", I18n.get("sale.product"), I18n.get("sale.quantity"),
                    I18n.get("sale.price"), I18n.get("memo.discount"), I18n.get("sale.line_total"), null));
            int n = 0;
            for (TransactionItem item : memo.details().items()) {
                n++;
                row(page, itemRow(false, discount, I18n.digits(String.valueOf(n)), UnitUtil.productName(item.getProduct()),
                        quantity(item), MoneyUtil.format(item.getUnitPrice()), MoneyUtil.format(item.getDiscountPerUnit()),
                        MoneyUtil.format(item.getTotalPrice()), returned(item)));
            }
        }
        row(page, line());

        totals(page, sale, paper);
        payments(page, memo, paper);
        balances(page, memo, paper);
        if (sale.getParty() != null && sale.getNote() != null && !sale.getNote().isBlank()) {
            row(page, wrapped(I18n.get("memo.note", sale.getNote()), "memo-small"));
        }

        Label thanks = new Label(I18n.get("memo.thanks"));
        thanks.getStyleClass().add("memo-thanks");
        thanks.setWrapText(true);
        HBox thanksBox = new HBox(thanks);
        thanksBox.setAlignment(Pos.CENTER);
        row(page, thanksBox);
        row(page, signatures());
        return page;
    }

    // ---------------------------------------------------------------- parts

    /** Logo beside the shop's name, address and contact (A4 / A5); on the roll, all centred one under another. */
    private static Node header(BusinessProfile p, MemoPaper paper) {
        VBox text = new VBox(2);
        text.setAlignment(Pos.CENTER);
        Label name = wrapped(p.getName(), "memo-shop");
        text.getChildren().add(name);
        if (p.getAddress() != null && !p.getAddress().isBlank()) {
            text.getChildren().add(wrapped(p.getAddress().replace('\n', ' '), "memo-small"));
        }
        String contact = I18n.digits(p.getPhone() == null ? "" : p.getPhone())
                + (p.getEmail() == null || p.getEmail().isBlank() ? "" : "  •  " + p.getEmail());
        if (!contact.isBlank()) {
            text.getChildren().add(wrapped(contact, "memo-small"));
        }
        ImageView logo = logo(p, paper.isRoll() ? LOGO_ROLL : LOGO_PAGE);
        if (paper.isRoll()) {
            VBox box = new VBox(4);
            box.setAlignment(Pos.CENTER);
            if (logo != null) {
                box.getChildren().add(logo);
            }
            box.getChildren().add(text);
            return box;
        }
        HBox box = new HBox(16);
        box.setAlignment(Pos.CENTER);
        if (logo != null) {
            box.getChildren().add(logo);
        }
        box.getChildren().add(text);
        return box;
    }

    private static ImageView logo(BusinessProfile p, double size) {
        if (p.getLogoPath() == null) {
            return null;
        }
        Path file = AppPaths.resolve(p.getLogoPath());
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Image image = new Image(file.toUri().toString(), size * 2, size * 2, true, true);
        if (image.isError()) {
            return null;
        }
        ImageView view = new ImageView(image);
        view.setFitHeight(size);
        view.setFitWidth(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }

    /** Party: name, mobile, address. Walk-in: "নগদ ক্রেতা" with the name and mobile kept in the note. */
    private static void customer(VBox page, BusinessTransaction sale) {
        Party party = sale.getParty();
        if (party == null) {
            row(page, wrapped(I18n.get("memo.customer", SalesListController.customer(sale)), null));
            return;
        }
        row(page, wrapped(I18n.get("memo.customer", PartyText.name(party)), null));
        row(page, wrapped(I18n.get("memo.mobile", I18n.digits(party.getPhone())), null));
        if (party.getAddress() != null && !party.getAddress().isBlank()) {
            row(page, wrapped(I18n.get("memo.address", party.getAddress().replace('\n', ' ')), null));
        }
    }

    /** One goods line of the A4 / A5 table; {@code head} = the column names. The discount column only when used. */
    private static HBox itemRow(boolean head, boolean discount, String no, String product, String qty, String price,
                                String disc, String total, String returned) {
        Label productLabel = wrapped(product, null);
        Region productCell = returned == null ? productLabel : new VBox(productLabel, wrapped(returned, "memo-small"));
        HBox.setHgrow(productCell, Priority.ALWAYS);
        productCell.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(8, cell(no, COL_NO, false), productCell, cell(qty, COL_QTY, true), cell(price, COL_MONEY, true));
        if (discount) {
            row.getChildren().add(cell(disc, COL_MONEY, true));
        }
        row.getChildren().add(cell(total, COL_MONEY, true));
        row.getStyleClass().add(head ? "memo-head-row" : "memo-item-row");
        return row;
    }

    /** Roll: the product on one row, "qty × price (− discount)" and the line total under it. */
    private static void rollItem(VBox page, TransactionItem item) {
        VBox box = new VBox(wrapped(UnitUtil.productName(item.getProduct()), "memo-strong"));
        String rate = quantity(item) + " × " + MoneyUtil.format(item.getUnitPrice())
                + (item.getDiscountPerUnit().signum() > 0 ? " − " + MoneyUtil.format(item.getDiscountPerUnit()) : "");
        box.getChildren().add(spread(rate, MoneyUtil.format(item.getTotalPrice())));
        String returned = returned(item);
        if (returned != null) {
            box.getChildren().add(wrapped(returned, "memo-small"));
        }
        box.getStyleClass().add("memo-item-row");
        row(page, box);
    }

    private static void totals(VBox page, BusinessTransaction sale, MemoPaper paper) {
        sum(page, paper, I18n.get("memo.subtotal"), sale.getSubtotal(), null);
        if (sale.getTaxAmount().signum() > 0) {
            sum(page, paper, I18n.get("memo.vat"), sale.getTaxAmount(), null);
        }
        if (sale.getDiscount().signum() > 0) {
            sum(page, paper, I18n.get("memo.bill_discount"), sale.getDiscount(), null);
        }
        sum(page, paper, I18n.get("sale.grand_total"), sale.getGrandTotal(), "memo-grand");
        if (sale.getReturnedAmount().signum() > 0) {
            sum(page, paper, I18n.get("memo.returned"), sale.getReturnedAmount(), null);
        }
        sum(page, paper, I18n.get("memo.paid"), sale.getPaidAmount(), null);
        sum(page, paper, I18n.get("sale.due"), sale.getDueAmount(), "memo-strong");
    }

    /** Each account the money went into; the date too when it came later (an installment). */
    private static void payments(VBox page, Memo memo, MemoPaper paper) {
        if (memo.details().payments().isEmpty()) {
            return;
        }
        row(page, wrapped(I18n.get("memo.payments"), "memo-strong"));
        BusinessTransaction sale = memo.details().sale();
        for (TransactionPayment p : memo.details().payments()) {
            String account = p.getAccount() == null ? "—" : I18n.pick(p.getAccount().getName(), p.getAccount().getNameEn());
            if (!p.getDate().equals(sale.getDate())) {
                account += " (" + DateUtil.format(p.getDate()) + ")";
            }
            sum(page, paper, account, p.getAmount(), null);
        }
    }

    /** The party's balance before this sale and now: বাকি when they owe us, জমা when they paid ahead. */
    private static void balances(VBox page, Memo memo, MemoPaper paper) {
        if (memo.before() == null) {
            return;
        }
        row(page, line());
        sum(page, paper, I18n.get(memo.before().signum() < 0 ? "memo.before_advance" : "memo.before_due"),
                memo.before().abs(), null);
        sum(page, paper, I18n.get(memo.now().signum() < 0 ? "memo.now_advance" : "memo.now_due"),
                memo.now().abs(), "memo-strong");
    }

    private static HBox signatures() {
        HBox row = new HBox(sign(I18n.get("memo.sign.customer")), grow(), sign(I18n.get("memo.sign.seller")));
        row.getStyleClass().add("memo-signs");
        return row;
    }

    private static VBox sign(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("memo-small");
        VBox box = new VBox(label);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("memo-sign");
        return box;
    }

    // ---------------------------------------------------------------- helpers

    /** A money row: label left, amount right; on A4 / A5 it sits in a column on the right. */
    private static void sum(VBox page, MemoPaper paper, String label, BigDecimal amount, String style) {
        HBox row = spread(label, MoneyUtil.format(amount));
        if (style != null) {
            row.getStyleClass().add(style);
        }
        if (paper.isRoll()) {
            row(page, row);
            return;
        }
        row.setMinWidth(TOTALS_WIDTH);
        row.setMaxWidth(TOTALS_WIDTH);
        HBox right = new HBox(row);
        right.setAlignment(Pos.CENTER_RIGHT);
        row(page, right);
    }

    /** Text on the left and on the right of one row. */
    private static HBox spread(String left, String right) {
        Label l = wrapped(left, null);
        HBox.setHgrow(l, Priority.ALWAYS);
        l.setMaxWidth(Double.MAX_VALUE);
        Label r = new Label(right);
        r.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(8, l, r);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static Label cell(String text, double width, boolean right) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinWidth(width);
        label.setPrefWidth(width);
        label.setMaxWidth(width);
        label.setAlignment(right ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        label.setTextAlignment(right ? TextAlignment.RIGHT : TextAlignment.LEFT);
        return label;
    }

    private static Label wrapped(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        if (style != null) {
            label.getStyleClass().add(style);
        }
        return label;
    }

    private static Region line() {
        Region line = new Region();
        line.getStyleClass().add("memo-line");
        return line;
    }

    private static Region grow() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Adds a part to the page; the printer may start a new sheet under it. */
    private static void row(VBox page, Node node) {
        node.getStyleClass().add(NodePrinter.ROW);
        page.getChildren().add(node);
    }

    private static String quantity(TransactionItem i) {
        return UnitUtil.number(i.getQuantity()) + " " + (i.getUnitRef() == null ? i.getUnit() : UnitUtil.name(i.getUnitRef()));
    }

    /** "ফেরত এসেছে: ২ পিস", or null when nothing came back. */
    private static String returned(TransactionItem i) {
        if (i.getReturnedQuantity().signum() == 0) {
            return null;
        }
        return I18n.get("memo.returned_qty", UnitUtil.number(i.getReturnedQuantity()) + " "
                + (i.getUnitRef() == null ? i.getUnit() : UnitUtil.name(i.getUnitRef())));
    }
}
