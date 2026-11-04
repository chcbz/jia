package cn.jia.core.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared text handling for explicit schema maintenance. Does not execute SQL or define business tables. */
public final class SchemaSqlText {
    private SchemaSqlText() { }
    public static List<String> splitSql(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean lineComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (lineComment) {
                if (character == '\n' || character == '\r') {
                    lineComment = false;
                    current.append(' ');
                }
                continue;
            }
            if (!quoted && character == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (character == '\'' && (index == 0 || sql.charAt(index - 1) != '\\')) quoted = !quoted;
            if (character == ';' && !quoted) {
                String statement = current.toString().trim();
                if (isOpenTriggerBody(statement)) {
                    current.append(character);
                } else {
                    if (!statement.isEmpty()) statements.add(statement);
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) statements.add(tail);
        return List.copyOf(statements);
    }

    public static String normalizeSql(String sql) {
        return sql.replaceAll("(?m)^\\s*--.*$", " ")
                .replace("`", "")
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isOpenTriggerBody(String statement) {
        String normalized = normalizeSql(statement);
        return normalized.startsWith("create trigger ") && !normalized.endsWith(" end");
    }

    private static void trimTrailingSpace(StringBuilder value) {
        while (!value.isEmpty() && Character.isWhitespace(value.charAt(value.length() - 1))) {
            value.setLength(value.length() - 1);
        }
    }

    private static boolean isCharsetIntroducer(String value, int index) {
        String introducer = "_utf8mb4";
        if (!value.regionMatches(true, index, introducer, 0, introducer.length())
                || index > 0 && isSqlWordCharacter(value.charAt(index - 1))) return false;
        int next = index + introducer.length();
        while (next < value.length() && Character.isWhitespace(value.charAt(next))) next++;
        return next < value.length() && value.charAt(next) == '\'';
    }

    private static boolean isSqlWordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    public static String normalizeTriggerSql(String sql) {
        String value = stripOuterTriggerParentheses(sql);
        StringBuilder normalized = new StringBuilder(value.length());
        boolean quoted = false;
        boolean pendingSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quoted) {
                normalized.append(character);
                if (character == '\\' && index + 1 < value.length()) {
                    normalized.append(value.charAt(++index));
                } else if (character == '\'' && index + 1 < value.length()
                        && value.charAt(index + 1) == '\'') {
                    normalized.append(value.charAt(++index));
                } else if (character == '\'') {
                    quoted = false;
                }
            } else if (Character.isWhitespace(character)) {
                pendingSpace = true;
            } else if (isCharsetIntroducer(value, index)) {
                index += "_utf8mb4".length() - 1;
                while (index + 1 < value.length() && Character.isWhitespace(value.charAt(index + 1))) index++;
            } else if (character == '`') {
                continue;
            } else if (character == '=') {
                trimTrailingSpace(normalized);
                normalized.append(character);
                pendingSpace = false;
            } else {
                appendTriggerSpace(normalized, pendingSpace);
                pendingSpace = false;
                normalized.append(Character.toLowerCase(character));
                if (character == '\'') quoted = true;
            }
        }
        return normalized.toString().trim();
    }

    private static void appendTriggerSpace(StringBuilder normalized, boolean pendingSpace) {
        if (pendingSpace && !normalized.isEmpty() && normalized.charAt(normalized.length() - 1) != '=') {
            normalized.append(' ');
        }
    }

    private static String stripOuterTriggerParentheses(String sql) {
        String current = sql.trim();
        while (current.startsWith("(") && current.endsWith(")")
                && matchingTriggerParenthesis(current, 0) == current.length() - 1) {
            current = current.substring(1, current.length() - 1).trim();
        }
        return current;
    }

    private static int matchingTriggerParenthesis(String value, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int index = open; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quoted) {
                if (character == '\\' && index + 1 < value.length()) {
                    index++;
                } else if (character == '\'' && index + 1 < value.length()
                        && value.charAt(index + 1) == '\'') {
                    index++;
                } else if (character == '\'') {
                    quoted = false;
                }
            } else if (character == '\'') {
                quoted = true;
            } else if (character == '(') {
                depth++;
            } else if (character == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

}
