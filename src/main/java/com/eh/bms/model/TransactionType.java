package com.eh.bms.model;

/** Stored as VARCHAR in {@code transactions.type}. */
public enum TransactionType {
    SALE,
    PURCHASE,
    EXPENSE,
    SALARY,
    TRANSFER
}
