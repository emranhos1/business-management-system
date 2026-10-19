package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.BusinessTransaction;
import com.eh.bms.model.Product;
import com.eh.bms.repository.TransactionRepository.Filter;
import com.eh.bms.service.ProductService;
import com.eh.bms.service.PurchaseService;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.DateRangePicker;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.PartyText;
import com.eh.bms.util.SearchPicker;
import com.eh.bms.util.TableHelper;
import com.eh.bms.util.TableHelper.RowAction;
import com.eh.bms.util.UnitUtil;
import com.eh.bms.util.WindowManager;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Purchases list (12.3): newest first; search by memo number, supplier name / mobile, the supplier's memo
 * number or note, by dates and by product. Row buttons: view, pay the due, return goods (12.4).
 */
public class PurchasesListController {

    private static final Logger LOG = Logger.getLogger(PurchasesListController.class.getName());
    private static final String VIEW_FXML = "/fxml/views/purchase-view.fxml";
    private static final String PAY_FXML = "/fxml/views/purchase-pay-form.fxml";
    private static final String RETURN_FXML = "/fxml/views/purchase-return-form.fxml";
    private static final double VIEW_WIDTH = 960;
    private static final double VIEW_HEIGHT = 720;

    @FXML private TextField searchField;
    @FXML private DateRangePicker dateRange;
    @FXML private TextField productFilterField;
    @FXML private StackPane tableHolder;

    private final PurchaseService service = new PurchaseService();
    private final ProductService productService = new ProductService();
    private PaginatedTable<BusinessTransaction> table;
    private SearchPicker<Product> productPicker;

    @FXML
    private void initialize() {
        productPicker = new SearchPicker<>(productFilterField,
                text -> productService.search(text, 0, 10).items(), UnitUtil::productName);

        table = new PaginatedTable<>(this::loadPage);
        TableView<BusinessTransaction> view = table.getTableView();
        view.getColumns().add(TableHelper.column("sale.col.memo", SalesListController::memoText));
        view.getColumns().add(TableHelper.column("label.date", p -> DateUtil.format(p.getDate())));
        view.getColumns().add(TableHelper.longTextColumn("purchase.supplier", PurchasesListController::supplier));
        view.getColumns().add(TableHelper.column("purchase.supplier_memo",
                p -> p.getSupplierMemoNo() == null ? "—" : p.getSupplierMemoNo()));
        view.getColumns().add(TableHelper.moneyColumn("sale.grand_total", p -> MoneyUtil.format(p.getGrandTotal())));
        view.getColumns().add(TableHelper.moneyColumn("purchase.paid", p -> MoneyUtil.format(p.getPaidAmount())));
        view.getColumns().add(TableHelper.styledColumn("sale.due",
                p -> p.getDueAmount().signum() == 0 ? "—" : MoneyUtil.format(p.getDueAmount()),
                p -> p.getDueAmount().signum() > 0 ? "money-out" : null));
        view.getColumns().add(TableHelper.moneyColumn("sale.col.returned",
                p -> p.getReturnedAmount().signum() == 0 ? "—" : MoneyUtil.format(p.getReturnedAmount())));

        List<RowAction<BusinessTransaction>> actions = new ArrayList<>();
        actions.add(new RowAction<>(Icons.LEDGER, "button.view", "purchase.view.title.short", "icon-view", this::openView));
        if (can(Action.CREATE)) {
            actions.add(new RowAction<>(Icons.MONEY_OUT, "purchase.action.pay", "purchase.pay.title.short", "icon-out",
                    this::openPay));
        }
        if (can(Action.EDIT)) {
            actions.add(new RowAction<>(Icons.RETURN, "sale.action.return", "purchase.return.title.short", "icon-edit",
                    this::openReturn));
        }
        view.getColumns().add(actionsColumn(actions));
        TableHelper.onDoubleClick(view, true, this::openView);

        // Any filter change searches again, after a short pause while typing
        PauseTransition pause = new PauseTransition(Duration.millis(300));
        pause.setOnFinished(e -> table.reload());
        searchField.textProperty().addListener((obs, old, now) -> pause.playFromStart());
        dateRange.setOnChange(table::reload);
        productPicker.valueProperty().addListener((obs, old, now) -> table.reload());
        productFilterField.textProperty().addListener((obs, old, now) -> {
            if (now == null || now.isBlank()) {
                table.reload();
            }
        });

        tableHolder.getChildren().setAll(table);
        table.reload();
    }

