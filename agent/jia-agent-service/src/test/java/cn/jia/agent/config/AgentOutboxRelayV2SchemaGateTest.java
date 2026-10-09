package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOutboxRelayV2SchemaGateTest {
    private static final String RETAINED_NATIVE_CHECK = "(((`protocol_version` is null) and (`tenant_id` = _utf8mb4\\'0\\') and (`data_domain_id` is null) and (`execution_pool_id` is null) and (`admission_epoch` is null) and (`producer_deployment_id` is null) and (`module_id` is null) and (`migration_epoch` is null) and (`claimed_deployment_id` is null) and (`claimed_worker_generation` is null)) or ((`protocol_version` = 2) and (length(`data_domain_id`) between 1 and 64) and regexp_like(`execution_pool_id`,_utf8mb4\\'^[a-z0-9-]{1,100}$\\') and (`admission_epoch` >= 0) and (length(`producer_deployment_id`) between 1 and 100) and regexp_like(`module_id`,_utf8mb4\\'^[a-z][a-z0-9_-]{0,63}$\\') and (`migration_epoch` >= 0) and (((`claimed_deployment_id` is null) and (`claimed_worker_generation` is null)) or ((length(`claimed_deployment_id`) between 1 and 100) and (`claimed_worker_generation` >= 0)))))";
    private static final String CHECK = """
            ((protocol_version IS NULL
              AND tenant_id = '0'
              AND data_domain_id IS NULL AND execution_pool_id IS NULL
              AND admission_epoch IS NULL AND producer_deployment_id IS NULL
              AND module_id IS NULL AND migration_epoch IS NULL
              AND claimed_deployment_id IS NULL AND claimed_worker_generation IS NULL)
             OR
             (protocol_version = 2
              AND OCTET_LENGTH(data_domain_id) BETWEEN 1 AND 64
              AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
              AND admission_epoch >= 0
              AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
              AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
              AND migration_epoch >= 0
              AND ((claimed_deployment_id IS NULL AND claimed_worker_generation IS NULL)
                   OR (OCTET_LENGTH(claimed_deployment_id) BETWEEN 1 AND 100
                       AND claimed_worker_generation >= 0))))
            """;

    @Test
    void acceptsExactRetainedNativeClauseForBothTablesAndRejectsSemanticDrift() {
        FakeCatalog nativeCatalog = canonicalCatalog();
        nativeCatalog.checks.get(AgentOutboxRelayV2SchemaGate.DELIVERY)
                .put("chk_delivery_relay_v2_scope", enforced(RETAINED_NATIVE_CHECK));
        nativeCatalog.checks.get(AgentOutboxRelayV2SchemaGate.OUTBOX)
                .put("chk_outbox_relay_v2_scope", enforced(RETAINED_NATIVE_CHECK));
        assertDoesNotThrow(() -> new AgentOutboxRelayV2SchemaGate(nativeCatalog).afterPropertiesSet());
        for (String mutation : List.of(
                CHECK.replace("^[a-z0-9-]{1,100}$", "^[A-Z0-9-]{1,100}$"),
                CHECK.replace("BETWEEN 1 AND 64", "BETWEEN 1 AND 65"),
                CHECK.replace("AND tenant_id = '0'", ""),
                CHECK.replace("execution_pool_id", "execution`_pool`_id"),
                CHECK + " OR 1=1", CHECK + " trailing", CHECK.replace("'0'", "'0 ")))
            for (String table : List.of(AgentOutboxRelayV2SchemaGate.DELIVERY, AgentOutboxRelayV2SchemaGate.OUTBOX)) {
                FakeCatalog drift = canonicalCatalog();
                drift.checks.get(table).put(table.equals(AgentOutboxRelayV2SchemaGate.DELIVERY)
                        ? "chk_delivery_relay_v2_scope" : "chk_outbox_relay_v2_scope", enforced(mutation));
                assertDenied(drift);
            }
    }

    @Test
    void originalManualSourceChecksPassWithoutResourceChanges() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("user.dir"),
                "..", "jia-agent-mapper", "src", "main", "resources", "db",
                "agent-outbox-relay-v2-manual-20260925.sql").normalize());
        for (String name : List.of("chk_delivery_relay_v2_scope", "chk_outbox_relay_v2_scope")) {
            String marker = "CONSTRAINT " + name + " CHECK (";
            int start = source.indexOf(marker) + marker.length(), end = start, depth = 1;
            assertTrue(start >= marker.length());
            for (; depth > 0; end++) {
                char c = source.charAt(end);
                if (c == '(') depth++;
                if (c == ')') depth--;
            }
            assertTrue(AgentOutboxRelayV2SchemaGate.isCanonicalScopeCheck(source.substring(start, end - 1)));
        }
    }

    @Test
    void acceptsOnlyCompleteManualRelayV2Catalog() {
        FakeCatalog catalog = canonicalCatalog();
        assertDoesNotThrow(() -> new AgentOutboxRelayV2SchemaGate(catalog).afterPropertiesSet());
        assertTrue(catalog.reads > 0);
    }

    @Test
    void acceptsMysqlRegexpLikeCatalogRenderingWithoutChangingIdentifierText() {
        FakeCatalog catalog = canonicalCatalog();
        catalog.checks.get(AgentOutboxRelayV2SchemaGate.DELIVERY).put(
                "chk_delivery_relay_v2_scope", enforced(CHECK
                        .replace("execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'",
                                "regexp_like(`execution_pool_id`,_utf8mb4'^[a-z0-9-]{1,100}$')")
                        .replace("module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'",
                                "regexp_like(`module_id`,_utf8mb4'^[a-z][a-z0-9_-]{0,63}$')")));
        assertDoesNotThrow(() -> new AgentOutboxRelayV2SchemaGate(catalog).afterPropertiesSet());

        FakeCatalog forgedIdentifier = canonicalCatalog();
        forgedIdentifier.checks.get(AgentOutboxRelayV2SchemaGate.DELIVERY).put(
                "chk_delivery_relay_v2_scope",
                enforced(CHECK.replace("execution_pool_id", "execution_pool_utf8mb4_id")));
        assertDenied(forgedIdentifier);
    }

    @Test
    void rejectsLegacyMissingMalformedOrUnenforcedCatalogState() {
        FakeCatalog missingTable = canonicalCatalog();
        missingTable.tables.remove(AgentOutboxRelayV2SchemaGate.OUTBOX);
        assertDenied(missingTable);

        FakeCatalog missingColumn = canonicalCatalog();
        missingColumn.columns.get(AgentOutboxRelayV2SchemaGate.OUTBOX)
                .removeIf(column -> column.name().equals("wire_payload"));
        assertDenied(missingColumn);

        FakeCatalog malformedColumn = canonicalCatalog();
        replaceColumn(malformedColumn, AgentOutboxRelayV2SchemaGate.DELIVERY,
                new AgentOutboxRelayV2SchemaGate.ColumnDefinition(
                        "execution_pool_id", "varchar", "varchar(101)", true, null,
                        "utf8mb4_0900_bin", "", ""));
        assertDenied(malformedColumn);

        FakeCatalog legacyCheck = canonicalCatalog();
        legacyCheck.checks.get(AgentOutboxRelayV2SchemaGate.DELIVERY)
                .put("chk_delivery_single_tenant", enforced("tenant_id = '0'"));
        assertDenied(legacyCheck);

        FakeCatalog unenforced = canonicalCatalog();
        unenforced.checks.get(AgentOutboxRelayV2SchemaGate.OUTBOX).put(
                "chk_outbox_relay_v2_scope",
                new AgentOutboxRelayV2SchemaGate.CheckDefinition(CHECK, false));
        assertDenied(unenforced);
    }

    @Test
    void rejectsBaseIndexEngineCollationAndForeignKeyDrift() {
        FakeCatalog missingBaseIndex = canonicalCatalog();
        missingBaseIndex.indexes.get(AgentOutboxRelayV2SchemaGate.OUTBOX)
                .remove("idx_outbox_publish");
        assertDenied(missingBaseIndex);

        FakeCatalog badEngine = canonicalCatalog();
        badEngine.tableDefinitions.put(AgentOutboxRelayV2SchemaGate.DELIVERY,
                new AgentOutboxRelayV2SchemaGate.TableDefinition(
                        "MyISAM", "utf8mb4_0900_bin"));
        assertDenied(badEngine);

        FakeCatalog badCollation = canonicalCatalog();
        badCollation.tableDefinitions.put(AgentOutboxRelayV2SchemaGate.OUTBOX,
                new AgentOutboxRelayV2SchemaGate.TableDefinition("InnoDB", "utf8mb4_general_ci"));
        assertDenied(badCollation);

        FakeCatalog foreignKey = canonicalCatalog();
        foreignKey.foreignKeys.put(AgentOutboxRelayV2SchemaGate.OUTBOX, 1);
        assertDenied(foreignKey);
    }

    @Test
    void rejectsRelayIndexCheckAndPoolRegexDrift() {
        FakeCatalog missing = canonicalCatalog();
        missing.indexes.get(AgentOutboxRelayV2SchemaGate.OUTBOX)
                .remove("idx_outbox_relay_v2_due");
        assertDenied(missing);

        FakeCatalog badPool = canonicalCatalog();
        badPool.checks.get(AgentOutboxRelayV2SchemaGate.DELIVERY).put(
                "chk_delivery_relay_v2_scope",
                enforced(CHECK.replace("^[a-z0-9-]{1,100}$", "^[A-Za-z0-9-]{1,100}$")));
        assertDenied(badPool);

        FakeCatalog missingLegacyTenant = canonicalCatalog();
        missingLegacyTenant.checks.get(AgentOutboxRelayV2SchemaGate.OUTBOX).put(
                "chk_outbox_relay_v2_scope",
                enforced(CHECK.replace("AND tenant_id = '0'", "")));
        assertDenied(missingLegacyTenant);

        FakeCatalog extra = canonicalCatalog();
        extra.checks.get(AgentOutboxRelayV2SchemaGate.OUTBOX).put(
                "chk_outbox_pool_shadow", enforced("execution_pool_id <> 'forbidden'"));
        assertDenied(extra);
    }

    @Test
    void publishV2SelectsReadOnlyGateConditionAndSuppressesLegacyDdlInitializer() {
        new ApplicationContextRunner()
                .withUserConfiguration(ConditionProbe.class,
                        AgentCommandTransportSchemaConfiguration.class)
                .withPropertyValues(
                        "agent.command-outbox.enabled=true",
                        "agent.rabbit-topology.enabled=true",
                        "agent.rabbit-publish.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean("v2SchemaMarker"));
                    assertTrue(context.getBeansOfType(
                            AgentCommandTransportSchemaInitializer.class).isEmpty());
                    assertTrue(context.getBeansOfType(Integer.class).isEmpty());
                });
    }

    @Test
    void legacyOutboxModeSelectsInitializerConditionOnly() {
        new ApplicationContextRunner()
                .withUserConfiguration(ConditionProbe.class)
                .withPropertyValues(
                        "agent.command-outbox.enabled=true",
                        "agent.rabbit-topology.enabled=false",
                        "agent.rabbit-publish.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean("legacySchemaMarker"));
                    assertTrue(context.getBeansOfType(String.class).isEmpty());
                });
    }

    private static void assertDenied(FakeCatalog catalog) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AgentOutboxRelayV2SchemaGate(catalog).afterPropertiesSet());
        assertTrue(failure.getMessage().startsWith(
                "Agent outbox relay v2 schema is unavailable:"));
    }

    private static void replaceColumn(FakeCatalog catalog, String table,
            AgentOutboxRelayV2SchemaGate.ColumnDefinition replacement) {
        List<AgentOutboxRelayV2SchemaGate.ColumnDefinition> columns = catalog.columns.get(table);
        int index = -1;
        for (int current = 0; current < columns.size(); current++) {
            if (columns.get(current).name().equals(replacement.name())) {
                index = current;
                break;
            }
        }
        if (index < 0) throw new AssertionError("missing test column " + replacement.name());
        columns.set(index, replacement);
    }

    private static FakeCatalog canonicalCatalog() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.tables.addAll(List.of(
                AgentOutboxRelayV2SchemaGate.DELIVERY,
                AgentOutboxRelayV2SchemaGate.OUTBOX));
        for (String table : catalog.tables) {
            catalog.tableDefinitions.put(table,
                    new AgentOutboxRelayV2SchemaGate.TableDefinition(
                            "InnoDB", "utf8mb4_0900_bin"));
            catalog.columns.put(table, new ArrayList<>(
                    AgentOutboxRelayV2SchemaGate.expectedColumns(table)));
            catalog.indexes.put(table, new LinkedHashMap<>(
                    AgentOutboxRelayV2SchemaGate.expectedIndexes(table)));
            catalog.foreignKeys.put(table, 0);
        }
        catalog.checks.put(AgentOutboxRelayV2SchemaGate.DELIVERY,
                new LinkedHashMap<>(Map.of(
                        "chk_delivery_relay_v2_scope", enforced(CHECK),
                        "chk_delivery_owner_nonempty",
                        enforced("octet_length(owner_jiacn) between 1 and 50"))));
        catalog.checks.put(AgentOutboxRelayV2SchemaGate.OUTBOX,
                new LinkedHashMap<>(Map.of(
                        "chk_outbox_relay_v2_scope", enforced(CHECK))));
        return catalog;
    }

    private static AgentOutboxRelayV2SchemaGate.CheckDefinition enforced(String clause) {
        return new AgentOutboxRelayV2SchemaGate.CheckDefinition(clause, true);
    }

    private static final class FakeCatalog implements AgentOutboxRelayV2SchemaGate.Catalog {
        private final List<String> tables = new ArrayList<>();
        private final Map<String, AgentOutboxRelayV2SchemaGate.TableDefinition> tableDefinitions =
                new LinkedHashMap<>();
        private final Map<String, List<AgentOutboxRelayV2SchemaGate.ColumnDefinition>> columns =
                new LinkedHashMap<>();
        private final Map<String, Map<String, AgentOutboxRelayV2SchemaGate.IndexDefinition>> indexes =
                new LinkedHashMap<>();
        private final Map<String, Integer> foreignKeys = new LinkedHashMap<>();
        private final Map<String, Map<String, AgentOutboxRelayV2SchemaGate.CheckDefinition>> checks =
                new LinkedHashMap<>();
        private int reads;

        @Override
        public String databaseProduct() {
            reads++;
            return "MySQL";
        }

        @Override
        public List<String> presentTables() {
            reads++;
            return List.copyOf(tables);
        }

        @Override
        public AgentOutboxRelayV2SchemaGate.TableDefinition table(String table) {
            reads++;
            return tableDefinitions.get(table);
        }

        @Override
        public List<AgentOutboxRelayV2SchemaGate.ColumnDefinition> columns(String table) {
            reads++;
            return List.copyOf(columns.getOrDefault(table, List.of()));
        }

        @Override
        public Map<String, AgentOutboxRelayV2SchemaGate.IndexDefinition> indexes(String table) {
            reads++;
            return Map.copyOf(indexes.getOrDefault(table, Map.of()));
        }

        @Override
        public int foreignKeyCount(String table) {
            reads++;
            return foreignKeys.getOrDefault(table, -1);
        }

        @Override
        public Map<String, AgentOutboxRelayV2SchemaGate.CheckDefinition> checks(String table) {
            reads++;
            return Map.copyOf(checks.getOrDefault(table, Map.of()));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConditionProbe {
        @Bean("v2SchemaMarker")
        @Conditional(AgentRabbitPublishEnabledCondition.class)
        String v2SchemaMarker() {
            return "v2";
        }

        @Bean("legacySchemaMarker")
        @Conditional(AgentCommandLegacyTransportSchemaEnabledCondition.class)
        Integer legacySchemaMarker() {
            return 1;
        }
    }
}
