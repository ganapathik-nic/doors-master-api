package org.gepnic.doors.masterapi.util;

import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;

public class SqlSecurityValidator {

    // Regex to match DDL and DML keywords as whole words
    // Covers: INSERT, UPDATE, DELETE, DROP, ALTER, TRUNCATE, CREATE, GRANT, REVOKE
    private static final String FORBIDDEN_PATTERN = 
        "(?i)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|GRANT|REVOKE|COPY|CALL|DO|EXECUTE|INTO|LOCK)\\b"
        + "|\\b(pg_sleep|pg_read_file|pg_read_binary_file|pg_ls_dir|pg_write_file|pg_terminate_backend|"
        + "pg_cancel_backend|pg_advisory_lock|set_config|lo_import|lo_export|dblink|nextval|setval)\\s*\\(";

    private static final Pattern PATTERN = Pattern.compile(FORBIDDEN_PATTERN);

    /**
     * Checks if the SQL contains any DDL or DML keywords.
     * @param sql The SQL string to validate
     * @return true if the SQL is safe (SELECT only), false if it contains forbidden keywords.
     */
    public static boolean isSafeSelectOnly(String sql) {
        if (sql == null || sql.isBlank() || sql.length() > 100_000 || PATTERN.matcher(sql).find()) return false;
        // Parse the original text: stripping comments can change statement boundaries.
        // Database read-only grants are still required, including function privileges.
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql, parser -> parser.withTimeOut(1000));
            return statements.getStatements().size() == 1
                    && statements.getStatements().getFirst() instanceof Select;
        } catch (Exception exception) {
            return false;
        }
    }
}
