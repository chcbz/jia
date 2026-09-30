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

/** Read-only exact catalog gate for the additive 2026-09-30 runtime-v2 persistence slice. */
public final class AgentRuntimeV2PersistenceSchemaGate implements InitializingBean {
    static final String BINDING = "agent_runtime_v2_binding";
    static final String GENERATION_FACT = "agent_runtime_v2_binding_generation_fact";
    static final String RECEIPT = "agent_command_v2_ack_receipt";
    static final String LEASE_FACT = "agent_command_v2_execution_lease_fact";
    static final String GRANT = "agent_command_v2_execution_grant";
    static final String ANCHOR = "agent_command_v2_execution_continuation_anchor";
    static final String REQUEST = "agent_command_v2_execution_request";
    static final String INBOX = "agent_consumer_inbox";
    static final Set<String> TABLES = Set.of(INBOX, BINDING, GENERATION_FACT, RECEIPT, LEASE_FACT,
            GRANT, ANCHOR, REQUEST);
    private static final String BIN = "utf8mb4_0900_bin";
    private final Catalog catalog;

    public AgentRuntimeV2PersistenceSchemaGate(JdbcTemplate jdbc) {
        this(new JdbcCatalog(Objects.requireNonNull(jdbc, "jdbc")));
    }
    AgentRuntimeV2PersistenceSchemaGate(Catalog catalog) { this.catalog = Objects.requireNonNull(catalog); }

