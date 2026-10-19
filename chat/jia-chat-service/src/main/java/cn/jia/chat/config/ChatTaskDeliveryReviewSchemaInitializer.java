package cn.jia.chat.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Fresh schema includes revision lineage from day one. Unknown installed shapes fail closed. */
@Component
@ConditionalOnProperty(prefix = "chat.delivery-review", name = "enabled", havingValue = "true")
public final class ChatTaskDeliveryReviewSchemaInitializer implements InitializingBean {
    static final String TABLE = "chat_task_delivery_review_operation";
    static final String RESOURCE = "db/chat-task-delivery-review-v1.sql";
    private final JdbcTemplate jdbc;
    private final ChatSchemaReadiness readiness;
    public ChatTaskDeliveryReviewSchemaInitializer(JdbcTemplate jdbc, ChatSchemaReadiness readiness) {
        this.jdbc = jdbc; this.readiness = readiness;
    }
    @Override public void afterPropertiesSet() throws Exception {
        readiness.ensureInitialized();
        String ddl = ddl();
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (!connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")
                    || connection.getMetaData().getDatabaseMajorVersion() < 8) throw new IllegalStateException("Delivery review requires MySQL 8");
            var locked = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            String lock = "cyf:chat:delivery-review:v1";
            if (!Integer.valueOf(1).equals(locked.queryForObject("SELECT GET_LOCK(?,-1)", Integer.class, lock))) throw new IllegalStateException("Review schema lock unavailable");
            try { locked.execute(ddl); validate(locked, ddl); }
            finally { if (!Integer.valueOf(1).equals(locked.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lock))) throw new IllegalStateException("Review schema lock lost"); }
            return null;
        });
    }
    static String ddl() {
        try {
            String value = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.stripLeading().startsWith("--")).reduce("", (a,b) -> a + b + '\n').strip();
            if (!value.startsWith("CREATE TABLE IF NOT EXISTS " + TABLE + " (") || !value.endsWith(";")
                    || value.substring(0, value.length() - 1).contains(";")) throw new IllegalStateException("Unexpected review DDL");
            return value;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Review schema resource unavailable", failure); }
    }
    static void validate(JdbcTemplate jdbc, String ddl) {
        Map<String, String> expected = columns(ddl);
        Map<String, String> actual = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("""
                SELECT column_name,column_type,is_nullable,collation_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='chat_task_delivery_review_operation' ORDER BY ordinal_position
                """)) {
            actual.put(text(row, "column_name"), text(row,"column_type").toLowerCase(Locale.ROOT) + "|"
                    + text(row,"is_nullable") + "|" + text(row,"collation_name"));
        }
        if (!expected.equals(actual)) throw new IllegalStateException("Review schema columns differ, including required lineage");
        Map<String, String> checks = checks(ddl);
        Map<String, String> installed = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("""
                SELECT t.constraint_name,c.check_clause,t.enforced FROM information_schema.table_constraints t
                JOIN information_schema.check_constraints c ON c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name
                WHERE t.table_schema=DATABASE() AND t.table_name='chat_task_delivery_review_operation' AND t.constraint_type='CHECK'
                """)) {
            if (!"YES".equalsIgnoreCase(text(row,"enforced"))) throw new IllegalStateException("Review check is not enforced");
            installed.put(text(row,"constraint_name"), expression(text(row,"check_clause")));
        }
        if (!checks.equals(installed)) throw new IllegalStateException("Review check definition drift");
        Map<String, String> indexes = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("""
                SELECT index_name,non_unique,column_name,seq_in_index,sub_part FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='chat_task_delivery_review_operation' ORDER BY index_name,seq_in_index
                """)) {
            if (row.entrySet().stream().anyMatch(e -> e.getKey().equalsIgnoreCase("sub_part") && e.getValue() != null)) throw new IllegalStateException("Review index prefix is not allowed");
            String name = text(row,"index_name");
            indexes.merge(name, text(row,"non_unique") + ":" + text(row,"column_name"), (a,b) -> a + "," + b.substring(2));
        }
        if (!Map.of("PRIMARY","0:tenant_id,owner_jiacn,client_id,operation_id",
                "uk_ctdro_key","0:tenant_id,owner_jiacn,client_id,task_id,idempotency_key",
                "uk_ctdro_source","0:tenant_id,owner_jiacn,client_id,task_id,source_request_id,decision",
                "idx_ctdro_delivery","1:tenant_id,owner_jiacn,client_id,task_id,delivery_id,updated_at").equals(indexes)) throw new IllegalStateException("Review index drift");
    }
    static Map<String, String> columns(String ddl) { return columns(ddl, 23); }
    static Map<String, String> columns(String ddl, int expectedCount) {
        var result = new LinkedHashMap<String, String>();
        var matcher = Pattern.compile("(?m)^ ([a-z][a-z0-9_]*) (VARCHAR\\([0-9]+\\)|CHAR\\([0-9]+\\)|MEDIUMTEXT|BIGINT)([^,\\n]*)[,\\n]").matcher(ddl);
        while (matcher.find()) {
            String tail = matcher.group(3), type = matcher.group(2).toLowerCase(Locale.ROOT);
            var collation = Pattern.compile("COLLATE ([a-z0-9_]+)").matcher(tail);
            String coll = collation.find() ? collation.group(1) : (type.equals("mediumtext") ? "utf8mb4_0900_bin" : "null");
            result.put(matcher.group(1), type + "|" + (tail.contains("NOT NULL") ? "NO" : "YES") + "|" + coll);
        }
        if (result.size() != expectedCount) throw new IllegalStateException("Review column declaration mismatch");
        return result;
    }
    static Map<String, String> checks(String ddl) {
        var result = new LinkedHashMap<String, String>();
        var matcher = Pattern.compile("CONSTRAINT ([a-z_]+) CHECK ").matcher(ddl);
        while (matcher.find()) {
            int begin = matcher.end(), end = closing(ddl, begin);
            result.put(matcher.group(1), expression(ddl.substring(begin, end + 1)));
        }
        return result;
    }
    /** Compare Boolean trees, not punctuation-stripped strings: OR/AND grouping is security relevant. */
    static String expression(String value) {
        StringBuilder clean = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'') quoted = !quoted;
            if (quoted || c == '\'') clean.append(c);
            else if (c != '`') clean.append(Character.isWhitespace(c) ? ' ' : Character.toLowerCase(c));
        }
        return booleanTree(clean.toString().replaceAll("_(utf8mb4|utf8mb3|utf8|ascii)(?=')", ""));
    }
    private static String booleanTree(String source) {
        String value = source.strip();
        while (value.startsWith("(") && closing(value, 0) == value.length() - 1) value = value.substring(1, value.length() - 1).strip();
        for (String operator : List.of("or", "and")) {
            List<String> parts = new ArrayList<>(); int depth = 0, start = 0; boolean quoted = false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i); if (c == '\'') quoted = !quoted;
                if (quoted) continue;
                if (c == '(') depth++; else if (c == ')') depth--;
                // Keywords can border parentheses/literals, but never split identifiers such as origin.
                if (depth == 0 && value.startsWith(operator, i)
                        && i > 0 && !Character.isJavaIdentifierPart(value.charAt(i - 1))
                        && i + operator.length() < value.length() && !Character.isJavaIdentifierPart(value.charAt(i + operator.length()))) {
                    parts.add(flatten(operator, booleanTree(value.substring(start, i)))); start = i + operator.length(); i = start - 1;
                }
            }
            if (!parts.isEmpty()) { parts.add(flatten(operator, booleanTree(value.substring(start)))); return operator + "[" + String.join(",", parts) + "]"; }
        }
        StringBuilder atom = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i); if (c == '\'') quoted = !quoted;
            if (quoted || !Character.isWhitespace(c)) atom.append(c);
        }
        return atom.toString();
    }
    private static String flatten(String operator, String child) {
        return child.startsWith(operator + "[") ? child.substring(operator.length() + 1, child.length() - 1) : child;
    }
    private static int closing(String value, int start) {
        int depth = 0; boolean quoted = false;
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i); if (c == '\'') quoted = !quoted;
            if (quoted) continue;
            if (c == '(') depth++; else if (c == ')' && --depth == 0) return i;
        }
        throw new IllegalStateException("Invalid review CHECK expression");
    }
    private static String text(Map<String,Object> row, String name) {
        return row.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(e -> String.valueOf(e.getValue())).findFirst().orElse("null");
    }
}
