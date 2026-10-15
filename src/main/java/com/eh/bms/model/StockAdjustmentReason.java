package com.eh.bms.model;

/** Why stock was adjusted by hand (9.5). Stored as VARCHAR in {@code stock_ledger.reason}. */
public enum StockAdjustmentReason {
    DAMAGE,
    LOSS,
    CORRECTION
}
