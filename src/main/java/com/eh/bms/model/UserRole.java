package com.eh.bms.model;

/**
 * A user's role (পদ), stored as VARCHAR in {@code users.role} and {@code permissions.role}. What each role may do is
 * set in the permission editor; the admin may do everything.
 * {@link #SUPER_ADMIN} is the support team's own hidden login (see {@code SupportAccount}): it can do everything an
 * admin can, never shows in the users list and cannot be edited, switched off or deleted from the app.
 */
public enum UserRole {
    ADMIN,
    MANAGER,
    CASHIER,
    /** Any other staff (N12); what they may do is set in the permission editor (nothing at first). */
    EMPLOYEE,
    SUPER_ADMIN
}
