package cn.jia.economy.config;

import cn.jia.economy.config.skillseed.EconomyPlatformSkillSeeder;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independently gated V0 platform package catalog and preview-only seeding. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EconomySkillSeedProperties.class)
public class EconomySkillSeedConfiguration {
    @Bean
    public PlatformSkillPackageCatalog platformSkillPackageCatalog() {
        return new PlatformSkillPackageCatalog();
    }

    @Bean
    public EconomySkillSeedGate economySkillSeedGate(EconomySkillSeedProperties properties) {
        return new EconomySkillSeedGate(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "economy.skill.seed", name = "enabled", havingValue = "true")
    public EconomyPlatformSkillSeeder economyPlatformSkillSeeder(
            EconomySkillMarketplaceMapper mapper,
            EconomySkillSeedGate gate,
            PlatformSkillPackageCatalog catalog,
            PlatformTransactionManager transactionManager) {
        return new EconomyPlatformSkillSeeder(mapper, gate, catalog, new TransactionTemplate(transactionManager));
    }
}
