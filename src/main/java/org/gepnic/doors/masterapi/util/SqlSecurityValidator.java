package org.gepnic.doors.masterapi.util;

import java.util.regex.Pattern;
import java.util.List;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesisFromItem;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.SubJoin;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

public class SqlSecurityValidator {

    // Regex to match DDL and DML keywords as whole words
    // Covers: INSERT, UPDATE, DELETE, DROP, ALTER, TRUNCATE, CREATE, GRANT, REVOKE
    private static final String FORBIDDEN_PATTERN = 
        "(?i)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|GRANT|REVOKE|COPY|CALL|DO|EXECUTE|INTO|LOCK)\\b"
        + "|\\b(pg_sleep|pg_read_file|pg_read_binary_file|pg_ls_dir|pg_write_file|pg_terminate_backend|"
        + "pg_cancel_backend|pg_advisory_lock|set_config|lo_import|lo_export|dblink|nextval|setval)\\s*\\(";

    private static final Pattern PATTERN = Pattern.compile(FORBIDDEN_PATTERN);
    private static final Pattern PLAIN_TEXT_LABEL = Pattern.compile("\\p{L}[\\p{L}\\p{M}]*(?: +\\p{L}[\\p{L}\\p{M}]*)*");

    /**
     * Accepts one read-only SELECT where each output field depends on table data
     * or is a plain alphabetic label.
     * @param sql The SQL string to validate
     * @return true for a source-backed SELECT without forbidden operations.
     */
    public static boolean isSafeSelectOnly(String sql) {
        if (sql == null || sql.isBlank() || sql.length() > 100_000) return false;
        // Parse the original text: stripping comments can change statement boundaries.
        // Database read-only grants are still required, including function privileges.
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql, parser -> parser.withTimeOut(1000));
            if (statements.getStatements().size() != 1
                    || !(statements.getStatements().getFirst() instanceof Select select)) return false;
            if (PATTERN.matcher(maskNonCode(sql)).find()) return false;
            // A table name or one real column must not launder unsafe fixed fields.
            // Inspect every projected field in every SELECT branch.
            return !new TablesNamesFinder().getTableList(select).isEmpty()
                    && hasDataBackedProjection(select.getSelectBody())
                    && withItemsAreDataBacked(select.getWithItemsList());
        } catch (Exception exception) {
            return false;
        }
    }

    // Keywords in values, quoted identifiers and comments are not SQL operations.
    // Preserve spacing so word boundaries in the executable SQL stay intact.
    private static String maskNonCode(String sql) {
        StringBuilder code = new StringBuilder(sql);
        for (int index = 0; index < sql.length();) {
            char current = sql.charAt(index);
            if (current == '\'' || current == '"') {
                char quote = current;
                int end = index + 1;
                while (end < sql.length()) {
                    if (sql.charAt(end) == quote) {
                        if (end + 1 < sql.length() && sql.charAt(end + 1) == quote) {
                            end += 2;
                            continue;
                        }
                        end++;
                        break;
                    }
                    end++;
                }
                for (int masked = index; masked < end; masked++) code.setCharAt(masked, ' ');
                index = end;
            } else if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                int end = sql.indexOf('\n', index + 2);
                if (end < 0) end = sql.length();
                for (int masked = index; masked < end; masked++) code.setCharAt(masked, ' ');
                index = end;
            } else if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                int end = sql.indexOf("*/", index + 2);
                end = end < 0 ? sql.length() : end + 2;
                for (int masked = index; masked < end; masked++) code.setCharAt(masked, ' ');
                index = end;
            } else {
                index++;
            }
        }
        return code.toString();
    }

    private static boolean withItemsAreDataBacked(List<WithItem> items) {
        if (items == null) return true;
        return items.stream().allMatch(item -> item.getSubSelect() != null
                && hasDataBackedProjection(item.getSubSelect().getSelectBody()));
    }

    private static boolean hasDataBackedProjection(SelectBody body) {
        if (body instanceof PlainSelect plain) {
            if (plain.getSelectItems() == null || plain.getSelectItems().isEmpty()) return false;
            boolean allFieldsAllowed = plain.getSelectItems().stream().allMatch(SqlSecurityValidator::isAllowedOutput);
            boolean hasTableData = plain.getSelectItems().stream().anyMatch(SqlSecurityValidator::isDataBackedOutput);
            if (!allFieldsAllowed || !hasTableData || !fromItemIsDataBacked(plain.getFromItem())) return false;
            return plain.getJoins() == null || plain.getJoins().stream()
                    .allMatch(join -> fromItemIsDataBacked(join.getRightItem()));
        }
        if (body instanceof SetOperationList operations) {
            return operations.getSelects() != null && !operations.getSelects().isEmpty()
                    && operations.getSelects().stream().allMatch(SqlSecurityValidator::hasDataBackedProjection);
        }
        return false;
    }

    private static boolean isAllowedOutput(SelectItem item) {
        if (item instanceof SelectExpressionItem expressionItem
                && expressionItem.getExpression() instanceof StringValue label) {
            String value = label.getValue();
            return value != null && value.length() <= 80
                    && PLAIN_TEXT_LABEL.matcher(value).matches();
        }
        return isDataBackedOutput(item);
    }

    private static boolean isDataBackedOutput(SelectItem item) {
        if (item instanceof AllColumns || item instanceof AllTableColumns) return true;
        if (!(item instanceof SelectExpressionItem expressionItem)) return false;
        var visitor = new DataColumnVisitor();
        expressionItem.getExpression().accept(visitor);
        return visitor.hasData;
    }

    private static boolean fromItemIsDataBacked(FromItem item) {
        if (item instanceof SubSelect subSelect) {
            return hasDataBackedProjection(subSelect.getSelectBody())
                    && withItemsAreDataBacked(subSelect.getWithItemsList());
        }
        if (item instanceof LateralSubSelect lateral) return fromItemIsDataBacked(lateral.getSubSelect());
        if (item instanceof ParenthesisFromItem parenthesized) return fromItemIsDataBacked(parenthesized.getFromItem());
        if (item instanceof SubJoin join) {
            return fromItemIsDataBacked(join.getLeft()) && join.getJoinList().stream()
                    .allMatch(part -> fromItemIsDataBacked(part.getRightItem()));
        }
        return item instanceof Table;
    }

    private static final class DataColumnVisitor extends ExpressionVisitorAdapter {
        private boolean hasData;

        @Override public void visit(Column column) { hasData = true; }

        @Override public void visit(Function function) {
            // COUNT(*) and COUNT(1) both depend on the table's row count.
            if ("COUNT".equalsIgnoreCase(function.getName())) hasData = true;
            super.visit(function);
        }
    }
}
