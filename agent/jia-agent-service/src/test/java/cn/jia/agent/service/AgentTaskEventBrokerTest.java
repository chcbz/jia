package cn.jia.agent.service;

import cn.jia.agent.service.AgentTaskEventBroker.TaskEventWakeup;
import cn.jia.agent.service.AgentTaskEventBroker.TaskScope;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskEventBrokerTest {
    private final AgentTaskEventBroker broker = new AgentTaskEventBroker();
    private final TaskScope scope = new TaskScope("tenant-a", "client-a", "task-a");

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
    }

    @Test
    void directBestEffortLetsSlowSubscriberMissWithoutBlockingFastSubscriber() {
        List<Long> fastVersions = new CopyOnWriteArrayList<>();
        ControlledSubscriber slow = new ControlledSubscriber();
        var fast = broker.stream(scope).subscribe(wakeup -> fastVersions.add(wakeup.eventVersion()));
        broker.stream(scope).subscribe(slow);

        broker.publish(scope, 1L);
        slow.requestOne();
        broker.publish(scope, 2L);

        assertEquals(List.of(1L, 2L), fastVersions);
        assertEquals(List.of(2L), slow.versions);
        fast.dispose();
        slow.cancelNow();
        assertEquals(0, broker.activeScopeCount());
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
                assertEquals(List.of(version + 10_000L), replacementEvents);
                replacement.dispose();
                assertEquals(0, broker.activeScopeCount());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
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
