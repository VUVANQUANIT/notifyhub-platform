package com.vuvanquan.notifyhub.auth.domain;

import java.util.Locale;

public final class IdentityRules {
    private IdentityRules() {}
    public static String slug(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("Tenant slug requires 3..63 lowercase letters, digits or hyphens");
        }
        return normalized;
    }
    public static String email(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("A valid email is required");
        }
        return normalized;
    }
    public static String password(String value) {
        if (value == null || value.codePointCount(0, value.length()) < 15 || value.length() > 128) {
            throw new IllegalArgumentException("Password requires 15..128 characters");
        }
        return value;
    }
    public static String name(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 100) throw new IllegalArgumentException("Name requires 1..100 characters");
        return value.strip();
    }
    public static void keepAdmin(Role oldRole, boolean oldEnabled, Role newRole, boolean newEnabled, long admins) {
        if (oldRole == Role.ADMIN && oldEnabled && (newRole != Role.ADMIN || !newEnabled) && admins <= 1) {
            throw new IllegalStateException("The last enabled administrator must be retained");
        }
    }
}
