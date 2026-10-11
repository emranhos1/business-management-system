package com.eh.bms.model;

/** Stored as VARCHAR in {@code transactions.status}. */
public enum TransactionStatus {
    DRAFT,
    CONFIRMED,
    CANCELLED
}
