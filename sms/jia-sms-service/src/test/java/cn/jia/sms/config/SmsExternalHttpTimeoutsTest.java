package cn.jia.sms.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.RequestDeadlineContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmsExternalHttpTimeoutsTest {
    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void defaultsPreserveExplicitTransportTimeouts() {
        SmsExternalHttpTimeouts timeouts = new SmsExternalHttpTimeouts();

        assertDoesNotThrow(timeouts::validate);
        assertEquals(250, timeouts.getConnectionRequestTimeoutMillis());
        assertEquals(500, timeouts.getConnectTimeoutMillis());
        assertEquals(1750, timeouts.getReadTimeoutMillis());
        assertEquals(2500, timeouts.getTotalTimeoutMillis());
    }

    @Test
    void formerTotalBudgetIsObservationOnlyAndDoesNotShrinkTransportTimeouts() {
        SmsExternalHttpTimeouts timeouts = new SmsExternalHttpTimeouts();
        ReflectionTestUtils.setField(timeouts, "totalTimeoutMillis", 1);
        timeouts.validate();
        MutableClock clock = new MutableClock();
        SmsExternalHttpClient.OperationBudget observation =
                new SmsExternalHttpClient.OperationBudget(timeouts, clock);
        clock.advanceMillis(10_000);

        SmsExternalHttpClient.CallTimeouts callTimeouts =
                new SmsExternalHttpClient(timeouts).prepareSdkCall(observation);

        assertEquals(250, callTimeouts.connectionRequestTimeoutMillis());
        assertEquals(500, callTimeouts.connectTimeoutMillis());
        assertEquals(1750, callTimeouts.readTimeoutMillis());
    }

    @Test
    void exhaustedRequestDeadlineDoesNotRefuseSmsDependencyWork() {
        SmsExternalHttpTimeouts timeouts = new SmsExternalHttpTimeouts();
        SmsExternalHttpClient.OperationBudget observation =
                new SmsExternalHttpClient.OperationBudget(timeouts, () -> 0L);

        SmsExternalHttpClient client = new SmsExternalHttpClient(timeouts);
        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(0))) {
            SmsExternalHttpClient.CallTimeouts callTimeouts = client.prepareSdkCall(observation);
            assertEquals(timeouts.getConnectTimeoutMillis(), callTimeouts.connectTimeoutMillis());
            assertEquals(timeouts.getReadTimeoutMillis(), callTimeouts.readTimeoutMillis());
        }
    }

    @Test
    void slowObservationContainsNoUrlOrPayload() {
        SmsExternalHttpTimeouts timeouts = new SmsExternalHttpTimeouts();
        ReflectionTestUtils.setField(timeouts, "totalTimeoutMillis", 20);
        MutableClock clock = new MutableClock();
        SmsExternalHttpClient.OperationBudget observation =
                new SmsExternalHttpClient.OperationBudget(timeouts, clock);
        long started = observation.markCallStarted();
        clock.advanceMillis(30);

        List<ILoggingEvent> events = captureLogs(() -> observation.observeIfSlow("http", started, "success"));

        assertEquals(1, events.size());
        String message = events.get(0).getFormattedMessage();
        assertTrue(message.contains("transport=http"));
        assertFalse(message.contains("client.example"));
        assertFalse(message.contains("sensitive"));
    }

    @Test
    void rejectsSmsNetworkCallsInsideTransactionsBeforeConnecting() {
        SmsExternalHttpClient client = new SmsExternalHttpClient(new SmsExternalHttpTimeouts());
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(SmsExternalHttpClient.SmsExternalCallRejectedException.class,
                () -> client.postForEntity(client.beginOperation(), "http://127.0.0.1:1",
                        HttpEntity.EMPTY, String.class));
    }

    private static List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(SmsExternalHttpClient.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    private static final class MutableClock implements LongSupplier {
        private long now;

        @Override
        public long getAsLong() {
            return now;
        }

        private void advanceMillis(long millis) {
            now += TimeUnit.MILLISECONDS.toNanos(millis);
        }
    }
}
