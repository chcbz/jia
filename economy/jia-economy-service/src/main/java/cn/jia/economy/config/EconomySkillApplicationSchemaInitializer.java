package cn.jia.economy.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fail-closed initializer/catalog validator for the separate W09 six-table application schema. */
public final class EconomySkillApplicationSchemaInitializer implements InitializingBean {
    static final String DDL_RESOURCE = "db/economy-v0-skill-application.sql";
    static final List<String> TABLES = List.of("economy_skill_actor_root", "economy_skill_agent_version", "economy_skill_result_receipt", "economy_skill_delivery_binding", "economy_skill_managed_credential", "economy_skill_credential_operation");
    static final List<String> TRIGGER_ORDER = List.of("trg_skill_app_result_no_update", "trg_skill_app_result_no_delete", "trg_skill_app_binding_no_update", "trg_skill_app_binding_no_delete", "trg_skill_app_credential_op_no_update", "trg_skill_app_credential_op_no_delete");

    private static final String BINARY_COLLATION = "utf8mb4_0900_bin";
    private static final String INITIALIZATION_LOCK = "cyf:economy-v0:skill-application";
    private static final int INITIALIZATION_LOCK_SECONDS = 10;
    private static final Pattern TABLE_NAME = Pattern.compile(
            "(?is)^CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+([a-z0-9_]+)\\s*\\(");
    private static final Pattern COLUMN = Pattern.compile(
            "(?is)^([a-z0-9_]+)\\s+([a-z]+(?:\\([0-9]+\\))?)(.*)$");
    private static final Pattern DEFAULT_VALUE = Pattern.compile(
            "(?is).*\\bDEFAULT\\s+('(?:''|[^'])*'|NULL|-?[0-9]+).*");
    private static final Pattern NAMED_INDEX = Pattern.compile(
            "(?is)^(UNIQUE\\s+)?KEY\\s+([a-z0-9_]+)\\s*\\(([^)]+)\\)$");
    private static final Pattern FOREIGN_KEY = Pattern.compile(
            "(?is)^CONSTRAINT\\s+([a-z0-9_]+)\\s+FOREIGN\\s+KEY\\s*\\(([^)]+)\\)"
                    + "\\s+REFERENCES\\s+([a-z0-9_]+)\\s*\\(([^)]+)\\)$");
    private static final Pattern CHECK = Pattern.compile(
            "(?is)^CONSTRAINT\\s+([a-z0-9_]+)\\s+CHECK\\s*\\((.*)\\)$");

    private final JdbcTemplate jdbcTemplate;

