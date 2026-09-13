package cn.jia.chat.ai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

final class AiModelBeanPostProcessor implements BeanPostProcessor {
    private final AiProviderProperties properties;

    AiModelBeanPostProcessor(AiProviderProperties properties) {
        this.properties = properties;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof OpenAiChatProperties openAiChatProperties) {
            properties.validate();
            // A retry can duplicate a billable provider call. Preserve the existing single-shot protection.
            openAiChatProperties.setMaxRetries(0);
            // Do not replace an explicitly configured provider/network timeout with an API performance goal.
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!properties.isEnabled() || bean instanceof DisabledChatModel || bean instanceof BudgetedChatModel) {
            return bean;
        }
        if (bean instanceof ChatModel chatModel) {
            return new BudgetedChatModel(chatModel, properties);
        }
        return bean;
    }
}
