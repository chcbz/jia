package cn.jia.chat.archive.config;

import cn.jia.chat.archive.service.ArchiveClerkFallbackProvider;
import cn.jia.chat.archive.service.ArchiveQuestionProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@ConditionalOnProperty(prefix = "archive.question", name = "enabled", havingValue = "true")
@Configuration
public class ArchiveQuestionProviderConfiguration {
    @Bean
    @ConditionalOnMissingBean(ArchiveQuestionProvider.class)
    public ArchiveQuestionProvider archiveQuestionProvider() {
        return new ArchiveClerkFallbackProvider();
    }
}
