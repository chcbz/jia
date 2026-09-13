package cn.jia.chat.ai;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
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

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService aiSynchronousExecutor(AiProviderProperties properties) {
        properties.validate();
        int concurrency = properties.getSynchronousConcurrency();
        return new ThreadPoolExecutor(concurrency, concurrency, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), daemonThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public static AiModelBeanPostProcessor aiModelBeanPostProcessor(
            AiProviderProperties properties,
            @Qualifier("aiSynchronousExecutor") ExecutorService aiSynchronousExecutor) {
        return new AiModelBeanPostProcessor(properties, aiSynchronousExecutor);
    }

    @Bean
    @ConditionalOnProperty(prefix = "jia.chat.ai", name = "provider", havingValue = "openai")
    public OpenAiHttpClientBuilderCustomizer openAiChatTransportCustomizer(AiProviderProperties properties) {
        properties.validate();
        return new OpenAiChatTransportCustomizer(properties);
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "chat-ai-bounded-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
