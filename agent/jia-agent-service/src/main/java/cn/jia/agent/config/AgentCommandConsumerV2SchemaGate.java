package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Read-only exact MySQL catalog gate; it never loads or executes the manual migration. */
public final class AgentCommandConsumerV2SchemaGate implements InitializingBean {
    static final String INBOX = "agent_consumer_inbox";
    static final String SCOPE_CHECK = "chk_inbox_consumer_v2_scope";
    static final String ACK_EVIDENCE_CHECK = "chk_inbox_consumer_v2_ack_evidence";
    static final String PARTICIPATING_FOREIGN_KEYS_SQL = """
            SELECT COUNT(*) FROM information_schema.referential_constraints
            WHERE (constraint_schema=DATABASE() AND table_name=?)
               OR (unique_constraint_schema=DATABASE() AND referenced_table_name=?)
            """;
    private static final String BINARY_COLLATION = "utf8mb4_0900_bin";
    private static final Set<String> V2_COLUMNS = Set.of(
            "protocol_version", "data_domain_id", "execution_pool_id", "admission_epoch",
            "producer_deployment_id", "module_id", "migration_epoch",
            "claimant_deployment_id", "claimant_worker_generation",
            "claim_delivery_version", "claim_inbox_version", "claim_started_at", "ack_delivery_version",
            "ack_message_id", "ack_status", "ack_at", "ack_agent_id",
            "ack_runtime_installation_id", "ack_runtime_instance_id");

    private final Catalog catalog;

    public AgentCommandConsumerV2SchemaGate(JdbcTemplate jdbc) {
        this(new JdbcCatalog(Objects.requireNonNull(jdbc, "jdbc")));
    }

    AgentCommandConsumerV2SchemaGate(Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public void afterPropertiesSet() {
        String product = catalog.databaseProduct();
        if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
            throw invalid("MySQL is required, got " + product);
        }
        if (!new LinkedHashSet<>(catalog.presentTables()).equals(Set.of(INBOX))) {
            throw invalid("required Inbox table is missing");
        }
        TableDefinition table = catalog.table(INBOX);
        if (table == null || !"InnoDB".equalsIgnoreCase(table.engine())
                || !BINARY_COLLATION.equalsIgnoreCase(table.collation())) {
            throw invalid("Inbox must be exact InnoDB/" + BINARY_COLLATION);
        }
        List<ColumnDefinition> actualColumns = catalog.columns(INBOX);
        boolean runtimeExtended = AgentRuntimeV2PersistenceSchemaGate.expectedInboxColumns()
                .stream().map(AgentCommandConsumerV2SchemaGate::copyRuntimeColumn).toList()
                .equals(actualColumns);
        if (!expectedColumns().equals(actualColumns) && !runtimeExtended) {
            throw invalid("Inbox has incompatible base or consumer-v2 columns");
        }
        Map<String, IndexDefinition> actualIndexes = catalog.indexes(INBOX);
        Map<String, IndexDefinition> runtimeIndexes = new TreeMap<>();
        AgentRuntimeV2PersistenceSchemaGate.expectedInboxIndexes().forEach((name, index) ->
                runtimeIndexes.put(name, new IndexDefinition(index.unique(), index.columns())));
        if (!expectedIndexes().equals(actualIndexes)
                && !(runtimeExtended && Map.copyOf(runtimeIndexes).equals(actualIndexes))) {
            throw invalid("Inbox has incompatible base or consumer-v2 indexes");
        }
        if (catalog.participatingForeignKeyCount(INBOX) != 0) {
            throw invalid("Inbox must not participate in database foreign keys");
        }
        Map<String, CheckDefinition> checks = catalog.checks(INBOX);
        Set<String> expectedCheckNames = runtimeExtended
                ? Set.of(SCOPE_CHECK, ACK_EVIDENCE_CHECK, "chk_inbox_runtime_v2_projection")
                : Set.of(SCOPE_CHECK, ACK_EVIDENCE_CHECK);
        if (!checks.keySet().equals(expectedCheckNames)) {
            throw invalid("Inbox CHECK set is incompatible");
        }
        CheckDefinition scope = checks.get(SCOPE_CHECK);
        if (scope == null || !scope.enforced() || !isCanonicalScopeCheck(scope.clause())) {
            throw invalid("Inbox v2 scope CHECK is missing, unenforced or incompatible");
        }
        CheckDefinition acknowledgement = checks.get(ACK_EVIDENCE_CHECK);
        boolean ackCompatible = runtimeExtended
                ? isCanonicalRuntimeAckEvidenceCheck(acknowledgement == null ? null : acknowledgement.clause())
                : isCanonicalAckEvidenceCheck(acknowledgement == null ? null : acknowledgement.clause());
        if (acknowledgement == null || !acknowledgement.enforced() || !ackCompatible) {
            throw invalid("Inbox v2 ACK evidence CHECK is missing, unenforced or incompatible");
        }
        CheckDefinition runtimeProjection = checks.get("chk_inbox_runtime_v2_projection");
        if (runtimeExtended && (runtimeProjection == null || !runtimeProjection.enforced()
                || !AgentRuntimeV2PersistenceSchemaGate.isCanonicalRuntimeProjectionCheck(
                runtimeProjection.clause()))) {
            throw invalid("Inbox runtime-v2 projection CHECK is missing, unenforced or incompatible");
        }
    }

