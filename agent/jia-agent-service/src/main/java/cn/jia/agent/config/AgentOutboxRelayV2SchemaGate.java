package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Read-only startup gate for the manually migrated Agent relay-v2 schema.
 *
 * <p>This class intentionally contains no DDL or application-row DML. Rabbit publishing stays
 * unavailable unless the two transport tables already expose the exact base schema plus the v2
 * columns, indexes and enforced scope CHECK constraints installed by the reviewed migration.</p>
 */
public final class AgentOutboxRelayV2SchemaGate implements InitializingBean {
    static final String DELIVERY = "agent_command_delivery";
    static final String OUTBOX = "agent_outbox_event";
    private static final String BINARY_COLLATION = "utf8mb4_0900_bin";
    private static final Set<String> TABLES = Set.of(DELIVERY, OUTBOX);
    private static final Set<String> RELAY_COLUMNS = Set.of(
            "protocol_version", "data_domain_id", "execution_pool_id", "admission_epoch",
            "producer_deployment_id", "module_id", "migration_epoch",
            "claimed_deployment_id", "claimed_worker_generation");
    private static final Map<String, String> REQUIRED_CHECKS = Map.of(
            DELIVERY, "chk_delivery_relay_v2_scope",
            OUTBOX, "chk_outbox_relay_v2_scope");

    private final Catalog catalog;

    public AgentOutboxRelayV2SchemaGate(JdbcTemplate jdbcTemplate) {
        this(new JdbcCatalog(Objects.requireNonNull(jdbcTemplate, "jdbcTemplate")));
    }

    AgentOutboxRelayV2SchemaGate(Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public void afterPropertiesSet() {
        String product = catalog.databaseProduct();
        if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
            throw invalid("MySQL is required, got " + product);
        }
        Set<String> present = new LinkedHashSet<>(catalog.presentTables());
        if (!present.equals(TABLES)) {
            throw invalid("required transport tables are missing: " + present);
        }
        for (String table : List.of(DELIVERY, OUTBOX)) {
            TableDefinition definition = catalog.table(table);
            if (definition == null || !"InnoDB".equalsIgnoreCase(definition.engine())
                    || !BINARY_COLLATION.equalsIgnoreCase(definition.collation())) {
                throw invalid(table + " must be exact InnoDB/" + BINARY_COLLATION);
            }
            if (!expectedColumns(table).equals(catalog.columns(table))) {
                throw invalid(table + " has incompatible base or relay-v2 columns");
            }
            if (!expectedIndexes(table).equals(catalog.indexes(table))) {
                throw invalid(table + " has incompatible base or relay-v2 indexes");
            }
            if (catalog.foreignKeyCount(table) != 0) {
                throw invalid(table + " must not participate in database foreign keys");
            }
            validateChecks(table, catalog.checks(table));
        }
    }

    private static void validateChecks(String table, Map<String, CheckDefinition> actual) {
        String requiredName = REQUIRED_CHECKS.get(table);
        CheckDefinition required = actual.get(requiredName);
        if (required == null || !required.enforced() || !isCanonicalScopeCheck(required.clause())) {
            throw invalid(table + " CHECK " + requiredName + " is missing, unenforced or incompatible");
        }
        if (DELIVERY.equals(table) && actual.containsKey("chk_delivery_single_tenant")) {
            throw invalid("legacy single-tenant CHECK is still installed");
        }
        for (Map.Entry<String, CheckDefinition> entry : actual.entrySet()) {
            if (requiredName.equals(entry.getKey())) continue;
            String clause;
            try { clause = normalize(entry.getValue().clause()); }
            catch (IllegalArgumentException malformed) {
                throw invalid(table + " has an unsupported or malformed extra CHECK");
            }
            boolean relayColumn = RELAY_COLUMNS.stream().anyMatch(clause::contains);
            if (entry.getKey().toLowerCase(Locale.ROOT).contains("relay_v2") || relayColumn) {
                throw invalid(table + " has an unexpected relay-v2 CHECK " + entry.getKey());
            }
        }
    }

