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
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SmsExternalHttpTimeoutsTest {
    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void defaultsDoNotInstallPerfAddedTransportOverrides() {
        SmsExternalHttpTimeouts timeouts = new SmsExternalHttpTimeouts();

        assertDoesNotThrow(timeouts::validate);
        assertNull(timeouts.getConnectionRequestTimeoutMillis());
        assertNull(timeouts.getConnectTimeoutMillis());
        assertNull(timeouts.getReadTimeoutMillis());
        assertEquals(Integer.valueOf(2500), timeouts.getTotalTimeoutMillis());
    }

    @Test
    void explicitOverridesAreNotShrunkByElapsedOrRequestDeadlineState() {
        SmsExternalHttpTimeouts timeouts = new SmsExternalHttpTimeouts();
        ReflectionTestUtils.setField(timeouts, "connectionRequestTimeoutMillis", 250);
        ReflectionTestUtils.setField(timeouts, "connectTimeoutMillis", 500);
        ReflectionTestUtils.setField(timeouts, "readTimeoutMillis", 1750);
        ReflectionTestUtils.setField(timeouts, "totalTimeoutMillis", 1);
        timeouts.validate();
        MutableClock clock = new MutableClock();
        SmsExternalHttpClient.OperationBudget observation =
                new SmsExternalHttpClient.OperationBudget(timeouts, clock);
        clock.advanceMillis(10_000);

        SmsExternalHttpClient client = new SmsExternalHttpClient(mock(RestTemplate.class), timeouts);
        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(0))) {
            SmsExternalHttpClient.CallTimeouts callTimeouts = client.prepareSdkCall(observation);
            assertEquals(Integer.valueOf(250), callTimeouts.connectionRequestTimeoutMillis());
            assertEquals(Integer.valueOf(500), callTimeouts.connectTimeoutMillis());
            assertEquals(Integer.valueOf(1750), callTimeouts.readTimeoutMillis());
        }
    }

    @Test
    void httpCallsReuseSharedConfiguredRestTemplate() {
        SmsExternalHttpTimeouts timeouts = new SmsExternalHttpTimeouts();
        RestTemplate sharedRestTemplate = mock(RestTemplate.class);
        when(sharedRestTemplate.postForEntity("https://client.example/sms", HttpEntity.EMPTY, String.class))
                .thenReturn(ResponseEntity.ok("ok"));
        SmsExternalHttpClient client = new SmsExternalHttpClient(sharedRestTemplate, timeouts);
        SmsExternalHttpClient.OperationBudget observation = client.beginOperation();

        ResponseEntity<String> response = client.postForEntity(
                observation, "https://client.example/sms", HttpEntity.EMPTY, String.class);

        assertEquals("ok", response.getBody());
        verify(sharedRestTemplate).postForEntity(
                "https://client.example/sms", HttpEntity.EMPTY, String.class);
    }

    @Test
    void defaultSlowObservationContainsNoUrlOrPayload() {
        SmsExternalHttpTimeouts timeouts = new SmsExternalHttpTimeouts();
        MutableClock clock = new MutableClock();
        SmsExternalHttpClient.OperationBudget observation =
                new SmsExternalHttpClient.OperationBudget(timeouts, clock);
        long started = observation.markCallStarted();
        clock.advanceMillis(3_000);

        List<ILoggingEvent> events = captureLogs(() -> observation.observeIfSlow("http", started, "success"));

        assertEquals(1, events.size());
        String message = events.get(0).getFormattedMessage();
        assertTrue(message.contains("transport=http"));
        assertFalse(message.contains("client.example"));
        assertFalse(message.contains("sensitive"));
    }

    @Test
    void rejectsSmsNetworkCallsInsideTransactionsBeforeConnecting() {
        RestTemplate sharedRestTemplate = mock(RestTemplate.class);
        SmsExternalHttpClient client = new SmsExternalHttpClient(
                sharedRestTemplate, new SmsExternalHttpTimeouts());
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(SmsExternalHttpClient.SmsExternalCallRejectedException.class,
                () -> client.postForEntity(client.beginOperation(), "http://127.0.0.1:1",
                        HttpEntity.EMPTY, String.class));
        verifyNoInteractions(sharedRestTemplate);
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
