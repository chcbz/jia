package cn.jia.agent.config.skill;

import cn.jia.agent.config.skill.skillseed.PlatformSkillSeeder;
import cn.jia.agent.config.skill.skillseed.PlatformSkillPackageCatalog;
import cn.jia.agent.mapper.skill.SkillCatalogMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independently gated V0 platform package catalog and preview-only seeding. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SkillSeedProperties.class)
public class SkillSeedConfiguration {
    @Bean
    public PlatformSkillPackageCatalog platformSkillPackageCatalog() {
        return new PlatformSkillPackageCatalog();
    }

    @Bean
    public SkillSeedGate economySkillSeedGate(SkillSeedProperties properties) {
        return new SkillSeedGate(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "economy.skill.seed", name = "enabled", havingValue = "true")
    public PlatformSkillSeeder economyPlatformSkillSeeder(
            SkillCatalogMapper mapper,
            SkillSeedGate gate,
            PlatformSkillPackageCatalog catalog,
            PlatformTransactionManager transactionManager) {
        return new PlatformSkillSeeder(mapper, gate, catalog, new TransactionTemplate(transactionManager));
    }
}
