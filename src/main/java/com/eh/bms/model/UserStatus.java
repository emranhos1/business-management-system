package com.eh.bms.model;

/** Stored as VARCHAR in {@code users.status}. */
public enum UserStatus {
    ACTIVE,
    INACTIVE,
    /** Registered from the login screen (N1); cannot log in until an admin approves it and picks its role. */
    PENDING
}
