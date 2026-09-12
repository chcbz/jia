package cn.jia.chat.archive.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ArchiveReaderProperties.class)
public class ArchiveReaderConfiguration {
    @Bean
    public ArchiveReaderAccessPolicy archiveReaderAccessPolicy(ArchiveReaderProperties properties) {
        return ArchiveReaderAccessPolicy.from(properties);
    }
}
