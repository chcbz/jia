package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRuntimeV2RegistrationSchemaGateTest {
    @Test void exactCatalogAcceptedAndExistingBaselineMetadataMayCoexist() {
        Fake catalog = canonical();
        String existing = "agent_runtime_v2_binding";
        catalog.columns.get(existing).add(0, new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                "baseline_column", "bigint", "bigint", false, null, null, "", ""));
        catalog.indexes.get(existing).put("baseline_index",
                new AgentRuntimeV2RegistrationSchemaGate.IndexDefinition(false, List.of("baseline_column")));
        catalog.checks.get(existing).put("baseline_check",
                new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition("baseline_column >= 0", true));
        assertDoesNotThrow(() -> new AgentRuntimeV2RegistrationSchemaGate(catalog).afterPropertiesSet());
        assertTrue(catalog.reads > 0);
    }

    @Test void rejectsProductTableEngineCollationAndForeignKeyDrift() {
        Fake product = canonical(); product.product = "PostgreSQL"; assertDenied(product);
        Fake missing = canonical(); missing.tables.remove(AgentRuntimeV2RegistrationSchemaGate.RECEIPT); assertDenied(missing);
        Fake engine = canonical(); engine.tablesByName.put(AgentRuntimeV2RegistrationSchemaGate.REQUEST,
                new AgentRuntimeV2RegistrationSchemaGate.TableDefinition("MyISAM", "utf8mb4_0900_bin")); assertDenied(engine);
        Fake collation = canonical(); collation.tablesByName.put(AgentRuntimeV2RegistrationSchemaGate.REQUEST,
                new AgentRuntimeV2RegistrationSchemaGate.TableDefinition("InnoDB", "utf8mb4_general_ci")); assertDenied(collation);
        Fake foreignKey = canonical(); foreignKey.foreignKeys.put(AgentRuntimeV2RegistrationSchemaGate.REQUEST, 1); assertDenied(foreignKey);
    }

    @Test void rejectsMissingExtraWrongAndGeneratedColumnDrift() {
        Fake missing = canonical(); missing.columns.get(AgentRuntimeV2RegistrationSchemaGate.RECEIPT).remove(2); assertDenied(missing);
        Fake extra = canonical(); extra.columns.get(AgentRuntimeV2RegistrationSchemaGate.AUTHORITY).add(
                new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition("attacker", "varchar", "varchar(1)", true, null, "utf8mb4_0900_bin", "", "")); assertDenied(extra);
        Fake wrongType = canonical(); replaceColumn(wrongType, AgentRuntimeV2RegistrationSchemaGate.REQUEST,
                "request_wire_sha256", column -> new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                        column.name(), "varbinary", "varbinary(32)", column.nullable(), column.defaultValue(),
                        column.collation(), column.extra(), column.generationExpression())); assertDenied(wrongType);
        Fake wrongNullability = canonical(); replaceColumn(wrongNullability, AgentRuntimeV2RegistrationSchemaGate.CHALLENGE,
                "challenge_digest", column -> new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                        column.name(), column.dataType(), column.columnType(), true, column.defaultValue(),
                        column.collation(), column.extra(), column.generationExpression())); assertDenied(wrongNullability);
        Fake generated = canonical(); replaceColumn(generated, AgentRuntimeV2RegistrationSchemaGate.AUTHORITY,
                "active_business_subject", column -> new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                        column.name(), column.dataType(), column.columnType(), column.nullable(), column.defaultValue(),
                        column.collation(), column.extra(), column.generationExpression() + " + 'forged'")); assertDenied(generated);
    }

    @Test void additiveCatalogColumnDefinitionsMatchManualDdl() throws Exception {
        Path migration = Path.of(System.getProperty("user.dir"), "..", "jia-agent-mapper",
                "src", "main", "resources", "db",
                "agent-runtime-v2-registration-manual-20260930.sql").normalize();
        Map<String, List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition>> ddl =
                additiveColumns(Files.readString(migration));
        for (String table : Set.of("agent_consumer_inbox", "agent_command_v2_ack_receipt",
                "agent_command_v2_execution_lease_fact", "agent_command_v2_execution_grant",
                "agent_command_v2_execution_continuation_anchor",
                "agent_command_v2_execution_request")) {
            assertEquals(AgentRuntimeV2RegistrationSchemaGate.expectedColumns().get(table),
                    ddl.get(table), table);
        }
    }

    @Test void rejectsMissingExtraWrongOrderAndUniquenessIndexDrift() {
        Fake missing = canonical(); missing.indexes.get(AgentRuntimeV2RegistrationSchemaGate.RECEIPT)
                .remove("uk_registration_receipt_generation_fact"); assertDenied(missing);
        Fake extra = canonical(); extra.indexes.get(AgentRuntimeV2RegistrationSchemaGate.AUTHORITY).put(
                "idx_attacker", new AgentRuntimeV2RegistrationSchemaGate.IndexDefinition(false, List.of("status"))); assertDenied(extra);
        Fake order = canonical(); order.indexes.get(AgentRuntimeV2RegistrationSchemaGate.REQUEST).put(
                "idx_registration_request_subject", new AgentRuntimeV2RegistrationSchemaGate.IndexDefinition(false,
                        List.of("request_kind", "registration_subject_sha256", "decided_at"))); assertDenied(order);
        Fake uniqueness = canonical(); uniqueness.indexes.get(AgentRuntimeV2RegistrationSchemaGate.CHALLENGE).put(
                "uk_reconnect_challenge_id", new AgentRuntimeV2RegistrationSchemaGate.IndexDefinition(false,
                        List.of("challenge_id"))); assertDenied(uniqueness);
    }

    @Test void rejectsMissingExtraUnenforcedAndWeakenedChecks() {
        Fake missing = canonical(); missing.checks.get(AgentRuntimeV2RegistrationSchemaGate.RECEIPT)
                .remove("chk_registration_receipt_canonical"); assertDenied(missing);
        Fake extra = canonical(); extra.checks.get(AgentRuntimeV2RegistrationSchemaGate.AUTHORITY).put(
                "chk_attacker", new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition("1=1", true)); assertDenied(extra);
        Fake unenforced = canonical(); String name = "chk_registration_request_canonical";
        String clause = AgentRuntimeV2RegistrationSchemaGate.expectedChecks()
                .get(AgentRuntimeV2RegistrationSchemaGate.REQUEST).get(name);
        unenforced.checks.get(AgentRuntimeV2RegistrationSchemaGate.REQUEST).put(name,
                new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition(clause, false)); assertDenied(unenforced);
        Fake weakened = canonical(); weakened.checks.get(AgentRuntimeV2RegistrationSchemaGate.REQUEST).put(name,
                new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition("(" + clause + ") OR 1=1", true)); assertDenied(weakened);
    }

    @Test void acceptsMysqlCheckMetadataFormattingButRejectsCaseAndWhitespaceLiteralDrift() {
        String name = "chk_registration_authority_canonical";
        String expected = AgentRuntimeV2RegistrationSchemaGate.expectedChecks()
                .get(AgentRuntimeV2RegistrationSchemaGate.AUTHORITY).get(name);
        String uuidPattern = "'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'";

        Fake mysqlMetadata = canonical();
        String mysqlClause = "((" + expected
                .replace("registration_authority_id REGEXP " + uuidPattern,
                        "regexp_like(`registration_authority_id`,_utf8mb4" + uuidPattern + ")")
                .replace("credential_kind='API_KEY'",
                        "`credential_kind` = _utf8mb4'API_KEY'") + "))";
        mysqlMetadata.checks.get(AgentRuntimeV2RegistrationSchemaGate.AUTHORITY).put(name,
                new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition(mysqlClause, true));
        assertDoesNotThrow(() -> new AgentRuntimeV2RegistrationSchemaGate(mysqlMetadata)
                .afterPropertiesSet());

        Fake caseDrift = canonical();
        caseDrift.checks.get(AgentRuntimeV2RegistrationSchemaGate.AUTHORITY).put(name,
                new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition(
                        expected.replace("'API_KEY'", "'api_key'"), true));
        assertDenied(caseDrift);

        Fake whitespaceDrift = canonical();
        whitespaceDrift.checks.get(AgentRuntimeV2RegistrationSchemaGate.AUTHORITY).put(name,
                new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition(
                        expected.replace("'API_KEY'", "'API_KEY '"), true));
        assertDenied(whitespaceDrift);
    }

    @Test void propertyIsOffByDefaultWithZeroJdbcAccessAndEnabledWithoutCatalogFailsClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new ApplicationContextRunner().withBean(JdbcTemplate.class, () -> jdbc)
                .withUserConfiguration(AgentRuntimeV2RegistrationSchemaConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(AgentRuntimeV2RegistrationSchemaGate.class).isEmpty());
                    verify(jdbc, never()).execute(any(ConnectionCallback.class));
                    verify(jdbc, never()).queryForMap(anyString(), any());
                    verify(jdbc, never()).queryForList(anyString(), eq(String.class));
                });
        new ApplicationContextRunner().withUserConfiguration(AgentRuntimeV2RegistrationSchemaConfiguration.class)
                .withPropertyValues("agent.runtime-v2-registration.enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    private static Map<String, List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition>>
            additiveColumns(String sql) {
        Set<String> targets = Set.of("agent_consumer_inbox", "agent_command_v2_ack_receipt",
                "agent_command_v2_execution_lease_fact", "agent_command_v2_execution_grant",
                "agent_command_v2_execution_continuation_anchor",
                "agent_command_v2_execution_request");
        Pattern alter = Pattern.compile("ALTER TABLE\\s+([a-z0-9_]+)\\s+(.*?);",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Pattern add = Pattern.compile(
                "ADD COLUMN\\s+([a-z0-9_]+)\\s+([a-z]+\\(\\d+\\))"
                        + "(?:\\s+COLLATE\\s+([a-z0-9_]+))?\\s+(NOT NULL|NULL)"
                        + "\\s+AFTER\\s+[a-z0-9_]+",
                Pattern.CASE_INSENSITIVE);
        Map<String, List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition>> result =
                new LinkedHashMap<>();
        Matcher blocks = alter.matcher(sql);
        while (blocks.find()) {
            String table = blocks.group(1).toLowerCase(Locale.ROOT);
            if (!targets.contains(table)) continue;
            List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition> columns =
                    new ArrayList<>();
            Matcher additions = add.matcher(blocks.group(2));
            while (additions.find()) {
                String columnType = additions.group(2).toLowerCase(Locale.ROOT);
                String collation = additions.group(3) == null ? null
                        : additions.group(3).toLowerCase(Locale.ROOT);
                columns.add(new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                        additions.group(1).toLowerCase(Locale.ROOT),
                        columnType.substring(0, columnType.indexOf('(')), columnType,
                        "NULL".equalsIgnoreCase(additions.group(4)), null, collation, "", ""));
            }
            assertFalse(columns.isEmpty(), table);
            assertNull(result.put(table, List.copyOf(columns)), table);
        }
        assertEquals(targets, result.keySet());
        return Map.copyOf(result);
    }

    private static void replaceColumn(Fake fake, String table, String name,
            java.util.function.Function<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition,
                    AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition> replacement) {
        List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition> columns = fake.columns.get(table);
        for (int index = 0; index < columns.size(); index++) {
            if (name.equals(columns.get(index).name())) {
                columns.set(index, replacement.apply(columns.get(index)));
                return;
            }
        }
        fail("fixture column not found: " + name);
    }
    private static void assertDenied(Fake fake) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AgentRuntimeV2RegistrationSchemaGate(fake).afterPropertiesSet());
        assertTrue(failure.getMessage().startsWith("Agent runtime v2 registration schema unavailable:"));
    }
    private static Fake canonical() {
        Fake fake = new Fake();
        fake.tables.addAll(AgentRuntimeV2RegistrationSchemaGate.TABLES.stream().sorted().toList());
        for (String table : fake.tables) {
            fake.tablesByName.put(table, new AgentRuntimeV2RegistrationSchemaGate.TableDefinition("InnoDB", "utf8mb4_0900_bin"));
            fake.columns.put(table, new ArrayList<>(AgentRuntimeV2RegistrationSchemaGate.expectedColumns().get(table)));
            fake.indexes.put(table, new LinkedHashMap<>(AgentRuntimeV2RegistrationSchemaGate.expectedIndexes().get(table)));
            Map<String, AgentRuntimeV2RegistrationSchemaGate.CheckDefinition> checks = new LinkedHashMap<>();
            AgentRuntimeV2RegistrationSchemaGate.expectedChecks().get(table).forEach((name, clause) ->
                    checks.put(name, new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition(clause, true)));
            fake.checks.put(table, checks);
            fake.foreignKeys.put(table, 0);
        }
        return fake;
    }

    private static final class Fake implements AgentRuntimeV2RegistrationSchemaGate.Catalog {
        String product = "MySQL 8.4"; int reads;
        final List<String> tables = new ArrayList<>();
        final Map<String, AgentRuntimeV2RegistrationSchemaGate.TableDefinition> tablesByName = new HashMap<>();
        final Map<String, List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition>> columns = new HashMap<>();
        final Map<String, Map<String, AgentRuntimeV2RegistrationSchemaGate.IndexDefinition>> indexes = new HashMap<>();
        final Map<String, Integer> foreignKeys = new HashMap<>();
        final Map<String, Map<String, AgentRuntimeV2RegistrationSchemaGate.CheckDefinition>> checks = new HashMap<>();
        public String databaseProduct() { reads++; return product; }
        public List<String> presentTables() { reads++; return List.copyOf(tables); }
        public AgentRuntimeV2RegistrationSchemaGate.TableDefinition table(String table) { reads++; return tablesByName.get(table); }
        public List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition> columns(String table) { reads++; return List.copyOf(columns.get(table)); }
        public Map<String, AgentRuntimeV2RegistrationSchemaGate.IndexDefinition> indexes(String table) { reads++; return Map.copyOf(indexes.get(table)); }
        public int participatingForeignKeyCount(String table) { reads++; return foreignKeys.getOrDefault(table, -1); }
        public Map<String, AgentRuntimeV2RegistrationSchemaGate.CheckDefinition> checks(String table) { reads++; return Map.copyOf(checks.get(table)); }
    }
}
