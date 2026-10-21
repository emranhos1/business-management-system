package com.eh.bms.model;

/**
 * App sections that have permissions. Stored as VARCHAR in {@code permissions.module}. Salary is part of the users
 * page (N12, admin only); the old EMPLOYEE rows are removed at startup ({@code LegacySchema}).
 */
public enum AppModule {
    DASHBOARD,
    PRODUCT,
    STOCK,
    PARTY,
    SALE,
    PURCHASE,
    EXPENSE,
    ACCOUNT,
    REPORT,
    SETTINGS,
    USER
}
