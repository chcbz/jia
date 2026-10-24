package cn.jia.agent.config;

import cn.jia.agent.dao.AgentCommandInboxV2Dao;
import cn.jia.agent.service.AgentCommandInboxV2Service;
import cn.jia.agent.service.AgentCommandAckV2Service;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentCommandConsumerV2SchemaGateTest {
    private static final WorkerScope WORKER_SCOPE = new WorkerScope(TenantId.of("1"),
            DataDomainId.of("cyf"), "local-test", "consumer-a", 3, 4, 7, "agent-command", 9);
    private static final String CHECK = """
            (((protocol_version IS NULL OR protocol_version = 1)
              AND tenant_id = '0'
              AND data_domain_id IS NULL
              AND execution_pool_id IS NULL
              AND admission_epoch IS NULL
              AND producer_deployment_id IS NULL
              AND module_id IS NULL
              AND migration_epoch IS NULL
              AND claimant_deployment_id IS NULL
              AND claimant_worker_generation IS NULL)
             OR
             (protocol_version = 2
              AND OCTET_LENGTH(data_domain_id) BETWEEN 1 AND 64
              AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
              AND admission_epoch >= 0
              AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
              AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
              AND migration_epoch >= 0
              AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
              AND claimant_worker_generation >= 0))
            """;
    private static final String ACK_CHECK = """
            (((protocol_version IS NULL OR protocol_version = 1)
              AND claim_delivery_version IS NULL
              AND claim_inbox_version IS NULL
              AND claim_started_at IS NULL
              AND ack_delivery_version IS NULL
              AND ack_message_id IS NULL
              AND ack_status IS NULL
              AND ack_at IS NULL
              AND ack_agent_id IS NULL
              AND ack_runtime_installation_id IS NULL
              AND ack_runtime_instance_id IS NULL)
             OR
             (protocol_version = 2
              AND ((attempt_count = 0 AND active_attempt = 0 AND status = 'EXPIRED'
                    AND claim_delivery_version IS NULL
                    AND claim_inbox_version IS NULL
                    AND claim_started_at IS NULL)
                   OR
                   (attempt_count >= 1 AND active_attempt >= 1
                    AND claim_delivery_version IS NOT NULL
                    AND claim_delivery_version >= 0
                    AND claim_inbox_version IS NOT NULL
                    AND claim_inbox_version >= 0
                    AND claim_started_at IS NOT NULL
                    AND claim_started_at > 0))
              AND ((ack_delivery_version IS NULL
                    AND ack_message_id IS NULL
                    AND ack_status IS NULL
                    AND ack_at IS NULL
                    AND ack_agent_id IS NULL
                    AND ack_runtime_installation_id IS NULL
                    AND ack_runtime_instance_id IS NULL)
                   OR
                   (ack_delivery_version IS NOT NULL
                    AND ack_delivery_version > claim_delivery_version
                    AND ack_message_id IS NOT NULL
                    AND OCTET_LENGTH(ack_message_id) BETWEEN 1 AND 100
                    AND ack_status IS NOT NULL
                    AND ack_status IN ('RECEIVED','STARTED','SUCCEEDED','FAILED','REJECTED')
                    AND ack_at IS NOT NULL AND ack_at > 0
                    AND ack_agent_id IS NOT NULL
                    AND OCTET_LENGTH(ack_agent_id) BETWEEN 1 AND 100
                    AND ack_runtime_installation_id IS NOT NULL
                    AND OCTET_LENGTH(ack_runtime_installation_id) BETWEEN 1 AND 100
                    AND ack_runtime_instance_id IS NOT NULL
                    AND OCTET_LENGTH(ack_runtime_instance_id) BETWEEN 1 AND 100))))
            """;

    @Test
    void acceptsLengthAndDelimiterRepresentationsButNotRegexOrStatusCaseDrift() {
        for (boolean escaped : List.of(false, true)) {
            FakeCatalog catalog = canonicalCatalog();
            String scope = CHECK.replace("OCTET_LENGTH(", "LENGTH(");
            String ack = ACK_CHECK.replace("OCTET_LENGTH(", "LENGTH(");
            if (escaped) {
                scope = scope.replaceAll("'([^']*)'", "_utf8mb4\\\\'$1\\\\'");
                ack = ack.replaceAll("'([^']*)'", "_utf8mb4\\\\'$1\\\\'");
            }
            catalog.checks.put(AgentCommandConsumerV2SchemaGate.SCOPE_CHECK, enforced(scope));
            catalog.checks.put(AgentCommandConsumerV2SchemaGate.ACK_EVIDENCE_CHECK, enforced(ack));
            assertDoesNotThrow(() -> new AgentCommandConsumerV2SchemaGate(catalog).afterPropertiesSet());
        }
        for (String mutation : List.of(ACK_CHECK.replace("'EXPIRED'", "'expired'"),
                ACK_CHECK.replace("'RECEIVED'", "'received'"), ACK_CHECK + " OR 1=1")) {
            FakeCatalog catalog = canonicalCatalog();
            catalog.checks.put(AgentCommandConsumerV2SchemaGate.ACK_EVIDENCE_CHECK, enforced(mutation));
            assertDenied(catalog);
        }
        FakeCatalog uppercase = canonicalCatalog();
        uppercase.checks.put(AgentCommandConsumerV2SchemaGate.SCOPE_CHECK,
                enforced(CHECK.replace("^[a-z0-9-]{1,100}$", "^[A-Z0-9-]{1,100}$")));
        assertDenied(uppercase);
    }

    @Test
    void acceptsOnlyCompleteManualConsumerV2Catalog() {
        FakeCatalog catalog = canonicalCatalog();

        assertDoesNotThrow(() -> new AgentCommandConsumerV2SchemaGate(catalog).afterPropertiesSet());
        assertTrue(catalog.reads > 0);
    }

    @Test
    void acceptsMysqlRegexpLikeRenderingButNotForgedIdentifiers() {
        FakeCatalog catalog = canonicalCatalog();
        catalog.checks.put(AgentCommandConsumerV2SchemaGate.SCOPE_CHECK, enforced(CHECK
                .replace("execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'",
                        "regexp_like(`execution_pool_id`, _utf8mb4'^[a-z0-9-]{1,100}$')")
                .replace("module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'",
                        "regexp_like(`module_id`, _utf8mb4'^[a-z][a-z0-9_-]{0,63}$')")));
        assertDoesNotThrow(() -> new AgentCommandConsumerV2SchemaGate(catalog).afterPropertiesSet());

        FakeCatalog forged = canonicalCatalog();
        forged.checks.put(AgentCommandConsumerV2SchemaGate.SCOPE_CHECK,
                enforced(CHECK.replace("execution_pool_id", "execution_pool_utf8mb4_id")));
        assertDenied(forged);
    }

    @Test
    void rejectsProductTableColumnEngineCollationAndForeignKeyDrift() {
        assertTrue(AgentCommandConsumerV2SchemaGate.PARTICIPATING_FOREIGN_KEYS_SQL
                .contains("information_schema.referential_constraints"));
        assertTrue(AgentCommandConsumerV2SchemaGate.PARTICIPATING_FOREIGN_KEYS_SQL
                .contains("unique_constraint_schema=DATABASE() AND referenced_table_name=?"));
        FakeCatalog product = canonicalCatalog();
        product.product = "PostgreSQL";
        assertDenied(product);

        FakeCatalog missingTable = canonicalCatalog();
        missingTable.tables.clear();
        assertDenied(missingTable);

        FakeCatalog extraTable = canonicalCatalog();
        extraTable.tables.add("shadow_inbox");
        assertDenied(extraTable);

        FakeCatalog missingColumn = canonicalCatalog();
        missingColumn.columns.removeIf(column -> column.name().equals("claimant_deployment_id"));
        assertDenied(missingColumn);

        FakeCatalog missingAckColumn = canonicalCatalog();
        missingAckColumn.columns.removeIf(column -> column.name().equals("ack_runtime_instance_id"));
        assertDenied(missingAckColumn);

        FakeCatalog reordered = canonicalCatalog();
        AgentCommandConsumerV2SchemaGate.ColumnDefinition first = reordered.columns.removeFirst();
        reordered.columns.add(first);
        assertDenied(reordered);

        FakeCatalog wrongType = canonicalCatalog();
        replaceColumn(wrongType, new AgentCommandConsumerV2SchemaGate.ColumnDefinition(
                "execution_pool_id", "varchar", "varchar(101)", true, null,
                "utf8mb4_0900_bin", "", ""));
        assertDenied(wrongType);

        FakeCatalog wrongEngine = canonicalCatalog();
        wrongEngine.table = new AgentCommandConsumerV2SchemaGate.TableDefinition(
                "MyISAM", "utf8mb4_0900_bin");
        assertDenied(wrongEngine);

        FakeCatalog wrongCollation = canonicalCatalog();
        wrongCollation.table = new AgentCommandConsumerV2SchemaGate.TableDefinition(
                "InnoDB", "utf8mb4_general_ci");
        assertDenied(wrongCollation);

        FakeCatalog foreignKey = canonicalCatalog();
        foreignKey.foreignKeys = 1;
        assertDenied(foreignKey);
    }

    @Test
    void rejectsBaseAndV2IndexDrift() {
        FakeCatalog missingBase = canonicalCatalog();
        missingBase.indexes.remove("idx_inbox_retry");
        assertDenied(missingBase);

        FakeCatalog missingV2 = canonicalCatalog();
        missingV2.indexes.remove("idx_inbox_consumer_v2_identity");
        assertDenied(missingV2);

        FakeCatalog uniqueDrift = canonicalCatalog();
        AgentCommandConsumerV2SchemaGate.IndexDefinition retry =
                uniqueDrift.indexes.get("idx_inbox_consumer_v2_retry");
        uniqueDrift.indexes.put("idx_inbox_consumer_v2_retry",
                new AgentCommandConsumerV2SchemaGate.IndexDefinition(true, retry.columns()));
        assertDenied(uniqueDrift);

        FakeCatalog columnDrift = canonicalCatalog();
        uniqueDrift = canonicalCatalog();
        retry = uniqueDrift.indexes.get("idx_inbox_consumer_v2_retry");
        List<String> columns = new ArrayList<>(retry.columns());
        columns.set(columns.size() - 1, "version");
        columnDrift.indexes.put("idx_inbox_consumer_v2_retry",
                new AgentCommandConsumerV2SchemaGate.IndexDefinition(false, columns));
        assertDenied(columnDrift);
    }

    @Test
    void rawCatalogIndexRowsRejectPrefixVisibilityTypeSequenceAndMixedUniqueness() {
        List<AgentCommandConsumerV2SchemaGate.IndexColumn> canonical = List.of(
                row("uk", false, 1, "tenant_id", null, "BTREE", "YES"),
                row("uk", false, 2, "message_id", null, "BTREE", "YES"));
        assertEquals(new AgentCommandConsumerV2SchemaGate.IndexDefinition(
                        true, List.of("tenant_id", "message_id")),
                AgentCommandConsumerV2SchemaGate.indexDefinitions(canonical).get("uk"));

        assertIndexRowsDenied(List.of(row("x", true, 1, "tenant_id", 5, "BTREE", "YES")));
        assertIndexRowsDenied(List.of(row("x", true, 1, "tenant_id", null, "HASH", "YES")));
        assertIndexRowsDenied(List.of(row("x", true, 1, "tenant_id", null, "BTREE", "NO")));
        assertIndexRowsDenied(List.of(row("x", true, 2, "tenant_id", null, "BTREE", "YES")));
        assertIndexRowsDenied(List.of(
                row("x", true, 1, "tenant_id", null, "BTREE", "YES"),
                row("x", false, 2, "message_id", null, "BTREE", "YES")));
    }

    @Test
    void rejectsMissingWeakExtraOrUnenforcedScopeCheck() {
        FakeCatalog missing = canonicalCatalog();
        missing.checks.clear();
        assertDenied(missing);

        FakeCatalog weakLegacyTenant = canonicalCatalog();
        weakLegacyTenant.checks.put(AgentCommandConsumerV2SchemaGate.SCOPE_CHECK,
                enforced(CHECK.replace("AND tenant_id = '0'", "")));
        assertDenied(weakLegacyTenant);

        FakeCatalog weakProtocol = canonicalCatalog();
        weakProtocol.checks.put(AgentCommandConsumerV2SchemaGate.SCOPE_CHECK,
                enforced(CHECK.replace("protocol_version = 1", "protocol_version <= 3")));
        assertDenied(weakProtocol);

        FakeCatalog weakPool = canonicalCatalog();
        weakPool.checks.put(AgentCommandConsumerV2SchemaGate.SCOPE_CHECK,
                enforced(CHECK.replace("^[a-z0-9-]{1,100}$", "^[A-Za-z0-9-]{1,100}$")));
        assertDenied(weakPool);

        FakeCatalog extra = canonicalCatalog();
        extra.checks.put("chk_inbox_consumer_v2_shadow", enforced("protocol_version <> 3"));
        assertDenied(extra);

        FakeCatalog unenforced = canonicalCatalog();
        unenforced.checks.put(AgentCommandConsumerV2SchemaGate.SCOPE_CHECK,
                new AgentCommandConsumerV2SchemaGate.CheckDefinition(CHECK, false));
        assertDenied(unenforced);

        FakeCatalog missingAck = canonicalCatalog();
        missingAck.checks.remove(AgentCommandConsumerV2SchemaGate.ACK_EVIDENCE_CHECK);
        assertDenied(missingAck);

        FakeCatalog weakAckVersion = canonicalCatalog();
        weakAckVersion.checks.put(AgentCommandConsumerV2SchemaGate.ACK_EVIDENCE_CHECK,
                enforced(ACK_CHECK.replace("ack_delivery_version > claim_delivery_version",
                        "ack_delivery_version >= claim_delivery_version")));
        assertDenied(weakAckVersion);

        FakeCatalog weakAckStatus = canonicalCatalog();
        weakAckStatus.checks.put(AgentCommandConsumerV2SchemaGate.ACK_EVIDENCE_CHECK,
                enforced(ACK_CHECK.replace(",'REJECTED'", ",'REJECTED','SENT'")));
        assertDenied(weakAckStatus);

        String legacyClaimEvidence = "  AND claim_inbox_version IS NULL\n"
                + "  AND claim_started_at IS NULL\n"
                + "  AND ack_delivery_version IS NULL";
        String legacyClaimEvidenceWithoutStart = "  AND claim_inbox_version IS NULL\n"
                + "  AND ack_delivery_version IS NULL";
        int legacyClaimEvidenceStart = ACK_CHECK.indexOf(legacyClaimEvidence);
        assertTrue(legacyClaimEvidenceStart >= 0);
        assertEquals(legacyClaimEvidenceStart, ACK_CHECK.lastIndexOf(legacyClaimEvidence));
        String missingLegacyClaimStartedAt = ACK_CHECK.replace(
                legacyClaimEvidence, legacyClaimEvidenceWithoutStart);
        assertEquals(ACK_CHECK.substring(0, legacyClaimEvidenceStart)
                        + legacyClaimEvidenceWithoutStart
                        + ACK_CHECK.substring(legacyClaimEvidenceStart + legacyClaimEvidence.length()),
                missingLegacyClaimStartedAt);
        assertEquals(ACK_CHECK.length()
                        - (legacyClaimEvidence.length() - legacyClaimEvidenceWithoutStart.length()),
                missingLegacyClaimStartedAt.length());
        FakeCatalog weakLegacyClaimStartedAt = canonicalCatalog();
        weakLegacyClaimStartedAt.checks.put(AgentCommandConsumerV2SchemaGate.ACK_EVIDENCE_CHECK,
                enforced(missingLegacyClaimStartedAt));
        assertDenied(weakLegacyClaimStartedAt);

        FakeCatalog unenforcedAck = canonicalCatalog();
        unenforcedAck.checks.put(AgentCommandConsumerV2SchemaGate.ACK_EVIDENCE_CHECK,
                new AgentCommandConsumerV2SchemaGate.CheckDefinition(ACK_CHECK, false));
        assertDenied(unenforcedAck);
    }

    @Test
    void defaultConfigurationStaysDormant() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentCommandConsumerV2Configuration.class)
                .run(context -> {
                    assertEquals(null, context.getStartupFailure());
                    assertTrue(context.getBeansOfType(AgentCommandConsumerV2SchemaGate.class).isEmpty());
                    assertTrue(context.getBeansOfType(AgentCommandInboxV2Service.class).isEmpty());
                    assertTrue(context.getBeansOfType(AgentCommandAckV2Service.class).isEmpty());
                });
    }

    @Test
    void listenerFlagFailsClosedWithoutDedicatedDependencies() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentCommandConsumerV2Configuration.class)
                .withPropertyValues("agent.rabbit-consume-v2.enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void consumerCoreFailsClosedWithoutRelaySchemaGate() {
        new ApplicationContextRunner()
                .withUserConfiguration(CoreDependencies.class,
                        AgentCommandConsumerV2Configuration.class)
                .withPropertyValues("agent.rabbit-consumer-v2-core.enabled=true")
                .run(context -> {
                    Throwable failure = rootCause(context.getStartupFailure());
                    assertTrue(failure.getMessage().contains("agentOutboxRelayV2SchemaGate"));
                });
    }

    @Test
    void consumerCoreRegistersOnlyWithBothSchemaGatesAndWorkerAuthority() {
        new ApplicationContextRunner()
                .withUserConfiguration(CoreDependencies.class, RelayGateDependency.class,
                        AgentCommandConsumerV2Configuration.class)
                .withPropertyValues("agent.rabbit-consumer-v2-core.enabled=true")
                .run(context -> {
                    assertEquals(null, context.getStartupFailure());
                    assertNotNull(context.getBean("agentCommandInboxV2Service",
                            AgentCommandInboxV2Service.class));
                    assertNotNull(context.getBean("agentCommandAckV2Service",
                            AgentCommandAckV2Service.class));
                });
    }

    @Test
    void consumerCoreSuppressesLegacyTransportInitializerCondition() {
        new ApplicationContextRunner()
                .withUserConfiguration(LegacyConditionProbe.class)
                .withPropertyValues(
                        "agent.command-outbox.enabled=true",
                        "agent.rabbit-consumer-v2-core.enabled=true")
                .run(context -> {
                    assertEquals(null, context.getStartupFailure());
                    assertTrue(context.getBeansOfType(Integer.class).isEmpty());
                });
    }

    private static Throwable rootCause(Throwable failure) {
        assertNotNull(failure);
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static AgentCommandConsumerV2SchemaGate.IndexColumn row(
            String name, boolean nonUnique, int sequence, String column,
            Integer prefixLength, String indexType, String visible) {
        return new AgentCommandConsumerV2SchemaGate.IndexColumn(
                name, nonUnique, sequence, column, prefixLength, indexType, visible);
    }

    private static void assertIndexRowsDenied(
            List<AgentCommandConsumerV2SchemaGate.IndexColumn> rows) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> AgentCommandConsumerV2SchemaGate.indexDefinitions(rows));
        assertTrue(failure.getMessage().startsWith("Agent consumer v2 schema unavailable:"));
    }

    private static void assertDenied(FakeCatalog catalog) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AgentCommandConsumerV2SchemaGate(catalog).afterPropertiesSet());
        assertTrue(failure.getMessage().startsWith("Agent consumer v2 schema unavailable:"));
    }

    private static void replaceColumn(FakeCatalog catalog,
            AgentCommandConsumerV2SchemaGate.ColumnDefinition replacement) {
        for (int index = 0; index < catalog.columns.size(); index++) {
            if (catalog.columns.get(index).name().equals(replacement.name())) {
                catalog.columns.set(index, replacement);
                return;
            }
        }
        throw new AssertionError("missing test column " + replacement.name());
    }

    private static FakeCatalog canonicalCatalog() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.tables.add(AgentCommandConsumerV2SchemaGate.INBOX);
        catalog.columns.addAll(AgentCommandConsumerV2SchemaGate.expectedColumns());
        catalog.indexes.putAll(AgentCommandConsumerV2SchemaGate.expectedIndexes());
        catalog.checks.put(AgentCommandConsumerV2SchemaGate.SCOPE_CHECK, enforced(CHECK));
        catalog.checks.put(AgentCommandConsumerV2SchemaGate.ACK_EVIDENCE_CHECK,
                enforced(ACK_CHECK));
        return catalog;
    }

    private static AgentCommandConsumerV2SchemaGate.CheckDefinition enforced(String clause) {
        return new AgentCommandConsumerV2SchemaGate.CheckDefinition(clause, true);
    }

    private static final class FakeCatalog implements AgentCommandConsumerV2SchemaGate.Catalog {
        private String product = "MySQL";
        private final List<String> tables = new ArrayList<>();
        private AgentCommandConsumerV2SchemaGate.TableDefinition table =
                new AgentCommandConsumerV2SchemaGate.TableDefinition(
                        "InnoDB", "utf8mb4_0900_bin");
        private final List<AgentCommandConsumerV2SchemaGate.ColumnDefinition> columns =
                new ArrayList<>();
        private final Map<String, AgentCommandConsumerV2SchemaGate.IndexDefinition> indexes =
                new LinkedHashMap<>();
        private int foreignKeys;
        private final Map<String, AgentCommandConsumerV2SchemaGate.CheckDefinition> checks =
                new LinkedHashMap<>();
        private int reads;

        @Override
        public String databaseProduct() {
            reads++;
            return product;
        }

        @Override
        public List<String> presentTables() {
            reads++;
            return List.copyOf(tables);
        }

        @Override
        public AgentCommandConsumerV2SchemaGate.TableDefinition table(String tableName) {
            reads++;
            return table;
        }

        @Override
        public List<AgentCommandConsumerV2SchemaGate.ColumnDefinition> columns(String tableName) {
            reads++;
            return List.copyOf(columns);
        }

        @Override
        public Map<String, AgentCommandConsumerV2SchemaGate.IndexDefinition> indexes(String tableName) {
            reads++;
            return Map.copyOf(indexes);
        }

        @Override
        public int participatingForeignKeyCount(String tableName) {
            reads++;
            return foreignKeys;
        }

        @Override
        public Map<String, AgentCommandConsumerV2SchemaGate.CheckDefinition> checks(String tableName) {
            reads++;
            return Map.copyOf(checks);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CoreDependencies {
        @Bean(name = "agentCommandConsumerV2SchemaGate")
        AgentCommandConsumerV2SchemaGate consumerSchemaGate() {
            return mock(AgentCommandConsumerV2SchemaGate.class);
        }

        @Bean
        AgentCommandInboxV2Dao inboxDao() {
            return mock(AgentCommandInboxV2Dao.class);
        }

        @Bean(name = "agentOutboxWorkerScope")
        WorkerScope workerScope() {
            return WORKER_SCOPE;
        }

        @Bean(name = "agentOutboxTenantModuleAdmissionGuard")
        TenantModuleAdmissionGuard admissionGuard() {
            return mock(TenantModuleAdmissionGuard.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RelayGateDependency {
        @Bean(name = "agentOutboxRelayV2SchemaGate")
        AgentOutboxRelayV2SchemaGate relaySchemaGate() {
            return mock(AgentOutboxRelayV2SchemaGate.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LegacyConditionProbe {
        @Bean
        @Conditional(AgentCommandLegacyTransportSchemaEnabledCondition.class)
        Integer legacyInitializerMarker() {
            return 1;
        }
    }
}
