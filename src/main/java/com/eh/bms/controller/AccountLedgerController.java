package com.eh.bms.controller;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountTransactionSource;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.service.AccountLedgerService;
import com.eh.bms.service.AccountLedgerService.LedgerRow;
import com.eh.bms.service.AccountLedgerService.Summary;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.PaginatedTable;
import com.eh.bms.util.PartyText;
import com.eh.bms.util.TableHelper;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One account's ledger (7.2) in plain words: money in / money out, and the balance after each line.
 * Newest entries first. The figures on top show the current balance (7.3) and the totals in and out.
 */
public class AccountLedgerController {

    private static final Logger LOG = Logger.getLogger(AccountLedgerController.class.getName());

    @FXML private Label totalInLabel;
    @FXML private Label totalOutLabel;
    @FXML private Label balanceLabel;
    @FXML private StackPane tableHolder;

    private final AccountLedgerService service = new AccountLedgerService();
    private PaginatedTable<LedgerRow> table;
    private long accountId;

    @FXML
    private void initialize() {
        table = new PaginatedTable<>(this::loadPage);
        TableView<LedgerRow> view = table.getTableView();
        view.getColumns().add(TableHelper.column("label.date", r -> DateUtil.format(r.date())));
        TableColumn<LedgerRow, String> details = TableHelper.longTextColumn("ledger.col.details", AccountLedgerController::details);
        view.getColumns().add(details);
        view.getColumns().add(styled(TableHelper.moneyColumn("ledger.col.in", r -> MoneyUtil.format(r.moneyIn())), "money-in"));
        view.getColumns().add(styled(TableHelper.moneyColumn("ledger.col.out", r -> MoneyUtil.format(r.moneyOut())), "money-out"));
        view.getColumns().add(TableHelper.moneyColumn("ledger.col.balance", r -> MoneyUtil.format(r.balance())));
        tableHolder.getChildren().setAll(table);
    }

    /** Loads the account's figures and first page; call before showing. */
    public void show(Account account) {
        accountId = account.getId();
        try {
            Summary s = service.summary(accountId);
            totalInLabel.setText(MoneyUtil.format(s.totalIn()));
            totalOutLabel.setText(MoneyUtil.format(s.totalOut()));
            balanceLabel.setText(MoneyUtil.format(s.balance()));
            if (s.balance().signum() < 0) {
                balanceLabel.getStyleClass().add("money-out");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load ledger summary", e);
            Notifier.error(I18n.get("msg.error"));
        }
        table.reload();
    }

    private PageResult<LedgerRow> loadPage(int offset, int limit) {
        if (accountId == 0) {
            return PageResult.empty();
        }
        try {
            return service.page(accountId, offset, limit);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load ledger", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
    }

    /** What the line is, in the current language, plus the user's note. */
    static String details(LedgerRow r) {
        String what = null;
        if (r.source() == AccountTransactionSource.TRANSFER && r.otherAccount() != null) {
            String other = I18n.pick(r.otherAccount().getName(), r.otherAccount().getNameEn());
            what = I18n.get(r.type() == AccountTransactionType.DEBIT ? "ledger.transfer_to" : "ledger.transfer_from", other);
        } else if (r.source() == AccountTransactionSource.SALE) {
            what = I18n.get(r.type() == AccountTransactionType.DEBIT ? "ledger.sale_refund" : "ledger.sale_in");
        } else if (r.source() == AccountTransactionSource.PARTY && r.party() != null) {
            what = I18n.get(r.type() == AccountTransactionType.DEBIT ? "ledger.party_paid" : "ledger.party_received",
                    PartyText.name(r.party()));
        }
        String note = r.note();
        if (what == null) {
            return note == null ? "—" : note;
        }
        return note == null ? what : what + " — " + note;
    }

    private static <T> TableColumn<T, String> styled(TableColumn<T, String> column, String styleClass) {
        column.getStyleClass().add(styleClass);
        return column;
    }

    @FXML
    private void onClose() {
        ((Stage) tableHolder.getScene().getWindow()).close();
    }
}
