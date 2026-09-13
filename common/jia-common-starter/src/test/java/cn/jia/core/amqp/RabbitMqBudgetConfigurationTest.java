package cn.jia.core.amqp;

import com.rabbitmq.client.AddressResolver;
import com.rabbitmq.client.Connection;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.retry.RetryTemplate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RabbitMqBudgetConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RabbitMqBudgetConfiguration.class);

    @Test
    void performanceTransportOverridesAreDisabledByDefault() {
        contextRunner.run(context -> assertFalse(context.containsBean("rabbitMqBudgetBeanPostProcessor")));
    }

    @Test
    void explicitFiniteTransportSettingsEnableTheAdapterWithoutAnApiSloCap() {
        contextRunner.withPropertyValues(
                        "jia.rabbitmq.budget.enabled=true",
                        "jia.rabbitmq.budget.connection-timeout=30s",
                        "jia.rabbitmq.budget.handshake-timeout=20s",
                        "jia.rabbitmq.budget.channel-rpc-timeout=45s",
                        "jia.rabbitmq.budget.channel-checkout-timeout=60s",
                        "jia.rabbitmq.budget.receive-timeout=10s",
                        "jia.rabbitmq.budget.reply-timeout=90s")
                .run(context -> {
                    assertTrue(context.containsBean("rabbitMqBudgetBeanPostProcessor"));
                    assertNull(context.getStartupFailure());
                });
    }

    @Test
    void enabledAdapterRequiresCompleteFiniteTransportConfiguration() {
        contextRunner.withPropertyValues("jia.rabbitmq.budget.enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
        assertThrows(NullPointerException.class, () -> waits(null, seconds(1), seconds(1),
                seconds(1), Duration.ZERO, seconds(1)));
        assertThrows(IllegalArgumentException.class, () -> waits(Duration.ZERO, seconds(1), seconds(1),
                seconds(1), Duration.ZERO, seconds(1)));
        assertThrows(IllegalArgumentException.class, () -> waits(Duration.ofNanos(1), seconds(1), seconds(1),
                seconds(1), Duration.ZERO, seconds(1)));
        assertThrows(IllegalArgumentException.class, () -> waits(seconds(1), seconds(1), seconds(1),
                seconds(1), Duration.ofMillis(-1), seconds(1)));
        assertThrows(IllegalArgumentException.class, () -> waits(
                Duration.ofMillis((long) Integer.MAX_VALUE + 1), seconds(1), seconds(1), seconds(1),
                Duration.ZERO, seconds(1)));
    }

    @Test
    void configuresDefaultAndDedicatedFactoriesWithoutConnectingOrChangingRecovery() {
        RabbitMqBudgetConfiguration.RabbitMqBudgetBeanPostProcessor processor = processor();
        NoNetworkConnectionFactory defaultNative = new NoNetworkConnectionFactory();
        NoNetworkConnectionFactory dedicatedNative = new NoNetworkConnectionFactory();
        CachingConnectionFactory defaultFactory = new CachingConnectionFactory(defaultNative);
        CachingConnectionFactory dedicatedFactory = new CachingConnectionFactory(dedicatedNative);
        // Spring may set its recovery policy during construction; configure the baseline afterward.
        defaultNative.setAutomaticRecoveryEnabled(true);
        defaultNative.setTopologyRecoveryEnabled(true);
        dedicatedNative.setAutomaticRecoveryEnabled(true);
        dedicatedNative.setTopologyRecoveryEnabled(true);
        dedicatedFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        dedicatedFactory.setPublisherReturns(true);
        dedicatedFactory.setChannelCacheSize(7);

        assertSame(defaultFactory, processor.postProcessBeforeInitialization(defaultFactory, "rabbitConnectionFactory"));
        assertSame(dedicatedFactory,
                processor.postProcessBeforeInitialization(dedicatedFactory, "agentRabbitConnectionFactory"));

        assertNativeWaits(defaultNative);
        assertNativeWaits(dedicatedNative);
        assertPublisherNativeWaits(dedicatedFactory);
        assertEquals(60_000L, field(defaultFactory, "channelCheckoutTimeout"));
        assertEquals(60_000L, field(dedicatedFactory, "channelCheckoutTimeout"));
        assertEquals(7, dedicatedFactory.getChannelCacheSize());
        assertTrue(dedicatedFactory.isPublisherConfirms());
        assertFalse(dedicatedFactory.isSimplePublisherConfirms());
        assertTrue(dedicatedFactory.isPublisherReturns());
        assertEquals(0, defaultNative.connectionAttempts.get());
        assertEquals(0, dedicatedNative.connectionAttempts.get());
    }

    @Test
    void configuresTemplateWaitsWithoutChangingRetryDeliveryOrTransactionSettings() {
        RabbitMqBudgetConfiguration.RabbitMqBudgetBeanPostProcessor processor = processor();
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(new NoNetworkConnectionFactory());
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        RetryTemplate retryTemplate = new RetryTemplate();
        template.setMandatory(true);
        template.setChannelTransacted(true);
        template.setReceiveTimeout(-1L);
        template.setReplyTimeout(5_000L);
        template.setRetryTemplate(retryTemplate);

        assertSame(template, processor.postProcessBeforeInitialization(template, "agentRabbitTemplate"));

        assertSame(connectionFactory, template.getConnectionFactory());
        assertTrue(template.isMandatoryFor(new Message(new byte[0])));
        assertTrue(template.isChannelTransacted());
        assertEquals(10_000L, field(template, "receiveTimeout"));
        assertEquals(90_000L, field(template, "replyTimeout"));
        assertSame(retryTemplate, field(template, "retryTemplate"));
    }

    private static RabbitMqBudgetConfiguration.RabbitMqBudgetBeanPostProcessor processor() {
        return new RabbitMqBudgetConfiguration.RabbitMqBudgetBeanPostProcessor(waits(
                seconds(30), seconds(20), seconds(45), seconds(60), seconds(10), seconds(90)));
    }

    private static RabbitMqWaitBudget waits(Duration connectionTimeout, Duration handshakeTimeout,
            Duration channelRpcTimeout, Duration channelCheckoutTimeout, Duration receiveTimeout,
            Duration replyTimeout) {
        return new RabbitMqWaitBudget(connectionTimeout, handshakeTimeout, channelRpcTimeout,
                channelCheckoutTimeout, receiveTimeout, replyTimeout);
    }

    private static Duration seconds(long seconds) {
        return Duration.ofSeconds(seconds);
    }

    private static void assertNativeWaits(NoNetworkConnectionFactory nativeFactory) {
        assertEquals(30_000, nativeFactory.getConnectionTimeout());
        assertEquals(20_000, nativeFactory.getHandshakeTimeout());
        assertEquals(45_000, nativeFactory.getChannelRpcTimeout());
        assertTrue(nativeFactory.isAutomaticRecoveryEnabled());
        assertTrue(nativeFactory.isTopologyRecoveryEnabled());
    }

    private static void assertPublisherNativeWaits(CachingConnectionFactory factory) {
        AbstractConnectionFactory publisherFactory = (AbstractConnectionFactory) factory.getPublisherConnectionFactory();
        com.rabbitmq.client.ConnectionFactory nativeFactory = publisherFactory.getRabbitConnectionFactory();
        assertEquals(30_000, nativeFactory.getConnectionTimeout());
        assertEquals(20_000, nativeFactory.getHandshakeTimeout());
        assertEquals(45_000, nativeFactory.getChannelRpcTimeout());
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
