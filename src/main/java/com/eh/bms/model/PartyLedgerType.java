package com.eh.bms.model;

/** Kind of party ledger entry. Stored as VARCHAR in {@code party_ledger.type}. */
public enum PartyLedgerType {
    SALE,
    PURCHASE,
    PAYMENT,
    RECEIPT,
    RETURN,
    /** The rest of a balance forgiven (a customer who left, a supplier who forgave): brings it to 0. */
    WRITE_OFF
}
