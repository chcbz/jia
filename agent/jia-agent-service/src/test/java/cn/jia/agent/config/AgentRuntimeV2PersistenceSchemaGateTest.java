package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeV2PersistenceSchemaGateTest {
    private static final String SCOPE = """
            (((protocol_version IS NULL OR protocol_version = 1) AND tenant_id = '0'
              AND data_domain_id IS NULL AND execution_pool_id IS NULL AND admission_epoch IS NULL
              AND producer_deployment_id IS NULL AND module_id IS NULL AND migration_epoch IS NULL
              AND claimant_deployment_id IS NULL AND claimant_worker_generation IS NULL)
             OR (protocol_version = 2 AND OCTET_LENGTH(data_domain_id) BETWEEN 1 AND 64
              AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$' AND admission_epoch >= 0
              AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
              AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$' AND migration_epoch >= 0
              AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
              AND claimant_worker_generation >= 0))
            """;
    private static final String ACK = """
            (((protocol_version IS NULL OR protocol_version = 1)
              AND claim_delivery_version IS NULL AND claim_inbox_version IS NULL
              AND claim_started_at IS NULL AND ack_delivery_version IS NULL
              AND ack_message_id IS NULL AND ack_status IS NULL AND ack_at IS NULL
              AND ack_agent_id IS NULL AND ack_runtime_installation_id IS NULL
              AND ack_runtime_instance_id IS NULL)
             OR (protocol_version = 2
              AND ((attempt_count = 0 AND active_attempt = 0 AND status = 'EXPIRED'
                    AND claim_delivery_version IS NULL AND claim_inbox_version IS NULL
                    AND claim_started_at IS NULL)
                   OR (attempt_count >= 1 AND active_attempt >= 1
                    AND claim_delivery_version IS NOT NULL AND claim_delivery_version >= 0
                    AND claim_inbox_version IS NOT NULL AND claim_inbox_version >= 0
                    AND claim_started_at IS NOT NULL AND claim_started_at > 0))
              AND ((ack_delivery_version IS NULL AND ack_message_id IS NULL
                    AND ack_status IS NULL AND ack_at IS NULL AND ack_agent_id IS NULL
                    AND ack_runtime_installation_id IS NULL AND ack_runtime_instance_id IS NULL)
                   OR (ack_delivery_version IS NOT NULL
                    AND ack_delivery_version > claim_delivery_version
                    AND ack_message_id IS NOT NULL
                    AND OCTET_LENGTH(ack_message_id) BETWEEN 1 AND 100
                    AND ack_status IS NOT NULL
                    AND ack_status IN ('RECEIVED','STARTED','SUCCEEDED','FAILED','REJECTED')
                    AND ack_at IS NOT NULL AND ack_at > 0
                    AND ack_agent_id IS NOT NULL
                    AND OCTET_LENGTH(ack_agent_id) BETWEEN 1 AND 100
                    AND (ack_runtime_installation_id IS NULL
                         OR OCTET_LENGTH(ack_runtime_installation_id) BETWEEN 1 AND 100)
                    AND ack_runtime_instance_id IS NOT NULL
                    AND OCTET_LENGTH(ack_runtime_instance_id) BETWEEN 1 AND 100))))
            """;

    @Test
    void acceptsExactRuntimeCatalogAndLegacyConsumerGateAlsoAcceptsExtension() {
        FakeCatalog catalog = canonical();
        assertDoesNotThrow(() -> new AgentRuntimeV2PersistenceSchemaGate(catalog).afterPropertiesSet());
        assertTrue(catalog.reads > 0);

        AgentCommandConsumerV2SchemaGate.Catalog legacyView = catalog.consumerView();
        assertDoesNotThrow(() -> new AgentCommandConsumerV2SchemaGate(legacyView).afterPropertiesSet());
    }

    @Test
    void acceptsOnlyCompleteExactRegistrationExtensionAcrossBothGates() {
        FakeCatalog extended = registrationCanonical();
        assertDoesNotThrow(() -> new AgentRuntimeV2PersistenceSchemaGate(extended).afterPropertiesSet());

        FakeCatalog partial = registrationCanonical();
        partial.columns.put(AgentRuntimeV2PersistenceSchemaGate.REQUEST,
                new ArrayList<>(AgentRuntimeV2PersistenceSchemaGate.expectedColumns()
                        .get(AgentRuntimeV2PersistenceSchemaGate.REQUEST)));
        assertDenied(partial);

        FakeCatalog weakened = registrationCanonical();
        weakened.checks.get(AgentRuntimeV2PersistenceSchemaGate.INBOX).put(
                "chk_inbox_runtime_v2_origin_evidence", enforced("1=1"));
        assertDenied(weakened);

        for (String table : List.of(AgentRuntimeV2PersistenceSchemaGate.BINDING,
                AgentRuntimeV2PersistenceSchemaGate.GENERATION_FACT,
                AgentRuntimeV2PersistenceSchemaGate.RECEIPT)) {
            FakeCatalog defaultDrift = registrationCanonical();
            replaceColumn(defaultDrift.columns.get(table), "evidence_provenance", column ->
                    new AgentRuntimeV2PersistenceSchemaGate.ColumnDefinition(
                            column.name(), column.dataType(), column.columnType(), column.nullable(),
                            "LEGACY_PRE_R2B1", column.collation(), column.extra(),
                            column.generationExpression()));
            assertDenied(defaultDrift);
        }
    }

    @Test
    void checkCanonicalizationPreservesBinaryLiteralCaseAndWhitespace() {
        String baselineName = "chk_runtime_v2_binding_state";
        String baselineClause = AgentRuntimeV2PersistenceSchemaGate.expectedCheckClauses()
                .get(baselineName);
        FakeCatalog baselineCaseDrift = canonical();
        baselineCaseDrift.checks.get(AgentRuntimeV2PersistenceSchemaGate.BINDING).put(
                baselineName, enforced(baselineClause.replace("'ACTIVE'", "'active'")));
        assertDenied(baselineCaseDrift);

        String extensionName = "chk_binding_generation_registration";
        String extensionClause = AgentRuntimeV2RegistrationSchemaGate.expectedChecks()
                .get(AgentRuntimeV2PersistenceSchemaGate.GENERATION_FACT).get(extensionName);
        FakeCatalog extensionCaseDrift = registrationCanonical();
        extensionCaseDrift.checks.get(AgentRuntimeV2PersistenceSchemaGate.GENERATION_FACT).put(
                extensionName, enforced(extensionClause.replace(
                        "'CURRENT_ADMISSION'", "'current_admission'")));
        assertDenied(extensionCaseDrift);

        FakeCatalog extensionWhitespaceDrift = registrationCanonical();
        extensionWhitespaceDrift.checks.get(AgentRuntimeV2PersistenceSchemaGate.GENERATION_FACT).put(
                extensionName, enforced(extensionClause.replace(
                        "'CURRENT_ADMISSION'", "'CURRENT_ADMISSION '")));
        assertDenied(extensionWhitespaceDrift);

        assertNotEquals(
                AgentRuntimeV2PersistenceSchemaGate.canonicalCheck("status='A  B'"),
                AgentRuntimeV2PersistenceSchemaGate.canonicalCheck("status='A B'"));
    }

    @Test
    void legacyConsumerGateRejectsWeakenedRuntimeProjectionChecksWithoutRuntimeGate() {
        for (String weakened : List.of(
                "1 = 1",
                "runtime_dispatch_id IS NULL OR protocol_version = 2",
                "(runtime_dispatch_id IS NULL AND runtime_binding_id IS NULL)"
                        + " OR (protocol_version = 2 AND runtime_binding_generation >= 0)")) {
            FakeCatalog catalog = canonical();
            catalog.checks.get(AgentRuntimeV2PersistenceSchemaGate.INBOX).put(
                    "chk_inbox_runtime_v2_projection", enforced(weakened));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new AgentCommandConsumerV2SchemaGate(catalog.consumerView())
                            .afterPropertiesSet());
            assertTrue(failure.getMessage().startsWith("Agent consumer v2 schema unavailable:"));
        }
    }

    @Test
    void rejectsMetadataAndCanonicalCheckDrift() {
        FakeCatalog missingColumn = canonical();
        missingColumn.columns.get(AgentRuntimeV2PersistenceSchemaGate.RECEIPT)
                .removeIf(column -> column.name().equals("request_fingerprint"));
        assertDenied(missingColumn);

        FakeCatalog wrongCollation = canonical();
        wrongCollation.tables.put(AgentRuntimeV2PersistenceSchemaGate.BINDING,
                new AgentRuntimeV2PersistenceSchemaGate.TableDefinition("InnoDB", "utf8mb4_general_ci"));
        assertDenied(wrongCollation);

        FakeCatalog wrongIndexOrder = canonical();
        Map<String, AgentRuntimeV2PersistenceSchemaGate.IndexDefinition> receiptIndexes =
                wrongIndexOrder.indexes.get(AgentRuntimeV2PersistenceSchemaGate.RECEIPT);
        receiptIndexes.put("uk_ack_receipt_dispatch_status",
                new AgentRuntimeV2PersistenceSchemaGate.IndexDefinition(true,
                        List.of("status", "runtime_dispatch_id")));
        assertDenied(wrongIndexOrder);

        FakeCatalog oldLeaseTransferUniqueness = canonical();
        oldLeaseTransferUniqueness.indexes.get(AgentRuntimeV2PersistenceSchemaGate.LEASE_FACT).put(
                "uk_execution_lease_fact_revision_type",
                new AgentRuntimeV2PersistenceSchemaGate.IndexDefinition(true,
                        List.of("runtime_dispatch_id", "lease_revision", "fact_type")));
        assertDenied(oldLeaseTransferUniqueness);

        FakeCatalog extraCheck = canonical();
        extraCheck.checks.get(AgentRuntimeV2PersistenceSchemaGate.LEASE_FACT)
                .put("chk_fake_backfill_authority", enforced("fact_sequence >= 0"));
        assertDenied(extraCheck);

        FakeCatalog unenforced = canonical();
        unenforced.checks.get(AgentRuntimeV2PersistenceSchemaGate.BINDING)
                .put("chk_runtime_v2_binding_state",
                        new AgentRuntimeV2PersistenceSchemaGate.CheckDefinition(
                                AgentRuntimeV2PersistenceSchemaGate.expectedCheckClauses()
                                        .get("chk_runtime_v2_binding_state"), false));
        assertDenied(unenforced);

        FakeCatalog oldNonNullableAck = canonical();
        String oldAck = ACK.replaceFirst(
                "(?s)\\(ack_runtime_installation_id IS NULL\\s+OR OCTET_LENGTH\\(ack_runtime_installation_id\\) BETWEEN 1 AND 100\\)",
                "ack_runtime_installation_id IS NOT NULL\n"
                        + "                    AND OCTET_LENGTH(ack_runtime_installation_id) BETWEEN 1 AND 100");
        assertNotEquals(ACK, oldAck, "negative ACK fixture must actually change the predicate");
        oldNonNullableAck.checks.get(AgentRuntimeV2PersistenceSchemaGate.INBOX)
                .put(AgentCommandConsumerV2SchemaGate.ACK_EVIDENCE_CHECK, enforced(oldAck));
        assertDenied(oldNonNullableAck);

        FakeCatalog tautologyBypass = canonical();
        String receiptCheck = AgentRuntimeV2PersistenceSchemaGate.expectedCheckClauses()
                .get("chk_ack_receipt_canonical");
        tautologyBypass.checks.get(AgentRuntimeV2PersistenceSchemaGate.RECEIPT)
                .put("chk_ack_receipt_canonical", enforced("(" + receiptCheck + ") OR 1 = 1"));
        assertDenied(tautologyBypass);
    }
    @Test
    void rejectsEachSecurityCriticalCheckWeakening() {
        assertCheckDrift("chk_inbox_runtime_v2_projection",
                "protocol_version IS NOT NULL AND protocol_version = 2", "protocol_version = 2");
        assertCheckDrift("chk_ack_receipt_canonical",
                "OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100", "runtime_instance_id IS NOT NULL");
        assertCheckDrift("chk_execution_lease_fact_canonical",
                "tenant_control_revision >= 0 AND module_mode_revision >= 0",
                "tenant_control_revision IS NOT NULL AND module_mode_revision >= 0");
        assertCheckDrift("chk_execution_lease_fact_canonical",
                "AND lease_until > created_at", "AND 1 = 1");
        assertCheckDrift("chk_execution_grant_canonical",
                "AND issued_at>0 AND lease_until>issued_at", "AND issued_at>0 AND lease_until>0");
        assertCheckDrift("chk_execution_grant_canonical",
                "AND original_start_lease_fact_id REGEXP", "AND current_lease_fact_id REGEXP");
        assertCheckDrift("chk_execution_request_canonical",
                "AND runtime_binding_id REGEXP", "AND execution_request_id REGEXP");
        assertCheckDrift("chk_execution_request_canonical",
                "AND execution_grant_id IS NOT NULL", "AND 1 = 1");
        assertCheckDrift("chk_execution_request_canonical",
                "AND denial_code IS NOT NULL", "AND 1 = 1");
    }


    @Test
    void finalR2a2ChecksRejectNullProjectionBypassesAndFencePreStartRejection() {
        JdbcTemplate jdbc = h2();
        String inboxCheck = AgentRuntimeV2PersistenceSchemaGate.expectedCheckClauses()
                .get("chk_inbox_runtime_v2_projection");
        jdbc.execute("""
                CREATE TABLE inbox_probe(
                  id INT PRIMARY KEY, protocol_version INT,
                  runtime_dispatch_id VARCHAR(36), runtime_binding_id VARCHAR(36),
                  runtime_binding_generation BIGINT, dispatch_runtime_instance_id VARCHAR(100),
                  dispatch_runtime_credential_client_id VARCHAR(50),
                  dispatch_receiver_registration_revision BIGINT, dispatch_prepared_at BIGINT,
                  execution_started_at BIGINT, execution_lease_until BIGINT,
                  execution_lease_revision BIGINT, execution_holder_binding_id VARCHAR(36),
                  execution_holder_binding_generation BIGINT,
                  execution_holder_runtime_instance_id VARCHAR(100),
                  execution_current_lease_fact_id VARCHAR(36), ack_message_id VARCHAR(100),
                  ack_runtime_binding_id VARCHAR(36), ack_runtime_binding_generation BIGINT,
                  ack_committed_at BIGINT, ack_status VARCHAR(20), ack_at BIGINT,
                  CONSTRAINT chk_inbox_runtime_v2_projection CHECK (%s))
                """.formatted(inboxCheck));
        String dispatch = "00000000-0000-4000-8000-000000000001";
        String binding = "00000000-0000-4000-8000-000000000002";
        jdbc.update("""
                INSERT INTO inbox_probe(id,protocol_version,runtime_dispatch_id,runtime_binding_id,
                  runtime_binding_generation,dispatch_runtime_instance_id,
                  dispatch_runtime_credential_client_id,dispatch_receiver_registration_revision,
                  dispatch_prepared_at) VALUES(1,2,?,?,3,'runtime-a','credential-r',17,100)
                """, dispatch, binding);
        for (String column : List.of("protocol_version", "runtime_dispatch_id", "runtime_binding_id",
                "runtime_binding_generation", "dispatch_runtime_instance_id",
                "dispatch_runtime_credential_client_id", "dispatch_receiver_registration_revision",
                "dispatch_prepared_at")) {
            assertRejectedUpdate(jdbc, "inbox_probe", column + "=NULL");
        }
        assertEquals(1, jdbc.update("""
                UPDATE inbox_probe SET ack_message_id='reject-a',ack_runtime_binding_id=?,
                  ack_runtime_binding_generation=3,ack_committed_at=101,ack_status='REJECTED',ack_at=100
                WHERE id=1
                """, binding));
        assertRejectedUpdate(jdbc, "inbox_probe", "ack_runtime_binding_generation=4");
        assertEquals(1, jdbc.update("""
                UPDATE inbox_probe SET ack_message_id=NULL,ack_runtime_binding_id=NULL,
                  ack_runtime_binding_generation=NULL,ack_committed_at=NULL,ack_status=NULL,ack_at=NULL,
                  execution_started_at=101,execution_lease_until=1000,execution_lease_revision=0,
                  execution_holder_binding_id=?,execution_holder_binding_generation=3,
                  execution_holder_runtime_instance_id='runtime-a',execution_current_lease_fact_id=?
                WHERE id=1
                """, binding, "00000000-0000-4000-8000-000000000003"));
        for (String column : List.of("execution_started_at", "execution_lease_until",
                "execution_lease_revision", "execution_holder_binding_id",
                "execution_holder_binding_generation", "execution_holder_runtime_instance_id",
                "execution_current_lease_fact_id")) {
            assertRejectedUpdate(jdbc, "inbox_probe", column + "=NULL");
        }

        String receiptCheck = AgentRuntimeV2PersistenceSchemaGate.expectedCheckClauses()
                .get("chk_ack_receipt_canonical");
        jdbc.execute("""
                CREATE TABLE receipt_probe(
                  id INT PRIMARY KEY,receipt_id VARCHAR(36),runtime_dispatch_id VARCHAR(36),
                  runtime_binding_id VARCHAR(36),inbox_id BIGINT,delivery_id BIGINT,
                  ack_message_id VARCHAR(100),source_message_id VARCHAR(100),status VARCHAR(20),
                  ack_at BIGINT,dispatch_binding_generation BIGINT,runtime_instance_id VARCHAR(100),
                  data_domain_id VARCHAR(64),execution_pool_id VARCHAR(100),owner_jiacn VARCHAR(100),
                  agent_id VARCHAR(100),producer_deployment_id VARCHAR(100),
                  claimant_deployment_id VARCHAR(100),claimant_worker_generation BIGINT,
                  admission_epoch BIGINT,module_id VARCHAR(64),migration_epoch BIGINT,
                  postcommit_delivery_version BIGINT,postcommit_inbox_version BIGINT,
                  lease_fact_id VARCHAR(36),contract_version INT,committed_at BIGINT,
                  ack_holder_binding_generation BIGINT,runtime_credential_client_id VARCHAR(50),
                  receiver_registration_revision BIGINT,receipt_wire_bytes VARBINARY(4096),
                  receipt_wire_sha256 BINARY(32),receipt_wire_contract_version INT,
                  CONSTRAINT chk_ack_receipt_canonical CHECK (%s))
                """.formatted(receiptCheck));
        jdbc.update("""
                INSERT INTO receipt_probe VALUES(1,?,?,?,1,1,'reject-a','source-a','REJECTED',
                  100,3,'runtime-a','cyf','pool-a','owner-a','agent-a','producer-a','claimant-b',
                  7,11,'agent-command',13,2,3,NULL,2,101,3,'credential-r',17,?, ?,2)
                """, "00000000-0000-4000-8000-000000000004", dispatch, binding,
                new byte[]{1}, new byte[32]);
        for (String column : List.of("ack_holder_binding_generation",
                "runtime_credential_client_id", "receiver_registration_revision",
                "receipt_wire_bytes", "receipt_wire_sha256", "receipt_wire_contract_version")) {
            assertRejectedUpdate(jdbc, "receipt_probe", column + "=NULL");
        }
        assertRejectedUpdate(jdbc, "receipt_probe", "ack_holder_binding_generation=4");
        assertEquals(1, jdbc.update("""
                UPDATE receipt_probe SET lease_fact_id=?,ack_holder_binding_generation=4 WHERE id=1
                """, "00000000-0000-4000-8000-000000000005"));

        String leaseCheck = AgentRuntimeV2PersistenceSchemaGate.expectedCheckClauses()
                .get("chk_execution_lease_fact_canonical");
        jdbc.execute("""
                CREATE TABLE lease_probe(
                  id INT PRIMARY KEY,lease_fact_id VARCHAR(36),runtime_dispatch_id VARCHAR(36),
                  runtime_binding_id VARCHAR(36),inbox_id BIGINT,delivery_id BIGINT,
                  fact_sequence BIGINT,lease_revision BIGINT,fact_type VARCHAR(20),
                  previous_lease_fact_id VARCHAR(36),effective_at BIGINT,
                  dispatch_binding_generation BIGINT,holder_binding_generation BIGINT,
                  runtime_instance_id VARCHAR(100),runtime_credential_client_id VARCHAR(50),
                  data_domain_id VARCHAR(64),execution_pool_id VARCHAR(100),owner_jiacn VARCHAR(100),
                  agent_id VARCHAR(100),producer_deployment_id VARCHAR(100),
                  claimant_deployment_id VARCHAR(100),claimant_worker_generation BIGINT,
                  admission_epoch BIGINT,receiver_registration_revision BIGINT,module_id VARCHAR(64),
                  migration_epoch BIGINT,lease_until BIGINT,causing_receipt_id VARCHAR(36),
                  tenant_control_operation_id VARCHAR(100),tenant_control_revision BIGINT,
                  tenant_control_cutoff_at BIGINT,module_control_operation_id VARCHAR(100),
                  module_mode_revision BIGINT,module_control_cutoff_at BIGINT,
                  deployment_control_operation_id VARCHAR(100),deployment_registration_revision BIGINT,
                  deployment_control_cutoff_at BIGINT,reason VARCHAR(500),created_at BIGINT,
                  CONSTRAINT chk_execution_lease_fact_canonical CHECK (%s))
                """.formatted(leaseCheck));
        jdbc.update("""
                INSERT INTO lease_probe VALUES(1,?,?,?,1,1,0,0,'START_GRANTED',NULL,100,
                  3,3,'runtime-a','credential-r','cyf','pool-a','owner-a','agent-a','producer-a',
                  'claimant-b',7,11,17,'agent-command',13,1000,?,NULL,0,NULL,NULL,0,NULL,NULL,17,
                  NULL,'start',101)
                """, "00000000-0000-4000-8000-000000000006", dispatch, binding,
                "00000000-0000-4000-8000-000000000007");
        for (String column : List.of("holder_binding_generation",
                "runtime_credential_client_id", "receiver_registration_revision")) {
            assertRejectedUpdate(jdbc, "lease_probe", column + "=NULL");
        }
        assertRejectedUpdate(jdbc, "lease_probe", "lease_until=created_at");
        assertEquals(1, jdbc.update("""
                UPDATE lease_probe SET fact_sequence=1,lease_revision=0,fact_type='EXPIRED',
                  previous_lease_fact_id=?,effective_at=200,lease_until=NULL,
                  causing_receipt_id=NULL,created_at=201 WHERE id=1
                """, "00000000-0000-4000-8000-000000000006"));

        String grantCheck = AgentRuntimeV2PersistenceSchemaGate.expectedCheckClauses()
                .get("chk_execution_grant_canonical");
        jdbc.execute("""
                CREATE TABLE grant_probe(
                  id INT PRIMARY KEY,execution_grant_id VARCHAR(36),grant_kind VARCHAR(20),
                  runtime_dispatch_id VARCHAR(36),started_receipt_id VARCHAR(36),
                  started_receipt_sha256 BINARY(32),runtime_binding_id VARCHAR(36),
                  runtime_instance_id VARCHAR(100),dispatch_binding_generation BIGINT,
                  holder_binding_generation BIGINT,original_start_lease_fact_id VARCHAR(36),
                  current_lease_fact_id VARCHAR(36),lease_revision BIGINT,lease_until BIGINT,
                  previous_execution_grant_id VARCHAR(36),issued_at BIGINT,
                  grant_wire_bytes VARBINARY(4096),grant_wire_sha256 BINARY(32),
                  grant_wire_contract_version INT,data_domain_id VARCHAR(64),
                  execution_pool_id VARCHAR(100),runtime_credential_client_id VARCHAR(50),
                  owner_jiacn VARCHAR(100),agent_id VARCHAR(100),producer_deployment_id VARCHAR(100),
                  claimant_deployment_id VARCHAR(100),claimant_worker_generation BIGINT,
                  receiver_registration_revision BIGINT,admission_epoch BIGINT,module_id VARCHAR(64),
                  migration_epoch BIGINT,CONSTRAINT chk_execution_grant_canonical CHECK (%s))
                """.formatted(grantCheck));
        String startFact = "00000000-0000-4000-8000-00000000000b";
        jdbc.update("""
                INSERT INTO grant_probe VALUES(1,?,'START',?,?,?,?,'runtime-a',3,3,?,?,0,1000,
                  NULL,101,?,?,2,'cyf','pool-a','credential-r','owner-a','agent-a','producer-a',
                  'claimant-b',7,17,11,'agent-command',13)
                """, "00000000-0000-4000-8000-00000000000c", dispatch,
                "00000000-0000-4000-8000-000000000004", new byte[32], binding,
                startFact, startFact, new byte[]{1}, new byte[32]);
        assertRejectedUpdate(jdbc, "grant_probe", "lease_until=issued_at");

        String requestCheck = AgentRuntimeV2PersistenceSchemaGate.expectedCheckClauses()
                .get("chk_execution_request_canonical");
        jdbc.execute("""
                CREATE TABLE request_probe(
                  id INT PRIMARY KEY,execution_request_id VARCHAR(36),request_kind VARCHAR(20),
                  request_fingerprint BINARY(32),runtime_dispatch_id VARCHAR(36),
                  started_receipt_id VARCHAR(36),runtime_binding_id VARCHAR(36),
                  runtime_instance_id VARCHAR(100),requested_holder_binding_generation BIGINT,
                  decision VARCHAR(20),execution_grant_id VARCHAR(36),denial_code VARCHAR(64),
                  decided_at BIGINT,contract_version INT,data_domain_id VARCHAR(64),
                  execution_pool_id VARCHAR(100),runtime_credential_client_id VARCHAR(50),
                  owner_jiacn VARCHAR(100),agent_id VARCHAR(100),producer_deployment_id VARCHAR(100),
                  claimant_deployment_id VARCHAR(100),claimant_worker_generation BIGINT,
                  receiver_registration_revision BIGINT,admission_epoch BIGINT,module_id VARCHAR(64),
                  migration_epoch BIGINT,
                  CONSTRAINT chk_execution_request_canonical CHECK (%s))
                """.formatted(requestCheck));
        jdbc.update("""
                INSERT INTO request_probe VALUES(1,?,'CONTINUATION',?,?,?,?,'runtime-a',3,
                  'GRANTED',?,NULL,102,2,'cyf','pool-a','credential-r','owner-a','agent-a',
                  'producer-a','claimant-b',7,17,11,'agent-command',13)
                """, "00000000-0000-4000-8000-000000000008", new byte[32], dispatch,
                "00000000-0000-4000-8000-000000000009", binding,
                "00000000-0000-4000-8000-00000000000a");
        assertRejectedUpdate(jdbc, "request_probe", "execution_grant_id=NULL");
        assertEquals(1, jdbc.update("""
                UPDATE request_probe SET decision='DENIED',execution_grant_id=NULL,
                  denial_code='CONTINUATION_NOT_AUTHORIZED' WHERE id=1
                """));
        assertRejectedUpdate(jdbc, "request_probe", "denial_code=NULL");
    }


    @Test
    void manualMigrationChecksMatchTheExactGateContract() throws Exception {
        Path sqlPath = Path.of(System.getProperty("user.dir"), "..", "jia-agent-mapper",
                "src", "main", "resources", "db",
                "agent-runtime-v2-persistence-manual-20260930.sql").normalize();
        String sql = Files.readString(sqlPath);
        assertTrue(sql.contains("DROP KEY uk_execution_lease_fact_revision_type"));
        assertTrue(sql.contains("(runtime_dispatch_id,holder_binding_generation,lease_revision,fact_type)"));
        AgentRuntimeV2PersistenceSchemaGate.expectedCheckClauses().forEach((name, expected) ->
                assertEquals(AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(expected),
                        AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(checkClause(sql, name)), name));
    }

    @Test
    void runtimeGateIsDormantForLegacyOnlyDeploymentAndFailsClosedWhenExplicitlyEnabled() {
        new ApplicationContextRunner()

                .withUserConfiguration(AgentRuntimeV2PersistenceSchemaConfiguration.class)
                .run(context -> {
                    assertEquals(null, context.getStartupFailure());
                    assertTrue(context.getBeansOfType(AgentRuntimeV2PersistenceSchemaGate.class).isEmpty());
                });
        new ApplicationContextRunner()
                .withUserConfiguration(AgentRuntimeV2PersistenceSchemaConfiguration.class)
                .withPropertyValues("agent.runtime-v2-persistence.enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
    private static String checkClause(String sql, String name) {
        String marker = "CONSTRAINT " + name + " CHECK (";
        int start = sql.lastIndexOf(marker);
        if (start < 0) throw new IllegalArgumentException("missing CHECK " + name);
        start += marker.length();
        int depth = 1;
        boolean quoted = false;
        for (int index = start; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (character == '\'') {
                if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && character == '(') {
                depth++;
            } else if (!quoted && character == ')' && --depth == 0) {
                return sql.substring(start, index);
            }
        }
        throw new IllegalArgumentException("unterminated CHECK " + name);
    }


    private static JdbcTemplate h2() {
        String url = "jdbc:h2:mem:runtime_v2_checks_" + UUID.randomUUID()
                + ";MODE=MYSQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        return new JdbcTemplate(new SingleConnectionDataSource(url, "sa", "", true));
    }

    private static void assertRejectedUpdate(JdbcTemplate jdbc, String table, String assignment) {
        assertThrows(RuntimeException.class,
                () -> jdbc.update("UPDATE " + table + " SET " + assignment + " WHERE id=1"));
    }

    private static void assertCheckDrift(String name, String target, String replacement) {
        FakeCatalog catalog = canonical();
        String expected = AgentRuntimeV2PersistenceSchemaGate.expectedCheckClauses().get(name);
        String weakened = expected.replaceFirst(java.util.regex.Pattern.quote(target),
                java.util.regex.Matcher.quoteReplacement(replacement));
        assertNotEquals(expected, weakened, "negative fixture must alter " + name);
        catalog.checks.get(tableForCheck(name)).put(name, enforced(weakened));
        assertDenied(catalog);
    }

    private static void assertDenied(FakeCatalog catalog) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AgentRuntimeV2PersistenceSchemaGate(catalog).afterPropertiesSet());
        assertTrue(failure.getMessage().startsWith(
                "Agent runtime v2 persistence schema unavailable:"));
    }

    private static FakeCatalog canonical() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.columns.putAll(copyColumns(AgentRuntimeV2PersistenceSchemaGate.expectedColumns()));
        catalog.indexes.putAll(copyIndexes(AgentRuntimeV2PersistenceSchemaGate.expectedIndexes()));
        for (String table : AgentRuntimeV2PersistenceSchemaGate.TABLES) {
            catalog.tableNames.add(table);
            catalog.tables.put(table, new AgentRuntimeV2PersistenceSchemaGate.TableDefinition(
                    "InnoDB", "utf8mb4_0900_bin"));
            catalog.checks.put(table, new LinkedHashMap<>());
        }
        catalog.checks.get(AgentRuntimeV2PersistenceSchemaGate.INBOX).put(
                AgentCommandConsumerV2SchemaGate.SCOPE_CHECK, enforced(SCOPE));
        catalog.checks.get(AgentRuntimeV2PersistenceSchemaGate.INBOX).put(
                AgentCommandConsumerV2SchemaGate.ACK_EVIDENCE_CHECK, enforced(ACK));
        AgentRuntimeV2PersistenceSchemaGate.expectedCheckClauses().forEach((name, clause) ->
                catalog.checks.get(tableForCheck(name)).put(name, enforced(clause)));
        return catalog;
    }

    private static FakeCatalog registrationCanonical() {
        FakeCatalog catalog = canonical();
        catalog.columns.clear();
        catalog.columns.putAll(copyColumns(AgentRuntimeV2PersistenceSchemaGate.registrationExtendedColumns()));
        catalog.indexes.clear();
        catalog.indexes.putAll(copyIndexes(AgentRuntimeV2PersistenceSchemaGate.registrationExtendedIndexes()));
        AgentRuntimeV2RegistrationSchemaGate.expectedChecks().forEach((table, checks) -> {
            if (AgentRuntimeV2PersistenceSchemaGate.TABLES.contains(table)) {
                checks.forEach((name, clause) -> catalog.checks.get(table).put(name, enforced(clause)));
            }
        });
        return catalog;
    }

    private static String tableForCheck(String name) {
        return switch (name) {
            case "chk_inbox_runtime_v2_projection" -> AgentRuntimeV2PersistenceSchemaGate.INBOX;
            case "chk_runtime_v2_binding_canonical", "chk_runtime_v2_binding_state",
                    "chk_runtime_v2_binding_route" -> AgentRuntimeV2PersistenceSchemaGate.BINDING;
            case "chk_binding_generation_fact_canonical" -> AgentRuntimeV2PersistenceSchemaGate.GENERATION_FACT;
            case "chk_ack_receipt_canonical" -> AgentRuntimeV2PersistenceSchemaGate.RECEIPT;
            case "chk_execution_lease_fact_canonical" -> AgentRuntimeV2PersistenceSchemaGate.LEASE_FACT;
            case "chk_execution_grant_canonical" -> AgentRuntimeV2PersistenceSchemaGate.GRANT;
            case "chk_execution_continuation_anchor" -> AgentRuntimeV2PersistenceSchemaGate.ANCHOR;
            case "chk_execution_request_canonical" -> AgentRuntimeV2PersistenceSchemaGate.REQUEST;
            default -> throw new IllegalArgumentException("unexpected check " + name);
        };
    }
    private static AgentRuntimeV2PersistenceSchemaGate.CheckDefinition enforced(String clause) {
        return new AgentRuntimeV2PersistenceSchemaGate.CheckDefinition(clause, true);
    }

    private static Map<String,List<AgentRuntimeV2PersistenceSchemaGate.ColumnDefinition>> copyColumns(
            Map<String,List<AgentRuntimeV2PersistenceSchemaGate.ColumnDefinition>> source) {
        Map<String,List<AgentRuntimeV2PersistenceSchemaGate.ColumnDefinition>> copy=new LinkedHashMap<>();
        source.forEach((k,v)->copy.put(k,new ArrayList<>(v))); return copy;
    }
    private static void replaceColumn(
            List<AgentRuntimeV2PersistenceSchemaGate.ColumnDefinition> columns, String name,
            java.util.function.Function<AgentRuntimeV2PersistenceSchemaGate.ColumnDefinition,
                    AgentRuntimeV2PersistenceSchemaGate.ColumnDefinition> replacement) {
        for (int index = 0; index < columns.size(); index++) {
            if (name.equals(columns.get(index).name())) {
                columns.set(index, replacement.apply(columns.get(index)));
                return;
            }
        }
        throw new AssertionError("column not found: " + name);
    }
    private static Map<String,Map<String,AgentRuntimeV2PersistenceSchemaGate.IndexDefinition>> copyIndexes(
            Map<String,Map<String,AgentRuntimeV2PersistenceSchemaGate.IndexDefinition>> source) {
        Map<String,Map<String,AgentRuntimeV2PersistenceSchemaGate.IndexDefinition>> copy=new LinkedHashMap<>();
        source.forEach((k,v)->copy.put(k,new LinkedHashMap<>(v))); return copy;
    }

    private static final class FakeCatalog implements AgentRuntimeV2PersistenceSchemaGate.Catalog {
        String product="MySQL"; int reads; final List<String> tableNames=new ArrayList<>();
        final Map<String,AgentRuntimeV2PersistenceSchemaGate.TableDefinition> tables=new LinkedHashMap<>();
        final Map<String,List<AgentRuntimeV2PersistenceSchemaGate.ColumnDefinition>> columns=new LinkedHashMap<>();
        final Map<String,Map<String,AgentRuntimeV2PersistenceSchemaGate.IndexDefinition>> indexes=new LinkedHashMap<>();
        final Map<String,Map<String,AgentRuntimeV2PersistenceSchemaGate.CheckDefinition>> checks=new LinkedHashMap<>();
        public String databaseProduct(){reads++;return product;}
        public List<String> presentTables(){reads++;return tableNames.stream().sorted().toList();}
        public AgentRuntimeV2PersistenceSchemaGate.TableDefinition table(String t){reads++;return tables.get(t);}
        public List<AgentRuntimeV2PersistenceSchemaGate.ColumnDefinition> columns(String t){reads++;return List.copyOf(columns.get(t));}
        public Map<String,AgentRuntimeV2PersistenceSchemaGate.IndexDefinition> indexes(String t){reads++;return Map.copyOf(indexes.get(t));}
        public int participatingForeignKeyCount(String t){reads++;return 0;}
        public Map<String,AgentRuntimeV2PersistenceSchemaGate.CheckDefinition> checks(String t){reads++;return Map.copyOf(checks.get(t));}

        AgentCommandConsumerV2SchemaGate.Catalog consumerView() {
            return new AgentCommandConsumerV2SchemaGate.Catalog() {
                public String databaseProduct(){return product;}
                public List<String> presentTables(){return List.of(AgentRuntimeV2PersistenceSchemaGate.INBOX);}
                public AgentCommandConsumerV2SchemaGate.TableDefinition table(String t){var d=tables.get(t);return new AgentCommandConsumerV2SchemaGate.TableDefinition(d.engine(),d.collation());}
                public List<AgentCommandConsumerV2SchemaGate.ColumnDefinition> columns(String t){return columns.get(t).stream().map(c->new AgentCommandConsumerV2SchemaGate.ColumnDefinition(c.name(),c.dataType(),c.columnType(),c.nullable(),c.defaultValue(),c.collation(),c.extra(),c.generationExpression())).toList();}
                public Map<String,AgentCommandConsumerV2SchemaGate.IndexDefinition> indexes(String t){Map<String,AgentCommandConsumerV2SchemaGate.IndexDefinition> out=new LinkedHashMap<>();indexes.get(t).forEach((n,i)->out.put(n,new AgentCommandConsumerV2SchemaGate.IndexDefinition(i.unique(),i.columns())));return out;}
                public int participatingForeignKeyCount(String t){return 0;}
                public Map<String,AgentCommandConsumerV2SchemaGate.CheckDefinition> checks(String t){Map<String,AgentCommandConsumerV2SchemaGate.CheckDefinition> out=new LinkedHashMap<>();checks.get(t).forEach((n,c)->out.put(n,new AgentCommandConsumerV2SchemaGate.CheckDefinition(c.clause(),c.enforced())));return out;}
            };
        }
    }
}
