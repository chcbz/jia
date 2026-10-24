package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Dormant, read-only, exact catalog gate for the manual R2b1 registration migration. */
public final class AgentRuntimeV2RegistrationSchemaGate implements InitializingBean {
    static final String AUTHORITY = "agent_runtime_v2_registration_authority";
    static final String RECEIPT = "agent_runtime_v2_registration_receipt";
    static final String REQUEST = "agent_runtime_v2_registration_request";
    static final String CHALLENGE = "agent_runtime_v2_reconnect_challenge";
    static final Set<String> NEW_TABLES = Set.of(AUTHORITY, RECEIPT, REQUEST, CHALLENGE);
    static final Set<String> TABLES = Set.of(
            "agent_runtime_v2_binding", "agent_runtime_v2_binding_generation_fact",
            AUTHORITY, RECEIPT, REQUEST, CHALLENGE, "agent_consumer_inbox",
            "agent_command_v2_ack_receipt", "agent_command_v2_execution_lease_fact",
            "agent_command_v2_execution_grant", "agent_command_v2_execution_continuation_anchor",
            "agent_command_v2_execution_request");
    private static final String BIN = "utf8mb4_0900_bin";
    private final Catalog catalog;

    public AgentRuntimeV2RegistrationSchemaGate(JdbcTemplate jdbc) {
        this(new JdbcCatalog(Objects.requireNonNull(jdbc, "jdbc")));
    }
    AgentRuntimeV2RegistrationSchemaGate(Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override public void afterPropertiesSet() {
        String product = catalog.databaseProduct();
        if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
            throw invalid("MySQL is required, got " + product);
        }
        if (!new LinkedHashSet<>(catalog.presentTables()).equals(TABLES)) {
            throw invalid("required table set is missing or ambiguous");
        }
        for (String table : TABLES) {
            TableDefinition definition = catalog.table(table);
            if (definition == null || !"InnoDB".equalsIgnoreCase(definition.engine())
                    || !BIN.equalsIgnoreCase(definition.collation())) {
                throw invalid(table + " must be exact InnoDB/" + BIN);
            }
            if (catalog.participatingForeignKeyCount(table) != 0) {
                throw invalid(table + " must not participate in foreign keys");
            }
            validateColumns(table, catalog.columns(table));
            validateIndexes(table, catalog.indexes(table));
            validateChecks(table, catalog.checks(table));
        }
        long readinessViolations = catalog.activationReadinessViolationCount();
        if (readinessViolations != 0) {
            throw invalid("activation evidence graph has " + readinessViolations + " unreachable or legacy-authority rows");
        }
    }

    private static void validateColumns(String table, List<ColumnDefinition> actual) {
        List<ColumnDefinition> expected = expectedColumns().get(table);
        if (NEW_TABLES.contains(table)) {
            if (expected.size() != actual.size()) throw invalid(table + " column contract drift");
            for (int i = 0; i < expected.size(); i++) {
                if (!columnMatches(expected.get(i), actual.get(i))) throw invalid(table + " column contract drift");
            }
            return;
        }
        Map<String, ColumnDefinition> byName = new LinkedHashMap<>();
        for (ColumnDefinition column : actual) {
            if (byName.put(column.name(), column) != null) throw invalid(table + " duplicate column metadata");
        }
        List<String> positions = new ArrayList<>();
        for (ColumnDefinition column : expected) {
            if (!columnMatches(column, byName.get(column.name()))) throw invalid(table + " additive column drift: " + column.name());
            positions.add(column.name());
        }
        List<String> actualOrder = actual.stream().map(ColumnDefinition::name).filter(positions::contains).toList();
        if (!actualOrder.equals(positions)) throw invalid(table + " additive column order drift");
    }

    private static boolean columnMatches(ColumnDefinition expected, ColumnDefinition actual) {
        return actual != null && Objects.equals(expected.name(), actual.name())
                && Objects.equals(expected.dataType(), actual.dataType())
                && Objects.equals(expected.columnType(), actual.columnType())
                && expected.nullable() == actual.nullable()
                && Objects.equals(expected.defaultValue(), actual.defaultValue())
                && Objects.equals(expected.collation(), actual.collation())
                && Objects.equals(expected.extra(), actual.extra())
                && AgentRuntimeV2PersistenceSchemaGate.sameGeneratedExpression(
                        expected.generationExpression(), actual.generationExpression());
    }

    private static void validateIndexes(String table, Map<String, IndexDefinition> actual) {
        Map<String, IndexDefinition> expected = expectedIndexes().get(table);
        if (NEW_TABLES.contains(table)) {
            if (!expected.equals(actual)) throw invalid(table + " index contract drift");
        } else {
            for (Map.Entry<String, IndexDefinition> entry : expected.entrySet()) {
                if (!entry.getValue().equals(actual.get(entry.getKey()))) {
                    throw invalid(table + " additive index drift: " + entry.getKey());
                }
            }
        }
    }

