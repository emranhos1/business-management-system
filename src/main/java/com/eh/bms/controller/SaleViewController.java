package com.eh.bms.controller;

import com.eh.bms.model.BusinessTransaction;
import com.eh.bms.model.TransactionItem;
import com.eh.bms.model.TransactionPayment;
import com.eh.bms.service.SaleService;
import com.eh.bms.service.SaleService.SaleDetails;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.UnitUtil;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;

/** One sale: totals, customer, lines (with what came back) and the money received. */
public class SaleViewController {

    private static final Logger LOG = Logger.getLogger(SaleViewController.class.getName());

    @FXML private Label grandLabel;
    @FXML private Label paidLabel;
    @FXML private Label dueLabel;
    @FXML private Label returnedLabel;
    @FXML private Label infoLabel;
    @FXML private Label sumsLabel;
    @FXML private TableView<TransactionItem> itemsTable;
    @FXML private TableView<TransactionPayment> paymentsTable;

    private final SaleService service = new SaleService();

    @FXML
    private void initialize() {
        TableHelper.setUp(itemsTable);
        itemsTable.getColumns().add(TableHelper.column("sale.product", i -> UnitUtil.productName(i.getProduct())));
        itemsTable.getColumns().add(TableHelper.moneyColumn("sale.quantity", SaleViewController::quantity));
        itemsTable.getColumns().add(TableHelper.moneyColumn("sale.price", i -> MoneyUtil.format(i.getUnitPrice())));
        itemsTable.getColumns().add(TableHelper.moneyColumn("sale.discount_unit",
                i -> i.getDiscountPerUnit().signum() == 0 ? "—" : MoneyUtil.format(i.getDiscountPerUnit())));
        itemsTable.getColumns().add(TableHelper.moneyColumn("sale.line_total", i -> MoneyUtil.format(i.getTotalPrice())));
        itemsTable.getColumns().add(TableHelper.moneyColumn("sale.col.returned_qty",
                i -> i.getReturnedQuantity().signum() == 0 ? "—" : UnitUtil.number(i.getReturnedQuantity())));

        TableHelper.setUp(paymentsTable);
        paymentsTable.getColumns().add(TableHelper.column("label.date", p -> DateUtil.format(p.getDate())));
        paymentsTable.getColumns().add(TableHelper.column("sale.account",
                p -> p.getAccount() == null ? "—" : I18n.pick(p.getAccount().getName(), p.getAccount().getNameEn())));
        paymentsTable.getColumns().add(TableHelper.moneyColumn("label.amount", p -> MoneyUtil.format(p.getAmount())));
        paymentsTable.getColumns().add(TableHelper.longTextColumn("label.note", p -> p.getNote() == null ? "—" : p.getNote()));
    }

    /** Loads the sale; call before showing. */
    public void show(long saleId) {
        try {
            SaleDetails d = service.details(saleId);
            BusinessTransaction s = d.sale();
            grandLabel.setText(MoneyUtil.format(s.getGrandTotal()));
            paidLabel.setText(MoneyUtil.format(s.getPaidAmount()));
            dueLabel.setText(MoneyUtil.format(s.getDueAmount()));
            if (s.getDueAmount().signum() > 0) {
                dueLabel.getStyleClass().add("money-out");
            }
            returnedLabel.setText(MoneyUtil.format(s.getReturnedAmount()));
            infoLabel.setText(I18n.get("sale.view.info", SalesListController.memoText(s), DateUtil.format(s.getDate()),
                    SalesListController.customer(s)));
            sumsLabel.setText(I18n.get("sale.view.sums", MoneyUtil.format(s.getSubtotal()),
                    MoneyUtil.format(s.getDiscount()), MoneyUtil.format(s.getTaxAmount())));
            itemsTable.getItems().setAll(d.items());
            paymentsTable.getItems().setAll(d.payments());
            TableHelper.fitColumns(itemsTable);
            TableHelper.fitColumns(paymentsTable);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load sale", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    private static String quantity(TransactionItem i) {
        return UnitUtil.number(i.getQuantity()) + " " + (i.getUnitRef() == null ? i.getUnit() : UnitUtil.name(i.getUnitRef()));
    }

    @FXML
    private void onClose() {
        ((Stage) itemsTable.getScene().getWindow()).close();
    }
}
