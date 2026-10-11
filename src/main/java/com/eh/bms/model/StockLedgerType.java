package com.eh.bms.model;

/** Why stock moved. Stored as VARCHAR in {@code stock_ledger.type}. */
public enum StockLedgerType {
    PURCHASE,
    SALE,
    RETURN,
    ADJUSTMENT
}
