package com.eh.bms.controller;

import com.eh.bms.model.Party;
import com.eh.bms.model.PartyLedgerType;
import com.eh.bms.service.PartyLedgerService;
import com.eh.bms.service.PartyLedgerService.LedgerRow;
import com.eh.bms.service.PartyLedgerService.Summary;
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
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One party's ledger (10.3) in plain words: columns দিলাম (what we gave: goods sold, money paid) and
 * পেলাম (what we got: goods bought, money received), and after each line what is due as "৳X পাবো" (green)
 * or "৳X দেবো" (red). No debit, credit or minus sign. Newest entries first.
 */
public class PartyLedgerController {

    private static final Logger LOG = Logger.getLogger(PartyLedgerController.class.getName());

    @FXML private Label balanceLabel;
    @FXML private Label openingLabel;
    @FXML private javafx.scene.layout.VBox openingTile;
    @FXML private Label totalGaveLabel;
    @FXML private Label totalGotLabel;
    @FXML private StackPane tableHolder;

    private final PartyLedgerService service = new PartyLedgerService();
    private PaginatedTable<LedgerRow> table;
    private long partyId;

    @FXML
    private void initialize() {
        table = new PaginatedTable<>(this::loadPage);
        TableView<LedgerRow> view = table.getTableView();
        view.getColumns().add(TableHelper.column("label.date", r -> DateUtil.format(r.date())));
        view.getColumns().add(TableHelper.longTextColumn("ledger.col.details", PartyLedgerController::details));
        view.getColumns().add(TableHelper.moneyColumn("party.ledger.col.gave", r -> MoneyUtil.format(r.gave())));
        view.getColumns().add(TableHelper.moneyColumn("party.ledger.col.got", r -> MoneyUtil.format(r.got())));
        view.getColumns().add(TableHelper.styledColumn("party.ledger.col.balance",
                r -> PartyText.balance(r.balance()), r -> PartyText.balanceStyle(r.balance())));
        tableHolder.getChildren().setAll(table);
    }

    /** Loads the party's figures and first page; call before showing. */
    public void show(Party party) {
        partyId = party.getId();
        try {
            Summary s = service.summary(partyId);
            setBalance(balanceLabel, s.balance());
            setBalance(openingLabel, s.opening());
            // Only when there was a due before the app; "no due" here read like it contradicts the balance
            boolean hasOpening = s.opening() != null && s.opening().signum() != 0;
            openingTile.setVisible(hasOpening);
            openingTile.setManaged(hasOpening);
            totalGaveLabel.setText(MoneyUtil.format(s.totalGave()));
            totalGotLabel.setText(MoneyUtil.format(s.totalGot()));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load party ledger summary", e);
            Notifier.error(I18n.get("msg.error"));
        }
        table.reload();
    }

    private static void setBalance(Label label, BigDecimal balance) {
        label.setText(PartyText.balance(balance));
        String style = PartyText.balanceStyle(balance);
        if (style != null) {
            label.getStyleClass().add(style);
        }
    }

    private PageResult<LedgerRow> loadPage(int offset, int limit) {
        if (partyId == 0) {
            return PageResult.empty();
        }
        try {
            return service.page(partyId, offset, limit);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load party ledger", e);
            Notifier.error(I18n.get("msg.error"));
            return PageResult.empty();
        }
    }

    /** What the line is (e.g. "টাকা পেলাম (ক্যাশ)"), plus the user's note. */
    static String details(LedgerRow r) {
        // Goods sent back to a supplier read differently from goods a customer brought back
        String what = r.purchase() && r.type() == PartyLedgerType.RETURN ? I18n.get("party.ledger.type.RETURN_OUT")
                : I18n.get("party.ledger.type." + r.type().name());
        if (r.account() != null) {
            what = I18n.get("party.ledger.via", what, I18n.pick(r.account().getName(), r.account().getNameEn()));
        }
        String note = r.note();
        // A purchase's memo no. ("#3") must not look like a sale's: "ক্রয় মেমো #3"
        if (r.purchase() && note != null && note.startsWith("#")) {
            note = I18n.get("ledger.purchase_memo_note", note);
        }
        return note == null ? what : what + " — " + note;
    }

    @FXML
    private void onClose() {
        ((Stage) tableHolder.getScene().getWindow()).close();
    }
}
