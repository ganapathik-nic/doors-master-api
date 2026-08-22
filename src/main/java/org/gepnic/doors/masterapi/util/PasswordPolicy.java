package org.gepnic.doors.masterapi.util;

public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static String violation(String password) {
        if (password == null || password.length() < 12) return "Password must contain at least 12 characters";
        if (password.length() > 128) return "Password must not exceed 128 characters";
        if (!password.matches(".*[A-Z].*")) return "Password must contain an uppercase letter";
        if (!password.matches(".*[a-z].*")) return "Password must contain a lowercase letter";
        if (!password.matches(".*\\d.*")) return "Password must contain a number";
        if (!password.matches(".*[^A-Za-z0-9\\s].*")) return "Password must contain a special character";
        if (password.matches(".*\\s.*")) return "Password must not contain whitespace";
        return null;
    }
}
