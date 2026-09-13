package cn.jia.core.deadline;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestDeadlineTest {
    @Test
    void usesMonotonicRemainingTimeAndRoundsPositiveRemainderUp() {
        AtomicLong clock = new AtomicLong(100);
        RequestDeadline deadline = RequestDeadline.start(3000, clock::get);

        assertEquals(3000, deadline.remainingMillis());
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(500) + 250_000);

        assertEquals(2500, deadline.remainingMillis());
        assertFalse(deadline.isExpired());
    }

    @Test
    void clampsDependencyBudgetAndReservesSafetyMargin() {
        AtomicLong clock = new AtomicLong();
        RequestDeadline deadline = RequestDeadline.start(800, clock::get);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(300));

        assertEquals(400, deadline.boundedBudgetMillis(1000, 100));
        assertEquals(200, deadline.boundedBudgetMillis(200, 100));

        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(401));
        assertEquals(0, deadline.boundedBudgetMillis(1000, 100));
    }

    @Test
    void zeroBudgetIsExpiredAndBackwardClockCannotCreateExtraBudget() {
        AtomicLong zeroClock = new AtomicLong(10);
        assertTrue(RequestDeadline.start(0, zeroClock::get).isExpired());

        AtomicLong clock = new AtomicLong(1000);
        RequestDeadline deadline = RequestDeadline.start(100, clock::get);
        clock.set(999);
        assertEquals(100, deadline.remainingMillis());
    }

    @Test
    void rejectsUnboundedOrInvalidBudgets() {
        assertThrows(IllegalArgumentException.class, () -> RequestDeadline.start(-1));
        assertThrows(IllegalArgumentException.class, () -> RequestDeadline.start(RequestDeadline.MAX_BUDGET_MILLIS + 1));
        RequestDeadline deadline = RequestDeadline.start(1);
        assertThrows(IllegalArgumentException.class, () -> deadline.boundedBudgetMillis(0, 0));
        assertThrows(IllegalArgumentException.class, () -> deadline.boundedBudgetMillis(1, -1));
    }
}
