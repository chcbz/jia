package cn.jia.agent.service;

import cn.jia.agent.service.AgentTaskEventBroker.TaskEventWakeup;
import cn.jia.agent.service.AgentTaskEventBroker.TaskScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskEventBrokerTest {
    private final AgentTaskEventBroker broker = new AgentTaskEventBroker();
    private final TaskScope scope = new TaskScope("tenant-a", "client-a", "task-a");

    @AfterEach
    void tearDown() {
        broker.close();
    }

    @Test
    void isolatesByteExactCaseAndUnicodeScopesWithoutNormalizing() {
        TaskScope tenantCase = new TaskScope("Tenant-a", "client-a", "task-a");
        TaskScope clientCase = new TaskScope("tenant-a", "Client-a", "task-a");
        TaskScope taskCase = new TaskScope("tenant-a", "client-a", "Task-a");
        TaskScope composed = new TaskScope("tenant-a", "client-a", "caf\u00e9");
        TaskScope decomposed = new TaskScope("tenant-a", "client-a", "cafe\u0301");
        List<TaskScope> scopes = List.of(
                scope, tenantCase, clientCase, taskCase, composed, decomposed);
        List<CopyOnWriteArrayList<Long>> received = scopes.stream()
                .map(ignored -> new CopyOnWriteArrayList<Long>())
                .toList();
        var subscriptions = new CopyOnWriteArrayList<reactor.core.Disposable>();
        for (int i = 0; i < scopes.size(); i++) {
            int index = i;
            subscriptions.add(broker.stream(scopes.get(i))
                    .subscribe(wakeup -> received.get(index).add(wakeup.eventVersion())));
        }

        for (int i = 0; i < scopes.size(); i++) {
            broker.publish(scopes.get(i), i + 1L);
        }

        awaitCondition(() -> received.stream().allMatch(values -> values.size() == 1));
        for (int i = 0; i < scopes.size(); i++) {
            assertEquals(List.of(i + 1L), received.get(i), scopes.get(i).toString());
        }
        subscriptions.forEach(reactor.core.Disposable::dispose);
        assertEquals(0, broker.activeScopeCount());
    }

    @Test
    void rejectsMalformedIdentitiesAndVersionsWithoutRewritingBytes() {
        for (String invalid : new String[] {null, "", " ", "\u00a0", " padded", "padded ",
                "\u2003padded", "padded\u2003", "line\nbreak", "nul\u0000byte"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new TaskScope(invalid, "client", "task"));
            assertThrows(IllegalArgumentException.class,
                    () -> new TaskScope("tenant", invalid, "task"));
            assertThrows(IllegalArgumentException.class,
                    () -> new TaskScope("tenant", "client", invalid));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new TaskScope("t".repeat(51), "client", "task"));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskScope("tenant", "c".repeat(51), "task"));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskScope("tenant", "client", "x".repeat(101)));
        assertThrows(NullPointerException.class, () -> broker.stream(null));
        assertThrows(IllegalArgumentException.class, () -> broker.publish(scope, 0));
        assertThrows(IllegalArgumentException.class, () -> broker.publish(scope, -1));
        assertThrows(IllegalArgumentException.class, () -> new AgentTaskEventBroker(0));

        TaskScope exact = new TaskScope("tenant", "client", "a b");
        assertEquals("a b", exact.taskId());
    }

    @Test
    void wakeupRecordContainsOnlyImmutableScopeAndVersion() {
        assertTrue(TaskScope.class.isRecord());
        assertTrue(TaskEventWakeup.class.isRecord());
        assertEquals(List.of("scope", "eventVersion"), Arrays.stream(
                        TaskEventWakeup.class.getRecordComponents())
                .map(component -> component.getName())
                .toList());
    }

    @Test
    void blindPublishDropsWithoutAllocatingAChannel() {
        broker.publish(scope, 1L);
        assertEquals(0, broker.activeScopeCount());
        assertEquals(0, broker.subscriberCount(scope));
        assertEquals(1L, broker.droppedWakeupCount());
    }

    @Test
    void directBestEffortLetsSlowSubscriberMissWithoutBlockingFastSubscriber() {
        List<Long> fastVersions = new CopyOnWriteArrayList<>();
        ControlledSubscriber slow = new ControlledSubscriber();
        var fast = broker.stream(scope).subscribe(wakeup -> fastVersions.add(wakeup.eventVersion()));
        broker.stream(scope).subscribe(slow);

        broker.publish(scope, 1L);
        awaitCondition(() -> fastVersions.equals(List.of(1L)));
        slow.requestOne();
        broker.publish(scope, 2L);

        awaitCondition(() -> fastVersions.equals(List.of(1L, 2L))
                && slow.versions.equals(List.of(2L)));
        fast.dispose();
        slow.cancelNow();
        assertEquals(0, broker.activeScopeCount());
    }

    @Test
    void boundedDispatcherDropsOverflowWithoutRunningCallbacksOnPublisherThread() {
        AgentTaskEventBroker bounded = new AgentTaskEventBroker(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        List<Long> received = new CopyOnWriteArrayList<>();
        AtomicReference<String> callbackThread = new AtomicReference<>();
        String publisherThread = Thread.currentThread().getName();
        var subscription = bounded.stream(scope).subscribe(wakeup -> {
            callbackThread.set(Thread.currentThread().getName());
            received.add(wakeup.eventVersion());
            if (wakeup.eventVersion() == 1L) {
                callbackEntered.countDown();
                await(releaseCallback);
            }
        });
        try {
            bounded.publish(scope, 1L);
            await(callbackEntered);

            bounded.publish(scope, 2L);
            bounded.publish(scope, 3L);
            assertEquals(List.of(1L), received);
            assertEquals(1L, bounded.droppedWakeupCount());
            assertTrue(!publisherThread.equals(callbackThread.get()));

            releaseCallback.countDown();
            awaitCondition(() -> received.equals(List.of(1L, 2L)));
        } finally {
            releaseCallback.countDown();
            subscription.dispose();
            bounded.close();
        }
    }

    @Test
    void closeCompletesActiveChannelsAndRejectsPostCloseAllocationAndPublication() {
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var subscription = broker.stream(scope)
                .subscribe(ignored -> { }, failure::set, () -> completed.set(true));
        assertEquals(1, broker.activeScopeCount());

        broker.close();

        assertTrue(broker.isClosed());
        assertTrue(completed.get());
        assertNull(failure.get());
        assertEquals(0, broker.activeScopeCount());
        assertEquals(0, broker.subscriberCount(scope));
        StepVerifier.create(broker.stream(scope))
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("closed"))
                .verify(Duration.ofSeconds(1));
        assertEquals(0, broker.activeScopeCount());
        assertThrows(IllegalStateException.class, () -> broker.publish(scope, 1L));
        assertEquals(1L, broker.droppedWakeupCount());
        subscription.dispose();
    }

    @Test
    void closeInterruptsBlockedWorkerAfterBoundedWaitWithoutCallbackNoise() {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch callbackExited = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        var subscription = broker.stream(scope).subscribe(wakeup -> {
            callbackEntered.countDown();
            try {
                neverReleased.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                callbackExited.countDown();
            }
        });
        broker.publish(scope, 1L);
        await(callbackEntered);

        assertTimeoutPreemptively(Duration.ofSeconds(2), broker::close);

        await(callbackExited);
        assertTrue(broker.isClosed());
        assertEquals(0, broker.activeScopeCount());
        subscription.dispose();
    }

    @Test
    void blockingCallbackCannotDelayFinalSubscriberCleanup() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        var subscription = broker.stream(scope).subscribe(wakeup -> {
            callbackEntered.countDown();
            await(releaseCallback);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            broker.publish(scope, 1L);
            await(callbackEntered);

            Future<?> dispose = executor.submit(subscription::dispose);
            dispose.get(1, TimeUnit.SECONDS);
            assertEquals(0, broker.activeScopeCount());
            assertEquals(0, broker.subscriberCount(scope));
        } finally {
            releaseCallback.countDown();
            subscription.dispose();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancelCompletionErrorAndFinalSubscriberEachCleanUpExactlyOnce() {
        var first = broker.stream(scope).subscribe();
        var second = broker.stream(scope).subscribe();
        assertEquals(2, broker.subscriberCount(scope));
        first.dispose();
        first.dispose();
        assertEquals(1, broker.subscriberCount(scope));
        second.dispose();
        assertEquals(0, broker.activeScopeCount());

        StepVerifier.create(broker.stream(scope).take(1))
                .then(() -> broker.publish(scope, 1L))
                .assertNext(wakeup -> assertEquals(1L, wakeup.eventVersion()))
                .expectComplete()
                .verify(Duration.ofSeconds(2));
        assertEquals(0, broker.activeScopeCount());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        broker.stream(scope)
                .map(wakeup -> {
                    throw new IllegalStateException("downstream failure");
                })
                .subscribe(ignored -> { }, failure::set);
        broker.publish(scope, 2L);
        awaitCondition(() -> failure.get() != null);
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals(0, broker.activeScopeCount());
    }

    @Test
    void publishDisposeRaceNeverLeaksOrLetsStaleCleanupRemoveReplacement() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 200; iteration++) {
                var subscription = broker.stream(scope).subscribe();
                CountDownLatch start = new CountDownLatch(1);
                long version = iteration + 1L;
                Future<?> publish = executor.submit(() -> {
                    await(start);
                    broker.publish(scope, version);
                });
                Future<?> dispose = executor.submit(() -> {
                    await(start);
                    subscription.dispose();
                });
                start.countDown();
                publish.get(2, TimeUnit.SECONDS);
                dispose.get(2, TimeUnit.SECONDS);
                assertEquals(0, broker.activeScopeCount());

                List<Long> replacementEvents = new CopyOnWriteArrayList<>();
                var replacement = broker.stream(scope)
                        .subscribe(wakeup -> replacementEvents.add(wakeup.eventVersion()));
                broker.publish(scope, version + 10_000L);
                awaitCondition(() -> replacementEvents.equals(List.of(version + 10_000L)));
                replacement.dispose();
                assertEquals(0, broker.activeScopeCount());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertTrue(condition.getAsBoolean(), "condition was not satisfied before timeout");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class ControlledSubscriber extends BaseSubscriber<TaskEventWakeup> {
        private final List<Long> versions = new CopyOnWriteArrayList<>();

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            // Deliberately start with zero demand.
        }

        @Override
        protected void hookOnNext(TaskEventWakeup value) {
            versions.add(value.eventVersion());
        }

        private void requestOne() {
            request(1);
        }

        private void cancelNow() {
            cancel();
        }
    }
}
