package cn.jia.chat.ai;

import java.time.Duration;
import java.util.concurrent.ExecutorService;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

final class AiModelBeanPostProcessor implements BeanPostProcessor {
    private final AiProviderProperties properties;
    private final ExecutorService synchronousExecutor;

    AiModelBeanPostProcessor(AiProviderProperties properties, ExecutorService synchronousExecutor) {
        this.properties = properties;
        this.synchronousExecutor = synchronousExecutor;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof OpenAiChatProperties openAiChatProperties) {
            properties.validate();
            openAiChatProperties.setMaxRetries(0);
            openAiChatProperties.setTimeout(Duration.ofMillis(properties.getTotalBudget().toMillis()));
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!properties.isEnabled() || bean instanceof DisabledChatModel || bean instanceof BudgetedChatModel) {
            return bean;
        }
        if (bean instanceof ChatModel chatModel) {
            return new BudgetedChatModel(chatModel, properties, synchronousExecutor);
        }
        return bean;
    }
}