    @Override public void afterPropertiesSet() {
        String product = catalog.databaseProduct();
        if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
            throw invalid("MySQL is required, got " + product);
        }
        if (!new LinkedHashSet<>(catalog.presentTables()).equals(TABLES)) {
            throw invalid("required runtime-v2 tables are missing or ambiguous");
        }
        List<ColumnDefinition> bindingColumns = catalog.columns(BINDING);
        boolean registrationExtended;
        if (expectedColumns().get(BINDING).equals(bindingColumns)) {
            registrationExtended = false;
        } else if (registrationExtendedColumns().get(BINDING).equals(bindingColumns)) {
            registrationExtended = true;
        } else {
            throw invalid(BINDING + " column contract drift");
        }
        for (String table : List.of(INBOX, BINDING, GENERATION_FACT, RECEIPT, LEASE_FACT,
                GRANT, ANCHOR, REQUEST)) {
            TableDefinition definition = catalog.table(table);
            if (definition == null || !"InnoDB".equalsIgnoreCase(definition.engine())
                    || !BIN.equalsIgnoreCase(definition.collation())) {
                throw invalid(table + " must be exact InnoDB/" + BIN);
            }
            List<ColumnDefinition> actualColumns = BINDING.equals(table)
                    ? bindingColumns : catalog.columns(table);
            Map<String, List<ColumnDefinition>> columnContract = registrationExtended
                    ? registrationExtendedColumns() : expectedColumns();
            if (!columnContract.get(table).equals(actualColumns)) {
                throw invalid(table + " column contract drift");
            }
            Map<String, Map<String, IndexDefinition>> indexContract = registrationExtended
                    ? registrationExtendedIndexes() : expectedIndexes();
            if (!indexContract.get(table).equals(catalog.indexes(table))) {
                throw invalid(table + " index contract drift");
            }
            if (catalog.participatingForeignKeyCount(table) != 0) {
                throw invalid(table + " must not participate in foreign keys");
            }
            validateChecks(table, catalog.checks(table), registrationExtended);
        }
    }

    static List<ColumnDefinition> expectedInboxColumns() {
        List<ColumnDefinition> result = new ArrayList<>();
        for (AgentCommandConsumerV2SchemaGate.ColumnDefinition column
                : AgentCommandConsumerV2SchemaGate.expectedColumns()) {
            result.add(copy(column));
            if ("ack_runtime_instance_id".equals(column.name())) {
                result.addAll(runtimeInboxColumns());
            }
        }
        return List.copyOf(result);
    }

    static Map<String, IndexDefinition> expectedInboxIndexes() {
        Map<String, IndexDefinition> result = new TreeMap<>();
        AgentCommandConsumerV2SchemaGate.expectedIndexes().forEach((name, value) ->
                result.put(name, new IndexDefinition(value.unique(), value.columns())));
        result.put("uk_inbox_runtime_dispatch_id", index(true, "runtime_dispatch_id"));
        result.put("idx_inbox_runtime_binding_dispatch", index(false, "runtime_binding_id",
                "runtime_binding_generation", "dispatch_runtime_instance_id", "runtime_dispatch_id"));
        return Map.copyOf(result);
    }

    static Map<String, List<ColumnDefinition>> expectedColumns() {
        Map<String, List<ColumnDefinition>> result = new LinkedHashMap<>();
        result.put(INBOX, expectedInboxColumns());
        result.put(BINDING, columns("""
                id|bigint|bigint|N|-|NULL|auto_increment
                runtime_binding_id|varchar|varchar(36)|N|B|NULL|-
                credential_kind|varchar|varchar(32)|N|B|NULL|-
                credential_reference|varchar|varchar(200)|N|B|NULL|-
                runtime_installation_id|varchar|varchar(100)|Y|B|NULL|-
                contract_version|smallint|smallint|N|-|NULL|-
                reconnect_verifier|binary|binary(32)|Y|-|NULL|-
                data_domain_id|varchar|varchar(64)|N|B|NULL|-
                execution_pool_id|varchar|varchar(100)|N|B|NULL|-
                owner_jiacn|varchar|varchar(100)|N|B|NULL|-
                agent_id|varchar|varchar(100)|N|B|NULL|-
                producer_deployment_id|varchar|varchar(100)|Y|B|NULL|-
                claimant_deployment_id|varchar|varchar(100)|N|B|NULL|-
                claimant_worker_generation|bigint|bigint|N|-|NULL|-
                receiver_registration_revision|bigint|bigint|N|-|NULL|-
                admission_epoch|bigint|bigint|N|-|NULL|-
                module_id|varchar|varchar(64)|N|B|NULL|-
                migration_epoch|bigint|bigint|N|-|NULL|-
                runtime_instance_id|varchar|varchar(100)|N|B|NULL|-
                binding_generation|bigint|bigint|N|-|NULL|-
                status|varchar|varchar(20)|N|B|NULL|-
                connected_at|bigint|bigint|N|-|NULL|-
                disconnected_at|bigint|bigint|Y|-|NULL|-
                revoked_at|bigint|bigint|Y|-|NULL|-
                revoke_reason|varchar|varchar(500)|Y|B|NULL|-
                socket_owner_instance_id|varchar|varchar(100)|Y|B|NULL|-
                socket_owner_epoch|bigint|bigint|Y|-|NULL|-
                socket_owner_lease_until|bigint|bigint|Y|-|NULL|-
                current_session_fence|varchar|varchar(100)|Y|B|NULL|-
                version|bigint|bigint|N|-|0|-
                tenant_id|varchar|varchar(50)|N|B|NULL|-
                client_id|varchar|varchar(50)|N|B|NULL|-
                create_time|bigint|bigint|N|-|NULL|-
                update_time|bigint|bigint|N|-|NULL|-
                """));
        result.put(GENERATION_FACT, generationFactColumns());
        result.put(RECEIPT, receiptColumns());
        result.put(LEASE_FACT, leaseColumns());
        result.put(GRANT, grantColumns());
        result.put(ANCHOR, anchorColumns());
        result.put(REQUEST, requestColumns());
        return Map.copyOf(result);
    }

    static Map<String, Map<String, IndexDefinition>> expectedIndexes() {
        Map<String, Map<String, IndexDefinition>> result = new LinkedHashMap<>();
        result.put(INBOX, expectedInboxIndexes());
        result.put(BINDING, indexes(
                entry("PRIMARY", true, "id"), entry("uk_runtime_v2_binding_id", true, "runtime_binding_id"),
                entry("idx_runtime_v2_binding_scope", false, "tenant_id","data_domain_id","execution_pool_id","client_id","owner_jiacn","agent_id","status","runtime_binding_id"),
                entry("idx_runtime_v2_binding_credential", false, "credential_kind","credential_reference","status"),
                entry("idx_runtime_v2_binding_route", false, "socket_owner_instance_id","socket_owner_epoch","socket_owner_lease_until","runtime_binding_id")));
        result.put(GENERATION_FACT, indexes(
                entry("PRIMARY", true, "id"), entry("uk_binding_generation_fact_id", true, "binding_generation_fact_id"),
                entry("uk_binding_generation_fact_sequence", true, "runtime_binding_id","fact_sequence"),
                entry("uk_binding_generation_fact_event", true, "runtime_binding_id","binding_generation","event_type"),
                entry("uk_binding_generation_fact_predecessor", true, "previous_fact_id"),
                entry("uk_binding_generation_fact_revoke", true, "revoked_runtime_binding_id"),
                entry("idx_binding_generation_fact_latest", false, "runtime_binding_id","fact_sequence")));
        result.put(RECEIPT, indexes(
                entry("PRIMARY", true, "id"), entry("uk_ack_receipt_id", true, "receipt_id"),
                entry("uk_ack_receipt_ack_message", true, "ack_message_id"),
                entry("uk_ack_receipt_dispatch_status", true, "runtime_dispatch_id","status"),
                entry("idx_ack_receipt_dispatch_binding", false, "runtime_dispatch_id","runtime_binding_id"),
                entry("idx_ack_receipt_binding_instance_commit", false, "runtime_binding_id","runtime_instance_id","committed_at")));
        result.put(LEASE_FACT, indexes(
                entry("PRIMARY", true, "id"), entry("uk_execution_lease_fact_id", true, "lease_fact_id"),
                entry("uk_execution_lease_fact_sequence", true, "runtime_dispatch_id","fact_sequence"),
                entry("uk_execution_lease_fact_revision_type", true, "runtime_dispatch_id","holder_binding_generation","lease_revision","fact_type"),
                entry("idx_execution_lease_fact_latest", false, "runtime_dispatch_id","fact_sequence"),
                entry("idx_execution_lease_fact_binding", false, "runtime_binding_id","holder_binding_generation","runtime_instance_id","fact_sequence"),
                entry("idx_execution_lease_fact_drainproof", false, "tenant_id","module_id","claimant_deployment_id","fact_type","effective_at","runtime_dispatch_id"),
                entry("idx_execution_lease_fact_pool_drain", false, "tenant_id","data_domain_id","execution_pool_id","migration_epoch","fact_type","effective_at")));
        result.put(GRANT, indexes(entry("PRIMARY", true, "id"),
                entry("uk_execution_grant_id", true, "execution_grant_id"),
                entry("uk_execution_grant_revision_kind", true, "runtime_dispatch_id","holder_binding_generation","lease_revision","grant_kind"),
                entry("idx_execution_grant_started", false, "runtime_dispatch_id","started_receipt_id","issued_at")));
        result.put(ANCHOR, indexes(entry("PRIMARY", true, "id"),
                entry("uk_execution_continuation_anchor", true, "runtime_dispatch_id","started_receipt_id","holder_binding_generation"),
                entry("uk_execution_continuation_grant", true, "execution_grant_id")));
        result.put(REQUEST, indexes(entry("PRIMARY", true, "id"),
                entry("uk_execution_request_id", true, "execution_request_id"),
                entry("idx_execution_request_anchor", false, "runtime_dispatch_id","started_receipt_id","requested_holder_binding_generation")));
        return Map.copyOf(result);
    }

    static Map<String, List<ColumnDefinition>> registrationExtendedColumns() {
        Map<String, List<ColumnDefinition>> result = new LinkedHashMap<>(expectedColumns());
        Map<String, List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition>> additions =
                AgentRuntimeV2RegistrationSchemaGate.expectedColumns();
        result.put(BINDING, insertAfter(result.get(BINDING), "contract_version", additions.get(BINDING)));
        result.put(GENERATION_FACT, insertAfter(
                insertAfter(result.get(GENERATION_FACT), "runtime_instance_id",
                        additions.get(GENERATION_FACT).subList(0, 4)),
                "migration_epoch", additions.get(GENERATION_FACT).subList(4, 11)));
        result.put(INBOX, insertAfter(result.get(INBOX), "dispatch_receiver_registration_revision", additions.get(INBOX)));
        result.put(RECEIPT, insertAfter(result.get(RECEIPT), "committed_at", additions.get(RECEIPT)));
        result.put(LEASE_FACT, insertAfter(result.get(LEASE_FACT), "created_at", additions.get(LEASE_FACT)));
        result.put(GRANT, insertAfter(result.get(GRANT), "migration_epoch", additions.get(GRANT)));
        result.put(ANCHOR, insertAfter(result.get(ANCHOR), "migration_epoch", additions.get(ANCHOR)));
        result.put(REQUEST, insertAfter(result.get(REQUEST), "migration_epoch", additions.get(REQUEST)));
        return Map.copyOf(result);
    }

    static Map<String, Map<String, IndexDefinition>> registrationExtendedIndexes() {
        Map<String, Map<String, IndexDefinition>> result = new LinkedHashMap<>();
        Map<String, Map<String, AgentRuntimeV2RegistrationSchemaGate.IndexDefinition>> additions =
                AgentRuntimeV2RegistrationSchemaGate.expectedIndexes();
        expectedIndexes().forEach((table, baseline) -> {
            Map<String, IndexDefinition> combined = new TreeMap<>(baseline);
            additions.get(table).forEach((name, index) ->
                    combined.put(name, new IndexDefinition(index.unique(), index.columns())));
            result.put(table, Map.copyOf(combined));
        });
        return Map.copyOf(result);
    }

    private static List<ColumnDefinition> insertAfter(List<ColumnDefinition> baseline, String anchor,
            List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition> additions) {
        List<ColumnDefinition> result = new ArrayList<>();
        boolean inserted = false;
        for (ColumnDefinition column : baseline) {
            result.add(column);
            if (anchor.equals(column.name())) {
                additions.forEach(value -> result.add(new ColumnDefinition(value.name(), value.dataType(),
                        value.columnType(), value.nullable(), value.defaultValue(), value.collation(),
                        value.extra(), value.generationExpression())));
                inserted = true;
            }
        }
        if (!inserted) throw invalid("registration extension anchor is missing: " + anchor);
        return List.copyOf(result);
    }

    private static Map<String, String> registrationCheckClauses() {
        Map<String, String> result = new LinkedHashMap<>(expectedCheckClauses());
        AgentRuntimeV2RegistrationSchemaGate.expectedChecks().forEach((table, checks) -> {
            if (TABLES.contains(table)) result.putAll(checks);
        });
        return Map.copyOf(result);
    }
    private static void validateChecks(String table, Map<String, CheckDefinition> checks, boolean registrationExtended) {
        Set<String> expected = switch (table) {
            case INBOX -> Set.of(AgentCommandConsumerV2SchemaGate.SCOPE_CHECK,
                    AgentCommandConsumerV2SchemaGate.ACK_EVIDENCE_CHECK,
                    "chk_inbox_runtime_v2_projection");
            case BINDING -> Set.of("chk_runtime_v2_binding_canonical",
                    "chk_runtime_v2_binding_state", "chk_runtime_v2_binding_route");
            case GENERATION_FACT -> Set.of("chk_binding_generation_fact_canonical");
            case RECEIPT -> Set.of("chk_ack_receipt_canonical");
            case LEASE_FACT -> Set.of("chk_execution_lease_fact_canonical");
            case GRANT -> Set.of("chk_execution_grant_canonical");
            case ANCHOR -> Set.of("chk_execution_continuation_anchor");
            case REQUEST -> Set.of("chk_execution_request_canonical");
            default -> throw invalid("unknown table " + table);
        };
        if (registrationExtended) {
            Set<String> combined = new java.util.HashSet<>(expected);
            combined.addAll(AgentRuntimeV2RegistrationSchemaGate.expectedChecks().get(table).keySet());
            expected = Set.copyOf(combined);
        }
        if (!checks.keySet().equals(expected) || checks.values().stream().anyMatch(c -> !c.enforced())) {
            throw invalid(table + " CHECK set is missing, extra, or unenforced");
        }
        if (INBOX.equals(table)) {
            if (!AgentCommandConsumerV2SchemaGate.isCanonicalScopeCheck(
                    checks.get(AgentCommandConsumerV2SchemaGate.SCOPE_CHECK).clause())
                    || !AgentCommandConsumerV2SchemaGate.isCanonicalRuntimeAckEvidenceCheck(
                    checks.get(AgentCommandConsumerV2SchemaGate.ACK_EVIDENCE_CHECK).clause())) {
                throw invalid("Inbox consumer checks are not the runtime-compatible contract");
            }
        }
        for (Map.Entry<String, CheckDefinition> entry : checks.entrySet()) {
            String expectedClause = registrationExtended
                    ? registrationCheckClauses().get(entry.getKey())
                    : expectedCheckClauses().get(entry.getKey());
            if (expectedClause != null && !canonicalCheck(expectedClause)
                    .equals(canonicalCheck(entry.getValue().clause()))) {
                throw invalid(table + " CHECK " + entry.getKey() + " is weakened or noncanonical");
            }
        }
    }

    static Map<String, String> expectedCheckClauses() {
        Map<String, String> checks = new LinkedHashMap<>(Map.of(
                "chk_inbox_runtime_v2_projection", """
                        (runtime_dispatch_id IS NULL
                         AND runtime_binding_id IS NULL AND runtime_binding_generation IS NULL
                         AND dispatch_runtime_instance_id IS NULL AND dispatch_prepared_at IS NULL
                         AND execution_started_at IS NULL AND execution_lease_until IS NULL
                         AND execution_lease_revision IS NULL
                         AND ack_runtime_binding_id IS NULL AND ack_runtime_binding_generation IS NULL
                         AND ack_committed_at IS NULL)
                        OR
                        (protocol_version = 2
                         AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                         AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                         AND runtime_binding_generation >= 0
                         AND OCTET_LENGTH(dispatch_runtime_instance_id) BETWEEN 1 AND 100
                         AND dispatch_prepared_at > 0
                         AND ((execution_started_at IS NULL AND execution_lease_until IS NULL
                               AND execution_lease_revision IS NULL)
                              OR (execution_started_at IS NOT NULL AND execution_started_at >= dispatch_prepared_at
                                  AND execution_lease_until IS NOT NULL
                                  AND execution_lease_until > execution_started_at
                                  AND execution_lease_revision IS NOT NULL AND execution_lease_revision >= 0))
                         AND ((ack_message_id IS NULL AND ack_runtime_binding_id IS NULL
                               AND ack_runtime_binding_generation IS NULL AND ack_committed_at IS NULL)
                              OR (ack_message_id IS NOT NULL
                                  AND ack_runtime_binding_id = runtime_binding_id
                                  AND CAST(ack_runtime_binding_id AS BINARY) = CAST(runtime_binding_id AS BINARY)
                                  AND OCTET_LENGTH(ack_runtime_binding_id) = OCTET_LENGTH(runtime_binding_id)
                                  AND ack_runtime_binding_generation = runtime_binding_generation
                                  AND ack_committed_at IS NOT NULL AND ack_committed_at >= ack_at)))
                        """,
                "chk_runtime_v2_binding_canonical", """
                        runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND credential_kind IN ('API_KEY','RUNTIME_V1_INSTALLATION','DEPLOYMENT_CREDENTIAL')
                        AND OCTET_LENGTH(credential_reference) BETWEEN 1 AND 200
                        AND ((credential_kind = 'RUNTIME_V1_INSTALLATION'
                              AND runtime_installation_id IS NOT NULL
                              AND runtime_installation_id = credential_reference
                              AND CAST(runtime_installation_id AS BINARY) = CAST(credential_reference AS BINARY)
                              AND OCTET_LENGTH(runtime_installation_id) = OCTET_LENGTH(credential_reference))
                             OR (credential_kind <> 'RUNTIME_V1_INSTALLATION' AND runtime_installation_id IS NULL))
                        AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
                        AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
                        AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
                        AND (producer_deployment_id IS NULL OR OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100)
                        AND contract_version IN (1,2)
                        AND ((contract_version=1 AND reconnect_verifier IS NULL AND current_session_fence IS NULL)
                             OR (contract_version=2 AND reconnect_verifier IS NOT NULL))
                        AND receiver_registration_revision >= 0
                        AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
                        AND claimant_worker_generation >= 0 AND admission_epoch >= 0
                        AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$' AND migration_epoch >= 0
                        AND OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100
                        AND binding_generation >= 0 AND version >= 0
                        """,
                "chk_runtime_v2_binding_state", """
                        (status = 'ACTIVE' AND disconnected_at IS NULL AND revoked_at IS NULL AND revoke_reason IS NULL)
                        OR (status = 'DISCONNECTED' AND disconnected_at IS NOT NULL
                            AND disconnected_at >= connected_at AND revoked_at IS NULL AND revoke_reason IS NULL)
                        OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_at >= connected_at
                            AND OCTET_LENGTH(revoke_reason) BETWEEN 1 AND 500)
                        """,
                "chk_runtime_v2_binding_route", """
                        (socket_owner_instance_id IS NULL AND socket_owner_epoch IS NULL
                         AND socket_owner_lease_until IS NULL)
                        OR (status = 'ACTIVE' AND OCTET_LENGTH(socket_owner_instance_id) BETWEEN 1 AND 100
                            AND socket_owner_epoch IS NOT NULL AND socket_owner_epoch >= 0
                            AND socket_owner_lease_until IS NOT NULL AND socket_owner_lease_until > connected_at)
                        """,
                "chk_binding_generation_fact_canonical", """
                        binding_generation_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND event_type IN ('CONNECTED','DISCONNECTED','REVOKED')
                        AND fact_sequence >= 0 AND binding_generation >= 0
                        AND ((fact_sequence=0 AND previous_fact_id IS NULL AND event_type='CONNECTED' AND binding_generation=0)
                             OR (fact_sequence>0 AND previous_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'))
                        AND claimant_worker_generation >= 0 AND receiver_registration_revision >= 0
                        AND admission_epoch >= 0 AND migration_epoch >= 0 AND socket_owner_epoch >= 0
                        AND socket_owner_lease_until > 0
                        AND effective_at > 0
                        AND ((event_type='REVOKED' AND OCTET_LENGTH(reason) BETWEEN 1 AND 500)
                             OR (event_type<>'REVOKED' AND reason IS NULL))
                        """,
                "chk_ack_receipt_canonical", """
                        receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND inbox_id > 0 AND delivery_id > 0
                        AND OCTET_LENGTH(ack_message_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(source_message_id) BETWEEN 1 AND 100
                        AND status IN ('RECEIVED','STARTED','SUCCEEDED','FAILED','REJECTED')
                        AND ack_at > 0 AND runtime_binding_generation >= 0
                        AND OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100
                        AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
                        AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
                        AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
                        AND claimant_worker_generation >= 0 AND admission_epoch >= 0
                        AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$' AND migration_epoch >= 0
                        AND postcommit_delivery_version >= 0 AND postcommit_inbox_version >= 0
                        AND (lease_fact_id IS NULL
                             OR lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
                        AND contract_version = 1 AND committed_at >= ack_at
                        """,
                "chk_execution_lease_fact_canonical", """
                        lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND inbox_id > 0 AND delivery_id > 0 AND fact_sequence >= 0 AND lease_revision >= 0
                        AND fact_type IN ('START_GRANTED','RENEWED','REVOKED','EXPIRED','SETTLED')
                        AND ((fact_sequence = 0 AND previous_lease_fact_id IS NULL)
                             OR (fact_sequence > 0
                                 AND previous_lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'))
                        AND effective_at > 0
                        AND ((fact_type IN ('START_GRANTED','RENEWED') AND lease_until > effective_at)
                             OR (fact_type IN ('REVOKED','EXPIRED','SETTLED') AND lease_until IS NULL))
                        AND (causing_receipt_id IS NULL
                             OR causing_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
                        AND runtime_binding_generation >= 0
                        AND OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100
                        AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
                        AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
                        AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
                        AND claimant_worker_generation >= 0 AND admission_epoch >= 0
                        AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$' AND migration_epoch >= 0
                        AND tenant_control_revision >= 0 AND module_mode_revision >= 0
                        AND deployment_registration_revision >= 0
                        AND (tenant_control_operation_id IS NULL
                             OR OCTET_LENGTH(tenant_control_operation_id) BETWEEN 1 AND 100)
                        AND (module_control_operation_id IS NULL
                             OR OCTET_LENGTH(module_control_operation_id) BETWEEN 1 AND 100)
                        AND (deployment_control_operation_id IS NULL
                             OR OCTET_LENGTH(deployment_control_operation_id) BETWEEN 1 AND 100)
                        AND OCTET_LENGTH(reason) BETWEEN 1 AND 500 AND created_at >= effective_at
                        """));
        checks.putAll(r2a2CheckClauses());
        return Map.copyOf(checks);
    }

    private static Map<String, String> r2a2CheckClauses() {
        return Map.of(
                "chk_inbox_runtime_v2_projection", """
                        (runtime_dispatch_id IS NULL
                         AND runtime_binding_id IS NULL AND runtime_binding_generation IS NULL
                         AND dispatch_runtime_instance_id IS NULL AND dispatch_runtime_credential_client_id IS NULL
                         AND dispatch_receiver_registration_revision IS NULL AND dispatch_prepared_at IS NULL
                         AND execution_started_at IS NULL AND execution_lease_until IS NULL
                         AND execution_lease_revision IS NULL
                         AND execution_holder_binding_id IS NULL AND execution_holder_binding_generation IS NULL
                         AND execution_holder_runtime_instance_id IS NULL AND execution_current_lease_fact_id IS NULL
                         AND ack_runtime_binding_id IS NULL AND ack_runtime_binding_generation IS NULL
                         AND ack_committed_at IS NULL)
                        OR
                        (protocol_version IS NOT NULL AND protocol_version = 2
                         AND runtime_dispatch_id IS NOT NULL
                         AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                         AND runtime_binding_id IS NOT NULL
                         AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                         AND runtime_binding_generation IS NOT NULL AND runtime_binding_generation >= 0
                         AND dispatch_runtime_instance_id IS NOT NULL
                         AND OCTET_LENGTH(dispatch_runtime_instance_id) BETWEEN 1 AND 100
                         AND dispatch_runtime_credential_client_id IS NOT NULL
                         AND OCTET_LENGTH(dispatch_runtime_credential_client_id) BETWEEN 1 AND 50
                         AND dispatch_receiver_registration_revision IS NOT NULL
                         AND dispatch_receiver_registration_revision >= 0
                         AND dispatch_prepared_at IS NOT NULL AND dispatch_prepared_at > 0
                         AND ((execution_started_at IS NULL AND execution_lease_until IS NULL
                               AND execution_lease_revision IS NULL AND execution_holder_binding_id IS NULL
                               AND execution_holder_binding_generation IS NULL
                               AND execution_holder_runtime_instance_id IS NULL
                               AND execution_current_lease_fact_id IS NULL)
                              OR (execution_started_at IS NOT NULL AND execution_started_at >= dispatch_prepared_at
                                  AND execution_lease_until IS NOT NULL
                                  AND execution_lease_until > execution_started_at
                                  AND execution_lease_revision IS NOT NULL AND execution_lease_revision >= 0
                                  AND execution_holder_binding_id IS NOT NULL
                                  AND execution_holder_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                                  AND execution_holder_binding_generation IS NOT NULL
                                  AND execution_holder_binding_generation >= 0
                                  AND execution_holder_runtime_instance_id IS NOT NULL
                                  AND OCTET_LENGTH(execution_holder_runtime_instance_id) BETWEEN 1 AND 100
                                  AND execution_current_lease_fact_id IS NOT NULL
                                  AND execution_current_lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'))
                         AND ((ack_message_id IS NULL AND ack_runtime_binding_id IS NULL
                               AND ack_runtime_binding_generation IS NULL AND ack_committed_at IS NULL)
                              OR (ack_message_id IS NOT NULL
                                  AND ack_runtime_binding_id IS NOT NULL
                                  AND ack_runtime_binding_id = runtime_binding_id
                                  AND CAST(ack_runtime_binding_id AS BINARY) = CAST(runtime_binding_id AS BINARY)
                                  AND OCTET_LENGTH(ack_runtime_binding_id) = OCTET_LENGTH(runtime_binding_id)
                                  AND ack_runtime_binding_generation IS NOT NULL
                                  AND ((ack_status IN ('RECEIVED','STARTED')
                                        AND ack_runtime_binding_generation = runtime_binding_generation)
                                       OR (ack_status='REJECTED' AND execution_holder_binding_generation IS NULL
                                           AND ack_runtime_binding_generation = runtime_binding_generation)
                                       OR (ack_status IN ('SUCCEEDED','FAILED','REJECTED')
                                           AND execution_holder_binding_generation IS NOT NULL
                                           AND ack_runtime_binding_generation = execution_holder_binding_generation))
                                  AND ack_committed_at IS NOT NULL AND ack_committed_at >= ack_at)))
                        """,
                "chk_ack_receipt_canonical", """
                        receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND inbox_id > 0 AND delivery_id > 0
                        AND OCTET_LENGTH(ack_message_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(source_message_id) BETWEEN 1 AND 100
                        AND status IN ('RECEIVED','STARTED','SUCCEEDED','FAILED','REJECTED')
                        AND ack_at > 0 AND dispatch_binding_generation >= 0
                        AND OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100
                        AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
                        AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
                        AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
                        AND claimant_worker_generation >= 0 AND admission_epoch >= 0
                        AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$' AND migration_epoch >= 0
                        AND postcommit_delivery_version >= 0 AND postcommit_inbox_version >= 0
                        AND (lease_fact_id IS NULL
                             OR lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
                        AND contract_version IN (1,2) AND committed_at >= ack_at
                        AND ((contract_version=1
                              AND ack_holder_binding_generation IS NULL
                              AND runtime_credential_client_id IS NULL
                              AND receiver_registration_revision IS NULL
                              AND receipt_wire_bytes IS NULL AND receipt_wire_sha256 IS NULL
                              AND receipt_wire_contract_version IS NULL)
                             OR (contract_version=2
                              AND ack_holder_binding_generation IS NOT NULL AND ack_holder_binding_generation >= 0
                              AND runtime_credential_client_id IS NOT NULL
                              AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50
                              AND receiver_registration_revision IS NOT NULL AND receiver_registration_revision >= 0
                              AND receipt_wire_bytes IS NOT NULL AND OCTET_LENGTH(receipt_wire_bytes) BETWEEN 1 AND 4096
                              AND receipt_wire_sha256 IS NOT NULL AND OCTET_LENGTH(receipt_wire_sha256)=32
                              AND receipt_wire_contract_version IS NOT NULL AND receipt_wire_contract_version=2
                              AND ((status='RECEIVED' AND lease_fact_id IS NULL)
                                   OR (status='REJECTED' AND lease_fact_id IS NULL
                                       AND ack_holder_binding_generation=dispatch_binding_generation)
                                   OR (status='STARTED' AND lease_fact_id IS NOT NULL
                                       AND ack_holder_binding_generation=dispatch_binding_generation)
                                   OR (status IN ('SUCCEEDED','FAILED','REJECTED') AND lease_fact_id IS NOT NULL))))
                        """,
                "chk_execution_lease_fact_canonical", """
                        lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND inbox_id > 0 AND delivery_id > 0 AND fact_sequence >= 0 AND lease_revision >= 0
                        AND fact_type IN ('START_GRANTED','HOLDER_TRANSFERRED','RENEWED','REVOKED','EXPIRED','SETTLED')
                        AND ((fact_sequence=0 AND previous_lease_fact_id IS NULL AND fact_type='START_GRANTED')
                             OR (fact_sequence>0 AND previous_lease_fact_id IS NOT NULL
                                 AND previous_lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'))
                        AND effective_at > 0 AND dispatch_binding_generation >= 0
                        AND holder_binding_generation IS NOT NULL AND holder_binding_generation >= 0
                        AND OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100
                        AND runtime_credential_client_id IS NOT NULL
                        AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50
                        AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
                        AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
                        AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
                        AND claimant_worker_generation >= 0 AND admission_epoch >= 0
                        AND receiver_registration_revision IS NOT NULL AND receiver_registration_revision >= 0
                        AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$' AND migration_epoch >= 0
                        AND ((fact_type IN ('START_GRANTED','HOLDER_TRANSFERRED','RENEWED')
                              AND lease_until IS NOT NULL AND lease_until > effective_at
                              AND lease_until > created_at)
                             OR (fact_type IN ('REVOKED','EXPIRED','SETTLED') AND lease_until IS NULL))
                        AND ((fact_type IN ('START_GRANTED','SETTLED') AND causing_receipt_id IS NOT NULL)
                             OR (fact_type NOT IN ('START_GRANTED','SETTLED') AND causing_receipt_id IS NULL))
                        AND (causing_receipt_id IS NULL
                             OR causing_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
                        AND tenant_control_revision >= 0 AND module_mode_revision >= 0
                        AND deployment_registration_revision >= 0
                        AND (tenant_control_operation_id IS NULL
                             OR OCTET_LENGTH(tenant_control_operation_id) BETWEEN 1 AND 100)
                        AND (module_control_operation_id IS NULL
                             OR OCTET_LENGTH(module_control_operation_id) BETWEEN 1 AND 100)
                        AND (deployment_control_operation_id IS NULL
                             OR OCTET_LENGTH(deployment_control_operation_id) BETWEEN 1 AND 100)
                        AND OCTET_LENGTH(reason) BETWEEN 1 AND 500 AND created_at >= effective_at
                        """,
                "chk_execution_grant_canonical", """
                        execution_grant_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND grant_kind IN ('START','CONTINUATION','RENEWAL')
                        AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND started_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND OCTET_LENGTH(started_receipt_sha256)=32
                        AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100
                        AND dispatch_binding_generation>=0
                        AND holder_binding_generation>=0 AND lease_revision>=0
                        AND issued_at>0 AND lease_until>issued_at
                        AND original_start_lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND current_lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND (previous_execution_grant_id IS NULL
                             OR previous_execution_grant_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
                        AND ((grant_kind='START' AND previous_execution_grant_id IS NULL
                              AND holder_binding_generation=dispatch_binding_generation
                              AND original_start_lease_fact_id=current_lease_fact_id)
                             OR (grant_kind IN ('CONTINUATION','RENEWAL') AND previous_execution_grant_id IS NOT NULL))
                        AND OCTET_LENGTH(grant_wire_bytes) BETWEEN 1 AND 4096
                        AND OCTET_LENGTH(grant_wire_sha256)=32 AND grant_wire_contract_version=2
                        AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
                        AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
                        AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50
                        AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
                        AND claimant_worker_generation>=0 AND receiver_registration_revision>=0
                        AND admission_epoch>=0 AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
                        AND migration_epoch>=0
                        """,
                "chk_execution_continuation_anchor", """
                        runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND started_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND execution_grant_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND holder_binding_generation>=0 AND created_at>0 AND contract_version=2
                        AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
                        AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
                        AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50
                        AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
                        AND claimant_worker_generation>=0 AND receiver_registration_revision>=0
                        AND admission_epoch>=0 AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
                        AND migration_epoch>=0
                        """,
                "chk_execution_request_canonical", """
                        execution_request_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND request_kind IN ('CONTINUATION','RENEWAL') AND OCTET_LENGTH(request_fingerprint)=32
                        AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND started_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                        AND OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100
                        AND requested_holder_binding_generation>=0 AND decision IN ('GRANTED','DENIED')
                        AND decided_at>0 AND contract_version=2
                        AND ((decision='GRANTED'
                              AND execution_grant_id IS NOT NULL
                              AND execution_grant_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                              AND denial_code IS NULL)
                             OR (decision='DENIED' AND execution_grant_id IS NULL
                                 AND denial_code IS NOT NULL
                                 AND OCTET_LENGTH(denial_code) BETWEEN 1 AND 64))
                        AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
                        AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
                        AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50
                        AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
                        AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
                        AND claimant_worker_generation>=0 AND receiver_registration_revision>=0
                        AND admission_epoch>=0 AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
                        AND migration_epoch>=0
                        """);
    }

    static boolean isCanonicalRuntimeProjectionCheck(String clause) {
        return canonicalCheck(expectedCheckClauses().get("chk_inbox_runtime_v2_projection"))
                .equals(canonicalCheck(clause));
    }

    private static List<ColumnDefinition> runtimeInboxColumns() {
        return List.of(c("runtime_dispatch_id","varchar","varchar(36)",true,BIN,null,""),
                c("runtime_binding_id","varchar","varchar(36)",true,BIN,null,""),
                c("runtime_binding_generation","bigint","bigint",true,null,null,""),
                c("dispatch_runtime_instance_id","varchar","varchar(100)",true,BIN,null,""),
                c("dispatch_runtime_credential_client_id","varchar","varchar(50)",true,BIN,null,""),
                c("dispatch_receiver_registration_revision","bigint","bigint",true,null,null,""),
                c("dispatch_prepared_at","bigint","bigint",true,null,null,""),
                c("execution_started_at","bigint","bigint",true,null,null,""),
                c("execution_lease_until","bigint","bigint",true,null,null,""),
                c("execution_lease_revision","bigint","bigint",true,null,null,""),
                c("execution_holder_binding_id","varchar","varchar(36)",true,BIN,null,""),
                c("execution_holder_binding_generation","bigint","bigint",true,null,null,""),
                c("execution_holder_runtime_instance_id","varchar","varchar(100)",true,BIN,null,""),
                c("execution_current_lease_fact_id","varchar","varchar(36)",true,BIN,null,""),
                c("ack_runtime_binding_id","varchar","varchar(36)",true,BIN,null,""),
                c("ack_runtime_binding_generation","bigint","bigint",true,null,null,""),
                c("ack_committed_at","bigint","bigint",true,null,null,""));
    }

    private static List<ColumnDefinition> generationFactColumns() {
        List<ColumnDefinition> result = new ArrayList<>(columns("""
                id|bigint|bigint|N|-|NULL|auto_increment
                binding_generation_fact_id|varchar|varchar(36)|N|B|NULL|-
                fact_sequence|bigint|bigint|N|-|NULL|-
                previous_fact_id|varchar|varchar(36)|Y|B|NULL|-
                runtime_binding_id|varchar|varchar(36)|N|B|NULL|-
                binding_generation|bigint|bigint|N|-|NULL|-
                event_type|varchar|varchar(20)|N|B|NULL|-
                revoked_runtime_binding_id|varchar|varchar(36)|Y|B|NULL|STORED GENERATED
                runtime_instance_id|varchar|varchar(100)|N|B|NULL|-
                data_domain_id|varchar|varchar(64)|N|B|NULL|-
                execution_pool_id|varchar|varchar(100)|N|B|NULL|-
                owner_jiacn|varchar|varchar(100)|N|B|NULL|-
                agent_id|varchar|varchar(100)|N|B|NULL|-
                claimant_deployment_id|varchar|varchar(100)|N|B|NULL|-
                claimant_worker_generation|bigint|bigint|N|-|NULL|-
                receiver_registration_revision|bigint|bigint|N|-|NULL|-
                admission_epoch|bigint|bigint|N|-|NULL|-
                module_id|varchar|varchar(64)|N|B|NULL|-
                migration_epoch|bigint|bigint|N|-|NULL|-
                session_fence|varchar|varchar(100)|N|B|NULL|-
                socket_owner_instance_id|varchar|varchar(100)|N|B|NULL|-
                socket_owner_epoch|bigint|bigint|N|-|NULL|-
                socket_owner_lease_until|bigint|bigint|N|-|NULL|-
                effective_at|bigint|bigint|N|-|NULL|-
                reason|varchar|varchar(500)|Y|B|NULL|-
                tenant_id|varchar|varchar(50)|N|B|NULL|-
                client_id|varchar|varchar(50)|N|B|NULL|-
                create_time|bigint|bigint|N|-|NULL|-
                update_time|bigint|bigint|N|-|NULL|-
                """));
        ColumnDefinition generated = result.get(7);
        result.set(7, new ColumnDefinition(generated.name(),generated.dataType(),generated.columnType(),
                generated.nullable(),generated.defaultValue(),generated.collation(),generated.extra(),
                "case when (`event_type` = _utf8mb4'REVOKED') then `runtime_binding_id` else NULL end"));
        return List.copyOf(result);
    }

    private static List<ColumnDefinition> receiptColumns() {
        return columns("""
                id|bigint|bigint|N|-|NULL|auto_increment
                receipt_id|varchar|varchar(36)|N|B|NULL|-
                runtime_dispatch_id|varchar|varchar(36)|N|B|NULL|-
                inbox_id|bigint|bigint|N|-|NULL|-
                delivery_id|bigint|bigint|N|-|NULL|-
                ack_message_id|varchar|varchar(100)|N|B|NULL|-
                source_message_id|varchar|varchar(100)|N|B|NULL|-
                status|varchar|varchar(20)|N|B|NULL|-
                ack_at|bigint|bigint|N|-|NULL|-
                request_fingerprint|binary|binary(32)|N|-|NULL|-
                runtime_binding_id|varchar|varchar(36)|N|B|NULL|-
                dispatch_binding_generation|bigint|bigint|N|-|NULL|-
                ack_holder_binding_generation|bigint|bigint|Y|-|NULL|-
                runtime_instance_id|varchar|varchar(100)|N|B|NULL|-
                runtime_credential_client_id|varchar|varchar(50)|Y|B|NULL|-
                data_domain_id|varchar|varchar(64)|N|B|NULL|-
                execution_pool_id|varchar|varchar(100)|N|B|NULL|-
                owner_jiacn|varchar|varchar(100)|N|B|NULL|-
                agent_id|varchar|varchar(100)|N|B|NULL|-
                producer_deployment_id|varchar|varchar(100)|N|B|NULL|-
                claimant_deployment_id|varchar|varchar(100)|N|B|NULL|-
                claimant_worker_generation|bigint|bigint|N|-|NULL|-
                receiver_registration_revision|bigint|bigint|Y|-|NULL|-
                admission_epoch|bigint|bigint|N|-|NULL|-
                module_id|varchar|varchar(64)|N|B|NULL|-
                migration_epoch|bigint|bigint|N|-|NULL|-
                postcommit_delivery_version|bigint|bigint|N|-|NULL|-
                postcommit_inbox_version|bigint|bigint|N|-|NULL|-
                lease_fact_id|varchar|varchar(36)|Y|B|NULL|-
                contract_version|smallint|smallint|N|-|NULL|-
                receipt_wire_bytes|varbinary|varbinary(4096)|Y|-|NULL|-
                receipt_wire_sha256|binary|binary(32)|Y|-|NULL|-
                receipt_wire_contract_version|smallint|smallint|Y|-|NULL|-
                committed_at|bigint|bigint|N|-|NULL|-
                tenant_id|varchar|varchar(50)|N|B|NULL|-
                client_id|varchar|varchar(50)|N|B|NULL|-
                create_time|bigint|bigint|N|-|NULL|-
                update_time|bigint|bigint|N|-|NULL|-
                """);
    }

    private static List<ColumnDefinition> leaseColumns() {
        return columns("""
                id|bigint|bigint|N|-|NULL|auto_increment
                lease_fact_id|varchar|varchar(36)|N|B|NULL|-
                runtime_dispatch_id|varchar|varchar(36)|N|B|NULL|-
                inbox_id|bigint|bigint|N|-|NULL|-
                delivery_id|bigint|bigint|N|-|NULL|-
                fact_sequence|bigint|bigint|N|-|NULL|-
                lease_revision|bigint|bigint|N|-|NULL|-
                fact_type|varchar|varchar(20)|N|B|NULL|-
                previous_lease_fact_id|varchar|varchar(36)|Y|B|NULL|-
                effective_at|bigint|bigint|N|-|NULL|-
                lease_until|bigint|bigint|Y|-|NULL|-
                causing_receipt_id|varchar|varchar(36)|Y|B|NULL|-
                runtime_binding_id|varchar|varchar(36)|N|B|NULL|-
                dispatch_binding_generation|bigint|bigint|N|-|NULL|-
                holder_binding_generation|bigint|bigint|Y|-|NULL|-
                runtime_instance_id|varchar|varchar(100)|N|B|NULL|-
                runtime_credential_client_id|varchar|varchar(50)|Y|B|NULL|-
                data_domain_id|varchar|varchar(64)|N|B|NULL|-
                execution_pool_id|varchar|varchar(100)|N|B|NULL|-
                owner_jiacn|varchar|varchar(100)|N|B|NULL|-
                agent_id|varchar|varchar(100)|N|B|NULL|-
                producer_deployment_id|varchar|varchar(100)|N|B|NULL|-
                claimant_deployment_id|varchar|varchar(100)|N|B|NULL|-
                claimant_worker_generation|bigint|bigint|N|-|NULL|-
                receiver_registration_revision|bigint|bigint|Y|-|NULL|-
                admission_epoch|bigint|bigint|N|-|NULL|-
                module_id|varchar|varchar(64)|N|B|NULL|-
                migration_epoch|bigint|bigint|N|-|NULL|-
                tenant_control_operation_id|varchar|varchar(100)|Y|B|NULL|-
                tenant_control_revision|bigint|bigint|N|-|NULL|-
                tenant_control_cutoff_at|bigint|bigint|Y|-|NULL|-
                module_control_operation_id|varchar|varchar(100)|Y|B|NULL|-
                module_mode_revision|bigint|bigint|N|-|NULL|-
                module_control_cutoff_at|bigint|bigint|Y|-|NULL|-
                deployment_control_operation_id|varchar|varchar(100)|Y|B|NULL|-
                deployment_registration_revision|bigint|bigint|N|-|NULL|-
                deployment_control_cutoff_at|bigint|bigint|Y|-|NULL|-
                reason|varchar|varchar(500)|N|B|NULL|-
                created_at|bigint|bigint|N|-|NULL|-
                tenant_id|varchar|varchar(50)|N|B|NULL|-
                client_id|varchar|varchar(50)|N|B|NULL|-
                create_time|bigint|bigint|N|-|NULL|-
                update_time|bigint|bigint|N|-|NULL|-
                """);
    }

    private static List<ColumnDefinition> grantColumns() {
        return columns("""
                id|bigint|bigint|N|-|NULL|auto_increment
                execution_grant_id|varchar|varchar(36)|N|B|NULL|-
                grant_kind|varchar|varchar(20)|N|B|NULL|-
                runtime_dispatch_id|varchar|varchar(36)|N|B|NULL|-
                started_receipt_id|varchar|varchar(36)|N|B|NULL|-
                started_receipt_sha256|binary|binary(32)|N|-|NULL|-
                runtime_binding_id|varchar|varchar(36)|N|B|NULL|-
                runtime_instance_id|varchar|varchar(100)|N|B|NULL|-
                dispatch_binding_generation|bigint|bigint|N|-|NULL|-
                holder_binding_generation|bigint|bigint|N|-|NULL|-
                original_start_lease_fact_id|varchar|varchar(36)|N|B|NULL|-
                current_lease_fact_id|varchar|varchar(36)|N|B|NULL|-
                lease_revision|bigint|bigint|N|-|NULL|-
                lease_until|bigint|bigint|N|-|NULL|-
                previous_execution_grant_id|varchar|varchar(36)|Y|B|NULL|-
                issued_at|bigint|bigint|N|-|NULL|-
                grant_wire_bytes|varbinary|varbinary(4096)|N|-|NULL|-
                grant_wire_sha256|binary|binary(32)|N|-|NULL|-
                grant_wire_contract_version|smallint|smallint|N|-|NULL|-
                data_domain_id|varchar|varchar(64)|N|B|NULL|-
                execution_pool_id|varchar|varchar(100)|N|B|NULL|-
                runtime_credential_client_id|varchar|varchar(50)|N|B|NULL|-
                owner_jiacn|varchar|varchar(100)|N|B|NULL|-
                agent_id|varchar|varchar(100)|N|B|NULL|-
                producer_deployment_id|varchar|varchar(100)|N|B|NULL|-
                claimant_deployment_id|varchar|varchar(100)|N|B|NULL|-
                claimant_worker_generation|bigint|bigint|N|-|NULL|-
                receiver_registration_revision|bigint|bigint|N|-|NULL|-
                admission_epoch|bigint|bigint|N|-|NULL|-
                module_id|varchar|varchar(64)|N|B|NULL|-
                migration_epoch|bigint|bigint|N|-|NULL|-
                tenant_id|varchar|varchar(50)|N|B|NULL|-
                client_id|varchar|varchar(50)|N|B|NULL|-
                create_time|bigint|bigint|N|-|NULL|-
                update_time|bigint|bigint|N|-|NULL|-
                """);
    }

    private static List<ColumnDefinition> anchorColumns() {
        return columns("""
                id|bigint|bigint|N|-|NULL|auto_increment
                runtime_dispatch_id|varchar|varchar(36)|N|B|NULL|-
                started_receipt_id|varchar|varchar(36)|N|B|NULL|-
                holder_binding_generation|bigint|bigint|N|-|NULL|-
                execution_grant_id|varchar|varchar(36)|N|B|NULL|-
                created_at|bigint|bigint|N|-|NULL|-
                contract_version|smallint|smallint|N|-|NULL|-
                data_domain_id|varchar|varchar(64)|N|B|NULL|-
                execution_pool_id|varchar|varchar(100)|N|B|NULL|-
                runtime_credential_client_id|varchar|varchar(50)|N|B|NULL|-
                owner_jiacn|varchar|varchar(100)|N|B|NULL|-
                agent_id|varchar|varchar(100)|N|B|NULL|-
                producer_deployment_id|varchar|varchar(100)|N|B|NULL|-
                claimant_deployment_id|varchar|varchar(100)|N|B|NULL|-
                claimant_worker_generation|bigint|bigint|N|-|NULL|-
                receiver_registration_revision|bigint|bigint|N|-|NULL|-
                admission_epoch|bigint|bigint|N|-|NULL|-
                module_id|varchar|varchar(64)|N|B|NULL|-
                migration_epoch|bigint|bigint|N|-|NULL|-
                tenant_id|varchar|varchar(50)|N|B|NULL|-
                client_id|varchar|varchar(50)|N|B|NULL|-
                create_time|bigint|bigint|N|-|NULL|-
                update_time|bigint|bigint|N|-|NULL|-
                """);
    }

    private static List<ColumnDefinition> requestColumns() {
        return columns("""
                id|bigint|bigint|N|-|NULL|auto_increment
                execution_request_id|varchar|varchar(36)|N|B|NULL|-
                request_kind|varchar|varchar(20)|N|B|NULL|-
                request_fingerprint|binary|binary(32)|N|-|NULL|-
                runtime_dispatch_id|varchar|varchar(36)|N|B|NULL|-
                started_receipt_id|varchar|varchar(36)|N|B|NULL|-
                runtime_binding_id|varchar|varchar(36)|N|B|NULL|-
                runtime_instance_id|varchar|varchar(100)|N|B|NULL|-
                requested_holder_binding_generation|bigint|bigint|N|-|NULL|-
                decision|varchar|varchar(20)|N|B|NULL|-
                execution_grant_id|varchar|varchar(36)|Y|B|NULL|-
                denial_code|varchar|varchar(64)|Y|B|NULL|-
                decided_at|bigint|bigint|N|-|NULL|-
                contract_version|smallint|smallint|N|-|NULL|-
                data_domain_id|varchar|varchar(64)|N|B|NULL|-
                execution_pool_id|varchar|varchar(100)|N|B|NULL|-
                runtime_credential_client_id|varchar|varchar(50)|N|B|NULL|-
                owner_jiacn|varchar|varchar(100)|N|B|NULL|-
                agent_id|varchar|varchar(100)|N|B|NULL|-
                producer_deployment_id|varchar|varchar(100)|N|B|NULL|-
                claimant_deployment_id|varchar|varchar(100)|N|B|NULL|-
                claimant_worker_generation|bigint|bigint|N|-|NULL|-
                receiver_registration_revision|bigint|bigint|N|-|NULL|-
                admission_epoch|bigint|bigint|N|-|NULL|-
                module_id|varchar|varchar(64)|N|B|NULL|-
                migration_epoch|bigint|bigint|N|-|NULL|-
                tenant_id|varchar|varchar(50)|N|B|NULL|-
                client_id|varchar|varchar(50)|N|B|NULL|-
                create_time|bigint|bigint|N|-|NULL|-
                update_time|bigint|bigint|N|-|NULL|-
                """);
    }

    private static List<ColumnDefinition> columns(String specs) {
        List<ColumnDefinition> result = new ArrayList<>();
        specs.lines().map(String::trim).filter(s -> !s.isEmpty()).forEach(line -> {
            String[] p = line.split("\\|", -1);
            result.add(c(p[0],p[1],p[2],"Y".equals(p[3]),"B".equals(p[4])?BIN:null,
                    "NULL".equals(p[5])?null:p[5],"-".equals(p[6])?"":p[6]));
        });
        return List.copyOf(result);
    }
    private static ColumnDefinition c(String n,String d,String t,boolean nullable,String coll,String def,String extra) {
        return new ColumnDefinition(n,d,t,nullable,def,coll,extra,"");
    }
    private static ColumnDefinition copy(AgentCommandConsumerV2SchemaGate.ColumnDefinition c) {
        return new ColumnDefinition(c.name(),c.dataType(),c.columnType(),c.nullable(),c.defaultValue(),
                c.collation(),c.extra(),c.generationExpression());
    }
    private static IndexDefinition index(boolean unique, String... columns) {
        return new IndexDefinition(unique, List.of(columns));
    }
    private record IndexEntry(String name, IndexDefinition definition) {}
    private static IndexEntry entry(String name, boolean unique, String... columns) {
        return new IndexEntry(name,index(unique,columns));
    }
    private static Map<String, IndexDefinition> indexes(IndexEntry... entries) {
        Map<String, IndexDefinition> result = new TreeMap<>();
        for (IndexEntry entry : entries) result.put(entry.name(), entry.definition());
        return Map.copyOf(result);
    }
    static String canonicalCheck(String value) {
        String normalized = normalize(value).replaceAll(
                "regexp_like\\(([a-z0-9_]+),\\s*('[^']*')\\)", "$1 regexp $2");
        return canonicalBoolean(normalized);
    }
    private static String canonicalBoolean(String expression) {
        String value = stripOuter(expression);
        List<String> alternatives = splitBoolean(value, "or");
        if (alternatives.size() > 1) {
            return "or(" + String.join(",", alternatives.stream()
                    .map(AgentRuntimeV2PersistenceSchemaGate::canonicalBoolean).toList()) + ")";
        }
        List<String> conjunctions = splitBoolean(value, "and");
        if (conjunctions.size() > 1) {
            return "and(" + String.join(",", conjunctions.stream()
                    .map(AgentRuntimeV2PersistenceSchemaGate::canonicalBoolean).toList()) + ")";
        }
        return value.trim();
    }
    private static List<String> splitBoolean(String expression, String operator) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean quoted = false;
        boolean between = false;
        for (int index = 0; index < expression.length();) {
            char character = expression.charAt(index);
            if (character == '\'') {
                if (quoted && index + 1 < expression.length()
                        && expression.charAt(index + 1) == '\'') {
                    index += 2;
                    continue;
                }
                quoted = !quoted;
                index++;
                continue;
            }
            if (quoted) {
                index++;
                continue;
            }
            if (character == '(') {
                depth++;
                index++;
                continue;
            }
            if (character == ')') {
                depth--;
                index++;
                continue;
            }
            if (depth == 0 && Character.isLetter(character)) {
                int end = index + 1;
                while (end < expression.length()
                        && (Character.isLetterOrDigit(expression.charAt(end))
                        || expression.charAt(end) == '_')) end++;
                String word = expression.substring(index, end);
                if ("between".equals(word)) {
                    between = true;
                } else if ("and".equals(word) && between) {
                    between = false;
                } else if (operator.equals(word)) {
                    result.add(expression.substring(start, index).trim());
                    start = end;
                }
                index = end;
                continue;
            }
            index++;
        }
        result.add(expression.substring(start).trim());
        return List.copyOf(result);
    }
    private static String stripOuter(String expression) {
        String value = expression.trim();
        while (value.startsWith("(") && matchingClose(value, 0) == value.length() - 1) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
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
        return -1;
    }
    private static String normalize(String value) {
        if (value == null) return "";
        StringBuilder normalized = new StringBuilder(value.length());
        boolean quoted = false;
        boolean pendingWhitespace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quoted) {
                normalized.append(character);
                if (character == '\'') {
                    if (index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                        normalized.append(value.charAt(++index));
                    } else {
                        quoted = false;
                    }
                }
                continue;
            }
            if (character == '\'') {
                appendPendingWhitespace(normalized, character, pendingWhitespace);
                pendingWhitespace = false;
                normalized.append(character);
                quoted = true;
                continue;
            }
            if (character == '`') continue;
            if (isUtf8mb4Introducer(value, index)) {
                index += "_utf8mb4".length() - 1;
                continue;
            }
            if (Character.isWhitespace(character)) {
                pendingWhitespace = true;
                continue;
            }
            appendPendingWhitespace(normalized, character, pendingWhitespace);
            pendingWhitespace = false;
            normalized.append(Character.toLowerCase(character));
        }
        return normalized.toString().trim();
    }
    private static boolean isUtf8mb4Introducer(String value, int index) {
        String introducer = "_utf8mb4";
        if (!value.regionMatches(true, index, introducer, 0, introducer.length())) return false;
        int next = index + introducer.length();
        while (next < value.length() && Character.isWhitespace(value.charAt(next))) next++;
        return next < value.length() && value.charAt(next) == '\'';
    }
    private static void appendPendingWhitespace(StringBuilder target, char next,
            boolean pendingWhitespace) {
        if (!pendingWhitespace || target.isEmpty()) return;
        char previous = target.charAt(target.length() - 1);
        if (!isCheckPunctuation(previous) && !isCheckPunctuation(next)) target.append(' ');
    }
    private static boolean isCheckPunctuation(char value) {
        return "=<>+(),".indexOf(value) >= 0;
    }
    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Agent runtime v2 persistence schema unavailable: " + reason);
    }

    interface Catalog {
        String databaseProduct(); List<String> presentTables(); TableDefinition table(String table);
        List<ColumnDefinition> columns(String table); Map<String,IndexDefinition> indexes(String table);
        int participatingForeignKeyCount(String table); Map<String,CheckDefinition> checks(String table);
    }
    record TableDefinition(String engine,String collation) {}
    record ColumnDefinition(String name,String dataType,String columnType,boolean nullable,
                            String defaultValue,String collation,String extra,String generationExpression) {}
    record IndexDefinition(boolean unique,List<String> columns) { IndexDefinition { columns=List.copyOf(columns); } }
    record CheckDefinition(String clause,boolean enforced) {}
    record IndexColumn(String name,boolean nonUnique,int sequence,String column,Integer prefix,
                       String type,String visible) {}

    private static final class JdbcCatalog implements Catalog {
        private final JdbcTemplate jdbc; private JdbcCatalog(JdbcTemplate jdbc){this.jdbc=jdbc;}
        public String databaseProduct(){return jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());}
        public List<String> presentTables(){return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()
                AND table_name IN ('agent_consumer_inbox','agent_runtime_v2_binding',
                'agent_runtime_v2_binding_generation_fact',
                'agent_command_v2_ack_receipt','agent_command_v2_execution_lease_fact',
                'agent_command_v2_execution_grant',
                'agent_command_v2_execution_continuation_anchor',
                'agent_command_v2_execution_request') ORDER BY table_name
                """,String.class);}
        public TableDefinition table(String table){List<TableDefinition> r=jdbc.query("""
                SELECT engine,table_collation FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """,(x,n)->new TableDefinition(x.getString(1),x.getString(2)),table);return r.size()==1?r.getFirst():null;}
        public List<ColumnDefinition> columns(String table){return jdbc.query("""
                SELECT column_name,data_type,column_type,is_nullable,column_default,collation_name,extra,generation_expression
                FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position
                """,(r,n)->new ColumnDefinition(r.getString(1),r.getString(2),r.getString(3),"YES".equalsIgnoreCase(r.getString(4)),
                r.getString(5),r.getString(6),Objects.toString(r.getString(7),""),Objects.toString(r.getString(8),"")),table);}
        public Map<String,IndexDefinition> indexes(String table){List<IndexColumn> rows=jdbc.query("""
                SELECT index_name,non_unique,seq_in_index,column_name,sub_part,index_type,is_visible
                FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=?
                ORDER BY index_name,seq_in_index
                """,(r,n)->new IndexColumn(r.getString(1),r.getBoolean(2),r.getInt(3),r.getString(4),
                (Integer)r.getObject(5),r.getString(6),r.getString(7)),table);
            Map<String,List<IndexColumn>> groups=new TreeMap<>(); rows.forEach(v->groups.computeIfAbsent(v.name(),k->new ArrayList<>()).add(v));
            Map<String,IndexDefinition> out=new TreeMap<>(); groups.forEach((name,values)->{boolean nu=values.getFirst().nonUnique();
                for(int i=0;i<values.size();i++){IndexColumn v=values.get(i);if(v.sequence()!=i+1||v.nonUnique()!=nu||v.prefix()!=null
                        ||!"BTREE".equalsIgnoreCase(v.type())||!"YES".equalsIgnoreCase(v.visible()))throw invalid(table+" index metadata drift");}
                out.put(name,new IndexDefinition(!nu,values.stream().map(IndexColumn::column).toList()));});return Map.copyOf(out);}
        public int participatingForeignKeyCount(String table){Integer n=jdbc.queryForObject(AgentCommandConsumerV2SchemaGate.PARTICIPATING_FOREIGN_KEYS_SQL,Integer.class,table,table);return n==null?-1:n;}
        public Map<String,CheckDefinition> checks(String table){List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT tc.constraint_name,cc.check_clause,tc.enforced FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema
                AND cc.constraint_name=tc.constraint_name WHERE tc.table_schema=DATABASE() AND tc.table_name=?
                AND tc.constraint_type='CHECK' ORDER BY tc.constraint_name
                """,table);Map<String,CheckDefinition> out=new TreeMap<>();for(Map<String,Object> row:rows){String n=Objects.toString(row.get("constraint_name"),null);
                if(n==null||out.put(n,new CheckDefinition(Objects.toString(row.get("check_clause"),""),"YES".equalsIgnoreCase(Objects.toString(row.get("enforced"),""))))!=null)throw invalid(table+" ambiguous CHECK metadata");}return Map.copyOf(out);}
    }
}
