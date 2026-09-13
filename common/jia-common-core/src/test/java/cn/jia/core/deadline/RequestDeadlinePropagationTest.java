package cn.jia.core.deadline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestDeadlinePropagationTest {
    @AfterEach
    void verifyNoLeak() {
        assertFalse(RequestDeadlineContext.current().isPresent());
    }

    @Test
    void omitsHeaderWithoutContextAndPreservesLegacyTimeout() {
        Map<String, String> headers = new LinkedHashMap<>();

        assertFalse(RequestDeadlinePropagation.writeCurrentHeader(headers::put));
        assertTrue(headers.isEmpty());
        assertEquals(60_000, RequestDeadlinePropagation.requireBudgetBeforeNewWork(
                60_000, 100, SafeRequestTimeoutException.Dependency.HTTP));
        assertThrows(IllegalArgumentException.class, () -> RequestDeadlinePropagation.requireBudgetBeforeNewWork(
                0, 100, SafeRequestTimeoutException.Dependency.HTTP));
        assertThrows(NullPointerException.class, () -> RequestDeadlinePropagation.requireBudgetBeforeNewWork(
                1000, 100, null));
    }

    @Test
    void doesNotPropagateOrShortenTimeoutAsTheObservationBudgetShrinks() {
        AtomicLong clock = new AtomicLong();
        RequestDeadline deadline = RequestDeadline.start(700, clock::get);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
        Map<String, String> headers = new LinkedHashMap<>();

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(deadline)) {
            assertFalse(RequestDeadlinePropagation.writeCurrentHeader(headers::put));
            assertEquals(60_000, RequestDeadlinePropagation.requireBudgetBeforeNewWork(
                    60_000, 100, SafeRequestTimeoutException.Dependency.HTTP));
        }

        assertTrue(headers.isEmpty());
    }

    @Test
    void expiredObservationDoesNotWriteHeadersOrRejectFollowingWork() {
        AtomicLong clock = new AtomicLong();
        RequestDeadline deadline = RequestDeadline.start(700, clock::get);
        AtomicBoolean writerCalled = new AtomicBoolean();
        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(deadline)) {
            for (long elapsed : new long[]{200, 500, 5000}) {
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(elapsed));
                assertFalse(RequestDeadlinePropagation.writeCurrentHeaderBeforeNewWork(
                        (name, value) -> writerCalled.set(true), SafeRequestTimeoutException.Dependency.HTTP));
                assertEquals(60_000, RequestDeadlinePropagation.requireBudgetBeforeNewWork(
                        60_000, 100, SafeRequestTimeoutException.Dependency.HTTP));
                assertTrue(RequestDeadlinePropagation.currentHeaderValue().isEmpty());
            }
            assertTrue(deadline.isExpired(), "overrun remains observable");
        }
        assertFalse(writerCalled.get());
    }

    @Test
    void strictHeaderPropagationPreservesLegacyCallersAndRejectsInvalidArguments() {
        AtomicBoolean writerCalled = new AtomicBoolean();

        assertFalse(RequestDeadlinePropagation.writeCurrentHeaderBeforeNewWork(
                (name, value) -> writerCalled.set(true), SafeRequestTimeoutException.Dependency.HTTP));
        assertFalse(writerCalled.get());
        assertThrows(IllegalArgumentException.class, () -> RequestDeadlinePropagation.writeCurrentHeaderBeforeNewWork(
                null, SafeRequestTimeoutException.Dependency.HTTP));
        assertThrows(NullPointerException.class, () -> RequestDeadlinePropagation.writeCurrentHeaderBeforeNewWork(
                (name, value) -> { }, null));
    }

    @Test
    void exhaustedBudgetPreservesTransportPolicyForEveryDependency() {
        RequestDeadline expired = RequestDeadline.start(0);
        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(expired)) {
            for (SafeRequestTimeoutException.Dependency dependency : SafeRequestTimeoutException.Dependency.values()) {
                assertEquals(60_000, RequestDeadlinePropagation.requireBudgetBeforeNewWork(60_000, 100, dependency));
                assertTrue(expired.isExpired());
            }
        }
    }

    @Test
    void dependencyFactoriesExposeOnlyFiniteSafeFailures() {
        SafeRequestTimeoutException timedOut = SafeRequestTimeoutException.dependencyTimedOutSafely(
                SafeRequestTimeoutException.Dependency.REDIS);
        SafeRequestTimeoutException unavailable = SafeRequestTimeoutException.dependencyUnavailableBeforeWork(
                SafeRequestTimeoutException.Dependency.LDAP);

        assertEquals(504, timedOut.failure().httpStatus());
        assertEquals(SafeRequestTimeoutException.WorkState.SAFE_TO_CANCEL, timedOut.workState());
        assertEquals(503, unavailable.failure().httpStatus());
        assertEquals(SafeRequestTimeoutException.WorkState.NOT_STARTED, unavailable.workState());
    }
}
