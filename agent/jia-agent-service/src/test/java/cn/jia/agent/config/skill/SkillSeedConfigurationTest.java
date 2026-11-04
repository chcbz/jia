package cn.jia.agent.config.skill;

import cn.jia.agent.config.skill.skillseed.PlatformSkillSeeder;
import cn.jia.agent.config.skill.skillseed.PlatformSkillPackageCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillSeedConfigurationTest {
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(SkillSeedConfiguration.class);

    @Test
    void seedDefaultsOffWithoutRequiringMapperTransactionManagerOrDatabase() {
        RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(SkillSeedProperties.class).enabled());
            assertFalse(context.getBean(SkillSeedGate.class).enabled());
            assertNotNull(context.getBean(PlatformSkillPackageCatalog.class));
            assertTrue(context.getBeansOfType(PlatformSkillSeeder.class).isEmpty());
        });
    }

    @Test
    void enabledSeedRequiresAtLeastOneExactPreviewScope() {
        RUNNER.withPropertyValues("economy.skill.seed.enabled=true").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(messages(context.getStartupFailure()).contains("Invalid economy.skill.seed configuration"));
        });
        assertThrows(IllegalStateException.class, () -> new SkillSeedGate(new SkillSeedProperties(true,
                List.of(new SkillSeedProperties.AllowedScope("tenant-*", "client-a")))));
    }

    @Test
    void scopeControlsRemainCaseSensitiveAndRejectDuplicates() {
        SkillSeedGate gate = new SkillSeedGate(new SkillSeedProperties(true,
                List.of(new SkillSeedProperties.AllowedScope("Tenant-A", "Client-A"))));
        assertTrue(gate.allows("Tenant-A", "Client-A"));
        assertFalse(gate.allows("tenant-a", "Client-A"));
        assertFalse(gate.allows("Tenant-A", "client-a"));
        assertThrows(IllegalStateException.class, () -> new SkillSeedGate(new SkillSeedProperties(true,
                List.of(
                        new SkillSeedProperties.AllowedScope("Tenant-A", "Client-A"),
                        new SkillSeedProperties.AllowedScope("Tenant-A", "Client-A")))));
    }

    private static String messages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            result.append(current.getMessage()).append('\n');
        }
        return result.toString();
    }
}
