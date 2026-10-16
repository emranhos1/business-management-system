package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.BusinessTransaction;
import com.eh.bms.model.Product;
import com.eh.bms.repository.SaleRepository.Filter;
import com.eh.bms.service.ProductService;
import com.eh.bms.service.SaleService;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.DateRangePicker;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Icons;
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
 * Sales list (11.6): newest first; search by memo number (11.8), customer name / mobile or note, by dates
 * and by product. Row buttons: view the sale, take an installment (11.5), return goods (11.7).
 */
public class SalesListController {

    private static final Logger LOG = Logger.getLogger(SalesListController.class.getName());
    private static final String VIEW_FXML = "/fxml/views/sale-view.fxml";
    private static final String COLLECT_FXML = "/fxml/views/sale-collect-form.fxml";
    private static final String RETURN_FXML = "/fxml/views/sale-return-form.fxml";
    private static final double VIEW_WIDTH = 900;
    private static final double VIEW_HEIGHT = 640;

    @FXML private TextField searchField;
    @FXML private DateRangePicker dateRange;
    @FXML private TextField productFilterField;
    @FXML private StackPane tableHolder;

    private final SaleService service = new SaleService();
    private final ProductService productService = new ProductService();
    private PaginatedTable<BusinessTransaction> table;
    private SearchPicker<Product> productPicker;

    @FXML
    private void initialize() {
        productPicker = new SearchPicker<>(productFilterField,
                text -> productService.search(text, 0, 10).items(), UnitUtil::productName);

        table = new PaginatedTable<>(this::loadPage);
        TableView<BusinessTransaction> view = table.getTableView();
        view.getColumns().add(TableHelper.column("sale.col.memo", s -> memoText(s)));
        view.getColumns().add(TableHelper.column("label.date", s -> DateUtil.format(s.getDate())));
        view.getColumns().add(TableHelper.longTextColumn("sale.customer", SalesListController::customer));
        view.getColumns().add(TableHelper.moneyColumn("sale.grand_total", s -> MoneyUtil.format(s.getGrandTotal())));
        view.getColumns().add(TableHelper.moneyColumn("sale.paid", s -> MoneyUtil.format(s.getPaidAmount())));
        view.getColumns().add(TableHelper.styledColumn("sale.due",
                s -> s.getDueAmount().signum() == 0 ? "—" : MoneyUtil.format(s.getDueAmount()),
                s -> s.getDueAmount().signum() > 0 ? "money-out" : null));
        view.getColumns().add(TableHelper.moneyColumn("sale.col.returned",
                s -> s.getReturnedAmount().signum() == 0 ? "—" : MoneyUtil.format(s.getReturnedAmount())));

        List<RowAction<BusinessTransaction>> actions = new ArrayList<>();
        actions.add(new RowAction<>(Icons.LEDGER, "button.view", "sale.view.title.short", "icon-view", this::openView));
        if (can(Action.CREATE)) {
            actions.add(new RowAction<>(Icons.MONEY_IN, "sale.action.collect", "sale.collect.title.short", "icon-in",
                    this::openCollect));
        }
        if (can(Action.EDIT)) {
            actions.add(new RowAction<>(Icons.RETURN, "sale.action.return", "sale.return.title.short", "icon-edit",
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

    /** Empties every filter (search, dates, product) and shows all sales again. */
    @FXML
    private void onClearFilters() {
        searchField.clear();
        productPicker.setValue(null);
        dateRange.clear();
    }

    /** Reloads from page 1 (e.g. after a new sale). */
    public void reload() {
        table.reload();
    }

    @SuppressWarnings("unchecked")
    private static TableColumn<BusinessTransaction, BusinessTransaction> actionsColumn(
            List<RowAction<BusinessTransaction>> actions) {
        return TableHelper.actionsColumn("label.actions", actions.toArray(new RowAction[0]));
    }

    static String memoText(BusinessTransaction s) {
        return s.getMemoNo() == null ? "—" : "#" + I18n.digits(String.valueOf(s.getMemoNo()));
    }

    /** The party's name, or "নগদ ক্রেতা" (plus the walk-in name kept in the note). */
    static String customer(BusinessTransaction s) {
        if (s.getParty() != null) {
            return PartyText.name(s.getParty());
        }
        return I18n.get("sale.walk_in.short") + (s.getNote() == null ? "" : " — " + s.getNote());
    }

    private PageResult<BusinessTransaction> loadPage(int offset, int limit) {
        try {
            Product product = productPicker.getValue();
            Filter filter = new Filter(searchField.getText(), dateRange.getFrom(), dateRange.getTo(),
                    product == null ? null : product.getId());
            return service.page(filter, offset, limit);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load sales", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
    }

    private void openView(BusinessTransaction sale) {
        FXMLLoader loader = WindowManager.loader(VIEW_FXML);
        Parent root = WindowManager.load(loader, VIEW_FXML);
        SaleViewController controller = loader.getController();
        controller.show(sale.getId());
        Stage stage = WindowManager.dialog(root, I18n.get("sale.view.title", memoText(sale)));
        // A list window: resizable and a fixed starting size, not a form that fits its content
        stage.getProperties().remove(WindowManager.AUTO_SIZE);
        stage.setResizable(true);
        stage.setWidth(VIEW_WIDTH);
        stage.setHeight(VIEW_HEIGHT);
        stage.showAndWait();
    }

    private void openCollect(BusinessTransaction sale) {
        if (sale.getDueAmount().signum() <= 0) {
            Notifier.error(I18n.get("sale.error.no_due"));
            return;
        }
        FXMLLoader loader = WindowManager.loader(COLLECT_FXML);
        Parent root = WindowManager.load(loader, COLLECT_FXML);
        SaleCollectFormController form = loader.getController();
        if (!form.setUp(sale)) {
            return;
        }
        WindowManager.dialog(root, I18n.get("sale.collect.title", memoText(sale))).showAndWait();
        if (form.isSaved()) {
            table.refresh();
        }
    }

    private void openReturn(BusinessTransaction sale) {
        if (sale.getReturnedAmount().compareTo(sale.getGrandTotal()) >= 0) {
            Notifier.error(I18n.get("sale.error.all_returned"));
            return;
        }
        FXMLLoader loader = WindowManager.loader(RETURN_FXML);
        Parent root = WindowManager.load(loader, RETURN_FXML);
        SaleReturnFormController form = loader.getController();
        if (!form.setUp(sale.getId())) {
            return;
        }
        WindowManager.dialog(root, I18n.get("sale.return.title", memoText(sale))).showAndWait();
        if (form.isSaved()) {
            table.refresh();
        }
    }

    private static boolean can(Action action) {
        return UserSession.isLoggedIn() && UserSession.get().can(AppModule.SALE, action);
    }
}
