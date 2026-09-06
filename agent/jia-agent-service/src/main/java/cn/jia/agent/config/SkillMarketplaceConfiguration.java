package cn.jia.agent.config;
import cn.jia.agent.skill.SkillPackages;
import cn.jia.economy.config.EconomySkillApplicationSchemaInitializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="economy.skill.marketplace",name="enabled",havingValue="true")
public class SkillMarketplaceConfiguration {
    @Bean public EconomySkillApplicationSchemaInitializer economySkillApplicationSchemaInitializer(JdbcTemplate jdbc) {
        return new EconomySkillApplicationSchemaInitializer(jdbc);
    }
    @Bean public SkillPackages skillPackages() { return new SkillPackages(); }
}
