package cn.jia.agent.config.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Independent schema gate; false unless explicitly enabled. */
@ConfigurationProperties(prefix = "economy.skill.schema")
public record SkillSchemaProperties(boolean enabled) {
    @ConstructorBinding
    public SkillSchemaProperties {
    }
}
