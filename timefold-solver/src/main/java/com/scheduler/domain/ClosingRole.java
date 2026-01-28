package com.scheduler.domain;

/**
 * Enum representing the closing roles that can be assigned to staff.
 * These are additional responsibilities on top of regular consultation work.
 */
public enum ClosingRole {
    ROLE_1R("1R"),
    ROLE_2F("2F"),
    ROLE_3F("3F");

    private final String displayName;

    ClosingRole(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
