package cn.jia.core.amqp;

import com.rabbitmq.client.AddressResolver;
import com.rabbitmq.client.Connection;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.retry.RetryTemplate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RabbitMqBudgetConfigurationTest {

    @Test
    void defaultsAreFiniteAndFitBothColdAndCachedPathsBeforeSafetyMargin() {
        RabbitMqWaitBudget budget = RabbitMqWaitBudget.defaults();

        assertEquals(2_500L, budget.requestBudgetMillis());
        assertEquals(100L, budget.safetyMarginMillis());
        assertEquals(500, budget.connectionTimeoutMillis());
        assertEquals(750, budget.handshakeTimeoutMillis());
        assertEquals(1_000, budget.channelRpcTimeoutMillis());
        assertEquals(500L, budget.channelCheckoutTimeoutMillis());
        assertEquals(0L, budget.receiveTimeoutMillis());
        assertEquals(2_000L, budget.replyTimeoutMillis());
        assertTrue(budget.coldConnectionPathMillis()
                <= budget.requestBudgetMillis() - budget.safetyMarginMillis());
        assertTrue(budget.cachedChannelPathMillis()
                <= budget.requestBudgetMillis() - budget.safetyMarginMillis());
    }

    @Test
    void invalidOrOverBudgetSettingsFailClosed() {
        assertThrows(NullPointerException.class, () -> budget(null, ms(100), ms(500), ms(750),
                ms(1_000), ms(500), Duration.ZERO, ms(2_000)));
        assertThrows(IllegalArgumentException.class, () -> budget(Duration.ZERO, ms(100), ms(500), ms(750),
                ms(1_000), ms(500), Duration.ZERO, ms(2_000)));
        assertThrows(IllegalArgumentException.class, () -> budget(ms(3_001), ms(100), ms(500), ms(750),
                ms(1_000), ms(500), Duration.ZERO, ms(2_000)));
        assertThrows(IllegalArgumentException.class, () -> budget(ms(2_500), ms(2_500), ms(500), ms(750),
                ms(1_000), ms(500), Duration.ZERO, ms(2_000)));
        assertThrows(IllegalArgumentException.class, () -> budget(ms(2_500), ms(100), Duration.ZERO, ms(750),
                ms(1_000), ms(500), Duration.ZERO, ms(2_000)));
        assertThrows(IllegalArgumentException.class, () -> budget(ms(2_500), ms(100), Duration.ofNanos(1), ms(750),
                ms(1_000), ms(500), Duration.ZERO, ms(2_000)));
        assertThrows(IllegalArgumentException.class, () -> budget(ms(2_500), ms(100), ms(500), ms(750),
                ms(1_151), ms(500), Duration.ZERO, ms(2_000)));
        assertThrows(IllegalArgumentException.class, () -> budget(ms(2_500), ms(100), ms(500), ms(750),
                ms(1_000), ms(1_401), Duration.ZERO, ms(2_000)));
        assertThrows(IllegalArgumentException.class, () -> budget(ms(2_500), ms(100), ms(500), ms(750),
                ms(1_000), ms(500), ms(-1), ms(2_000)));
        assertThrows(IllegalArgumentException.class, () -> budget(ms(2_500), ms(100), ms(500), ms(750),
                ms(1_000), ms(500), Duration.ZERO, ms(2_401)));
        assertThrows(IllegalArgumentException.class, () -> budget(ms(2_500), ms(100),
                ms((long) Integer.MAX_VALUE + 1), ms(1), ms(1), ms(1), Duration.ZERO, ms(1)));
    }

    @Test
    void boundsDefaultAndDedicatedFactoriesWithoutConnectingOrChangingPublisherSemantics() {
        RabbitMqBudgetConfiguration.RabbitMqBudgetBeanPostProcessor processor = processor();
        NoNetworkConnectionFactory defaultNative = new NoNetworkConnectionFactory();
        NoNetworkConnectionFactory dedicatedNative = new NoNetworkConnectionFactory();
        CachingConnectionFactory defaultFactory = new CachingConnectionFactory(defaultNative);
        CachingConnectionFactory dedicatedFactory = new CachingConnectionFactory(dedicatedNative);
        dedicatedFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        dedicatedFactory.setPublisherReturns(true);
        dedicatedFactory.setChannelCacheSize(7);

        assertSame(defaultFactory, processor.postProcessBeforeInitialization(defaultFactory, "rabbitConnectionFactory"));
        assertSame(dedicatedFactory,
                processor.postProcessBeforeInitialization(dedicatedFactory, "agentRabbitConnectionFactory"));

        assertNativeBudget(defaultNative);
        assertNativeBudget(dedicatedNative);
        assertPublisherNativeBudget(dedicatedFactory);
        assertEquals(500L, field(defaultFactory, "channelCheckoutTimeout"));
        assertEquals(500L, field(dedicatedFactory, "channelCheckoutTimeout"));
        assertEquals(7, dedicatedFactory.getChannelCacheSize());
        assertTrue(dedicatedFactory.isPublisherConfirms());
        assertFalse(dedicatedFactory.isSimplePublisherConfirms());
        assertTrue(dedicatedFactory.isPublisherReturns());
        assertEquals(0, defaultNative.connectionAttempts.get());
        assertEquals(0, dedicatedNative.connectionAttempts.get());
    }

    @Test
    void boundsTemplateWaitsAndDisablesRetryWithoutChangingDeliveryOrTransactionSettings() {
        RabbitMqBudgetConfiguration.RabbitMqBudgetBeanPostProcessor processor = processor();
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(new NoNetworkConnectionFactory());
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        template.setChannelTransacted(true);
        template.setReceiveTimeout(-1L);
        template.setReplyTimeout(60_000L);
        template.setRetryTemplate(new RetryTemplate());

        assertSame(template, processor.postProcessBeforeInitialization(template, "agentRabbitTemplate"));

        assertSame(connectionFactory, template.getConnectionFactory());
        assertTrue(template.isMandatoryFor(new Message(new byte[0])));
        assertTrue(template.isChannelTransacted());
        assertEquals(0L, field(template, "receiveTimeout"));
        assertEquals(2_000L, field(template, "replyTimeout"));
        assertNull(field(template, "retryTemplate"));
    }

    private static RabbitMqBudgetConfiguration.RabbitMqBudgetBeanPostProcessor processor() {
        return new RabbitMqBudgetConfiguration.RabbitMqBudgetBeanPostProcessor(RabbitMqWaitBudget.defaults());
    }

    private static RabbitMqWaitBudget budget(Duration requestBudget, Duration safetyMargin,
            Duration connectionTimeout, Duration handshakeTimeout, Duration channelRpcTimeout,
            Duration channelCheckoutTimeout, Duration receiveTimeout, Duration replyTimeout) {
        return new RabbitMqWaitBudget(requestBudget, safetyMargin, connectionTimeout, handshakeTimeout,
                channelRpcTimeout, channelCheckoutTimeout, receiveTimeout, replyTimeout);
    }

    private static Duration ms(long millis) {
        return Duration.ofMillis(millis);
    }

    private static void assertNativeBudget(NoNetworkConnectionFactory nativeFactory) {
        assertEquals(500, nativeFactory.getConnectionTimeout());
        assertEquals(750, nativeFactory.getHandshakeTimeout());
        assertEquals(1_000, nativeFactory.getChannelRpcTimeout());
        assertFalse(nativeFactory.isAutomaticRecoveryEnabled());
        assertFalse(nativeFactory.isTopologyRecoveryEnabled());
    }

    private static void assertPublisherNativeBudget(CachingConnectionFactory factory) {
        AbstractConnectionFactory publisherFactory = (AbstractConnectionFactory) factory.getPublisherConnectionFactory();
        com.rabbitmq.client.ConnectionFactory nativeFactory = publisherFactory.getRabbitConnectionFactory();
        assertEquals(500, nativeFactory.getConnectionTimeout());
        assertEquals(750, nativeFactory.getHandshakeTimeout());
        assertEquals(1_000, nativeFactory.getChannelRpcTimeout());
        assertFalse(nativeFactory.isAutomaticRecoveryEnabled());
        assertFalse(nativeFactory.isTopologyRecoveryEnabled());
    }

    private static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
        throw new AssertionError("Missing field " + name);
    }

    private static final class NoNetworkConnectionFactory extends com.rabbitmq.client.ConnectionFactory {
        private final AtomicInteger connectionAttempts = new AtomicInteger();

        @Override
        public Connection newConnection(ExecutorService executor, AddressResolver addressResolver, String clientName)
                throws IOException, TimeoutException {
            connectionAttempts.incrementAndGet();
            throw new AssertionError("configuration must not establish a RabbitMQ connection");
        }
    }
}