    static boolean isCanonicalScopeCheck(String clause) {
        try {
            List<String> branches = splitBoolean(normalize(clause), "or");
            return branches.size() == 2 && legacyBranch(branches.get(0)) && v2Branch(branches.get(1));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean legacyBranch(String branch) {
        return predicates(branch, "and").equals(expected(
                "protocol_version is null",
                "tenant_id = '0'",
                "data_domain_id is null",
                "execution_pool_id is null",
                "admission_epoch is null",
                "producer_deployment_id is null",
                "module_id is null",
                "migration_epoch is null",
                "claimed_deployment_id is null",
                "claimed_worker_generation is null"));
    }

    private static boolean v2Branch(String branch) {
        List<String> actual = splitBoolean(stripOuter(branch), "and");
        if (actual.size() != 8) return false;
        List<String> expected = expected(
                "protocol_version = 2",
                "octet_length(data_domain_id) between 1 and 64",
                "execution_pool_id regexp '^[a-z0-9-]{1,100}$'",
                "admission_epoch >= 0",
                "octet_length(producer_deployment_id) between 1 and 100",
                "module_id regexp '^[a-z][a-z0-9_-]{0,63}$'",
                "migration_epoch >= 0");
        for (int index = 0; index < expected.size(); index++) {
            if (!predicate(actual.get(index)).equals(predicate(expected.get(index)))) return false;
        }
        List<String> claimed = splitBoolean(stripOuter(actual.get(7)), "or");
        if (claimed.size() != 2) return false;
        return predicates(claimed.get(0), "and").equals(expected(
                        "claimed_deployment_id is null",
                        "claimed_worker_generation is null"))
                && predicates(claimed.get(1), "and").equals(expected(
                        "octet_length(claimed_deployment_id) between 1 and 100",
                        "claimed_worker_generation >= 0"));
    }

    private static List<String> predicates(String expression, String operator) {
        return splitBoolean(stripOuter(expression), operator).stream()
                .map(AgentOutboxRelayV2SchemaGate::predicate).toList();
    }

    private static List<String> expected(String... predicates) {
        return java.util.Arrays.stream(predicates).map(AgentOutboxRelayV2SchemaGate::predicate).toList();
    }

    private static String predicate(String expression) {
        return AgentMysqlCheckCanonicalizer.sql(expression);
    }

    private static List<String> splitBoolean(String expression, String operator) {
        return AgentMysqlCheckCanonicalizer.split(expression, operator);
    }

    private static String normalize(String expression) {
        return AgentMysqlCheckCanonicalizer.sql(expression);
    }

    private static String stripOuter(String expression) {
        return AgentMysqlCheckCanonicalizer.sql(expression);
    }

    static List<ColumnDefinition> expectedColumns(String table) {
        AgentCommandTransportSchemaInitializer.TableExpectation base =
                AgentCommandTransportSchemaInitializer.expectedTables().get(table);
        if (base == null) throw invalid("unknown transport table " + table);
        List<ColumnDefinition> result = new ArrayList<>();
        for (int index = 0; index < base.columns().size(); index++) {
            AgentCommandTransportSchemaInitializer.ColumnDefinition column = base.columns().get(index);
            result.add(new ColumnDefinition(column.name(), column.dataType(), column.columnType(),
                    column.nullable(), column.defaultValue(), column.collation(), column.extra(),
                    column.generationExpression()));
            if (index == 0) result.addAll(relayColumns());
        }
        return List.copyOf(result);
    }

    static Map<String, IndexDefinition> expectedIndexes(String table) {
        AgentCommandTransportSchemaInitializer.TableExpectation base =
                AgentCommandTransportSchemaInitializer.expectedTables().get(table);
        if (base == null) throw invalid("unknown transport table " + table);
        Map<String, IndexDefinition> result = new TreeMap<>();
        base.indexes().forEach((name, index) -> result.put(
                name, new IndexDefinition(index.unique(), index.columns())));
        if (DELIVERY.equals(table)) {
            result.put("idx_delivery_relay_v2_due", relayIndex("next_retry_at"));
            result.put("idx_delivery_relay_v2_lease", relayIndex("lease_until"));
        } else {
            result.put("idx_outbox_relay_v2_due", relayIndex("next_retry_at"));
            result.put("idx_outbox_relay_v2_lease", relayIndex("lease_until"));
        }
        return Map.copyOf(result);
    }

    private static List<ColumnDefinition> relayColumns() {
        return List.of(
                column("protocol_version", "smallint", "smallint", null),
                column("data_domain_id", "varchar", "varchar(64)", BINARY_COLLATION),
                column("execution_pool_id", "varchar", "varchar(100)", BINARY_COLLATION),
                column("admission_epoch", "bigint", "bigint", null),
                column("producer_deployment_id", "varchar", "varchar(100)", BINARY_COLLATION),
                column("module_id", "varchar", "varchar(64)", BINARY_COLLATION),
                column("migration_epoch", "bigint", "bigint", null),
                column("claimed_deployment_id", "varchar", "varchar(100)", BINARY_COLLATION),
                column("claimed_worker_generation", "bigint", "bigint", null));
    }

    private static ColumnDefinition column(
            String name, String dataType, String columnType, String collation) {
        return new ColumnDefinition(name, dataType, columnType, true, null, collation, "", "");
    }

    private static IndexDefinition relayIndex(String dueColumn) {
        return new IndexDefinition(false, List.of(
                "tenant_id", "data_domain_id", "execution_pool_id", "protocol_version",
                "admission_epoch", "module_id", "migration_epoch", "status", dueColumn, "id"));
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Agent outbox relay v2 schema is unavailable: " + reason);
    }

    interface Catalog {
        String databaseProduct();
        List<String> presentTables();
        TableDefinition table(String table);
        List<ColumnDefinition> columns(String table);
        Map<String, IndexDefinition> indexes(String table);
        int foreignKeyCount(String table);
        Map<String, CheckDefinition> checks(String table);
    }

    record TableDefinition(String engine, String collation) {
    }

    record ColumnDefinition(String name, String dataType, String columnType, boolean nullable,
                            String defaultValue, String collation, String extra,
                            String generationExpression) {
    }

    record IndexDefinition(boolean unique, List<String> columns) {
        IndexDefinition {
            columns = List.copyOf(columns);
        }
    }

    record CheckDefinition(String clause, boolean enforced) {
    }

    private record IndexColumn(String name, boolean nonUnique, int sequence, String column,
                               Integer prefixLength, String indexType, String visible) {
    }

    private static final class JdbcCatalog implements Catalog {
        private final JdbcTemplate jdbc;

        private JdbcCatalog(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String databaseProduct() {
            return jdbc.execute((ConnectionCallback<String>) connection ->
                    connection.getMetaData().getDatabaseProductName());
        }

        @Override
        public List<String> presentTables() {
            return jdbc.queryForList("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name IN ('agent_command_delivery','agent_outbox_event')
                    ORDER BY table_name
                    """, String.class);
        }

        @Override
        public TableDefinition table(String table) {
            List<TableDefinition> rows = jdbc.query("""
                    SELECT engine,table_collation FROM information_schema.tables
                    WHERE table_schema=DATABASE() AND table_name=?
                    """, (rs, row) -> new TableDefinition(
                    rs.getString("engine"), rs.getString("table_collation")), table);
            if (rows.size() != 1) throw invalid(table + " table catalog is ambiguous");
            return rows.get(0);
        }

        @Override
        public List<ColumnDefinition> columns(String table) {
            return jdbc.query("""
                    SELECT column_name,data_type,column_type,is_nullable,column_default,
                           collation_name,extra,generation_expression
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = ?
                    ORDER BY ordinal_position
                    """, (rs, row) -> new ColumnDefinition(
                    rs.getString("column_name"), rs.getString("data_type"),
                    rs.getString("column_type"), "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                    rs.getString("column_default"), rs.getString("collation_name"),
                    rs.getString("extra"), AgentCommandTransportSchemaInitializer
                            .normalizeGeneratedExpression(rs.getString("generation_expression"))), table);
        }

        @Override
        public Map<String, IndexDefinition> indexes(String table) {
            List<IndexColumn> rows = jdbc.query("""
                    SELECT index_name,non_unique,seq_in_index,column_name,sub_part,index_type,is_visible
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = ?
                    ORDER BY index_name,seq_in_index
                    """, (rs, row) -> new IndexColumn(
                    rs.getString("index_name"), rs.getInt("non_unique") != 0,
                    rs.getInt("seq_in_index"), rs.getString("column_name"),
                    (Integer) rs.getObject("sub_part"), rs.getString("index_type"),
                    rs.getString("is_visible")), table);
            Map<String, List<IndexColumn>> grouped = new TreeMap<>();
            for (IndexColumn row : rows) {
                if (row.name() == null || row.column() == null || row.prefixLength() != null
                        || !"BTREE".equalsIgnoreCase(row.indexType())
                        || !"YES".equalsIgnoreCase(row.visible())) {
                    throw invalid(table + " has an incompatible index component");
                }
                grouped.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
            }
            Map<String, IndexDefinition> result = new TreeMap<>();
            for (Map.Entry<String, List<IndexColumn>> entry : grouped.entrySet()) {
                List<IndexColumn> components = entry.getValue();
                boolean nonUnique = components.get(0).nonUnique();
                List<String> columns = new ArrayList<>();
                for (int index = 0; index < components.size(); index++) {
                    IndexColumn component = components.get(index);
                    if (component.sequence() != index + 1 || component.nonUnique() != nonUnique) {
                        throw invalid(table + " has incompatible index ordering");
                    }
                    columns.add(component.column());
                }
                result.put(entry.getKey(), new IndexDefinition(!nonUnique, columns));
            }
            return Map.copyOf(result);
        }

        @Override
        public int foreignKeyCount(String table) {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.referential_constraints
                    WHERE (constraint_schema=DATABASE() AND table_name=?)
                       OR (unique_constraint_schema=DATABASE() AND referenced_table_name=?)
                    """, Integer.class, table, table);
            if (count == null || count < 0) throw invalid(table + " foreign-key catalog is unavailable");
            return count;
        }

        @Override
        public Map<String, CheckDefinition> checks(String table) {
            List<Map.Entry<String, CheckDefinition>> rows = jdbc.query("""
                    SELECT tc.constraint_name,cc.check_clause,tc.enforced
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.check_constraints cc
                      ON cc.constraint_schema=tc.constraint_schema
                     AND cc.constraint_name=tc.constraint_name
                    WHERE tc.table_schema=DATABASE() AND tc.table_name=?
                      AND tc.constraint_type='CHECK'
                    ORDER BY tc.constraint_name
                    """, (rs, row) -> Map.entry(rs.getString("constraint_name"),
                    new CheckDefinition(rs.getString("check_clause"),
                            "YES".equalsIgnoreCase(rs.getString("enforced")))), table);
            Map<String, CheckDefinition> result = new TreeMap<>();
            for (Map.Entry<String, CheckDefinition> row : rows) {
                if (row.getKey() == null || row.getValue().clause() == null
                        || result.put(row.getKey(), row.getValue()) != null) {
                    throw invalid(table + " has an ambiguous CHECK catalog");
                }
            }
            return Map.copyOf(result);
        }
    }
}
