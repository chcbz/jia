package cn.jia.agent.skill;

import cn.jia.agent.config.SkillMarketplaceConfiguration;
import cn.jia.economy.config.EconomySkillApplicationSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Runtime capability is built in; activating it must never activate schema maintenance. */
class SkillManagementBuiltinConfigurationTest {
    @Test void packagesExistWithoutFeatureFlagsAndIgnoreHistoricalFalse() {
        for (String legacy : new String[]{"", "false", "true"}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                if (!legacy.isEmpty()) context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("legacy-feature-flags", Map.of(
                                "economy.skill.marketplace.enabled", legacy,
                                "agent.position-skills.enabled", legacy)));
                context.register(SkillMarketplaceConfiguration.class);
                context.refresh();
                assertEquals(1, context.getBeansOfType(SkillPackages.class).size());
                assertTrue(context.getBeansOfType(EconomySkillApplicationSchemaInitializer.class).isEmpty(),
                        "ordinary startup must not initialize or migrate tables");
            }
        }
    }
}