    static List<ColumnDefinition> expectedColumns() {
        AgentCommandTransportSchemaInitializer.TableExpectation base =
                AgentCommandTransportSchemaInitializer.expectedTables().get(INBOX);
        if (base == null) throw invalid("base Inbox schema is unavailable");
        List<ColumnDefinition> result = new ArrayList<>();
        for (int index = 0; index < base.columns().size(); index++) {
            AgentCommandTransportSchemaInitializer.ColumnDefinition column = base.columns().get(index);
            result.add(new ColumnDefinition(column.name(), column.dataType(), column.columnType(),
                    column.nullable(), column.defaultValue(), column.collation(), column.extra(),
                    column.generationExpression()));
            if (index == 0) result.addAll(v2ScopeColumns());
            if ("version".equals(column.name())) result.addAll(v2AckColumns());
        }
        return List.copyOf(result);
    }

    static Map<String, IndexDefinition> expectedIndexes() {
        AgentCommandTransportSchemaInitializer.TableExpectation base =
                AgentCommandTransportSchemaInitializer.expectedTables().get(INBOX);
        if (base == null) throw invalid("base Inbox schema is unavailable");
        Map<String, IndexDefinition> result = new TreeMap<>();
        base.indexes().forEach((name, index) -> result.put(
                name, new IndexDefinition(index.unique(), index.columns())));
        List<String> prefix = List.of("tenant_id", "data_domain_id", "execution_pool_id",
                "protocol_version", "admission_epoch", "module_id", "migration_epoch",
                "claimant_deployment_id", "claimant_worker_generation");
        List<String> identity = new ArrayList<>(prefix);
        identity.add("consumer_name");
        identity.add("message_id");
        result.put("idx_inbox_consumer_v2_identity", new IndexDefinition(false, identity));
        List<String> retry = new ArrayList<>(prefix);
        retry.add("status");
        retry.add("next_retry_at");
        retry.add("id");
        result.put("idx_inbox_consumer_v2_retry", new IndexDefinition(false, retry));
        return Map.copyOf(result);
    }

    private static List<ColumnDefinition> v2ScopeColumns() {
        return List.of(
                column("protocol_version", "smallint", "smallint", null),
                column("data_domain_id", "varchar", "varchar(64)", BINARY_COLLATION),
                column("execution_pool_id", "varchar", "varchar(100)", BINARY_COLLATION),
                column("admission_epoch", "bigint", "bigint", null),
                column("producer_deployment_id", "varchar", "varchar(100)", BINARY_COLLATION),
                column("module_id", "varchar", "varchar(64)", BINARY_COLLATION),
                column("migration_epoch", "bigint", "bigint", null),
                column("claimant_deployment_id", "varchar", "varchar(100)", BINARY_COLLATION),
                column("claimant_worker_generation", "bigint", "bigint", null));
    }

    private static List<ColumnDefinition> v2AckColumns() {
        return List.of(
                column("claim_delivery_version", "bigint", "bigint", null),
                column("claim_inbox_version", "bigint", "bigint", null),
                column("claim_started_at", "bigint", "bigint", null),
                column("ack_delivery_version", "bigint", "bigint", null),
                column("ack_message_id", "varchar", "varchar(100)", BINARY_COLLATION),
                column("ack_status", "varchar", "varchar(20)", BINARY_COLLATION),
                column("ack_at", "bigint", "bigint", null),
                column("ack_agent_id", "varchar", "varchar(100)", BINARY_COLLATION),
                column("ack_runtime_installation_id", "varchar", "varchar(100)", BINARY_COLLATION),
                column("ack_runtime_instance_id", "varchar", "varchar(100)", BINARY_COLLATION));
    }