    private static void validateChecks(String table, Map<String, CheckDefinition> actual) {
        Map<String, String> expected = expectedChecks().get(table);
        if (NEW_TABLES.contains(table) && !actual.keySet().equals(expected.keySet())) {
            throw invalid(table + " CHECK set drift");
        }
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            CheckDefinition definition = actual.get(entry.getKey());
            if (definition == null || !definition.enforced()
                    || !digest(entry.getValue()).equals(digest(definition.clause()))) {
                throw invalid(table + " CHECK drift: " + entry.getKey());
            }
        }
    }

    static Map<String, List<ColumnDefinition>> expectedColumns() {
        Map<String, List<ColumnDefinition>> result = new LinkedHashMap<>();
        result.put("agent_runtime_v2_binding", columns("""
                evidence_provenance|varchar|varchar(24)|N|B|R2B1_ENFORCED|-|-
                registration_subject_sha256|binary|binary(32)|Y|-|NULL|-|-
                live_registration_subject_sha256|char|char(64)|Y|B|NULL|STORED GENERATED|case when ((`status` <> _utf8mb4'REVOKED') and (`registration_subject_sha256` is not null)) then lower(hex(`registration_subject_sha256`)) else NULL end
                live_business_subject|char|char(64)|Y|B|NULL|STORED GENERATED|case when ((`status` <> _utf8mb4'REVOKED') and (`contract_version` = 2) and (`registration_subject_sha256` is not null)) then sha2(concat(lpad(octet_length(`tenant_id`),3,_utf8mb4'0'),_utf8mb4':',`tenant_id`,lpad(octet_length(`client_id`),3,_utf8mb4'0'),_utf8mb4':',`client_id`,lpad(octet_length(`owner_jiacn`),3,_utf8mb4'0'),_utf8mb4':',`owner_jiacn`,lpad(octet_length(`agent_id`),3,_utf8mb4'0'),_utf8mb4':',`agent_id`),256) else NULL end
                registration_authority_id|varchar|varchar(36)|Y|B|NULL|-|-
                registration_authority_revision|bigint|bigint|Y|-|NULL|-|-
                process_public_key_spki|binary|binary(44)|Y|-|NULL|-|-
                process_public_key_sha256|binary|binary(32)|Y|-|NULL|-|-
                verifier_version|bigint|bigint|Y|-|NULL|-|-
                verifier_key_id|varchar|varchar(100)|Y|B|NULL|-|-
                current_registration_receipt_id|varchar|varchar(36)|Y|B|NULL|-|-
                current_registration_receipt_sha256|binary|binary(32)|Y|-|NULL|-|-
                """));
        result.put("agent_runtime_v2_binding_generation_fact", columns("""
                evidence_provenance|varchar|varchar(24)|N|B|R2B1_ENFORCED|-|-
                registration_subject_sha256|binary|binary(32)|Y|-|NULL|-|-
                registration_authority_id|varchar|varchar(36)|Y|B|NULL|-|-
                registration_authority_revision|bigint|bigint|Y|-|NULL|-|-
                runtime_credential_client_id|varchar|varchar(50)|Y|B|NULL|-|-
                process_public_key_sha256|binary|binary(32)|Y|-|NULL|-|-
                verifier_version|bigint|bigint|Y|-|NULL|-|-
                registration_receipt_id|varchar|varchar(36)|Y|B|NULL|-|-
                registration_receipt_sha256|binary|binary(32)|Y|-|NULL|-|-
                connection_admission_kind|varchar|varchar(20)|Y|B|NULL|-|-
                drain_control_operation_id|varchar|varchar(100)|Y|B|NULL|-|-
                generation_snapshot_sha256|binary|binary(32)|Y|-|NULL|-|-
                """));
        result.put(AUTHORITY, columns("""
                id|bigint|bigint|N|-|NULL|auto_increment|-
                registration_authority_id|varchar|varchar(36)|N|B|NULL|-|-
                credential_kind|varchar|varchar(32)|N|B|NULL|-|-
                credential_reference|varchar|varchar(200)|N|B|NULL|-|-
                credential_digest|binary|binary(32)|N|-|NULL|-|-
                credential_key_id|varchar|varchar(100)|N|B|NULL|-|-
                owner_jiacn|varchar|varchar(100)|N|B|NULL|-|-
                agent_id|varchar|varchar(100)|N|B|NULL|-|-
                data_domain_id|varchar|varchar(64)|N|B|NULL|-|-
                execution_pool_id|varchar|varchar(100)|N|B|NULL|-|-
                module_id|varchar|varchar(64)|N|B|NULL|-|-
                claimant_deployment_id|varchar|varchar(100)|N|B|NULL|-|-
                authority_revision|bigint|bigint|N|-|NULL|-|-
                status|varchar|varchar(20)|N|B|NULL|-|-
                active_credential_reference|char|char(64)|Y|B|NULL|STORED GENERATED|case when (`status` = _utf8mb4'ACTIVE') then sha2(concat(lpad(octet_length(`credential_kind`),3,_utf8mb4'0'),_utf8mb4':',`credential_kind`,lpad(octet_length(`credential_reference`),3,_utf8mb4'0'),_utf8mb4':',`credential_reference`,lpad(octet_length(`tenant_id`),3,_utf8mb4'0'),_utf8mb4':',`tenant_id`,lpad(octet_length(`client_id`),3,_utf8mb4'0'),_utf8mb4':',`client_id`,lpad(octet_length(`owner_jiacn`),3,_utf8mb4'0'),_utf8mb4':',`owner_jiacn`,lpad(octet_length(`agent_id`),3,_utf8mb4'0'),_utf8mb4':',`agent_id`),256) else NULL end
                active_business_subject|char|char(64)|Y|B|NULL|STORED GENERATED|case when (`status` = _utf8mb4'ACTIVE') then sha2(concat(lpad(octet_length(`tenant_id`),3,_utf8mb4'0'),_utf8mb4':',`tenant_id`,lpad(octet_length(`client_id`),3,_utf8mb4'0'),_utf8mb4':',`client_id`,lpad(octet_length(`owner_jiacn`),3,_utf8mb4'0'),_utf8mb4':',`owner_jiacn`,lpad(octet_length(`agent_id`),3,_utf8mb4'0'),_utf8mb4':',`agent_id`),256) else NULL end
                revoked_at|bigint|bigint|Y|-|NULL|-|-
                revoke_reason|varchar|varchar(500)|Y|B|NULL|-|-
                version|bigint|bigint|N|-|0|-|-
                tenant_id|varchar|varchar(50)|N|B|NULL|-|-
                client_id|varchar|varchar(50)|N|B|NULL|-|-
                create_time|bigint|bigint|N|-|NULL|-|-
                update_time|bigint|bigint|N|-|NULL|-|-
                """));
        result.put(RECEIPT, columns("""
                id|bigint|bigint|N|-|NULL|auto_increment|-
                registration_receipt_id|varchar|varchar(36)|N|B|NULL|-|-
                receipt_kind|varchar|varchar(20)|N|B|NULL|-|-
                registration_request_id|varchar|varchar(36)|N|B|NULL|-|-
                previous_registration_receipt_id|varchar|varchar(36)|Y|B|NULL|-|-
                previous_registration_receipt_sha256|binary|binary(32)|Y|-|NULL|-|-
                runtime_binding_id|varchar|varchar(36)|N|B|NULL|-|-
                binding_generation|bigint|bigint|N|-|NULL|-|-
                binding_generation_fact_id|varchar|varchar(36)|N|B|NULL|-|-
                runtime_instance_id|varchar|varchar(36)|N|B|NULL|-|-
                process_public_key_sha256|binary|binary(32)|N|-|NULL|-|-
                verifier_version|bigint|bigint|N|-|NULL|-|-
                verifier_sha256|binary|binary(32)|N|-|NULL|-|-
                verifier_cipher_suite|varchar|varchar(64)|N|B|NULL|-|-
                server_ephemeral_public_key_spki|binary|binary(44)|N|-|NULL|-|-
                nonce|binary|binary(12)|N|-|NULL|-|-
                sealed_verifier|binary|binary(48)|N|-|NULL|-|-
                data_domain_id|varchar|varchar(64)|N|B|NULL|-|-
                execution_pool_id|varchar|varchar(100)|N|B|NULL|-|-
                runtime_credential_client_id|varchar|varchar(50)|N|B|NULL|-|-
                owner_jiacn|varchar|varchar(100)|N|B|NULL|-|-
                agent_id|varchar|varchar(100)|N|B|NULL|-|-
                registration_authority_id|varchar|varchar(36)|N|B|NULL|-|-
                registration_authority_revision|bigint|bigint|N|-|NULL|-|-
                claimant_deployment_id|varchar|varchar(100)|N|B|NULL|-|-
                claimant_worker_generation|bigint|bigint|N|-|NULL|-|-
                receiver_registration_revision|bigint|bigint|N|-|NULL|-|-
                admission_epoch|bigint|bigint|N|-|NULL|-|-
                module_id|varchar|varchar(64)|N|B|NULL|-|-
                migration_epoch|bigint|bigint|N|-|NULL|-|-
                connected_at|bigint|bigint|N|-|NULL|-|-
                receipt_wire_bytes|varbinary|varbinary(4096)|N|-|NULL|-|-
                receipt_wire_sha256|binary|binary(32)|N|-|NULL|-|-
                contract_version|smallint|smallint|N|-|NULL|-|-
                tenant_id|varchar|varchar(50)|N|B|NULL|-|-
                client_id|varchar|varchar(50)|N|B|NULL|-|-
                create_time|bigint|bigint|N|-|NULL|-|-
                update_time|bigint|bigint|N|-|NULL|-|-
                """));
        result.put(REQUEST, columns("""
                id|bigint|bigint|N|-|NULL|auto_increment|-
                registration_request_id|varchar|varchar(36)|N|B|NULL|-|-
                request_kind|varchar|varchar(20)|N|B|NULL|-|-
                request_wire_sha256|binary|binary(32)|N|-|NULL|-|-
                semantic_sha256|binary|binary(32)|N|-|NULL|-|-
                registration_subject_sha256|binary|binary(32)|N|-|NULL|-|-
                registration_authority_sha256|binary|binary(32)|N|-|NULL|-|-
                runtime_binding_id|varchar|varchar(36)|Y|B|NULL|-|-
                binding_generation|bigint|bigint|Y|-|NULL|-|-
                runtime_instance_id|varchar|varchar(36)|N|B|NULL|-|-
                process_public_key_sha256|binary|binary(32)|N|-|NULL|-|-
                registration_authority_id|varchar|varchar(36)|N|B|NULL|-|-
                registration_authority_revision|bigint|bigint|N|-|NULL|-|-
                data_domain_id|varchar|varchar(64)|N|B|NULL|-|-
                execution_pool_id|varchar|varchar(100)|N|B|NULL|-|-
                runtime_credential_client_id|varchar|varchar(50)|N|B|NULL|-|-
                owner_jiacn|varchar|varchar(100)|N|B|NULL|-|-
                agent_id|varchar|varchar(100)|N|B|NULL|-|-
                claimant_deployment_id|varchar|varchar(100)|N|B|NULL|-|-
                claimant_worker_generation|bigint|bigint|N|-|NULL|-|-
                receiver_registration_revision|bigint|bigint|N|-|NULL|-|-
                admission_epoch|bigint|bigint|N|-|NULL|-|-
                module_id|varchar|varchar(64)|N|B|NULL|-|-
                migration_epoch|bigint|bigint|N|-|NULL|-|-
                decision|varchar|varchar(20)|N|B|NULL|-|-
                registration_receipt_id|varchar|varchar(36)|Y|B|NULL|-|-
                registration_receipt_sha256|binary|binary(32)|Y|-|NULL|-|-
                denial_code|varchar|varchar(40)|Y|B|NULL|-|-
                decided_at|bigint|bigint|N|-|NULL|-|-
                contract_version|smallint|smallint|N|-|NULL|-|-
                tenant_id|varchar|varchar(50)|N|B|NULL|-|-
                client_id|varchar|varchar(50)|N|B|NULL|-|-
                create_time|bigint|bigint|N|-|NULL|-|-
                update_time|bigint|bigint|N|-|NULL|-|-
                """));
        result.put(CHALLENGE, columns("""
                id|bigint|bigint|N|-|NULL|auto_increment|-
                challenge_request_id|varchar|varchar(36)|N|B|NULL|-|-
                challenge_id|varchar|varchar(36)|N|B|NULL|-|-
                request_wire_sha256|binary|binary(32)|N|-|NULL|-|-
                semantic_sha256|binary|binary(32)|N|-|NULL|-|-
                registration_subject_sha256|binary|binary(32)|N|-|NULL|-|-
                registration_authority_sha256|binary|binary(32)|N|-|NULL|-|-
                registration_authority_id|varchar|varchar(36)|N|B|NULL|-|-
                registration_authority_revision|bigint|bigint|N|-|NULL|-|-
                runtime_binding_id|varchar|varchar(36)|N|B|NULL|-|-
                binding_generation|bigint|bigint|N|-|NULL|-|-
                prior_registration_receipt_id|varchar|varchar(36)|N|B|NULL|-|-
                prior_registration_receipt_sha256|binary|binary(32)|N|-|NULL|-|-
                runtime_instance_id|varchar|varchar(36)|N|B|NULL|-|-
                process_public_key_sha256|binary|binary(32)|N|-|NULL|-|-
                challenge_digest|binary|binary(32)|N|-|NULL|-|-
                challenge_digest_key_id|varchar|varchar(100)|N|B|NULL|-|-
                challenge_cipher_suite|varchar|varchar(64)|N|B|NULL|-|-
                server_ephemeral_public_key_spki|binary|binary(44)|N|-|NULL|-|-
                nonce|binary|binary(12)|N|-|NULL|-|-
                sealed_challenge|binary|binary(48)|N|-|NULL|-|-
                challenge_sha256|binary|binary(32)|N|-|NULL|-|-
                challenge_wire_bytes|varbinary|varbinary(2048)|N|-|NULL|-|-
                challenge_wire_sha256|binary|binary(32)|N|-|NULL|-|-
                issuer_socket_owner_instance_id|varchar|varchar(100)|N|B|NULL|-|-
                issuer_socket_owner_epoch|bigint|bigint|N|-|NULL|-|-
                provisional_session_fence|varchar|varchar(36)|N|B|NULL|-|-
                issued_at|bigint|bigint|N|-|NULL|-|-
                expires_at|bigint|bigint|N|-|NULL|-|-
                status|varchar|varchar(20)|N|B|NULL|-|-
                consumed_reconnect_request_id|varchar|varchar(36)|Y|B|NULL|-|-
                consumed_at|bigint|bigint|Y|-|NULL|-|-
                denial_code|varchar|varchar(40)|Y|B|NULL|-|-
                contract_version|smallint|smallint|N|-|NULL|-|-
                tenant_id|varchar|varchar(50)|N|B|NULL|-|-
                client_id|varchar|varchar(50)|N|B|NULL|-|-
                create_time|bigint|bigint|N|-|NULL|-|-
                update_time|bigint|bigint|N|-|NULL|-|-
                """));
        result.put("agent_consumer_inbox", List.of(
                provenance(),
                column("dispatch_generation_fact_id", "varchar", "varchar(36)", true, BIN),
                column("dispatch_generation_snapshot_sha256", "binary", "binary(32)", true, null),
                column("execution_origin_snapshot_sha256", "binary", "binary(32)", true, null)));
        result.put("agent_command_v2_ack_receipt", evidenceColumns("execution_origin_snapshot_sha256", "ack_holder_generation_fact_id", "ack_holder_generation_snapshot_sha256"));
        result.put("agent_command_v2_execution_lease_fact", evidenceColumns("execution_origin_snapshot_sha256", "holder_generation_fact_id", "holder_generation_snapshot_sha256"));
        result.put("agent_command_v2_execution_grant", evidenceColumns("execution_origin_snapshot_sha256", "holder_generation_fact_id", "holder_generation_snapshot_sha256"));
        result.put("agent_command_v2_execution_continuation_anchor", evidenceColumns("execution_origin_snapshot_sha256", "holder_generation_fact_id", "holder_generation_snapshot_sha256"));
        result.put("agent_command_v2_execution_request", evidenceColumns("execution_origin_snapshot_sha256", "requested_holder_generation_fact_id", "requested_holder_generation_snapshot_sha256"));
        return Map.copyOf(result);
    }

    static Map<String, Map<String, IndexDefinition>> expectedIndexes() {
        Map<String, Map<String, IndexDefinition>> result = new LinkedHashMap<>();
        result.put("agent_runtime_v2_binding", indexes(entry("uk_runtime_v2_binding_live_subject", true, "live_registration_subject_sha256"), entry("uk_runtime_v2_binding_live_business", true, "live_business_subject")));
        result.put("agent_runtime_v2_binding_generation_fact", indexes(entry("uk_binding_generation_registration_receipt", true, "registration_receipt_id")));
        result.put(AUTHORITY, indexes(entry("PRIMARY", true, "id"), entry("uk_registration_authority_id", true, "registration_authority_id"), entry("uk_registration_authority_subject", true, "credential_kind","credential_reference","tenant_id","client_id","owner_jiacn","agent_id"), entry("uk_registration_authority_active_credential", true, "active_credential_reference"), entry("uk_registration_authority_active_business", true, "active_business_subject")));
        result.put(RECEIPT, indexes(entry("PRIMARY", true, "id"), entry("uk_registration_receipt_id", true, "registration_receipt_id"), entry("uk_registration_receipt_request", true, "registration_request_id"), entry("uk_registration_receipt_binding_generation", true, "runtime_binding_id","binding_generation"), entry("uk_registration_receipt_generation_fact", true, "binding_generation_fact_id"), entry("uk_registration_receipt_predecessor", true, "previous_registration_receipt_id")));
        result.put(REQUEST, indexes(entry("PRIMARY", true, "id"), entry("uk_registration_request_id", true, "registration_request_id"), entry("uk_registration_request_receipt", true, "registration_receipt_id"), entry("idx_registration_request_subject", false, "registration_subject_sha256","request_kind","decided_at")));
        result.put(CHALLENGE, indexes(entry("PRIMARY", true, "id"), entry("uk_reconnect_challenge_request", true, "challenge_request_id"), entry("uk_reconnect_challenge_id", true, "challenge_id"), entry("uk_reconnect_challenge_consumed_request", true, "consumed_reconnect_request_id"), entry("idx_reconnect_challenge_binding", false, "runtime_binding_id","binding_generation","expires_at")));
        for (String table : TABLES) result.putIfAbsent(table, Map.of());
        return Map.copyOf(result);
    }

    static Map<String, Map<String, String>> expectedChecks() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        result.put("agent_runtime_v2_binding", one("chk_runtime_v2_binding_registration", "contract_version <> 2 OR (evidence_provenance='LEGACY_PRE_R2B1' AND status='REVOKED' AND registration_subject_sha256 IS NULL AND registration_authority_id IS NULL AND registration_authority_revision IS NULL AND process_public_key_spki IS NULL AND process_public_key_sha256 IS NULL AND verifier_version IS NULL AND verifier_key_id IS NULL AND current_registration_receipt_id IS NULL AND current_registration_receipt_sha256 IS NULL) OR (evidence_provenance='R2B1_ENFORCED' AND registration_subject_sha256 IS NOT NULL AND OCTET_LENGTH(registration_subject_sha256)=32 AND registration_authority_id IS NOT NULL AND registration_authority_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' AND registration_authority_revision IS NOT NULL AND registration_authority_revision >= 0 AND process_public_key_spki IS NOT NULL AND OCTET_LENGTH(process_public_key_spki)=44 AND process_public_key_sha256 IS NOT NULL AND OCTET_LENGTH(process_public_key_sha256)=32 AND verifier_version IS NOT NULL AND verifier_version = binding_generation + 1 AND verifier_key_id IS NOT NULL AND OCTET_LENGTH(verifier_key_id) BETWEEN 1 AND 100 AND reconnect_verifier IS NOT NULL AND OCTET_LENGTH(reconnect_verifier)=32 AND current_registration_receipt_id IS NOT NULL AND current_registration_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' AND current_registration_receipt_sha256 IS NOT NULL AND OCTET_LENGTH(current_registration_receipt_sha256)=32)"));
        result.put("agent_runtime_v2_binding_generation_fact", one("chk_binding_generation_registration", "(evidence_provenance='LEGACY_PRE_R2B1' AND registration_subject_sha256 IS NULL AND registration_authority_id IS NULL AND registration_authority_revision IS NULL AND runtime_credential_client_id IS NULL AND process_public_key_sha256 IS NULL AND verifier_version IS NULL AND registration_receipt_id IS NULL AND registration_receipt_sha256 IS NULL AND connection_admission_kind IS NULL AND drain_control_operation_id IS NULL AND generation_snapshot_sha256 IS NULL) OR (evidence_provenance='R2B1_ENFORCED' AND event_type <> 'CONNECTED' AND registration_subject_sha256 IS NULL AND registration_authority_id IS NULL AND registration_authority_revision IS NULL AND runtime_credential_client_id IS NULL AND process_public_key_sha256 IS NULL AND verifier_version IS NULL AND registration_receipt_id IS NULL AND registration_receipt_sha256 IS NULL AND connection_admission_kind IS NULL AND drain_control_operation_id IS NULL AND generation_snapshot_sha256 IS NULL) OR (evidence_provenance='R2B1_ENFORCED' AND event_type='CONNECTED' AND registration_subject_sha256 IS NOT NULL AND OCTET_LENGTH(registration_subject_sha256)=32 AND registration_authority_id IS NOT NULL AND registration_authority_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' AND registration_authority_revision IS NOT NULL AND registration_authority_revision >= 0 AND runtime_credential_client_id IS NOT NULL AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50 AND process_public_key_sha256 IS NOT NULL AND OCTET_LENGTH(process_public_key_sha256)=32 AND verifier_version IS NOT NULL AND verifier_version = binding_generation + 1 AND registration_receipt_id IS NOT NULL AND registration_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' AND registration_receipt_sha256 IS NOT NULL AND OCTET_LENGTH(registration_receipt_sha256)=32 AND connection_admission_kind IS NOT NULL AND connection_admission_kind IN ('CURRENT_ADMISSION','FROZEN_DRAIN') AND ((connection_admission_kind='CURRENT_ADMISSION' AND drain_control_operation_id IS NULL) OR (connection_admission_kind='FROZEN_DRAIN' AND drain_control_operation_id IS NOT NULL AND OCTET_LENGTH(drain_control_operation_id) BETWEEN 1 AND 100)) AND generation_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(generation_snapshot_sha256)=32)"));
        result.put(AUTHORITY, one("chk_registration_authority_canonical", "registration_authority_id IS NOT NULL AND registration_authority_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' AND credential_kind IS NOT NULL AND credential_kind='API_KEY' AND credential_reference IS NOT NULL AND OCTET_LENGTH(credential_reference) BETWEEN 1 AND 200 AND credential_digest IS NOT NULL AND OCTET_LENGTH(credential_digest)=32 AND credential_key_id IS NOT NULL AND OCTET_LENGTH(credential_key_id) BETWEEN 1 AND 100 AND tenant_id IS NOT NULL AND tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$' AND authority_revision IS NOT NULL AND authority_revision >= 0 AND status IS NOT NULL AND status IN ('ACTIVE','REVOKED') AND ((status='ACTIVE' AND revoked_at IS NULL AND revoke_reason IS NULL) OR (status='REVOKED' AND revoked_at IS NOT NULL AND revoked_at > 0 AND revoke_reason IS NOT NULL AND OCTET_LENGTH(revoke_reason) BETWEEN 1 AND 500)) AND version IS NOT NULL AND version >= 0"));
        result.put(RECEIPT, one("chk_registration_receipt_canonical", "contract_version IS NOT NULL AND contract_version=2 AND receipt_kind IS NOT NULL AND receipt_kind IN ('CREATE','RECONNECT') AND registration_receipt_id IS NOT NULL AND registration_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' AND registration_request_id IS NOT NULL AND registration_request_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' AND ((receipt_kind='CREATE' AND previous_registration_receipt_id IS NULL AND previous_registration_receipt_sha256 IS NULL AND binding_generation=0) OR (receipt_kind='RECONNECT' AND previous_registration_receipt_id IS NOT NULL AND previous_registration_receipt_sha256 IS NOT NULL AND OCTET_LENGTH(previous_registration_receipt_sha256)=32 AND binding_generation>0)) AND binding_generation IS NOT NULL AND binding_generation >= 0 AND verifier_version IS NOT NULL AND verifier_version=binding_generation+1 AND process_public_key_sha256 IS NOT NULL AND OCTET_LENGTH(process_public_key_sha256)=32 AND verifier_sha256 IS NOT NULL AND OCTET_LENGTH(verifier_sha256)=32 AND verifier_cipher_suite IS NOT NULL AND verifier_cipher_suite='X25519-HKDF-SHA256-AES-256-GCM' AND server_ephemeral_public_key_spki IS NOT NULL AND OCTET_LENGTH(server_ephemeral_public_key_spki)=44 AND nonce IS NOT NULL AND OCTET_LENGTH(nonce)=12 AND sealed_verifier IS NOT NULL AND OCTET_LENGTH(sealed_verifier)=48 AND tenant_id IS NOT NULL AND tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$' AND receipt_wire_bytes IS NOT NULL AND OCTET_LENGTH(receipt_wire_bytes) BETWEEN 1 AND 4096 AND receipt_wire_sha256 IS NOT NULL AND OCTET_LENGTH(receipt_wire_sha256)=32 AND connected_at IS NOT NULL AND connected_at>0"));
        result.put(REQUEST, one("chk_registration_request_canonical", "contract_version IS NOT NULL AND contract_version=2 AND request_kind IS NOT NULL AND request_kind IN ('CREATE','RECONNECT') AND request_wire_sha256 IS NOT NULL AND OCTET_LENGTH(request_wire_sha256)=32 AND semantic_sha256 IS NOT NULL AND OCTET_LENGTH(semantic_sha256)=32 AND registration_subject_sha256 IS NOT NULL AND OCTET_LENGTH(registration_subject_sha256)=32 AND registration_authority_sha256 IS NOT NULL AND OCTET_LENGTH(registration_authority_sha256)=32 AND tenant_id IS NOT NULL AND tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$' AND decision IS NOT NULL AND decision IN ('GRANTED','DENIED') AND ((decision='GRANTED' AND registration_receipt_id IS NOT NULL AND registration_receipt_sha256 IS NOT NULL AND OCTET_LENGTH(registration_receipt_sha256)=32 AND denial_code IS NULL) OR (decision='DENIED' AND registration_receipt_id IS NULL AND registration_receipt_sha256 IS NULL AND denial_code IS NOT NULL AND OCTET_LENGTH(denial_code) BETWEEN 1 AND 40)) AND decided_at IS NOT NULL AND decided_at>0"));
        result.put(CHALLENGE, one("chk_reconnect_challenge_canonical", "contract_version IS NOT NULL AND contract_version=2 AND challenge_request_id IS NOT NULL AND challenge_request_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' AND challenge_id IS NOT NULL AND challenge_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' AND request_wire_sha256 IS NOT NULL AND OCTET_LENGTH(request_wire_sha256)=32 AND semantic_sha256 IS NOT NULL AND OCTET_LENGTH(semantic_sha256)=32 AND registration_subject_sha256 IS NOT NULL AND OCTET_LENGTH(registration_subject_sha256)=32 AND registration_authority_sha256 IS NOT NULL AND OCTET_LENGTH(registration_authority_sha256)=32 AND binding_generation IS NOT NULL AND binding_generation>=0 AND prior_registration_receipt_sha256 IS NOT NULL AND OCTET_LENGTH(prior_registration_receipt_sha256)=32 AND process_public_key_sha256 IS NOT NULL AND OCTET_LENGTH(process_public_key_sha256)=32 AND challenge_digest IS NOT NULL AND OCTET_LENGTH(challenge_digest)=32 AND challenge_digest_key_id IS NOT NULL AND OCTET_LENGTH(challenge_digest_key_id) BETWEEN 1 AND 100 AND challenge_cipher_suite IS NOT NULL AND challenge_cipher_suite='X25519-HKDF-SHA256-AES-256-GCM' AND server_ephemeral_public_key_spki IS NOT NULL AND OCTET_LENGTH(server_ephemeral_public_key_spki)=44 AND nonce IS NOT NULL AND OCTET_LENGTH(nonce)=12 AND sealed_challenge IS NOT NULL AND OCTET_LENGTH(sealed_challenge)=48 AND challenge_sha256 IS NOT NULL AND OCTET_LENGTH(challenge_sha256)=32 AND challenge_wire_bytes IS NOT NULL AND OCTET_LENGTH(challenge_wire_bytes) BETWEEN 1 AND 2048 AND challenge_wire_sha256 IS NOT NULL AND OCTET_LENGTH(challenge_wire_sha256)=32 AND issuer_socket_owner_epoch IS NOT NULL AND issuer_socket_owner_epoch>=0 AND issued_at IS NOT NULL AND issued_at>0 AND expires_at IS NOT NULL AND expires_at>issued_at AND tenant_id IS NOT NULL AND tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$' AND status IS NOT NULL AND status IN ('ISSUED','CONSUMED','DENIED') AND ((status='ISSUED' AND consumed_reconnect_request_id IS NULL AND consumed_at IS NULL AND denial_code IS NULL) OR (status='CONSUMED' AND consumed_reconnect_request_id IS NOT NULL AND consumed_at IS NOT NULL AND consumed_at>=issued_at AND denial_code IS NULL) OR (status='DENIED' AND consumed_reconnect_request_id IS NULL AND consumed_at IS NULL AND denial_code IS NOT NULL AND OCTET_LENGTH(denial_code) BETWEEN 1 AND 40))"));
        result.put("agent_consumer_inbox", one("chk_inbox_runtime_v2_origin_evidence", "(evidence_provenance='LEGACY_PRE_R2B1' AND dispatch_generation_fact_id IS NULL AND dispatch_generation_snapshot_sha256 IS NULL AND execution_origin_snapshot_sha256 IS NULL) OR (evidence_provenance='R2B1_ENFORCED' AND (protocol_version IS NULL OR protocol_version<>2 OR runtime_dispatch_id IS NULL) AND dispatch_generation_fact_id IS NULL AND dispatch_generation_snapshot_sha256 IS NULL AND execution_origin_snapshot_sha256 IS NULL) OR (evidence_provenance='R2B1_ENFORCED' AND protocol_version=2 AND runtime_dispatch_id IS NOT NULL AND dispatch_generation_fact_id IS NOT NULL AND dispatch_generation_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' AND dispatch_generation_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(dispatch_generation_snapshot_sha256)=32 AND execution_origin_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(execution_origin_snapshot_sha256)=32)"));
        result.put("agent_command_v2_ack_receipt", one("chk_ack_receipt_snapshot_links", "(evidence_provenance='LEGACY_PRE_R2B1' AND execution_origin_snapshot_sha256 IS NULL AND ack_holder_generation_fact_id IS NULL AND ack_holder_generation_snapshot_sha256 IS NULL) OR (evidence_provenance='R2B1_ENFORCED' AND contract_version<>2 AND execution_origin_snapshot_sha256 IS NULL AND ack_holder_generation_fact_id IS NULL AND ack_holder_generation_snapshot_sha256 IS NULL) OR (evidence_provenance='R2B1_ENFORCED' AND contract_version=2 AND execution_origin_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(execution_origin_snapshot_sha256)=32 AND ack_holder_generation_fact_id IS NOT NULL AND ack_holder_generation_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' AND ack_holder_generation_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(ack_holder_generation_snapshot_sha256)=32)"));
        String holder = "(evidence_provenance='LEGACY_PRE_R2B1' AND execution_origin_snapshot_sha256 IS NULL AND %1$s IS NULL AND %2$s IS NULL) OR (evidence_provenance='R2B1_ENFORCED' AND execution_origin_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(execution_origin_snapshot_sha256)=32 AND %1$s IS NOT NULL AND %1$s REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' AND %2$s IS NOT NULL AND OCTET_LENGTH(%2$s)=32)";
        result.put("agent_command_v2_execution_lease_fact", one("chk_execution_lease_snapshot_links", holder.formatted("holder_generation_fact_id","holder_generation_snapshot_sha256")));
        result.put("agent_command_v2_execution_grant", one("chk_execution_grant_snapshot_links", holder.formatted("holder_generation_fact_id","holder_generation_snapshot_sha256")));
        result.put("agent_command_v2_execution_continuation_anchor", one("chk_execution_anchor_snapshot_links", holder.formatted("holder_generation_fact_id","holder_generation_snapshot_sha256")));
        result.put("agent_command_v2_execution_request", one("chk_execution_request_snapshot_links", holder.formatted("requested_holder_generation_fact_id","requested_holder_generation_snapshot_sha256")));
        return Map.copyOf(result);
    }

    private static List<ColumnDefinition> evidenceColumns(String first, String second, String third) {
        return List.of(provenance(), column(first, "binary", "binary(32)", true, null),
                column(second, "varchar", "varchar(36)", true, BIN),
                column(third, "binary", "binary(32)", true, null));
    }
    private static ColumnDefinition provenance() {
        return new ColumnDefinition("evidence_provenance", "varchar", "varchar(24)", false,
                "R2B1_ENFORCED", BIN, "", "");
    }
    private static ColumnDefinition column(String name, String dataType, String columnType,
            boolean nullable, String collation) {
        return new ColumnDefinition(name, dataType, columnType, nullable, null, collation, "", "");
    }
    private static List<ColumnDefinition> columns(String text) {
        List<ColumnDefinition> result = new ArrayList<>();
        for (String raw : text.strip().split("\\R")) {
            String[] value = raw.strip().split("\\|", -1);
            result.add(new ColumnDefinition(value[0], value[1], value[2], "Y".equals(value[3]),
                    "NULL".equals(value[5]) ? null : value[5], "B".equals(value[4]) ? BIN : null,
                    "-".equals(value[6]) ? "" : value[6], "-".equals(value[7]) ? "" : value[7]));
        }
        return List.copyOf(result);
    }
    private static Map<String, IndexDefinition> indexes(IndexEntry... entries) {
        Map<String, IndexDefinition> result = new TreeMap<>();
        for (IndexEntry entry : entries) result.put(entry.name(), new IndexDefinition(entry.unique(), entry.columns()));
        return Map.copyOf(result);
    }
    private static IndexEntry entry(String name, boolean unique, String... columns) {
        return new IndexEntry(name, unique, List.of(columns));
    }
    private static Map<String, String> one(String name, String clause) { return Map.of(name, clause); }
    private static String digest(String clause) {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256").digest(
                    AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(clause)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value);
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Agent runtime v2 registration schema unavailable: " + reason);
    }

    interface Catalog {
        String databaseProduct(); List<String> presentTables(); TableDefinition table(String table);
        List<ColumnDefinition> columns(String table); Map<String, IndexDefinition> indexes(String table);
        int participatingForeignKeyCount(String table); Map<String, CheckDefinition> checks(String table);
        long activationReadinessViolationCount();
    }
    record TableDefinition(String engine, String collation) {}
    record ColumnDefinition(String name, String dataType, String columnType, boolean nullable,
                            String defaultValue, String collation, String extra, String generationExpression) {}
    record IndexDefinition(boolean unique, List<String> columns) { IndexDefinition { columns = List.copyOf(columns); } }
    record CheckDefinition(String clause, boolean enforced) {}
    private record IndexEntry(String name, boolean unique, List<String> columns) {}
    private record IndexColumn(String name, boolean nonUnique, int sequence, String column,
                               Integer prefix, String type, String visible) {}

    static final class JdbcCatalog implements Catalog {
        private final JdbcTemplate jdbc;
        JdbcCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        public String databaseProduct() {
            return jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
        }
        public List<String> presentTables() {
            return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('agent_runtime_v2_binding','agent_runtime_v2_binding_generation_fact','agent_runtime_v2_registration_authority','agent_runtime_v2_registration_receipt','agent_runtime_v2_registration_request','agent_runtime_v2_reconnect_challenge','agent_consumer_inbox','agent_command_v2_ack_receipt','agent_command_v2_execution_lease_fact','agent_command_v2_execution_grant','agent_command_v2_execution_continuation_anchor','agent_command_v2_execution_request') ORDER BY table_name", String.class);
        }
        public TableDefinition table(String table) {
            List<TableDefinition> rows = jdbc.query("SELECT engine,table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", (rs, row) -> new TableDefinition(rs.getString(1), rs.getString(2)), table);
            return rows.size() == 1 ? rows.getFirst() : null;
        }
        public List<ColumnDefinition> columns(String table) {
            return jdbc.query("SELECT column_name,data_type,column_type,is_nullable,column_default,collation_name,extra,generation_expression FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position", (rs, row) -> new ColumnDefinition(rs.getString(1), rs.getString(2), rs.getString(3), "YES".equalsIgnoreCase(rs.getString(4)), rs.getString(5), rs.getString(6), Objects.toString(rs.getString(7), ""), Objects.toString(rs.getString(8), "")), table);
        }
        public Map<String, IndexDefinition> indexes(String table) {
            List<IndexColumn> rows = jdbc.query("SELECT index_name,non_unique,seq_in_index,column_name,sub_part,index_type,is_visible FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index", (rs, row) -> new IndexColumn(rs.getString(1), rs.getBoolean(2), rs.getInt(3), rs.getString(4), (Integer) rs.getObject(5), rs.getString(6), rs.getString(7)), table);
            Map<String, List<IndexColumn>> grouped = new TreeMap<>();
            rows.forEach(value -> grouped.computeIfAbsent(value.name(), ignored -> new ArrayList<>()).add(value));
            Map<String, IndexDefinition> result = new TreeMap<>();
            grouped.forEach((name, values) -> {
                boolean nonUnique = values.getFirst().nonUnique();
                for (int i = 0; i < values.size(); i++) {
                    IndexColumn value = values.get(i);
                    if (value.sequence() != i + 1 || value.nonUnique() != nonUnique || value.prefix() != null
                            || !"BTREE".equalsIgnoreCase(value.type()) || !"YES".equalsIgnoreCase(value.visible())) {
                        throw invalid(table + " index metadata drift");
                    }
                }
                result.put(name, new IndexDefinition(!nonUnique, values.stream().map(IndexColumn::column).toList()));
            });
            return Map.copyOf(result);
        }
        public int participatingForeignKeyCount(String table) {
            Integer value = jdbc.queryForObject(AgentCommandConsumerV2SchemaGate.PARTICIPATING_FOREIGN_KEYS_SQL, Integer.class, table, table);
            return value == null ? -1 : value;
        }
        public Map<String, CheckDefinition> checks(String table) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT tc.constraint_name,cc.check_clause,tc.enforced FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name WHERE tc.table_schema=DATABASE() AND tc.table_name=? AND tc.constraint_type='CHECK' ORDER BY tc.constraint_name", table);
            Map<String, CheckDefinition> result = new TreeMap<>();
            for (Map<String, Object> row : rows) {
                String name = Objects.toString(row.get("constraint_name"), null);
                CheckDefinition previous = result.put(name, new CheckDefinition(Objects.toString(row.get("check_clause"), ""), "YES".equalsIgnoreCase(Objects.toString(row.get("enforced"), ""))));
                if (name == null || previous != null) throw invalid(table + " ambiguous CHECK metadata");
            }
            return Map.copyOf(result);
        }
        public long activationReadinessViolationCount() {
            Long value = jdbc.queryForObject("""
                    SELECT
                      (SELECT COUNT(*) FROM agent_runtime_v2_binding b
                        WHERE b.contract_version=2 AND b.evidence_provenance='LEGACY_PRE_R2B1'
                          AND b.status<>'REVOKED')
                    + (SELECT COUNT(*) FROM agent_runtime_v2_binding b
                        WHERE b.contract_version=2 AND b.evidence_provenance='R2B1_ENFORCED'
                          AND b.status='ACTIVE' AND NOT EXISTS (
                            SELECT 1 FROM agent_runtime_v2_binding_generation_fact g
                             WHERE g.evidence_provenance='R2B1_ENFORCED'
                               AND g.binding_generation_fact_id IS NOT NULL
                               AND g.runtime_binding_id=b.runtime_binding_id
                               AND g.binding_generation=b.binding_generation
                               AND g.runtime_instance_id=b.runtime_instance_id
                               AND g.event_type='CONNECTED'
                               AND g.registration_receipt_id=b.current_registration_receipt_id
                               AND g.registration_receipt_sha256=b.current_registration_receipt_sha256
                               AND EXISTS (SELECT 1 FROM agent_runtime_v2_registration_receipt rr
                                    WHERE rr.registration_receipt_id=g.registration_receipt_id
                                      AND rr.receipt_wire_sha256=g.registration_receipt_sha256
                                      AND rr.binding_generation_fact_id=g.binding_generation_fact_id
                                      AND rr.runtime_binding_id=g.runtime_binding_id
                                      AND rr.binding_generation=g.binding_generation)))
                    + (SELECT COUNT(*) FROM agent_consumer_inbox i
                        WHERE i.evidence_provenance='R2B1_ENFORCED' AND i.protocol_version=2
                          AND i.runtime_dispatch_id IS NOT NULL AND (
                            NOT EXISTS (SELECT 1 FROM agent_runtime_v2_binding b
                              WHERE b.evidence_provenance='R2B1_ENFORCED'
                                AND b.runtime_binding_id=i.runtime_binding_id)
                            OR NOT EXISTS (SELECT 1 FROM agent_runtime_v2_binding_generation_fact g
                              WHERE g.evidence_provenance='R2B1_ENFORCED'
                                AND g.binding_generation_fact_id=i.dispatch_generation_fact_id
                                AND g.generation_snapshot_sha256=i.dispatch_generation_snapshot_sha256
                                AND g.runtime_binding_id=i.runtime_binding_id
                                AND g.binding_generation=i.runtime_binding_generation
                                AND g.runtime_instance_id=i.dispatch_runtime_instance_id
                                AND g.event_type='CONNECTED')
                            OR (i.execution_current_lease_fact_id IS NOT NULL AND NOT EXISTS (
                              SELECT 1 FROM agent_command_v2_execution_lease_fact f
                               WHERE f.evidence_provenance='R2B1_ENFORCED'
                                 AND f.lease_fact_id=i.execution_current_lease_fact_id
                                 AND f.runtime_dispatch_id=i.runtime_dispatch_id
                                 AND f.runtime_binding_id=i.execution_holder_binding_id
                                 AND f.holder_binding_generation=i.execution_holder_binding_generation
                                 AND f.runtime_instance_id=i.execution_holder_runtime_instance_id))))
                    + (SELECT COUNT(*) FROM agent_command_v2_ack_receipt e
                        WHERE e.evidence_provenance='R2B1_ENFORCED' AND e.contract_version=2
                          AND (NOT EXISTS (SELECT 1 FROM agent_consumer_inbox i
                                WHERE i.evidence_provenance='R2B1_ENFORCED'
                                  AND i.runtime_dispatch_id=e.runtime_dispatch_id
                                  AND i.execution_origin_snapshot_sha256=e.execution_origin_snapshot_sha256)
                            OR NOT EXISTS (SELECT 1 FROM agent_runtime_v2_binding_generation_fact g
                                WHERE g.evidence_provenance='R2B1_ENFORCED'
                                  AND g.binding_generation_fact_id=e.ack_holder_generation_fact_id
                                  AND g.generation_snapshot_sha256=e.ack_holder_generation_snapshot_sha256
                                  AND g.runtime_binding_id=e.runtime_binding_id
                                  AND g.binding_generation=e.ack_holder_binding_generation
                                  AND g.runtime_instance_id=e.runtime_instance_id
                                  AND g.event_type='CONNECTED')))
                    + (SELECT COUNT(*) FROM agent_command_v2_execution_lease_fact e
                        WHERE e.evidence_provenance='R2B1_ENFORCED' AND (
                          NOT EXISTS (SELECT 1 FROM agent_consumer_inbox i
                            WHERE i.evidence_provenance='R2B1_ENFORCED'
                              AND i.runtime_dispatch_id=e.runtime_dispatch_id
                              AND i.execution_origin_snapshot_sha256=e.execution_origin_snapshot_sha256)
                          OR NOT EXISTS (SELECT 1 FROM agent_runtime_v2_binding_generation_fact g
                            WHERE g.evidence_provenance='R2B1_ENFORCED'
                              AND g.binding_generation_fact_id=e.holder_generation_fact_id
                              AND g.generation_snapshot_sha256=e.holder_generation_snapshot_sha256
                              AND g.runtime_binding_id=e.runtime_binding_id
                              AND g.binding_generation=e.holder_binding_generation
                              AND g.runtime_instance_id=e.runtime_instance_id
                              AND g.event_type='CONNECTED')))
                    + (SELECT COUNT(*) FROM agent_command_v2_execution_grant e
                        WHERE e.evidence_provenance='R2B1_ENFORCED' AND (
                          NOT EXISTS (SELECT 1 FROM agent_consumer_inbox i
                            WHERE i.evidence_provenance='R2B1_ENFORCED'
                              AND i.runtime_dispatch_id=e.runtime_dispatch_id
                              AND i.execution_origin_snapshot_sha256=e.execution_origin_snapshot_sha256)
                          OR NOT EXISTS (SELECT 1 FROM agent_runtime_v2_binding_generation_fact g
                            WHERE g.evidence_provenance='R2B1_ENFORCED'
                              AND g.binding_generation_fact_id=e.holder_generation_fact_id
                              AND g.generation_snapshot_sha256=e.holder_generation_snapshot_sha256
                              AND g.runtime_binding_id=e.runtime_binding_id
                              AND g.binding_generation=e.holder_binding_generation
                              AND g.runtime_instance_id=e.runtime_instance_id
                              AND g.event_type='CONNECTED')))
                    + (SELECT COUNT(*) FROM agent_command_v2_execution_request e
                        WHERE e.evidence_provenance='R2B1_ENFORCED' AND (
                          NOT EXISTS (SELECT 1 FROM agent_consumer_inbox i
                            WHERE i.evidence_provenance='R2B1_ENFORCED'
                              AND i.runtime_dispatch_id=e.runtime_dispatch_id
                              AND i.execution_origin_snapshot_sha256=e.execution_origin_snapshot_sha256)
                          OR NOT EXISTS (SELECT 1 FROM agent_runtime_v2_binding_generation_fact g
                            WHERE g.evidence_provenance='R2B1_ENFORCED'
                              AND g.binding_generation_fact_id=e.requested_holder_generation_fact_id
                              AND g.generation_snapshot_sha256=e.requested_holder_generation_snapshot_sha256
                              AND g.runtime_binding_id=e.runtime_binding_id
                              AND g.binding_generation=e.requested_holder_binding_generation
                              AND g.runtime_instance_id=e.runtime_instance_id
                              AND g.event_type='CONNECTED')))
                    + (SELECT COUNT(*) FROM agent_command_v2_execution_continuation_anchor e
                        WHERE e.evidence_provenance='R2B1_ENFORCED' AND (
                          NOT EXISTS (SELECT 1 FROM agent_command_v2_execution_grant x
                            JOIN agent_consumer_inbox i ON i.runtime_dispatch_id=x.runtime_dispatch_id
                              AND i.evidence_provenance='R2B1_ENFORCED'
                              AND i.execution_origin_snapshot_sha256=e.execution_origin_snapshot_sha256
                           WHERE x.evidence_provenance='R2B1_ENFORCED'
                             AND x.execution_grant_id=e.execution_grant_id
                             AND x.runtime_dispatch_id=e.runtime_dispatch_id)
                          OR NOT EXISTS (SELECT 1 FROM agent_runtime_v2_binding_generation_fact g
                            JOIN agent_command_v2_execution_grant x
                              ON x.execution_grant_id=e.execution_grant_id
                           WHERE g.evidence_provenance='R2B1_ENFORCED'
                             AND x.evidence_provenance='R2B1_ENFORCED'
                             AND g.binding_generation_fact_id=e.holder_generation_fact_id
                             AND g.generation_snapshot_sha256=e.holder_generation_snapshot_sha256
                             AND g.runtime_binding_id=x.runtime_binding_id
                             AND g.binding_generation=e.holder_binding_generation
                             AND g.event_type='CONNECTED')))
                    """, Long.class);
            return value == null ? -1 : value;
        }
    }
}
