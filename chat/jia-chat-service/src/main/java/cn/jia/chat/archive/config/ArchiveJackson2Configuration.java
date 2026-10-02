package cn.jia.chat.archive.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the legacy Jackson 2 mapper used only by the archive contracts.
 *
 * <p>Spring Boot 4 configures its HTTP stack with Jackson 3 ({@code tools.jackson}) and therefore
 * does not create this distinct Jackson 2 type. Keeping the bridge here preserves the archive
 * request, digest, and persisted-snapshot codec without replacing the Boot MVC mapper.
 */
@Configuration(proxyBeanMethods = false)
public class ArchiveJackson2Configuration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper archiveJackson2ObjectMapper() {
        return new ObjectMapper();
    }
}
