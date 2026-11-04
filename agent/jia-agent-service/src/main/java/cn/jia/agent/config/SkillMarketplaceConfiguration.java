package cn.jia.agent.config;
import cn.jia.agent.skill.SkillPackages;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Path;
import cn.jia.agent.config.skill.SkillApplicationSchemaInitializer;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
@Configuration(proxyBeanMethods=false)
public class SkillMarketplaceConfiguration {
    @org.springframework.context.annotation.Profile("schema-maintenance")
    @Bean public SkillApplicationSchemaInitializer economySkillApplicationSchemaInitializer(JdbcTemplate jdbc) {
        return new SkillApplicationSchemaInitializer(jdbc);
    }
    @Bean public SkillPackages skillPackages(
            @Value("${economy.skill.marketplace.package-store-root:/home/isp/state/cyf-skill-packages}") String root) {
        return new SkillPackages(Path.of(root));
    }
}
