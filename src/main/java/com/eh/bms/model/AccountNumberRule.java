package com.eh.bms.model;

/** What account number an account type needs. Stored as VARCHAR in {@code account_types.number_rule}. */
public enum AccountNumberRule {
    /** No number (e.g. cash). */
    NONE,
    /** Bank account number: digits only, up to 20. */
    BANK,
    /** Mobile number: exactly 11 digits (bKash, Nagad, Rocket, ...). */
    MOBILE
}
