package com.eh.bms.model;

/**
 * What made an account ledger entry. Stored as VARCHAR in {@code account_transactions.source}.
 * Later stages add their own (sale, purchase, expense, salary, party payment).
 */
public enum AccountTransactionSource {
    /** Money moved between two accounts; {@code reference_id} is the other side's entry. */
    TRANSFER
}
