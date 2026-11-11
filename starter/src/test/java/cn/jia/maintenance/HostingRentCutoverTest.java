package cn.jia.maintenance;

import cn.jia.agent.hosting.HostingRentTransitionApplication;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.ScheduledWorkStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class HostingRentCutoverTest {
    private static final FixedTenantAdmissionPolicy SCOPE = new FixedTenantAdmissionPolicy("tenant", "client");
    private static String input(String extra) {
        return "{\"cutoverAt\":1000,\"subjects\":[{\"actorId\":\"login\",\"ownerJiacn\":\"owner\",\"agentId\":\"jyt-fixture-likui\",\"installationId\":\"rti_" + "1".repeat(32) + "\"}]" + extra + "}";
    }
    @Test void inputCannotChooseItsOwnEnvironmentPartition() {
        var commands = HostingRentCutover.requests(input("").getBytes(StandardCharsets.UTF_8), SCOPE);
        assertEquals(1, commands.size()); assertEquals("tenant", commands.getFirst().actor().tenantId());
        assertEquals("client", commands.getFirst().actor().clientId()); assertEquals(1000, commands.getFirst().cutoverAt());
        assertThrows(RuntimeException.class, () -> HostingRentCutover.requests(input(",\"tenantId\":\"other\"").getBytes(StandardCharsets.UTF_8), SCOPE));
    }
    @Test void invalidAndAmbiguousInputsAreRejectedBeforeAnyServiceCall() {
        for (String input : new String[]{"null", "{}", "{\"cutoverAt\":1000,\"subjects\":[]}",
                input(",\"cutoverAt\":1001"), input("").replace(":1000", ":\"1000\""), input("") + " {}"}) {
            assertThrows(RuntimeException.class, () -> HostingRentCutover.requests(input.getBytes(StandardCharsets.UTF_8), SCOPE));
        }
    }
    @Test void duplicateAgentsCannotReceiveTwoCutoversInOneBatch() {
        String value = input(""); String subject = value.substring(value.indexOf("[{" ) + 1, value.lastIndexOf(']'));
        String duplicate = "{\"cutoverAt\":1000,\"subjects\":[" + subject + "," + subject + "]}";
        assertThrows(IllegalArgumentException.class, () -> HostingRentCutover.requests(duplicate.getBytes(StandardCharsets.UTF_8), SCOPE));
    }
    @Test void maintenanceGraphIsNotRegisteredInNormalContext() {
        new ApplicationContextRunner().withUserConfiguration(HostingRentCutover.Context.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(HostingRentTransitionApplication.class).isEmpty());
            assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
        });
    }
    @Test void explicitGraphStartsWithoutBusinessWorkersOrSchemaWrites() {
        new ApplicationContextRunner().withUserConfiguration(HostingRentCutover.Context.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("schema-maintenance", "hosting-rent-cutover"))
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:cutover;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
                        "cyf.security.fixed-tenant.tenant-id=tenant", "cyf.security.fixed-tenant.client-id=client")
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> String.valueOf(context.getStartupFailure()));
                    assertNotNull(context.getBean(HostingRentTransitionApplication.class));
                    assertNotNull(context.getBean(ScheduledWorkStore.class));
                    assertTrue(context.getBeansOfType(org.springframework.scheduling.TaskScheduler.class).isEmpty());
                    assertTrue(context.getBeansOfType(org.springframework.boot.ApplicationRunner.class).isEmpty());
                    assertTrue(context.getBeansOfType(org.springframework.boot.CommandLineRunner.class).isEmpty());
                    for (String name : context.getBeanDefinitionNames()) {
                        assertFalse(name.contains("SchemaInitializer") || name.contains("Worker") || name.contains("Coordinator"), name);
                    }
                    assertEquals(0, new JdbcTemplate(context.getBean(DataSource.class)).queryForObject(
                            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'", Integer.class));
                });
    }
}