    public EconomySkillApplicationSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql();
        withInitializationLock(() -> {
            Map<String, TableSpec> expected = expectedTables();
            List<String> present = inspectPresentTables();
            if (present.isEmpty()) {
                for (String statement : tableDdlStatements()) jdbcTemplate.execute(statement);
            } else if (!present.equals(TABLES.stream().sorted().toList())) {
                throw partialSchema(present);
            }
            present = inspectPresentTables();
            if (!present.equals(TABLES.stream().sorted().toList())) {
                throw new IllegalStateException("ECO-V0 skill schema creation did not produce exact 6/6 tables: " + present);
            }
            validateTables(expected);
            ensureAndValidateTriggers();
        });
    }

    void validateSchema() {
        requireMySql();
        withInitializationLock(() -> {
            List<String> present = inspectPresentTables();
            if (!present.equals(TABLES.stream().sorted().toList())) throw partialSchema(present);
            validateTables(expectedTables());
            ensureAndValidateTriggers();
        });
    }

    private void validateTables(Map<String, TableSpec> expected) {
        for (TableSpec table : expected.values()) {
            TableDefinition definition = jdbcTemplate.queryForObject("""
                    SELECT engine,table_collation
                    FROM information_schema.tables
                    WHERE table_schema=DATABASE() AND table_name=?
                    """, (rs, rowNum) -> new TableDefinition(
                    rs.getString("engine"), rs.getString("table_collation")), table.name());
            if (definition == null || !"InnoDB".equalsIgnoreCase(definition.engine())
                    || !BINARY_COLLATION.equalsIgnoreCase(definition.collation())) {
                throw new IllegalStateException("ECO-V0 skill table " + table.name()
                        + " must be exact InnoDB/" + BINARY_COLLATION + ", got " + definition);
            }
            List<ColumnSpec> columns = jdbcTemplate.query("""
                    SELECT column_name,column_type,is_nullable,column_default,collation_name,extra
                    FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name=?
                    ORDER BY ordinal_position
                    """, (rs, rowNum) -> new ColumnSpec(
                    rs.getString("column_name"), rs.getString("column_type").toLowerCase(Locale.ROOT),
                    "YES".equalsIgnoreCase(rs.getString("is_nullable")), rs.getString("column_default"),
                    rs.getString("collation_name"), normalizeExtra(rs.getString("extra"))), table.name());
            if (!table.columns().equals(columns)) {
                throw new IllegalStateException("ECO-V0 skill table " + table.name()
                        + " has incompatible columns: " + columns);
            }
            Map<String, IndexSpec> indexes = inspectIndexes(table.name());
            if (!table.indexes().equals(indexes)) {
                throw new IllegalStateException("ECO-V0 skill table " + table.name()
                        + " has incompatible indexes: " + indexes);
            }
            Map<String, ForeignKeySpec> foreignKeys = inspectForeignKeys(table.name());
            if (!table.foreignKeys().equals(foreignKeys)) {
                throw new IllegalStateException("ECO-V0 skill table " + table.name()
                        + " has incompatible foreign keys: " + foreignKeys);
            }
            Map<String, CheckSpec> checks = inspectChecks(table.name());
            if (!table.checks().equals(checks)) {
                throw new IllegalStateException("ECO-V0 skill table " + table.name()
                        + " has incompatible CHECK constraints: " + checks);
            }
        }
    }

    private Map<String, IndexSpec> inspectIndexes(String table) {
        List<IndexColumn> rows = jdbcTemplate.query("""
                SELECT index_name,non_unique,seq_in_index,column_name,sub_part,index_type,is_visible
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=?
                ORDER BY index_name,seq_in_index
                """, (rs, rowNum) -> new IndexColumn(
                rs.getString("index_name"), rs.getInt("non_unique") != 0,
                rs.getInt("seq_in_index"), rs.getString("column_name"),
                (Integer) rs.getObject("sub_part"), rs.getString("index_type"), rs.getString("is_visible")), table);
        Map<String, List<IndexColumn>> grouped = new TreeMap<>();
        for (IndexColumn row : rows) {
            if (row.subPart() != null || !"BTREE".equalsIgnoreCase(row.indexType())
                    || !"YES".equalsIgnoreCase(row.visible())) {
                throw new IllegalStateException("ECO-V0 skill table " + table
                        + " has incompatible index component: " + row);
            }
            grouped.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, IndexSpec> result = new TreeMap<>();
        for (Map.Entry<String, List<IndexColumn>> entry : grouped.entrySet()) {
            List<IndexColumn> parts = entry.getValue();
            boolean unique = !parts.getFirst().nonUnique();
            List<String> columns = new ArrayList<>();
            for (int index = 0; index < parts.size(); index++) {
                IndexColumn part = parts.get(index);
                if (part.sequence() != index + 1 || unique == part.nonUnique()) {
                    throw new IllegalStateException("ECO-V0 skill table " + table
                            + " has incompatible index ordering: " + parts);
                }
                columns.add(part.column());
            }
            result.put(entry.getKey(), new IndexSpec(entry.getKey(), unique, List.copyOf(columns)));
        }
        return result;
    }

    private Map<String, ForeignKeySpec> inspectForeignKeys(String table) {
        List<ForeignKeyColumn> rows = jdbcTemplate.query("""
                SELECT k.constraint_name,k.ordinal_position,k.column_name,
                       k.referenced_table_name,k.referenced_column_name,
                       k.referenced_table_schema,DATABASE() AS current_schema_name,
                       r.update_rule,r.delete_rule
                FROM information_schema.key_column_usage k
                JOIN information_schema.referential_constraints r
                  ON r.constraint_schema=k.constraint_schema
                 AND r.table_name=k.table_name
                 AND r.constraint_name=k.constraint_name
                WHERE k.constraint_schema=DATABASE() AND k.table_name=?
                  AND k.referenced_table_name IS NOT NULL
                ORDER BY k.constraint_name,k.ordinal_position
                """, (rs, rowNum) -> new ForeignKeyColumn(
                rs.getString("constraint_name"), rs.getInt("ordinal_position"),
                rs.getString("column_name"), rs.getString("referenced_table_name"),
                rs.getString("referenced_column_name"), rs.getString("referenced_table_schema"),
                rs.getString("current_schema_name"), normalizeReferentialRule(rs.getString("update_rule")),
                normalizeReferentialRule(rs.getString("delete_rule"))), table);
        Map<String, List<ForeignKeyColumn>> grouped = new TreeMap<>();
        for (ForeignKeyColumn row : rows) {
            if (row.currentSchema() == null || !row.currentSchema().equals(row.referencedSchema())) {
                throw new IllegalStateException("ECO-V0 skill table " + table
                        + " has a foreign key outside the current database: " + row.name());
            }
            grouped.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, ForeignKeySpec> result = new TreeMap<>();
        for (Map.Entry<String, List<ForeignKeyColumn>> entry : grouped.entrySet()) {
            List<ForeignKeyColumn> parts = entry.getValue();
            List<String> columns = new ArrayList<>();
            List<String> referencedColumns = new ArrayList<>();
            ForeignKeyColumn first = parts.getFirst();
            for (int index = 0; index < parts.size(); index++) {
                ForeignKeyColumn part = parts.get(index);
                if (part.position() != index + 1
                        || !first.referencedTable().equals(part.referencedTable())
                        || !first.updateRule().equals(part.updateRule())
                        || !first.deleteRule().equals(part.deleteRule())) {
                    throw new IllegalStateException("ECO-V0 skill table " + table
                            + " has incompatible foreign-key ordering: " + parts);
                }
                columns.add(part.column());
                referencedColumns.add(part.referencedColumn());
            }
            result.put(entry.getKey(), new ForeignKeySpec(entry.getKey(), List.copyOf(columns),
                    first.referencedTable(), List.copyOf(referencedColumns),
                    first.updateRule(), first.deleteRule()));
        }
        return result;
    }

    private Map<String, CheckSpec> inspectChecks(String table) {
        List<CheckSpec> rows = jdbcTemplate.query("""
                SELECT tc.constraint_name,cc.check_clause,tc.enforced
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_catalog=tc.constraint_catalog
                 AND cc.constraint_schema=tc.constraint_schema
                 AND cc.constraint_name=tc.constraint_name
                WHERE tc.constraint_schema=DATABASE() AND tc.table_name=?
                  AND tc.constraint_type='CHECK'
                ORDER BY tc.constraint_name
                """, (rs, rowNum) -> new CheckSpec(
                rs.getString("constraint_name"),
                EconomySchemaInitializer.normalizeCheckClause(rs.getString("check_clause")),
                "YES".equalsIgnoreCase(rs.getString("enforced"))), table);
        Map<String, CheckSpec> result = new TreeMap<>();
        for (CheckSpec row : rows) {
            if (result.put(row.name(), row) != null) {
                throw new IllegalStateException("ECO-V0 skill table " + table + " has ambiguous CHECK constraints");
            }
        }
        return result;
    }

    private void ensureAndValidateTriggers() {
        Map<String, TriggerSpec> expected = expectedTriggers();
        Map<String, TriggerSpec> actual = inspectTriggers();
        for (TriggerSpec found : actual.values()) {
            TriggerSpec required = expected.get(found.name());
            if (required == null || !triggerMatches(required, found)) {
                throw new IllegalStateException("ECO-V0 skill trigger " + found.name() + " is incompatible");
            }
        }
        for (TriggerSpec required : expected.values()) {
            if (!actual.containsKey(required.name())) jdbcTemplate.execute(createTriggerSql(required));
        }
        actual = inspectTriggers();
        if (actual.size() != expected.size()) {
            throw new IllegalStateException("ECO-V0 skill schema requires six exact immutable-row triggers");
        }
        for (TriggerSpec required : expected.values()) {
            TriggerSpec found = actual.get(required.name());
            if (found == null || !triggerMatches(required, found)) {
                throw new IllegalStateException("ECO-V0 skill trigger " + required.name() + " is incompatible");
            }
        }
    }

    private Map<String, TriggerSpec> inspectTriggers() {
        List<TriggerSpec> rows = jdbcTemplate.query("""
                SELECT trigger_name,event_object_table,action_timing,event_manipulation,action_statement
                FROM information_schema.triggers
                WHERE trigger_schema=DATABASE()
                  AND (event_object_table IN (
                        'economy_skill_actor_root','economy_skill_agent_version','economy_skill_result_receipt','economy_skill_delivery_binding','economy_skill_managed_credential','economy_skill_credential_operation')
                       OR trigger_name LIKE 'trg_skill_app_%')
                ORDER BY trigger_name
                """, (rs, rowNum) -> new TriggerSpec(
                rs.getString("trigger_name"), rs.getString("event_object_table"),
                rs.getString("action_timing"), rs.getString("event_manipulation"),
                rs.getString("action_statement")));
        Map<String, TriggerSpec> result = new TreeMap<>();
        for (TriggerSpec row : rows) {
            if (result.put(row.name(), row) != null) {
                throw new IllegalStateException("ECO-V0 skill trigger catalog is ambiguous");
            }
        }
        return result;
    }

    static Map<String, TriggerSpec> expectedTriggers() {
        Map<String, TriggerSpec> result = new TreeMap<>();
        immutablePair(result, "result", "economy_skill_result_receipt", "skill result receipts");
        immutablePair(result, "binding", "economy_skill_delivery_binding", "skill delivery bindings");
        immutablePair(result, "credential_op", "economy_skill_credential_operation", "credential operations");
        return Map.copyOf(result);
    }

    private static void immutablePair(
            Map<String, TriggerSpec> target, String shortName, String table, String subject) {
        String update = "trg_skill_app_" + shortName + "_no_update";
        String delete = "trg_skill_app_" + shortName + "_no_delete";
        target.put(update, signal(update, table, "UPDATE", "ECO-V0: immutable " + subject + " cannot be updated"));
        target.put(delete, signal(delete, table, "DELETE", "ECO-V0: immutable " + subject + " cannot be deleted"));
    }

    private static TriggerSpec signal(String name, String table, String event, String message) {
        return new TriggerSpec(name, table, "BEFORE", event,
                "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '" + message + "'; END");
    }

    private static String createTriggerSql(TriggerSpec trigger) {
        return "CREATE TRIGGER " + trigger.name() + " " + trigger.timing() + " " + trigger.event()
                + " ON " + trigger.table() + " FOR EACH ROW " + trigger.statement();
    }

    private static boolean triggerMatches(TriggerSpec expected, TriggerSpec actual) {
        return expected.table().equalsIgnoreCase(actual.table())
                && expected.timing().equalsIgnoreCase(actual.timing())
                && expected.event().equalsIgnoreCase(actual.event())
                && EconomySchemaInitializer.normalizeTriggerSql(expected.statement())
                .equals(EconomySchemaInitializer.normalizeTriggerSql(actual.statement()));
    }

    static List<String> tableDdlStatements() {
        String sql;
        try {
            sql = new ClassPathResource(DDL_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Missing ECO-V0 skill DDL resource " + DDL_RESOURCE, exception);
        }
        List<String> statements = EconomySchemaInitializer.splitSql(sql);
        if (statements.size() != TABLES.size()) {
            throw new IllegalStateException("ECO-V0 skill DDL must contain only six additive tables");
        }
        for (int index = 0; index < statements.size(); index++) {
            String normalized = EconomySchemaInitializer.normalizeSql(statements.get(index));
            if (!normalized.startsWith("create table if not exists " + TABLES.get(index) + " ")
                    || containsUnsafeSql(normalized)) {
                throw new IllegalStateException("ECO-V0 skill DDL contains unsafe or reordered SQL");
            }
        }
        return List.copyOf(statements);
    }

    static Map<String, TableSpec> expectedTables() {
        Map<String, TableSpec> result = new LinkedHashMap<>();
        for (String statement : tableDdlStatements()) {
            TableSpec table = parseTable(statement);
            if (result.put(table.name(), table) != null) {
                throw new IllegalStateException("Duplicate ECO-V0 skill table declaration " + table.name());
            }
        }
        if (!List.copyOf(result.keySet()).equals(TABLES)) {
            throw new IllegalStateException("ECO-V0 skill DDL table order drift: " + result.keySet());
        }
        return Map.copyOf(result);
    }

    private static TableSpec parseTable(String statement) {
        Matcher tableMatcher = TABLE_NAME.matcher(statement.trim());
        if (!tableMatcher.find()) throw new IllegalStateException("Invalid ECO-V0 skill CREATE TABLE statement");
        String tableName = tableMatcher.group(1).toLowerCase(Locale.ROOT);
        int open = statement.indexOf('(', tableMatcher.end() - 1);
        int close = matchingClose(statement, open);
        List<ColumnSpec> columns = new ArrayList<>();
        Map<String, IndexSpec> indexes = new TreeMap<>();
        Map<String, ForeignKeySpec> foreignKeys = new TreeMap<>();
        Map<String, CheckSpec> checks = new TreeMap<>();
        for (String raw : splitTopLevel(statement.substring(open + 1, close))) {
            String part = raw.trim();
            if (part.regionMatches(true, 0, "PRIMARY KEY", 0, "PRIMARY KEY".length())) {
                indexes.put("PRIMARY", new IndexSpec("PRIMARY", true, columnsInParentheses(part)));
                continue;
            }
            Matcher index = NAMED_INDEX.matcher(part);
            if (index.matches()) {
                indexes.put(index.group(2), new IndexSpec(index.group(2), index.group(1) != null,
                        parseColumns(index.group(3))));
                continue;
            }
            Matcher foreignKey = FOREIGN_KEY.matcher(part);
            if (foreignKey.matches()) {
                foreignKeys.put(foreignKey.group(1), new ForeignKeySpec(
                        foreignKey.group(1), parseColumns(foreignKey.group(2)),
                        foreignKey.group(3), parseColumns(foreignKey.group(4)), "RESTRICT", "RESTRICT"));
                continue;
            }
            Matcher check = CHECK.matcher(part);
            if (check.matches()) {
                checks.put(check.group(1), new CheckSpec(check.group(1),
                        EconomySchemaInitializer.normalizeCheckClause(check.group(2)), true));
                continue;
            }
            Matcher column = COLUMN.matcher(part);
            if (!column.matches()) throw new IllegalStateException("Unsupported ECO-V0 skill DDL component: " + part);
            String type = column.group(2).toLowerCase(Locale.ROOT);
            String modifiers = column.group(3);
            boolean nullable = !modifiers.toUpperCase(Locale.ROOT).contains("NOT NULL");
            String defaultValue = defaultValue(modifiers);
            String collation = isTextType(type) ? BINARY_COLLATION : null;
            String extra = modifiers.toUpperCase(Locale.ROOT).contains("AUTO_INCREMENT") ? "auto_increment" : "";
            columns.add(new ColumnSpec(column.group(1), type, nullable, defaultValue, collation, extra));
        }
        return new TableSpec(tableName, List.copyOf(columns), Map.copyOf(indexes),
                Map.copyOf(foreignKeys), Map.copyOf(checks));
    }

    private static String defaultValue(String modifiers) {
        Matcher matcher = DEFAULT_VALUE.matcher(modifiers);
        if (!matcher.matches()) return null;
        String value = matcher.group(1);
        if ("NULL".equalsIgnoreCase(value)) return null;
        if (value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }

    private static boolean isTextType(String type) {
        return type.startsWith("varchar(") || "text".equals(type);
    }

    private static List<String> columnsInParentheses(String value) {
        int open = value.indexOf('(');
        int close = value.lastIndexOf(')');
        if (open < 0 || close <= open) throw new IllegalStateException("Invalid index columns: " + value);
        return parseColumns(value.substring(open + 1, close));
    }

    private static List<String> parseColumns(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) result.add(part.trim().replace("`", ""));
        return List.copyOf(result);
    }

    private static List<String> splitTopLevel(String body) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (character == '\'' && (!quoted || index + 1 >= body.length() || body.charAt(index + 1) != '\'')) {
                quoted = !quoted;
            } else if (quoted && character == '\'' && index + 1 < body.length() && body.charAt(index + 1) == '\'') {
                current.append(character);
                character = body.charAt(++index);
            } else if (!quoted && character == '(') {
                depth++;
            } else if (!quoted && character == ')') {
                depth--;
            } else if (!quoted && character == ',' && depth == 0) {
                result.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(character);
        }
        if (!current.toString().isBlank()) result.add(current.toString());
        return List.copyOf(result);
    }

    private static int matchingClose(String value, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int index = open; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\'') {
                if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && character == '(') {
                depth++;
            } else if (!quoted && character == ')' && --depth == 0) {
                return index;
            }
        }
        throw new IllegalStateException("Unclosed ECO-V0 skill CREATE TABLE body");
    }

    private static boolean containsUnsafeSql(String normalized) {
        return normalized.contains(" insert ") || normalized.contains(" update ")
                || normalized.contains(" delete ") || normalized.contains(" drop ")
                || normalized.contains(" alter ") || normalized.contains(" create trigger ");
    }

    private void requireMySql() {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) throw new IllegalStateException("ECO-V0 skill schema requires a JDBC DataSource");
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("ECO-V0 skill schema requires MySQL; got " + product);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to determine ECO-V0 skill database dialect", exception);
        }
    }

    private List<String> inspectPresentTables() {
        return jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema=DATABASE()
                  AND table_name IN (
                    'economy_skill_actor_root','economy_skill_agent_version','economy_skill_result_receipt','economy_skill_delivery_binding','economy_skill_managed_credential','economy_skill_credential_operation')
                ORDER BY table_name
                """, String.class);
    }

    private void withInitializationLock(Runnable action) {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) throw new IllegalStateException("ECO-V0 skill schema requires a JDBC DataSource");
        try (Connection connection = dataSource.getConnection()) {
            if (!acquireLock(connection)) {
                throw new IllegalStateException("Timed out acquiring ECO-V0 skill schema initialization lock");
            }
            try {
                action.run();
            } finally {
                releaseLock(connection);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to serialize ECO-V0 skill schema initialization", exception);
        }
    }

    private boolean acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, INITIALIZATION_LOCK);
            statement.setInt(2, INITIALIZATION_LOCK_SECONDS);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1 && !result.wasNull();
            }
        }
    }

    private void releaseLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, INITIALIZATION_LOCK);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                    throw new IllegalStateException("ECO-V0 skill schema initialization lock was not held");
                }
            }
        }
    }

    private static IllegalStateException partialSchema(List<String> present) {
        return new IllegalStateException("ECO-V0 skill schema is partial or drifted; expected exact 6/6 tables, found "
                + present + ". Refusing automatic repair or destructive replacement.");
    }

    private static String normalizeReferentialRule(String value) {
        if (value == null) return null;
        return "NO ACTION".equalsIgnoreCase(value) ? "RESTRICT" : value.toUpperCase(Locale.ROOT);
    }

    private static String normalizeExtra(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    record TableDefinition(String engine, String collation) {
    }

    record ColumnSpec(
            String name, String columnType, boolean nullable, String defaultValue, String collation, String extra) {
    }

    record IndexColumn(
            String name, boolean nonUnique, int sequence, String column, Integer subPart,
            String indexType, String visible) {
    }

    record IndexSpec(String name, boolean unique, List<String> columns) {
    }

    record ForeignKeyColumn(
            String name, int position, String column, String referencedTable, String referencedColumn,
            String referencedSchema, String currentSchema, String updateRule, String deleteRule) {
    }

    record ForeignKeySpec(
            String name, List<String> columns, String referencedTable, List<String> referencedColumns,
            String updateRule, String deleteRule) {
    }

    record CheckSpec(String name, String normalizedClause, boolean enforced) {
    }

    record TableSpec(
            String name, List<ColumnSpec> columns, Map<String, IndexSpec> indexes,
            Map<String, ForeignKeySpec> foreignKeys, Map<String, CheckSpec> checks) {
    }

    record TriggerSpec(String name, String table, String timing, String event, String statement) {
    }
}