    private static ColumnDefinition column(
            String name, String dataType, String columnType, String collation) {
        return new ColumnDefinition(name, dataType, columnType, true,
                null, collation, "", "");
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
        List<String> actual = splitBoolean(stripOuter(branch), "and");
        if (actual.size() != 10) return false;
        List<String> protocol = predicates(actual.get(0), "or");
        if (!protocol.equals(expected("protocol_version is null", "protocol_version = 1"))) {
            return false;
        }
        return predicate(actual.get(1)).equals(predicate("tenant_id = '0'"))
                && predicates(String.join(" and ", actual.subList(2, actual.size())), "and")
                .equals(expected(
                        "data_domain_id is null", "execution_pool_id is null",
                        "admission_epoch is null", "producer_deployment_id is null",
                        "module_id is null", "migration_epoch is null",
                        "claimant_deployment_id is null", "claimant_worker_generation is null"));
    }

    private static boolean v2Branch(String branch) {
        return predicates(branch, "and").equals(expected(
                "protocol_version = 2",
                "octet_length(data_domain_id) between 1 and 64",
                "execution_pool_id regexp '^[a-z0-9-]{1,100}$'",
                "admission_epoch >= 0",
                "octet_length(producer_deployment_id) between 1 and 100",
                "module_id regexp '^[a-z][a-z0-9_-]{0,63}$'",
                "migration_epoch >= 0",
                "octet_length(claimant_deployment_id) between 1 and 100",
                "claimant_worker_generation >= 0"));
    }

