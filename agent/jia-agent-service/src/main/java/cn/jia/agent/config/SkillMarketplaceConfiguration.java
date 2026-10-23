package cn.jia.agent.config;
import cn.jia.agent.skill.SkillPackages;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Path;
import cn.jia.economy.config.EconomySkillApplicationSchemaInitializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="economy.skill.marketplace",name="enabled",havingValue="true")
public class SkillMarketplaceConfiguration {
    @org.springframework.context.annotation.Profile("schema-maintenance")
    @Bean public EconomySkillApplicationSchemaInitializer economySkillApplicationSchemaInitializer(JdbcTemplate jdbc) {
        return new EconomySkillApplicationSchemaInitializer(jdbc);
    }
    @Bean public SkillPackages skillPackages(
            @Value("${economy.skill.marketplace.package-store-root:/home/isp/state/cyf-skill-packages}") String root) {
        return new SkillPackages(Path.of(root));
    }
}
