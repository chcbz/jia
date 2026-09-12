package cn.jia.sms.config;

import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.RequestDeadlineContext;
import cn.jia.core.deadline.SafeRequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SmsExternalHttpTimeoutsTest {
    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void defaultsFitTheTotalBudget() {
        SmsExternalHttpTimeouts timeouts = new SmsExternalHttpTimeouts();

        assertDoesNotThrow(timeouts::validate);
        assertEquals(250, timeouts.getConnectionRequestTimeoutMillis());
        assertEquals(500, timeouts.getConnectTimeoutMillis());
        assertEquals(1750, timeouts.getReadTimeoutMillis());
        assertEquals(2500, timeouts.getTotalTimeoutMillis());
        assertEquals(100, timeouts.getSafetyMarginMillis());
    }

    @Test
    void rejectsPhaseBudgetsThatExceedTheTotalBudget() {
        SmsExternalHttpTimeouts timeouts = new SmsExternalHttpTimeouts();
        ReflectionTestUtils.setField(timeouts, "readTimeoutMillis", 1751);

        assertThrows(IllegalStateException.class, timeouts::validate);
    }

    @Test
    void capsEveryCallByTheRemainingOperationBudget() {
        SmsExternalHttpTimeouts timeouts = new SmsExternalHttpTimeouts();
        MutableClock clock = new MutableClock();
        SmsExternalHttpClient.OperationBudget budget = new SmsExternalHttpClient.OperationBudget(timeouts, clock);

        assertEquals(2400, budget.remainingForNewCallMillis());
        SmsExternalHttpClient.CallTimeouts first = budget.nextCallTimeouts();
        assertEquals(250, first.connectionRequestTimeoutMillis());
        assertEquals(500, first.connectTimeoutMillis());
        assertEquals(1650, first.readTimeoutMillis());
        clock.advanceMillis(2399);
        assertThrows(SmsExternalHttpClient.SmsExternalBudgetExhaustedException.class,
                budget::remainingForNewCallMillis);
    }

    @Test
    void requestDeadlinePreventsStartingSmsDependencyWork() {
        SmsExternalHttpTimeouts timeouts = new SmsExternalHttpTimeouts();
        SmsExternalHttpClient.OperationBudget budget = new SmsExternalHttpClient.OperationBudget(timeouts, () -> 0L);

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(0))) {
            SafeRequestTimeoutException exception = assertThrows(SafeRequestTimeoutException.class,
                    budget::remainingForNewCallMillis);
            assertEquals(SafeRequestTimeoutException.Dependency.SMS, exception.dependency());
            assertEquals(SafeRequestTimeoutException.WorkState.NOT_STARTED, exception.workState());
        }
    }

    @Test
    void rejectsSmsNetworkCallsInsideTransactionsBeforeConnecting() {
        SmsExternalHttpClient client = new SmsExternalHttpClient(new SmsExternalHttpTimeouts());
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(SmsExternalHttpClient.SmsExternalCallRejectedException.class,
                () -> client.postForEntity(client.beginOperation(), "http://127.0.0.1:1",
                        HttpEntity.EMPTY, String.class));
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
