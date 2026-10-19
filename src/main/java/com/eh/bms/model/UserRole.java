package com.eh.bms.model;

/**
 * Stored as VARCHAR in {@code users.role} and {@code permissions.role}.
 * {@link #SUPER_ADMIN} is the support team's own hidden login (see {@code SupportAccount}): it can do everything an
 * admin can, never shows in the users list and cannot be edited, switched off or deleted from the app.
 */
public enum UserRole {
    ADMIN,
    MANAGER,
    CASHIER,
    SUPER_ADMIN
}
