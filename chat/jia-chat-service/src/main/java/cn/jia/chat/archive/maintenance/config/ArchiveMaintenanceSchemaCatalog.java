package cn.jia.chat.archive.maintenance.config;

import cn.jia.chat.archive.config.ArchiveSchemaCatalog;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact DDL contract for the additive maintenance tables. The resource is also used for creation. */
public final class ArchiveMaintenanceSchemaCatalog {
    public static final List<String> TABLE_ORDER = List.of("archive_collection", "archive_collection_manager",
            "archive_collection_work", "archive_appointment_slot", "archive_appointment",
            "archive_source_snapshot", "archive_confirmed_request", "archive_maintenance_job", "archive_job_run", "archive_execution_grant", "archive_draft", "archive_validation",
            "archive_publication", "archive_publication_readback", "archive_edition_withdrawal", "archive_event", "archive_business_outbox", "archive_operation",
            "archive_admin_operation_receipt");
    private static final Pattern TABLE = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS (\\w+) \\(\\s*(.*?)\\s*\\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;",
            Pattern.DOTALL);
    private static final Pattern COLUMN = Pattern.compile(
            "^(\\w+) ((?:VARCHAR|CHAR|BINARY)\\(\\d+\\)|BIGINT|TEXT|LONGTEXT|TIMESTAMP\\(6\\))"
                    + "(?: CHARACTER SET (\\w+) COLLATE (\\w+))? (NOT NULL|NULL)(?:\\s.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX = Pattern.compile("^(?:(UNIQUE) )?KEY (\\w+) \\(([^)]+)\\)$");
    private static final Pattern FK = Pattern.compile(
            "^CONSTRAINT (\\w+) FOREIGN KEY \\(([^)]+)\\) REFERENCES (\\w+)\\(([^)]+)\\)$");
    private static final Pattern CHECK = Pattern.compile("^CONSTRAINT (\\w+) CHECK \\((.*)\\)$");

    public record Column(String type, boolean nullable, String collation) { }
    public record Table(Map<String, Column> columns, Map<String, String> indexes,
                        Map<String, String> foreignKeys, Map<String, String> checks) { }
    public record Definition(Map<String, Table> tables) { }

    private static final String LEGACY_WAITING_SHAPE =
            "((state='WAITING_INPUT') AND (run_id IS NULL) AND (draft_id IS NULL) "
                    + "AND (appointment_id IS NULL) AND (publication_id IS NULL)) OR "
                    + "((state='WAITING_ASSIGNEE') AND (run_id IS NULL) AND (draft_id IS NULL) "
                    + "AND (appointment_id IS NULL) AND (source_id IS NOT NULL) "
                    + "AND (work_id IS NOT NULL) AND (publication_id IS NULL)) OR "
                    + "(state='CANCELLED') OR ((state NOT IN "
                    + "('WAITING_INPUT','WAITING_ASSIGNEE','CANCELLED')) AND (run_id IS NOT NULL) "
                    + "AND (draft_id IS NOT NULL) AND (appointment_id IS NOT NULL) "
                    + "AND (source_id IS NOT NULL) AND (work_id IS NOT NULL))";

    private ArchiveMaintenanceSchemaCatalog() { }

    static String normalizeCheck(String value) { return ArchiveSchemaCatalog.normalizeCheck(value); }

    public static Definition expected() {
        try (var input = new ClassPathResource("db/archive-maintenance-schema.sql").getInputStream()) {
            return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Archive maintenance DDL unavailable", e);
        }
    }

