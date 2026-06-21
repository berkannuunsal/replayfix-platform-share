package com.etiya.replayfix.service;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class SqlReadOnlyGuard {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|truncate|alter|create|grant|revoke|execute|call|copy|vacuum|analyze)\\b",
            Pattern.CASE_INSENSITIVE
    );

    public void validateSelectOnly(String sql) {
        String value = sql == null ? "" : sql.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("SQL must not be blank");
        }
        String normalized = stripTrailingSemicolon(value).trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith("select ")) {
            throw new IllegalArgumentException("Only SELECT statements are allowed");
        }
        if (containsMultipleStatements(value)) {
            throw new IllegalArgumentException("Multiple SQL statements are not allowed");
        }
        if (FORBIDDEN.matcher(normalized).find()) {
            throw new IllegalArgumentException("SQL contains a forbidden operation");
        }
    }

    private boolean containsMultipleStatements(String sql) {
        String value = sql.trim();
        int first = value.indexOf(';');
        if (first < 0) {
            return false;
        }
        return first != value.length() - 1
                || value.substring(0, value.length() - 1).contains(";");
    }

    private String stripTrailingSemicolon(String sql) {
        return sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
    }
}
