package cn.jia.economy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Independent schema gate; false unless explicitly enabled. */
@ConfigurationProperties(prefix = "economy.skill.schema")
public record EconomySkillSchemaProperties(boolean enabled) {
    @ConstructorBinding
    public EconomySkillSchemaProperties {
    }
}