    static Definition parse(String ddl) {
        Map<String, Table> tables = new LinkedHashMap<>();
        Matcher matcher = TABLE.matcher(ddl);
        while (matcher.find()) {
            String name = matcher.group(1);
            Map<String, Column> columns = new LinkedHashMap<>();
            Map<String, String> indexes = new LinkedHashMap<>();
            Map<String, String> foreignKeys = new LinkedHashMap<>();
            Map<String, String> checks = new LinkedHashMap<>();
            for (String line : matcher.group(2).split("\\R")) {
                String declaration = line.strip().replaceFirst(",$", "");
                if (declaration.isEmpty()) continue;
                Matcher column = COLUMN.matcher(declaration);
                Matcher index = INDEX.matcher(declaration);
                Matcher fk = FK.matcher(declaration);
                Matcher check = CHECK.matcher(declaration);
                if (column.matches()) {
                    String collation = column.group(4);
                    put(columns, column.group(1), new Column(column.group(2).toLowerCase(Locale.ROOT),
                            "NULL".equals(column.group(5)), collation));
                } else if (declaration.startsWith("PRIMARY KEY (")) {
                    put(indexes, "PRIMARY", "0:" + declaration.substring("PRIMARY KEY (".length(), declaration.length() - 1).replace(" ", ""));
                } else if (index.matches()) {
                    put(indexes, index.group(2), (index.group(1) == null ? "1:" : "0:")
                            + index.group(3).replace(" ", ""));
                } else if (fk.matches()) {
                    String[] from = fk.group(2).replace(" ", "").split(",");
                    String[] to = fk.group(4).replace(" ", "").split(",");
                    if (from.length != to.length) throw new IllegalStateException("Archive FK arity drift: " + name);
                    StringBuilder value = new StringBuilder();
                    for (int i = 0; i < from.length; i++) {
                        if (i > 0) value.append(',');
                        value.append(from[i]).append('>').append(fk.group(3)).append('.').append(to[i]);
                    }
                    put(foreignKeys, fk.group(1), value.toString());
                } else if (check.matches()) {
                    put(checks, check.group(1), "YES:" + ArchiveSchemaCatalog.normalizeCheck(check.group(2)));
                } else {
                    throw new IllegalStateException("Unrecognized archive maintenance DDL: " + name + " / " + declaration);
                }
            }
            put(tables, name, new Table(Map.copyOf(columns), Map.copyOf(indexes),
                    Map.copyOf(foreignKeys), Map.copyOf(checks)));
        }
        if (!List.copyOf(tables.keySet()).equals(TABLE_ORDER) || tables.values().stream().anyMatch(t -> t.columns().isEmpty())) {
            throw new IllegalStateException("Archive maintenance DDL table list drift");
        }
        return new Definition(Map.copyOf(tables));
    }


    static Table predecessorOutboxSourceTable(Definition current, String tableName) {
        Table table = current.tables().get(tableName);
        if (table == null || !("archive_event".equals(tableName)
                || "archive_edition_withdrawal".equals(tableName))) {
            throw new IllegalStateException("Archive outbox predecessor table is invalid");
        }
        Map<String, String> checks = new LinkedHashMap<>(table.checks());
        String checkName = "archive_event".equals(tableName)
                ? "chk_archive_event_outbox" : "chk_archive_withdrawal_outbox";
        checks.put(checkName, "YES:" + normalizeCheck("outbox_state IN ('PENDING','DELIVERED')"));
        return new Table(table.columns(), table.indexes(), table.foreignKeys(), Map.copyOf(checks));
    }

    static Table previousWaitingShapeJobTable(Definition current) {
        Table job = current.tables().get("archive_maintenance_job");
        Map<String, String> checks = new LinkedHashMap<>(job.checks());
        checks.put("chk_archive_job_waiting_shape", "YES:" + normalizeCheck(LEGACY_WAITING_SHAPE));
        return new Table(job.columns(), job.indexes(), job.foreignKeys(), Map.copyOf(checks));
    }
    static Table legacyWaitingJobTable(Definition current) {
        Table job = current.tables().get("archive_maintenance_job");
        Map<String, Column> columns = new LinkedHashMap<>(job.columns());
        columns.remove("target_agent_id");
        for (String name : List.of("run_id", "appointment_id", "appointment_revision", "agent_id",
                "binding_version", "permission_profile", "work_id", "canonical_key", "title",
                "source_id", "source_sha256", "source_summary", "rights_basis", "draft_id")) {
            Column column = columns.get(name);
            if (column == null) throw new IllegalStateException("Archive legacy job column unavailable: " + name);
            columns.put(name, new Column(column.type(), false, column.collation()));
        }
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("chk_archive_job_mode", "YES:" + normalizeCheck("publication_mode IN ('MANUAL','AUTO')"));
        checks.put("chk_archive_job_revision", "YES:" + normalizeCheck("revision >= 1"));
        return new Table(Map.copyOf(columns), job.indexes(), job.foreignKeys(), Map.copyOf(checks));
    }

    static void verify(String name, Table expected, Table actual, String engineAndCollation) {
        if (expected == null || !"InnoDB:utf8mb4_0900_bin".equals(engineAndCollation))
            throw new IllegalStateException("Archive maintenance schema drift at " + name + ".engine");
        if (!expected.columns().equals(actual.columns()))
            throw new IllegalStateException("Archive maintenance schema drift at " + name + ".columns");
        if (!expected.indexes().equals(actual.indexes()))
            throw new IllegalStateException("Archive maintenance schema drift at " + name + ".indexes");
        if (!expected.foreignKeys().equals(actual.foreignKeys()))
            throw new IllegalStateException("Archive maintenance schema drift at " + name + ".foreignKeys");
        if (!expected.checks().equals(actual.checks()))
            throw new IllegalStateException("Archive maintenance schema drift at " + name + ".checks");
    }
    private static <T> void put(Map<String, T> map, String key, T value) {
        if (map.putIfAbsent(key, value) != null) throw new IllegalStateException("Duplicate archive DDL item: " + key);
    }
}