    static boolean isCanonicalAckEvidenceCheck(String clause) {
        try {
            List<String> branches = splitBoolean(normalize(clause), "or");
            return branches.size() == 2 && legacyAckBranch(branches.get(0))
                    && v2AckBranch(branches.get(1));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    static boolean isCanonicalRuntimeAckEvidenceCheck(String clause) {
        try {
            List<String> branches = splitBoolean(normalize(clause), "or");
            return branches.size() == 2 && legacyAckBranch(branches.get(0))
                    && v2RuntimeAckBranch(branches.get(1));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean legacyAckBranch(String branch) {
        List<String> actual = splitBoolean(stripOuter(branch), "and");
        if (actual.size() != 11) return false;
        if (!predicates(actual.get(0), "or")
                .equals(expected("protocol_version is null", "protocol_version = 1"))) {
            return false;
        }
        return predicates(String.join(" and ", actual.subList(1, actual.size())), "and")
                .equals(expected(
                        "claim_delivery_version is null", "claim_inbox_version is null",
                        "claim_started_at is null",
                        "ack_delivery_version is null", "ack_message_id is null",
                        "ack_status is null", "ack_at is null", "ack_agent_id is null",
                        "ack_runtime_installation_id is null", "ack_runtime_instance_id is null"));
    }

    private static boolean v2AckBranch(String branch) {
        List<String> actual = splitBoolean(stripOuter(branch), "and");
        if (actual.size() != 3 || !predicate(actual.get(0)).equals(predicate("protocol_version = 2"))) {
            return false;
        }
        List<String> claimBranches = splitBoolean(stripOuter(actual.get(1)), "or");
        if (claimBranches.size() != 2
                || !predicates(claimBranches.get(0), "and").equals(expected(
                        "attempt_count = 0", "active_attempt = 0", "status = 'EXPIRED'",
                        "claim_delivery_version is null", "claim_inbox_version is null",
                        "claim_started_at is null"))
                || !predicates(claimBranches.get(1), "and").equals(expected(
                        "attempt_count >= 1", "active_attempt >= 1",
                        "claim_delivery_version is not null", "claim_delivery_version >= 0",
                        "claim_inbox_version is not null", "claim_inbox_version >= 0",
                        "claim_started_at is not null", "claim_started_at > 0"))) {
            return false;
        }
        List<String> ackBranches = splitBoolean(stripOuter(actual.get(2)), "or");
        return ackBranches.size() == 2
                && predicates(ackBranches.get(0), "and").equals(expected(
                        "ack_delivery_version is null", "ack_message_id is null",
                        "ack_status is null", "ack_at is null", "ack_agent_id is null",
                        "ack_runtime_installation_id is null", "ack_runtime_instance_id is null"))
                && predicates(ackBranches.get(1), "and").equals(expected(
                        "ack_delivery_version is not null",
                        "ack_delivery_version > claim_delivery_version",
                        "ack_message_id is not null",
                        "octet_length(ack_message_id) between 1 and 100",
                        "ack_status is not null",
                        "ack_status in ('RECEIVED','STARTED','SUCCEEDED','FAILED','REJECTED')",
                        "ack_at is not null", "ack_at > 0", "ack_agent_id is not null",
                        "octet_length(ack_agent_id) between 1 and 100",
                        "ack_runtime_installation_id is not null",
                        "octet_length(ack_runtime_installation_id) between 1 and 100",
                        "ack_runtime_instance_id is not null",
                        "octet_length(ack_runtime_instance_id) between 1 and 100"));
    }

    private static boolean v2RuntimeAckBranch(String branch) {
        List<String> actual = splitBoolean(stripOuter(branch), "and");
        if (actual.size() != 4
                || !predicate(actual.get(0)).equals(predicate("protocol_version is not null"))
                || !predicate(actual.get(1)).equals(predicate("protocol_version = 2"))) {
            return false;
        }
        List<String> claimBranches = splitBoolean(stripOuter(actual.get(2)), "or");
        if (claimBranches.size() != 2
                || !predicates(claimBranches.get(0), "and").equals(expected(
                        "attempt_count = 0", "active_attempt = 0", "status = 'EXPIRED'",
                        "claim_delivery_version is null", "claim_inbox_version is null",
                        "claim_started_at is null"))
                || !predicates(claimBranches.get(1), "and").equals(expected(
                        "attempt_count >= 1", "active_attempt >= 1",
                        "claim_delivery_version is not null", "claim_delivery_version >= 0",
                        "claim_inbox_version is not null", "claim_inbox_version >= 0",
                        "claim_started_at is not null", "claim_started_at > 0"))) {
            return false;
        }
        List<String> ackBranches = splitBoolean(stripOuter(actual.get(3)), "or");
        return ackBranches.size() == 2
                && predicates(ackBranches.get(0), "and").equals(expected(
                        "ack_delivery_version is null", "ack_message_id is null",
                        "ack_status is null", "ack_at is null", "ack_agent_id is null",
                        "ack_runtime_installation_id is null", "ack_runtime_instance_id is null"))
                && predicates(ackBranches.get(1), "and").equals(expected(
                        "ack_delivery_version is not null",
                        "ack_delivery_version > claim_delivery_version",
                        "ack_message_id is not null",
                        "octet_length(ack_message_id) between 1 and 100",
                        "ack_status is not null",
                        "ack_status in ('RECEIVED','STARTED','SUCCEEDED','FAILED','REJECTED')",
                        "ack_at is not null", "ack_at > 0", "ack_agent_id is not null",
                        "octet_length(ack_agent_id) between 1 and 100",
                        "ack_runtime_installation_id is null or octet_length(ack_runtime_installation_id) between 1 and 100",
                        "ack_runtime_instance_id is not null",
                        "octet_length(ack_runtime_instance_id) between 1 and 100"));
    }

    private static ColumnDefinition copyRuntimeColumn(
            AgentRuntimeV2PersistenceSchemaGate.ColumnDefinition column) {
        return new ColumnDefinition(column.name(), column.dataType(), column.columnType(),
                column.nullable(), column.defaultValue(), column.collation(), column.extra(),
                column.generationExpression());
    }

    private static List<String> predicates(String expression, String operator) {
        return splitBoolean(stripOuter(expression), operator).stream()
                .map(AgentCommandConsumerV2SchemaGate::predicate).toList();
    }

    private static List<String> expected(String... predicates) {
        return java.util.Arrays.stream(predicates).map(AgentCommandConsumerV2SchemaGate::predicate).toList();
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

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Agent consumer v2 schema unavailable: " + reason);
    }

    interface Catalog {
        String databaseProduct();
        List<String> presentTables();
        TableDefinition table(String table);
        List<ColumnDefinition> columns(String table);
        Map<String, IndexDefinition> indexes(String table);
        int participatingForeignKeyCount(String table);
        Map<String, CheckDefinition> checks(String table);
    }

    record TableDefinition(String engine, String collation) {}

    record ColumnDefinition(String name, String dataType, String columnType, boolean nullable,
                            String defaultValue, String collation, String extra,
                            String generationExpression) {}

    record IndexDefinition(boolean unique, List<String> columns) {
        IndexDefinition {
            columns = List.copyOf(columns);
        }
    }

    record CheckDefinition(String clause, boolean enforced) {}

    record IndexColumn(String name, boolean nonUnique, int sequence, String column,
                       Integer prefixLength, String indexType, String visible) {}

    static Map<String, IndexDefinition> indexDefinitions(List<IndexColumn> rows) {
        Map<String, List<IndexColumn>> grouped = new TreeMap<>();
        for (IndexColumn row : rows) {
            if (row.prefixLength() != null || !"BTREE".equalsIgnoreCase(row.indexType())
                    || !"YES".equalsIgnoreCase(row.visible())) {
                throw invalid("Inbox index uses prefix, non-BTREE, or invisible columns");
            }
            grouped.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, IndexDefinition> result = new TreeMap<>();
        grouped.forEach((name, values) -> {
            boolean nonUnique = values.getFirst().nonUnique();
            for (int index = 0; index < values.size(); index++) {
                IndexColumn value = values.get(index);
                if (value.sequence() != index + 1) {
                    throw invalid("Inbox index sequence is incompatible");
                }
                if (value.nonUnique() != nonUnique) {
                    throw invalid("Inbox index uniqueness is inconsistent");
                }
            }
            result.put(name, new IndexDefinition(!nonUnique,
                    values.stream().map(IndexColumn::column).toList()));
        });
        return Map.copyOf(result);
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
                    WHERE table_schema=DATABASE() AND table_name='agent_consumer_inbox'
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
            return rows.size() == 1 ? rows.getFirst() : null;
        }

        @Override
        public List<ColumnDefinition> columns(String table) {
            return jdbc.query("""
                    SELECT column_name,data_type,column_type,is_nullable,column_default,
                           collation_name,extra,generation_expression
                    FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name=?
                    ORDER BY ordinal_position
                    """, (rs, row) -> new ColumnDefinition(
                    rs.getString("column_name"), rs.getString("data_type"),
                    rs.getString("column_type"), "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                    rs.getString("column_default"), rs.getString("collation_name"),
                    Objects.toString(rs.getString("extra"), ""),
                    Objects.toString(rs.getString("generation_expression"), "")), table);
        }

        @Override
        public Map<String, IndexDefinition> indexes(String table) {
            List<IndexColumn> rows = jdbc.query("""
                    SELECT index_name,non_unique,seq_in_index,column_name,sub_part,index_type,is_visible
                    FROM information_schema.statistics
                    WHERE table_schema=DATABASE() AND table_name=?
                    ORDER BY index_name,seq_in_index
                    """, (rs, row) -> new IndexColumn(
                    rs.getString("index_name"), rs.getBoolean("non_unique"),
                    rs.getInt("seq_in_index"), rs.getString("column_name"),
                    (Integer) rs.getObject("sub_part"), rs.getString("index_type"),
                    rs.getString("is_visible")), table);
            return indexDefinitions(rows);
        }

        @Override
        public int participatingForeignKeyCount(String table) {
            Integer count = jdbc.queryForObject(
                    PARTICIPATING_FOREIGN_KEYS_SQL, Integer.class, table, table);
            if (count == null || count < 0) {
                throw invalid("Inbox foreign-key catalog is unavailable");
            }
            return count;
        }

        @Override
        public Map<String, CheckDefinition> checks(String table) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT tc.constraint_name,cc.check_clause,tc.enforced
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.check_constraints cc
                      ON cc.constraint_schema=tc.constraint_schema
                     AND cc.constraint_name=tc.constraint_name
                    WHERE tc.table_schema=DATABASE() AND tc.table_name=?
                      AND tc.constraint_type='CHECK'
                    ORDER BY tc.constraint_name
                    """, table);
            Map<String, CheckDefinition> result = new TreeMap<>();
            for (Map<String, Object> row : rows) {
                String name = Objects.toString(row.get("constraint_name"), null);
                if (name == null || result.put(name, new CheckDefinition(
                        Objects.toString(row.get("check_clause"), ""),
                        "YES".equalsIgnoreCase(Objects.toString(row.get("enforced"), "")))) != null) {
                    throw invalid("Inbox CHECK metadata is ambiguous");
                }
            }
            return Map.copyOf(result);
        }
    }
}
