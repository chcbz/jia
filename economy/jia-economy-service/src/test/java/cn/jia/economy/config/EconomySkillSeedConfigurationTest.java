package cn.jia.economy.config;

import cn.jia.economy.config.skillseed.EconomyPlatformSkillSeeder;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomySkillSeedConfigurationTest {
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(EconomySkillSeedConfiguration.class);

    @Test
    void seedDefaultsOffWithoutRequiringMapperTransactionManagerOrDatabase() {
        RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(EconomySkillSeedProperties.class).enabled());
            assertFalse(context.getBean(EconomySkillSeedGate.class).enabled());
            assertNotNull(context.getBean(PlatformSkillPackageCatalog.class));
            assertTrue(context.getBeansOfType(EconomyPlatformSkillSeeder.class).isEmpty());
        });
    }

    @Test
    void enabledSeedRequiresAtLeastOneExactPreviewScope() {
        RUNNER.withPropertyValues("economy.skill.seed.enabled=true").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(messages(context.getStartupFailure()).contains("Invalid economy.skill.seed configuration"));
        });
        assertThrows(IllegalStateException.class, () -> new EconomySkillSeedGate(new EconomySkillSeedProperties(true,
                List.of(new EconomySkillSeedProperties.AllowedScope("tenant-*", "client-a")))));
    }

    @Test
    void scopeControlsRemainCaseSensitiveAndRejectDuplicates() {
        EconomySkillSeedGate gate = new EconomySkillSeedGate(new EconomySkillSeedProperties(true,
                List.of(new EconomySkillSeedProperties.AllowedScope("Tenant-A", "Client-A"))));
        assertTrue(gate.allows("Tenant-A", "Client-A"));
        assertFalse(gate.allows("tenant-a", "Client-A"));
        assertFalse(gate.allows("Tenant-A", "client-a"));
        assertThrows(IllegalStateException.class, () -> new EconomySkillSeedGate(new EconomySkillSeedProperties(true,
                List.of(
                        new EconomySkillSeedProperties.AllowedScope("Tenant-A", "Client-A"),
                        new EconomySkillSeedProperties.AllowedScope("Tenant-A", "Client-A")))));
    }

    private static String messages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            result.append(current.getMessage()).append('\n');
        }
        return result.toString();
    }
}
