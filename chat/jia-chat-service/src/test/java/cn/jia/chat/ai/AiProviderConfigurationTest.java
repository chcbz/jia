package cn.jia.chat.ai;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderConfigurationTest {
    @Test
    void defaultsAreDisabledAndSlowThresholdsDoNotConstrainTransportOrdering() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.validate();

        assertFalse(properties.isEnabled());
        assertEquals(AiProviderProperties.Provider.DISABLED, properties.getProvider());
        assertEquals(Duration.ofMillis(500), properties.getConnectBudget());
        assertEquals(Duration.ofMillis(2500), properties.getFirstTokenBudget());
        assertEquals(Duration.ofSeconds(25), properties.getTotalBudget());

        properties.setConnectBudget(Duration.ofSeconds(3));
        assertTrue(properties.getConnectBudget().compareTo(properties.getFirstTokenBudget()) > 0);
        properties.validate();
    }

    @Test
    void defaultOffFilterBlocksOnlyProviderChatAutoConfigurations() {
        AiChatAutoConfigurationImportFilter filter = new AiChatAutoConfigurationImportFilter();
        filter.setEnvironment(new MockEnvironment());

        boolean[] matches = filter.match(new String[] {
                AiChatAutoConfigurationImportFilter.OPENAI_CHAT_AUTO_CONFIGURATION,
                AiChatAutoConfigurationImportFilter.DEEPSEEK_CHAT_AUTO_CONFIGURATION,
                "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration"
        }, null);

        assertFalse(matches[0]);
        assertFalse(matches[1]);
        assertTrue(matches[2]);
    }

    @Test
    void explicitActivationPreservesExactSelectedProvider() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("jia.chat.ai.enabled", "true")
                .withProperty("jia.chat.ai.provider", "openai")
                .withProperty("spring.ai.model.chat", "openai");
        AiProviderActivationGuard guard = new AiProviderActivationGuard();
        guard.setEnvironment(environment);
        guard.postProcessBeanFactory(new DefaultListableBeanFactory());
    }

    @Test
    void activationFailsClosedOnProviderIdentityMismatch() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("jia.chat.ai.enabled", "true")
                .withProperty("jia.chat.ai.provider", "openai")
                .withProperty("spring.ai.model.chat", "deepseek");
        AiProviderActivationGuard guard = new AiProviderActivationGuard();
        guard.setEnvironment(environment);

        assertThrows(IllegalStateException.class,
                () -> guard.postProcessBeanFactory(new DefaultListableBeanFactory()));
    }

    @Test
    void postProcessorPreservesConfiguredProviderTimeoutAndZeroSdkRetries() {
        AiProviderProperties properties = enabledProperties();
        AiModelBeanPostProcessor processor = new AiModelBeanPostProcessor(properties);
        OpenAiChatProperties openAi = new OpenAiChatProperties();
        Duration configuredNetworkTimeout = Duration.ofSeconds(90);
        openAi.setTimeout(configuredNetworkTimeout);

        processor.postProcessBeforeInitialization(openAi, "openAiChatProperties");

        assertEquals(0, openAi.getMaxRetries());
        assertEquals(configuredNetworkTimeout, openAi.getTimeout());
        ChatModel wrapped = assertInstanceOf(ChatModel.class,
                processor.postProcessAfterInitialization((ChatModel) prompt -> null, "providerModel"));
        assertInstanceOf(BudgetedChatModel.class, wrapped);
    }

    @Test
    void genericSpringAiRetryTemplateExecutesFailureOnlyOnce() {
        AtomicInteger attempts = new AtomicInteger();
        RetryException failure = assertThrows(RetryException.class,
                () -> new AiProviderBudgetConfiguration().aiNoRetryTemplate().execute(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("provider body");
                }));

        assertEquals(1, attempts.get());
        assertFalse(failure.getMessage().contains("provider body"));
    }

    @Test
    void transportScopeRecognizesOnlyChatEndpoints() {
        assertTrue(OpenAiChatTransportCustomizer.isChatPath("/v1/chat/completions"));
        assertTrue(OpenAiChatTransportCustomizer.isChatPath("/v1/responses"));
        assertFalse(OpenAiChatTransportCustomizer.isChatPath("/v1/audio/transcriptions"));
        assertFalse(OpenAiChatTransportCustomizer.isChatPath("/v1/embeddings"));
    }

    @Test
    void disabledModelReturnsOnlySafeFiniteFailure() {
        AiProviderCallException failure = assertThrows(AiProviderCallException.class,
                () -> new DisabledChatModel().call(new org.springframework.ai.chat.prompt.Prompt("private prompt")));

        assertEquals(AiFailureCategory.DISABLED, failure.category());
        assertFalse(failure.retryable());
        assertFalse(failure.getMessage().contains("private prompt"));
        assertEquals(null, failure.getCause());
    }

    private static AiProviderProperties enabledProperties() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setEnabled(true);
        properties.setProvider(AiProviderProperties.Provider.OPENAI);
        properties.setConnectBudget(Duration.ofMillis(50));
        properties.setFirstTokenBudget(Duration.ofMillis(100));
        properties.setTotalBudget(Duration.ofMillis(200));
        properties.validate();
        return properties;
    }
}
