package com.eh.bms.model;

/**
 * Stored as VARCHAR in {@code accounts.status}. An inactive account (balance 0) is hidden from the pick lists;
 * its ledger stays and it can be switched on again.
 */
public enum AccountStatus {
    ACTIVE,
    INACTIVE
}
