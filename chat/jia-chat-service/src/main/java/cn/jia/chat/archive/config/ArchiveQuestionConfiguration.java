package cn.jia.chat.archive.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ArchiveQuestionProperties.class)
public class ArchiveQuestionConfiguration {
    @Bean
    public ArchiveQuestionAccessPolicy archiveQuestionAccessPolicy(
            ArchiveQuestionProperties properties, ArchiveReaderAccessPolicy readerAccessPolicy) {
        return ArchiveQuestionAccessPolicy.from(properties, readerAccessPolicy);
    }
}
