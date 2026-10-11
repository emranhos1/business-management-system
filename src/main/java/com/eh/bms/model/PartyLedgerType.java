package com.eh.bms.model;

/** Kind of party ledger entry. Stored as VARCHAR in {@code party_ledger.type}. */
public enum PartyLedgerType {
    SALE,
    PURCHASE,
    PAYMENT,
    RECEIPT,
    RETURN
}
