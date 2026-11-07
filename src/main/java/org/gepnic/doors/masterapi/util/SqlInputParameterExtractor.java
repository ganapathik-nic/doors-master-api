package org.gepnic.doors.masterapi.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Extracts DOORS :p_ inputs while ignoring literals, identifiers and comments. */
public final class SqlInputParameterExtractor {
    private SqlInputParameterExtractor() {}

    public static List<String> extract(String sql) {
        if (sql == null || sql.isBlank()) return List.of();
        Set<String> names = new LinkedHashSet<>();
        int length = sql.length();
        for (int i = 0; i < length;) {
            char current = sql.charAt(i);
            if (current == '\'' || current == '"') {
                char quote = current;
                boolean escapedString = quote == '\'' && i > 0
                        && (sql.charAt(i - 1) == 'e' || sql.charAt(i - 1) == 'E')
                        && (i < 2 || !isIdentifierPart(sql.charAt(i - 2)));
                i++;
                while (i < length) {
                    if (escapedString && sql.charAt(i) == '\\') { i += 2; continue; }
                    if (sql.charAt(i++) == quote) {
                        if (i < length && sql.charAt(i) == quote) { i++; continue; }
                        break;
                    }
                }
                continue;
            }
            if (current == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i + 2);
                i = end < 0 ? length : end + 1;
                continue;
            }
            if (current == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                int depth = 1;
                i += 2;
                while (i < length && depth > 0) {
                    if (i + 1 < length && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') { depth++; i += 2; }
                    else if (i + 1 < length && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') { depth--; i += 2; }
                    else i++;
                }
                continue;
            }
            if (current == '$') {
                int tagEnd = sql.indexOf('$', i + 1);
                if (tagEnd > i && validDollarTag(sql, i + 1, tagEnd)) {
                    String tag = sql.substring(i, tagEnd + 1);
                    int bodyEnd = sql.indexOf(tag, tagEnd + 1);
                    i = bodyEnd < 0 ? length : bodyEnd + tag.length();
                    continue;
                }
            }
            if (current == ':' && (i == 0 || sql.charAt(i - 1) != ':')
                    && i + 3 < length && sql.charAt(i + 1) == 'p' && sql.charAt(i + 2) == '_') {
                int end = i + 3;
                while (end < length && isIdentifierPart(sql.charAt(end))) end++;
                names.add(sql.substring(i + 1, end));
                i = end;
                continue;
            }
            i++;
        }
        return new ArrayList<>(names);
    }

    private static boolean validDollarTag(String sql, int start, int end) {
        if (start == end) return true;
        char first = sql.charAt(start);
        if (!(Character.isLetter(first) || first == '_')) return false;
        for (int i = start + 1; i < end; i++) if (!isIdentifierPart(sql.charAt(i))) return false;
        return true;
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }
}
