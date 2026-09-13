package cn.jia.core.amqp;

import com.rabbitmq.client.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Applies bounded RabbitMQ waits without changing routing, confirms, returns, transactions, ACKs or outbox state. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
public class RabbitMqBudgetConfiguration {

    @Bean
    static BeanPostProcessor rabbitMqBudgetBeanPostProcessor(
            @Value("${jia.rabbitmq.budget.request-budget:2500ms}") Duration requestBudget,
            @Value("${jia.rabbitmq.budget.safety-margin:100ms}") Duration safetyMargin,
            @Value("${jia.rabbitmq.budget.connection-timeout:500ms}") Duration connectionTimeout,
            @Value("${jia.rabbitmq.budget.handshake-timeout:750ms}") Duration handshakeTimeout,
            @Value("${jia.rabbitmq.budget.channel-rpc-timeout:1000ms}") Duration channelRpcTimeout,
            @Value("${jia.rabbitmq.budget.channel-checkout-timeout:500ms}") Duration channelCheckoutTimeout,
            @Value("${jia.rabbitmq.budget.receive-timeout:0ms}") Duration receiveTimeout,
            @Value("${jia.rabbitmq.budget.reply-timeout:2000ms}") Duration replyTimeout) {
        RabbitMqWaitBudget budget = new RabbitMqWaitBudget(requestBudget, safetyMargin, connectionTimeout,
                handshakeTimeout, channelRpcTimeout, channelCheckoutTimeout, receiveTimeout, replyTimeout);
        return new RabbitMqBudgetBeanPostProcessor(budget);
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
            // Native recovery can replay topology or reconnect independently of the existing outbox/confirm owner.
            factory.setAutomaticRecoveryEnabled(false);
            factory.setTopologyRecoveryEnabled(false);
        }

        private void configureTemplate(RabbitTemplate template) {
            template.setReceiveTimeout(budget.receiveTimeoutMillis());
            template.setReplyTimeout(budget.replyTimeoutMillis());
            // A timeout or publish exception can mean delivery is unknown; never infer that replay is safe here.
            template.setRetryTemplate(null);
        }
    }
}
