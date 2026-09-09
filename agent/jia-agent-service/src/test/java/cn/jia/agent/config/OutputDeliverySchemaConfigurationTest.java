package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutputDeliverySchemaConfigurationTest {
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(OutputDeliverySchemaConfiguration.class);

    @Test
    void defaultDisabledNeedsNoJdbcBean() {
        RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(
                    OutputDeliverySchemaInitializer.class).isEmpty());
        });
    }

    @Test
    void explicitDisabledPerformsNoJdbcAccess() throws Exception {
        AtomicInteger accesses = new AtomicInteger();
        DataSource poison = mock(DataSource.class);
        when(poison.getConnection()).thenAnswer(invocation -> {
            accesses.incrementAndGet();
            throw new AssertionError("disabled OD01 must not inspect the old schema");
        });
        RUNNER.withBean(JdbcTemplate.class, () -> new JdbcTemplate(poison))
                .withPropertyValues("agent.output-delivery.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(
                            OutputDeliverySchemaInitializer.class).isEmpty());
                    assertEquals(0, accesses.get());
                });
    }

    @Test
    void migrationResourceHasFrozenIdentityStatementSet() {
        var ddl = OutputDeliverySchemaInitializer.ddlStatements();
        assertEquals(4, ddl.size());
        assertTrue(ddl.get(0).startsWith("CREATE TABLE IF NOT EXISTS output_source_binding"));
        assertTrue(ddl.get(1).startsWith("CREATE TABLE IF NOT EXISTS output_run_binding"));
        assertTrue(ddl.get(2).startsWith("CREATE TABLE IF NOT EXISTS output_access_ticket"));
        assertTrue(ddl.get(3).startsWith("ALTER TABLE agent_runtime"));
        assertTrue(ddl.get(2).contains("KEY idx_output_ticket_binding_window"));
        assertTrue(!ddl.get(2).contains("UNIQUE KEY idx_output_ticket_binding_window"));
    }
}
