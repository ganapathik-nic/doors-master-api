package org.gepnic.doors.masterapi.util;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class SqlSecurityValidator {

    // Regex to match DDL and DML keywords as whole words
    // Covers: INSERT, UPDATE, DELETE, DROP, ALTER, TRUNCATE, CREATE, GRANT, REVOKE
    private static final String FORBIDDEN_PATTERN = 
        "(?i)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|GRANT|REVOKE)\\b";

    private static final Pattern PATTERN = Pattern.compile(FORBIDDEN_PATTERN);

    /**
     * Checks if the SQL contains any DDL or DML keywords.
     * @param sql The SQL string to validate
     * @return true if the SQL is safe (SELECT only), false if it contains forbidden keywords.
     */
    public static boolean isSafeSelectOnly(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return true;
        }
        
        // Remove comments to prevent bypasses like: /* DROP */ SELECT ...
        String cleanSql = sql.replaceAll("(?s)/\\*.*?\\*/|--.*", "");
        
        Matcher matcher = PATTERN.matcher(cleanSql);
        return !matcher.find();
    }
}