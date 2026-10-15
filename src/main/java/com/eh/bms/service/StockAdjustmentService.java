package com.eh.bms.service;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Product;
import com.eh.bms.model.StockAdjustmentReason;
import com.eh.bms.model.StockLedgerType;
import com.eh.bms.util.TransactionHelper;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Stock adjustment (9.5): one ADJUSTMENT row in the stock ledger with a reason (damage / loss / correction),
 * valued at the product's current average cost. Damage and loss always take stock out; a correction
 * may add or take out. Stock never goes below zero (the ledger refuses and nothing is written).
 * The ledger row and its audit row are written in one transaction.
 */
public class StockAdjustmentService {

    private final StockLedgerService ledger = new StockLedgerService();
    private final AuditService auditService = new AuditService();

    /**
     * @param signedQuantity + adds stock, - takes it out; never 0. Negative only for DAMAGE and LOSS.
     * @return DONE with the new ledger entry, or INSUFFICIENT_STOCK with the stock available (nothing saved)
     */
    public StockLedgerService.Result adjust(long productId, LocalDate date, StockAdjustmentReason reason,
                                            BigDecimal signedQuantity, String note) {
        if (reason == null) {
            throw new IllegalArgumentException("Reason is required");
        }
        if (date == null) {
            throw new IllegalArgumentException("Date is required");
        }
        if (signedQuantity == null || signedQuantity.signum() == 0) {
            throw new IllegalArgumentException("Quantity must not be 0");
        }
        if (reason != StockAdjustmentReason.CORRECTION && signedQuantity.signum() > 0) {
            throw new IllegalArgumentException(reason + " can only take stock out");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();

        return TransactionHelper.inTransaction(session -> {
            StockLedgerService.Result result = ledger.post(session, productId, date, StockLedgerType.ADJUSTMENT,
                    signedQuantity, null, reason, null, cleanNote);
            if (result.status() != StockLedgerService.Status.DONE) {
                return result;
            }
            Product product = session.find(Product.class, productId);
            auditService.created(session, AppModule.STOCK, result.entry().getId(),
                    "adjustment product=" + (product == null ? productId : product.getNameEn())
                            + " reason=" + reason
                            + " quantity=" + signedQuantity.stripTrailingZeros().toPlainString()
                            + " date=" + date
                            + (cleanNote == null ? "" : " note=" + cleanNote));
            return result;
        });
    }
}
