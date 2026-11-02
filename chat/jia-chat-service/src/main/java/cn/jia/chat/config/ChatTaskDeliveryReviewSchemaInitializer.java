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
@org.springframework.context.annotation.Profile("schema-maintenance")
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

    private static String text(Map<String,Object> row, String name) {
        return row.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(e -> String.valueOf(e.getValue())).findFirst().orElse("null");
    }
}
