package com.eh.bms.model;

/** Stored as VARCHAR in {@code account_transactions.type}. */
public enum AccountTransactionType {
    /** Money goes out of the account. */
    DEBIT,
    /** Money comes into the account. */
    CREDIT
}
