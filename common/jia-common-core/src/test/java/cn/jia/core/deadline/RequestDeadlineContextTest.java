package cn.jia.core.deadline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestDeadlineContextTest {
    @AfterEach
    void verifyNoLeak() {
        assertFalse(RequestDeadlineContext.current().isPresent());
    }

    @Test
    void nestedScopesRestoreThePreviousDeadline() {
        RequestDeadline outer = RequestDeadline.start(3000);
        RequestDeadline inner = RequestDeadline.start(1000);

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(outer)) {
            assertSame(outer, RequestDeadlineContext.current().orElseThrow());
            try (RequestDeadlineContext.Scope nested = RequestDeadlineContext.open(inner)) {
                assertSame(inner, RequestDeadlineContext.current().orElseThrow());
            }
            assertSame(outer, RequestDeadlineContext.current().orElseThrow());
        }
    }

    @Test
    void capturedRunnableInstallsCapturedValueAndRestoresWorkerValue() {
        RequestDeadline captured = RequestDeadline.start(3000);
        RequestDeadline worker = RequestDeadline.start(1000);
        Runnable wrapped;
        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(captured)) {
            wrapped = RequestDeadlineContext.wrap(() ->
                    assertSame(captured, RequestDeadlineContext.current().orElseThrow()));
        }

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(worker)) {
            wrapped.run();
            assertSame(worker, RequestDeadlineContext.current().orElseThrow());
        }
    }

    @Test
    void emptySnapshotClearsStaleWorkerContextForTaskDuration() throws Exception {
        RequestDeadlineContext.Snapshot empty = RequestDeadlineContext.capture();
        RequestDeadline stale = RequestDeadline.start(3000);
        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(stale)) {
            assertFalse(empty.wrap(() -> RequestDeadlineContext.current().isPresent()).call());
            assertSame(stale, RequestDeadlineContext.current().orElseThrow());
        }
    }

    @Test
    void crossThreadCloseIsRejectedWithoutClearingOwnerContext() throws Exception {
        RequestDeadline deadline = RequestDeadline.start(3000);
        RequestDeadlineContext.Scope scope = RequestDeadlineContext.open(deadline);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                scope.close();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        other.start();
        other.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertSame(deadline, RequestDeadlineContext.current().orElseThrow());
        scope.close();
    }

    @Test
    void nullTasksAndScopesAreRejectedBeforeCaptureCanLeak() {
        assertThrows(NullPointerException.class, () -> RequestDeadlineContext.open(null));
        assertThrows(IllegalArgumentException.class, () -> RequestDeadlineContext.wrap((Runnable) null));
        assertThrows(IllegalArgumentException.class,
                () -> RequestDeadlineContext.wrap((java.util.concurrent.Callable<Object>) null));
    }
}
