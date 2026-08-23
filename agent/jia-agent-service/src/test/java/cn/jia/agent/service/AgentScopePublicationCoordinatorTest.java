package cn.jia.agent.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentScopePublicationCoordinatorTest {
    private final AgentScopePublicationCoordinator coordinator =
            new AgentScopePublicationCoordinator();

    @Test
    void sameScopeQueryAndPublishCannotBeOvertaken() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstQueried = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        try {
            Future<?> first = executor.submit(() -> coordinator.execute(
                    "client-a", "tenant-a", () -> {
                        order.add("A-query");
                        firstQueried.countDown();
                        await(releaseFirst);
                        order.add("A-publish");
                    }));
            assertTrue(firstQueried.await(1, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> {
                secondAttempted.countDown();
                coordinator.execute("client-a", "tenant-a", () -> {
                    secondEntered.countDown();
                    order.add("B-query");
                    order.add("B-publish");
                });
            });

            assertTrue(secondAttempted.await(1, TimeUnit.SECONDS));
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
            assertEquals(List.of("A-query", "A-publish", "B-query", "B-publish"), order);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void differentScopesDoNotBlockEachOther() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> coordinator.execute(
                    "client-a", "tenant-a", () -> {
                        firstEntered.countDown();
                        await(releaseFirst);
                    }));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> coordinator.execute(
                    "client-b", "tenant-a", secondEntered::countDown));

            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            second.get(1, TimeUnit.SECONDS);
            releaseFirst.countDown();
            first.get(1, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void exceptionAlwaysReleasesScopeLock() {
        try {
            coordinator.execute("client-a", "tenant-a", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException ignored) {
            // Expected; the next publication proves finally released the lock.
        }

        List<String> published = new CopyOnWriteArrayList<>();
        coordinator.execute("client-a", "tenant-a", () -> {
            published.add("next");
        });
        assertEquals(List.of("next"), published);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", interrupted);
        }
    }
}
