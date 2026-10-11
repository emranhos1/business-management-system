package com.eh.bms.model;

/** Direction of a party's opening balance. Stored as VARCHAR in {@code parties.balance_type}. */
public enum BalanceType {
    /** Party owes us. */
    RECEIVABLE,
    /** We owe the party. */
    PAYABLE
}
