package cn.jia.agent.config;

import cn.jia.agent.service.PersonalWorkspaceStorage;
import cn.jia.agent.service.impl.DisabledPersonalWorkspaceStorage;
import cn.jia.agent.service.impl.FileSystemPersonalWorkspaceStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.util.LinkedHashSet;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PersonalWorkspaceStorageProperties.class, PersonalWorkspaceExecutionProperties.class})
public class PersonalWorkspaceStorageConfiguration {
    @Bean
    public PersonalWorkspaceStorage personalWorkspaceStorage(PersonalWorkspaceStorageProperties properties) {
        if (!properties.enabled()) return new DisabledPersonalWorkspaceStorage();
        if (properties.rootDirectory() == null || properties.rootDirectory().isBlank()
                || properties.maxContentBytes() == null || properties.maxContentBytes() < 1
                || properties.allowedMimeTypes().isEmpty()) {
            throw new IllegalStateException("Invalid personal workspace storage configuration");
        }
        return new FileSystemPersonalWorkspaceStorage(Path.of(properties.rootDirectory()),
                properties.maxContentBytes(), new LinkedHashSet<>(properties.allowedMimeTypes()));
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.personal-workspace-storage", name = "enabled", havingValue = "true")
    public PersonalWorkspaceSchemaInitializer personalWorkspaceSchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new PersonalWorkspaceSchemaInitializer(jdbcTemplate);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.personal-workspace-storage", name = "enabled", havingValue = "true")
    public PersonalWorkspaceExecutionSchemaInitializer personalWorkspaceExecutionSchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new PersonalWorkspaceExecutionSchemaInitializer(jdbcTemplate);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.personal-workspace-storage", name = "enabled", havingValue = "true")
    public PersonalWorkspaceTaskLinkSchemaInitializer personalWorkspaceTaskLinkSchemaInitializer(
            JdbcTemplate jdbcTemplate) {
        return new PersonalWorkspaceTaskLinkSchemaInitializer(jdbcTemplate);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.personal-workspace-storage", name = "enabled", havingValue = "true")
    public PersonalWorkspaceConversationLinkSchemaInitializer personalWorkspaceConversationLinkSchemaInitializer(
            JdbcTemplate jdbcTemplate) {
        return new PersonalWorkspaceConversationLinkSchemaInitializer(jdbcTemplate);
    }
}
