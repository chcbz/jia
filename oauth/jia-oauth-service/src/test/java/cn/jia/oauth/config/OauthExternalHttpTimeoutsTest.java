package cn.jia.oauth.config;

import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.RequestDeadlineContext;
import cn.jia.core.deadline.SafeRequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OauthExternalHttpTimeoutsTest {
    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void defaultsFitTheCallbackBudget() {
        OauthExternalHttpTimeouts timeouts = new OauthExternalHttpTimeouts();

        assertDoesNotThrow(timeouts::validate);
        assertEquals(500, timeouts.getConnectTimeoutMillis());
        assertEquals(1750, timeouts.getReadTimeoutMillis());
        assertEquals(2500, timeouts.getTotalTimeoutMillis());
        assertEquals(100, timeouts.getSafetyMarginMillis());
    }

    @Test
    void rejectsPhaseBudgetsThatExceedTheTotalBudget() {
        OauthExternalHttpTimeouts timeouts = new OauthExternalHttpTimeouts();
        ReflectionTestUtils.setField(timeouts, "readTimeoutMillis", 2001);

        assertThrows(IllegalStateException.class, timeouts::validate);
    }

    @Test
    void doesNotStartAnotherProviderCallAfterTheLocalBudgetIsExhausted() {
        OauthExternalHttpTimeouts timeouts = new OauthExternalHttpTimeouts();
        MutableClock clock = new MutableClock();
        OauthExternalHttpClient.CallbackBudget budget = new OauthExternalHttpClient.CallbackBudget(timeouts, clock);

        assertEquals(2400, budget.remainingForNewCallMillis());
        clock.advanceMillis(2399);
        assertThrows(OauthExternalHttpClient.OauthExternalBudgetExhaustedException.class,
                budget::remainingForNewCallMillis);
    }

    @Test
    void requestDeadlineCapsEveryProviderCall() {
        OauthExternalHttpTimeouts timeouts = new OauthExternalHttpTimeouts();
        OauthExternalHttpClient.CallbackBudget budget = new OauthExternalHttpClient.CallbackBudget(timeouts, () -> 0L);

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(0))) {
            SafeRequestTimeoutException exception = assertThrows(SafeRequestTimeoutException.class,
                    budget::remainingForNewCallMillis);
            assertEquals(SafeRequestTimeoutException.Dependency.HTTP, exception.dependency());
            assertEquals(SafeRequestTimeoutException.WorkState.NOT_STARTED, exception.workState());
        }
    }

    @Test
    void rejectsProviderNetworkCallsInsideTransactionsBeforeConnecting() {
        OauthExternalHttpClient client = new OauthExternalHttpClient(new OauthExternalHttpTimeouts());
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(OauthExternalHttpClient.OauthExternalCallRejectedException.class,
                () -> client.exchange(client.beginCallback(), "http://127.0.0.1:1", HttpMethod.GET,
                        HttpEntity.EMPTY));
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
