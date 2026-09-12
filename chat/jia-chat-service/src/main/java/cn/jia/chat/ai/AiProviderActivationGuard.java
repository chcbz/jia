package cn.jia.chat.ai;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/** Validates provider identity before any provider singleton or credentials are initialized. */
public final class AiProviderActivationGuard implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        AiProviderProperties properties = Binder.get(environment)
                .bind("jia.chat.ai", Bindable.of(AiProviderProperties.class))
                .orElseGet(AiProviderProperties::new);
        properties.validate();
        if (!properties.isEnabled()) {
            return;
        }

        String explicitProvider = environment.getProperty("jia.chat.ai.provider");
        String selectedChatModel = environment.getProperty("spring.ai.model.chat", "openai");
        String expected = properties.getProvider().id();
        if (!expected.equals(explicitProvider) || !expected.equals(selectedChatModel)) {
            throw new IllegalStateException("Invalid external AI activation configuration: provider identity mismatch");
        }
    }
}
