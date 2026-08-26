package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCommandTransportSchemaInitializerTest {
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(
                    AgentRabbitSafetyConfiguration.class,
                    AgentCommandTransportSchemaConfiguration.class);

    @Test
    void absentFlagRegistersNoInitializerAndRequiresNoJdbcBean() {
        RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(
                    AgentCommandTransportSchemaInitializer.class).isEmpty());
        });
    }

    @Test
    void explicitAllFlagsOffRegistersNoInitializerAndPerformsNoDatabaseAccess() {
        AtomicInteger accesses = new AtomicInteger();
        DataSource poisonDataSource = mock(DataSource.class);
        try {
            when(poisonDataSource.getConnection()).thenAnswer(invocation -> {
                accesses.incrementAndGet();
                throw new AssertionError("D01 must not connect while command outbox is disabled");
            });
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        JdbcTemplate poison = new JdbcTemplate(poisonDataSource);
        RUNNER.withBean(JdbcTemplate.class, () -> poison)
                .withPropertyValues(
                        "agent.command-outbox.enabled=false",
                        "agent.rabbit-topology.enabled=false",
                        "agent.rabbit-publish.enabled=false",
                        "agent.rabbit-consume.enabled=false",
                        "agent.rabbit-dispatch.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(
                            AgentCommandTransportSchemaInitializer.class).isEmpty());
                    assertEquals(0, accesses.get());
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "on", "yes", "1"})
    void binderTrueSpellingsRegisterInitializerAndReachD01Validation(String spelling)
            throws Exception {
        RecordingCatalogJdbcTemplate partial = partialCatalog("agent_command_delivery");
        RUNNER.withBean(JdbcTemplate.class, () -> partial)
                .withPropertyValues("agent.command-outbox.enabled=" + spelling)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, spelling);
                    assertTrue(failureChain(failure).contains("exact 0/5, legacy 3/5, current 4/5, or 5/5"),
                            spelling + ": " + failureChain(failure));
                    assertEquals(List.of(), partial.executedSql, spelling);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "off", "no", "0"})
    void binderFalseSpellingsRegisterNoInitializerAndPerformNoDatabaseAccess(String spelling) {
        AtomicInteger accesses = new AtomicInteger();
        JdbcTemplate poison = poisonJdbcTemplate(accesses);
        RUNNER.withBean(JdbcTemplate.class, () -> poison)
                .withPropertyValues("agent.command-outbox.enabled=" + spelling)
                .run(context -> {
                    assertNull(context.getStartupFailure(), spelling);
                    assertFalse(context.getBean(AgentRabbitSafetyGate.class)
                            .commandOutboxEnabled(), spelling);
                    assertTrue(context.getBeansOfType(
                            AgentCommandTransportSchemaInitializer.class).isEmpty(), spelling);
                    assertEquals(0, accesses.get(), spelling);
                });
    }

    @Test
    void malformedBooleanFailsBindingBeforeAnyDatabaseAccess() {
        AtomicInteger accesses = new AtomicInteger();
        JdbcTemplate poison = poisonJdbcTemplate(accesses);
        RUNNER.withBean(JdbcTemplate.class, () -> poison)
                .withPropertyValues("agent.command-outbox.enabled=not-a-boolean")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    assertTrue(failureChain(failure).contains("agent.command-outbox"),
                            failureChain(failure));
                    assertEquals(0, accesses.get());
                });
    }

    @Test
    void oneOrTwoOfFiveFailClosedWithoutAutoCompletion() throws Exception {
        for (List<String> present : List.of(
                List.of("agent_command_delivery"),
                List.of("agent_command_delivery", "agent_outbox_event"))) {
            RecordingCatalogJdbcTemplate jdbc = partialCatalog(
                    present.toArray(String[]::new));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new AgentCommandTransportSchemaInitializer(jdbc).afterPropertiesSet());
            assertTrue(failure.getMessage().contains(present.size() + "/5"), failure.getMessage());
            assertEquals(List.of(), jdbc.executedSql, present.toString());
        }
    }

    @Test
    void exactLegacyThreeTableCatalogAddsAuditAndRedriveControlTables() throws Exception {
        RecordingCatalogJdbcTemplate legacy = partialCatalog(
                "agent_command_delivery", "agent_outbox_event", "agent_consumer_inbox");
        assertThrows(RuntimeException.class,
                () -> new AgentCommandTransportSchemaInitializer(legacy).afterPropertiesSet());
        assertEquals(2, legacy.executedSql.size());
        assertTrue(legacy.executedSql.get(0).toLowerCase(Locale.ROOT)
                .contains("create table if not exists agent_command_operation_audit"));
        assertTrue(legacy.executedSql.get(1).toLowerCase(Locale.ROOT)
                .contains("create table if not exists agent_command_redrive_operation"));
    }

    @Test
    void freshAndCurrentFourTableCatalogsExecuteOnlyTheirExactMissingSuffix() throws Exception {
        RecordingCatalogJdbcTemplate fresh = partialCatalog();
        assertThrows(RuntimeException.class,
                () -> new AgentCommandTransportSchemaInitializer(fresh).afterPropertiesSet());
        assertEquals(5, fresh.executedSql.size());

        RecordingCatalogJdbcTemplate current = partialCatalog(
                "agent_command_delivery", "agent_outbox_event", "agent_consumer_inbox",
                "agent_command_operation_audit");
        assertThrows(RuntimeException.class,
                () -> new AgentCommandTransportSchemaInitializer(current).afterPropertiesSet());
        assertEquals(1, current.executedSql.size());
        assertTrue(current.executedSql.getFirst().toLowerCase(Locale.ROOT)
                .contains("create table if not exists agent_command_redrive_operation"));
    }

    @Test
    void exactFiveTableCatalogRunsValidationWithoutDdl() throws Exception {
        RecordingCatalogJdbcTemplate exact = partialCatalog(
                "agent_command_delivery", "agent_outbox_event", "agent_consumer_inbox",
                "agent_command_operation_audit", "agent_command_redrive_operation");
        assertThrows(RuntimeException.class,
                () -> new AgentCommandTransportSchemaInitializer(exact).afterPropertiesSet());
        assertEquals(List.of(), exact.executedSql);
    }

    @Test
    void wrongCompositionThreeAndFourOfFiveFailBeforeAnyDdl() throws Exception {
        for (List<String> present : List.of(
                List.of("agent_command_delivery", "agent_outbox_event",
                        "agent_command_operation_audit"),
                List.of("agent_command_delivery", "agent_outbox_event",
                        "agent_consumer_inbox", "agent_command_redrive_operation"))) {
            RecordingCatalogJdbcTemplate jdbc = partialCatalog(present.toArray(String[]::new));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new AgentCommandTransportSchemaInitializer(jdbc).afterPropertiesSet());
            assertTrue(failure.getMessage().contains(present.size() + "/5"), failure.getMessage());
            assertEquals(List.of(), jdbc.executedSql);
        }
    }

    @Test
    void initializerDdlIsExactlyFiveCreateStatementsAndContainsNoDataMutation() {
        List<String> statements = AgentCommandTransportSchemaInitializer.ddlStatements();
        assertEquals(5, statements.size());
        for (int index = 0; index < statements.size(); index++) {
            String normalized = statements.get(index)
                    .replaceAll("(?m)^\\s*--.*$", " ")
                    .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            assertTrue(normalized.startsWith("create table if not exists "
                    + AgentCommandTransportSchemaInitializer.TABLES.get(index) + " "));
            for (String forbidden : List.of(
                    " insert into ", " update ", " delete from ", " replace into ")) {
                assertFalse((" " + normalized + " ").contains(forbidden), forbidden);
            }
        }
        Map<String, AgentCommandTransportSchemaInitializer.TriggerDefinition> triggers =
                AgentCommandTransportSchemaInitializer.expectedAuditTriggers();
        assertEquals(List.of(
                        AgentCommandTransportSchemaInitializer.AUDIT_DELETE_TRIGGER,
                        AgentCommandTransportSchemaInitializer.AUDIT_UPDATE_TRIGGER),
                triggers.keySet().stream().sorted().toList());
        assertEquals("DELETE", triggers.get(
                AgentCommandTransportSchemaInitializer.AUDIT_DELETE_TRIGGER).event());
        assertEquals("UPDATE", triggers.get(
                AgentCommandTransportSchemaInitializer.AUDIT_UPDATE_TRIGGER).event());
        triggers.values().forEach(trigger -> {
            assertEquals("agent_command_operation_audit", trigger.table());
            assertEquals("BEFORE", trigger.timing());
            assertTrue(AgentCommandTransportSchemaInitializer.normalizeSql(trigger.statement())
                    .startsWith("signal sqlstate '45000' set message_text = 'd09:"));
        });
    }

    @Test
    void validatorCatalogFreezesScopedUniquenessAndOutboxMessageIndexIsNonUnique() {
        Map<String, AgentCommandTransportSchemaInitializer.TableExpectation> expected =
                AgentCommandTransportSchemaInitializer.expectedTables();
        assertEquals(AgentCommandTransportSchemaInitializer.TABLES.stream().sorted().toList(),
                expected.keySet().stream().sorted().toList());

        assertEquals(new AgentCommandTransportSchemaInitializer.IndexDefinition(
                        true, List.of("tenant_id", "client_id", "command_id")),
                expected.get("agent_command_delivery").indexes().get("uk_delivery_command"));
        assertEquals(new AgentCommandTransportSchemaInitializer.IndexDefinition(
                        true, List.of("tenant_id", "client_id", "event_id")),
                expected.get("agent_outbox_event").indexes().get("uk_outbox_event_id"));
        assertEquals(new AgentCommandTransportSchemaInitializer.IndexDefinition(
                        false, List.of("tenant_id", "client_id", "message_id")),
                expected.get("agent_outbox_event").indexes().get("idx_outbox_message"));
        assertEquals(new AgentCommandTransportSchemaInitializer.IndexDefinition(
                        true, List.of("tenant_id", "client_id", "consumer_name", "message_id")),
                expected.get("agent_consumer_inbox").indexes().get("uk_consumer_message"));

        AgentCommandTransportSchemaInitializer.TableExpectation redrive =
                expected.get("agent_command_redrive_operation");
        assertEquals(new AgentCommandTransportSchemaInitializer.IndexDefinition(
                        true, List.of("tenant_id", "client_id", "operation_id")),
                redrive.indexes().get("uk_redrive_operation_id"));
        assertEquals(new AgentCommandTransportSchemaInitializer.IndexDefinition(
                        true, List.of("tenant_id", "client_id", "delivery_id",
                                "source_message_id", "source_attempt", "redrive_guard")),
                redrive.indexes().get("uk_redrive_operation_guard"));
        Map<String, AgentCommandTransportSchemaInitializer.ColumnDefinition> columns =
                redrive.columns().stream().collect(java.util.stream.Collectors.toMap(
                        AgentCommandTransportSchemaInitializer.ColumnDefinition::name,
                        column -> column));
        assertEquals("enum('PENDING','SUCCEEDED','FAILED')",
                columns.get("outcome_state").columnType());
        assertEquals("if(outcome_state='pending',1,null)",
                columns.get("disposition_guard").generationExpression());
        assertEquals("if(settlement_statein('source_requeued','not_acquired'),null,1)",
                columns.get("redrive_guard").generationExpression());
    }

    private RecordingCatalogJdbcTemplate partialCatalog(String... tables) throws Exception {
        return new RecordingCatalogJdbcTemplate(mysqlDataSource(), List.of(tables));
    }

    private JdbcTemplate poisonJdbcTemplate(AtomicInteger accesses) {
        DataSource source = mock(DataSource.class);
        try {
            when(source.getConnection()).thenAnswer(invocation -> {
                accesses.incrementAndGet();
                throw new AssertionError("D01 must not access the database for this flag value");
            });
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return new JdbcTemplate(source);
    }

    private DataSource mysqlDataSource() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        return source;
    }

    private String failureChain(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            result.append(current.getClass().getName()).append(':')
                    .append(current.getMessage()).append('\n');
        }
        return result.toString();
    }

    private static final class RecordingCatalogJdbcTemplate extends JdbcTemplate {
        private final List<String> present;
        private final List<String> executedSql = new ArrayList<>();

        private RecordingCatalogJdbcTemplate(DataSource dataSource, List<String> present) {
            super(dataSource);
            this.present = new ArrayList<>(present);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> elementType) {
            if (sql.toLowerCase(Locale.ROOT).contains("information_schema.tables")) {
                return (List<T>) present.stream().sorted().toList();
            }
            return List.of();
        }

        @Override
        public void execute(String sql) {
            executedSql.add(sql);
            String normalized = sql.replaceAll("(?m)^\\s*--.*$", " ")
                    .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            String prefix = "create table if not exists ";
            if (normalized.startsWith(prefix)) {
                String table = normalized.substring(prefix.length()).split(" ", 2)[0];
                if (!present.contains(table)) present.add(table);
            }
        }
    }
}
