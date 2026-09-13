package cn.jia.core.amqp;

import com.rabbitmq.client.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Applies explicitly enabled RabbitMQ transport waits without changing confirms, retries, recovery, ACKs or state. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
public class RabbitMqBudgetConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "jia.rabbitmq.budget", name = "enabled", havingValue = "true")
    static BeanPostProcessor rabbitMqBudgetBeanPostProcessor(Environment environment) {
        // A static post-processor can be instantiated before embedded placeholder/conversion services.
        // Read explicit values directly from the already available Environment instead.
        RabbitMqWaitBudget budget = new RabbitMqWaitBudget(
                configuredDuration(environment, "connection-timeout"),
                configuredDuration(environment, "handshake-timeout"),
                configuredDuration(environment, "channel-rpc-timeout"),
                configuredDuration(environment, "channel-checkout-timeout"),
                configuredDuration(environment, "receive-timeout"),
                configuredDuration(environment, "reply-timeout"));
        return new RabbitMqBudgetBeanPostProcessor(budget);
    }

    private static java.time.Duration configuredDuration(Environment environment, String name) {
        return DurationStyle.detectAndParse(environment.getRequiredProperty("jia.rabbitmq.budget." + name));
    }

    static final class RabbitMqBudgetBeanPostProcessor implements BeanPostProcessor {
        private final RabbitMqWaitBudget budget;

        RabbitMqBudgetBeanPostProcessor(RabbitMqWaitBudget budget) {
            this.budget = budget;
        }

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
            if (bean instanceof AbstractConnectionFactory connectionFactory) {
                configureConnectionFactory(connectionFactory,
                        Collections.newSetFromMap(new IdentityHashMap<>()));
            }
            if (bean instanceof RabbitTemplate template) {
                configureTemplate(template);
            }
            return bean;
        }

        private void configureConnectionFactory(AbstractConnectionFactory factory,
                Set<AbstractConnectionFactory> configured) {
            if (!configured.add(factory)) {
                return;
            }
            configureNativeFactory(factory.getRabbitConnectionFactory());
            if (factory instanceof CachingConnectionFactory cachingFactory) {
                // A checkout timeout is allowed to fail acquisition; it must never be converted into a successful send.
                cachingFactory.setChannelCheckoutTimeout(budget.channelCheckoutTimeoutMillis());
            }
            if (factory.hasPublisherConnectionFactory()
                    && factory.getPublisherConnectionFactory() instanceof AbstractConnectionFactory publisherFactory) {
                configureConnectionFactory(publisherFactory, configured);
            }
        }

        private void configureNativeFactory(ConnectionFactory factory) {
            factory.setConnectionTimeout(budget.connectionTimeoutMillis());
            factory.setHandshakeTimeout(budget.handshakeTimeoutMillis());
            factory.setChannelRpcTimeout(budget.channelRpcTimeoutMillis());
        }

        private void configureTemplate(RabbitTemplate template) {
            template.setReceiveTimeout(budget.receiveTimeoutMillis());
            template.setReplyTimeout(budget.replyTimeoutMillis());
        }
    }
}
