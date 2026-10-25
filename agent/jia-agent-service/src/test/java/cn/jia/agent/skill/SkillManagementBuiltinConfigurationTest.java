package cn.jia.agent.skill;

import cn.jia.agent.config.SkillMarketplaceConfiguration;
import cn.jia.economy.config.EconomySkillApplicationSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

/** Runtime capability is built in; activating it must never activate schema maintenance. */
class SkillManagementBuiltinConfigurationTest {
    @Test void packagesExistWithoutFeatureFlagsAndIgnoreHistoricalFalse() {
        for (String legacy : new String[]{"", "false", "true"}) {
            var runner = new ApplicationContextRunner().withUserConfiguration(SkillMarketplaceConfiguration.class);
            if (!legacy.isEmpty()) runner = runner.withPropertyValues(
                    "economy.skill.marketplace.enabled=" + legacy,
                    "agent.position-skills.enabled=" + legacy);
            runner.run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(1, context.getBeansOfType(SkillPackages.class).size());
                assertTrue(context.getBeansOfType(EconomySkillApplicationSchemaInitializer.class).isEmpty(),
                        "ordinary startup must not initialize or migrate tables");
            });
        }
    }
}
