package cn.jia.agent.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Splits bundled MySQL DDL, not a general migration runner. Callers retain their table allowlists. */
final class HallSchemaSql {
    private HallSchemaSql() { }

    static List<String> split(String source) {
        Objects.requireNonNull(source, "source");
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && quote != '`') {
                    // MySQL string escape: consume the escaped character, including another slash.
                    if (++i >= source.length()) throw invalid();
                    current.append(source.charAt(i));
                } else if (c == quote) {
                    if (i + 1 < source.length() && source.charAt(i + 1) == quote) {
                        current.append(source.charAt(++i)); // doubled quote/backtick
                    } else quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c; current.append(c); continue;
            }
            boolean dashComment = c == '-' && i + 1 < source.length() && source.charAt(i + 1) == '-'
                    && (i + 2 == source.length() || Character.isWhitespace(source.charAt(i + 2)));
            if (c == '#' || dashComment) {
                while (i + 1 < source.length() && source.charAt(i + 1) != '\n' && source.charAt(i + 1) != '\r') i++;
                current.append(' '); continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                // Executable/versioned comments could conceal statements from the DDL allowlist.
                if (source.startsWith("/*!", i) || source.startsWith("/*M!", i)) throw invalid();
                int end = source.indexOf("*/", i + 2);
                if (end < 0) throw invalid();
                i = end + 1; current.append(' '); continue;
            }
            if (c == ';') {
                add(statements, current); current.setLength(0);
            } else current.append(c);
        }
        if (quote != 0) throw invalid();
        add(statements, current);
        return List.copyOf(statements);
    }

    private static void add(List<String> statements, StringBuilder current) {
        String value = current.toString().strip();
        if (!value.isEmpty()) statements.add(value);
    }
    private static IllegalStateException invalid() {
        return new IllegalStateException("Malformed or executable-comment Hall DDL");
    }
}
