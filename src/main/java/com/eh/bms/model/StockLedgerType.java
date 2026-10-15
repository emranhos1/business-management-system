package com.eh.bms.model;

/** Why stock moved. Stored as VARCHAR in {@code stock_ledger.type}. */
public enum StockLedgerType {
    PURCHASE,
    SALE,
    /** Kept from the first version; new code uses SALE_RETURN / PURCHASE_RETURN. */
    RETURN,
    /** Damage, loss or correction; {@code reason} says which. In or out. */
    ADJUSTMENT,
    /** Stock the product had before the app was used. */
    OPENING,
    /** Goods a customer brought back (stock in). */
    SALE_RETURN,
    /** Goods sent back to a supplier (stock out). */
    PURCHASE_RETURN
}