    /** Empties every filter (search, dates, product) and shows all purchases again. */
    @FXML
    private void onClearFilters() {
        searchField.clear();
        productPicker.setValue(null);
        dateRange.clear();
    }

    /** Reloads from page 1 (e.g. after a new purchase). */
    public void reload() {
        table.reload();
    }

    @SuppressWarnings("unchecked")
    private static TableColumn<BusinessTransaction, BusinessTransaction> actionsColumn(
            List<RowAction<BusinessTransaction>> actions) {
        return TableHelper.actionsColumn("label.actions", actions.toArray(new RowAction[0]));
    }

    /** The supplier's name, or "নগদ কেনা" (plus the seller's name kept in the note). */
    static String supplier(BusinessTransaction p) {
        if (p.getParty() != null) {
            return PartyText.name(p.getParty());
        }
        return I18n.get("purchase.cash.short") + (p.getNote() == null ? "" : " — " + p.getNote());
    }

    private PageResult<BusinessTransaction> loadPage(int offset, int limit) {
        try {
            Product product = productPicker.getValue();
            // Bangla digits typed (memo no., mobile) search as 0-9, the way they are saved
            Filter filter = new Filter(InputFilters.toAsciiDigits(searchField.getText()), dateRange.getFrom(), dateRange.getTo(),
                    product == null ? null : product.getId());
            return service.page(filter, offset, limit);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load purchases", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
    }

    private void openView(BusinessTransaction purchase) {
        FXMLLoader loader = WindowManager.loader(VIEW_FXML);
        Parent root = WindowManager.load(loader, VIEW_FXML);
        PurchaseViewController controller = loader.getController();
        controller.show(purchase.getId());
        Stage stage = WindowManager.dialog(root, I18n.get("purchase.view.title", SalesListController.memoText(purchase)));
        // A list window: a fixed size (capped to the screen), not a form that fits its content
        WindowManager.sizeListWindow(stage, VIEW_WIDTH, VIEW_HEIGHT);
        stage.showAndWait();
    }

    private void openPay(BusinessTransaction purchase) {
        if (purchase.getDueAmount().signum() <= 0) {
            Notifier.error(I18n.get("purchase.error.no_due"));
            return;
        }
        FXMLLoader loader = WindowManager.loader(PAY_FXML);
        Parent root = WindowManager.load(loader, PAY_FXML);
        PurchasePayFormController form = loader.getController();
        if (!form.setUp(purchase)) {
            return;
        }
        WindowManager.dialog(root, I18n.get("purchase.pay.title", SalesListController.memoText(purchase))).showAndWait();
        if (form.isSaved()) {
            table.refresh();
        }
    }

    private void openReturn(BusinessTransaction purchase) {
        FXMLLoader loader = WindowManager.loader(RETURN_FXML);
        Parent root = WindowManager.load(loader, RETURN_FXML);
        PurchaseReturnFormController form = loader.getController();
        if (!form.setUp(purchase.getId())) {
            return;
        }
        WindowManager.dialog(root, I18n.get("purchase.return.title", SalesListController.memoText(purchase))).showAndWait();
        if (form.isSaved()) {
            table.refresh();
        }
    }

    private static boolean can(Action action) {
        return UserSession.isLoggedIn() && UserSession.get().can(AppModule.PURCHASE, action);
    }
}
