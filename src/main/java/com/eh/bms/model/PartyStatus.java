package com.eh.bms.model;

/** Stored as VARCHAR in {@code parties.status}. An inactive party is hidden from lists and pickers; its ledger stays. */
public enum PartyStatus {
    ACTIVE,
    INACTIVE
}
