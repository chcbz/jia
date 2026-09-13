package cn.jia.chat.ai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProviderProperties.class)
public class AiProviderBudgetConfiguration {
    @Bean
    public static AiProviderActivationGuard aiProviderActivationGuard() {
        return new AiProviderActivationGuard();
    }

    @Bean
    @ConditionalOnProperty(prefix = "jia.chat.ai", name = "enabled", havingValue = "false", matchIfMissing = true)
    public ChatModel disabledChatModel() {
        return new DisabledChatModel();
    }

    @Bean(name = "retryTemplate")
    public RetryTemplate aiNoRetryTemplate() {
        return new RetryTemplate(RetryPolicy.builder().maxRetries(0).build());
    }

    @Bean
    public static AiModelBeanPostProcessor aiModelBeanPostProcessor(AiProviderProperties properties) {
        return new AiModelBeanPostProcessor(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "jia.chat.ai", name = "provider", havingValue = "openai")
    public OpenAiHttpClientBuilderCustomizer openAiChatTransportCustomizer(AiProviderProperties properties) {
        properties.validate();
        return new OpenAiChatTransportCustomizer(properties);
    }
}
