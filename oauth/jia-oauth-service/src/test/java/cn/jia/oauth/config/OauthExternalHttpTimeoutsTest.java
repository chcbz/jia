package cn.jia.oauth.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OauthExternalHttpTimeoutsTest {
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
    void doesNotStartAnotherProviderCallAfterTheRemainingBudgetIsExhausted() {
        OauthExternalHttpTimeouts timeouts = new OauthExternalHttpTimeouts();
        MutableClock clock = new MutableClock();
        OauthExternalHttpClient.CallbackBudget budget = new OauthExternalHttpClient.CallbackBudget(timeouts, clock);

        assertEquals(2400, budget.remainingForNewCallMillis());
        clock.advanceMillis(2399);
        assertThrows(OauthExternalHttpClient.OauthExternalBudgetExhaustedException.class,
                budget::remainingForNewCallMillis);
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
