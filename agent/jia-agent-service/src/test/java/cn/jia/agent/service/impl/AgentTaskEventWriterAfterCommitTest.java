package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.entity.AgentTaskEventWriteResult;
import cn.jia.agent.service.AgentTaskEventBroker.TaskScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskEventWriterAfterCommitTest {
    private AgentTaskEventTestFixture fixture;
    private List<Long> received;
    private reactor.core.Disposable subscription;

    @BeforeEach
    void setUp() throws Exception {
        fixture = AgentTaskEventTestFixture.h2("c02_writer");
        fixture.seedTask();
        received = new CopyOnWriteArrayList<>();
        subscription = fixture.eventBroker.stream(new TaskScope(
                        AgentTaskEventTestFixture.TENANT,
                        AgentTaskEventTestFixture.CLIENT,
                        AgentTaskEventTestFixture.TASK))
                .subscribe(wakeup -> received.add(wakeup.eventVersion()));
    }

    @AfterEach
    void tearDown() {
        if (subscription != null) {
            subscription.dispose();
        }
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void writerPublishesOnlyAfterCommitAndPreservesMultipleAppendOrder() {
        TransactionTemplate outer = new TransactionTemplate(fixture.transactionManager);
        outer.executeWithoutResult(status -> {
            fixture.writer.append(fixture.command("evt-c02-1")
                    .setEventType(TaskEventType.TASK_STARTED));
            fixture.writer.append(fixture.command("evt-c02-2")
                    .setEventType(TaskEventType.PROGRESS_REPORTED));
            assertTrue(received.isEmpty());
        });

        awaitReceived(List.of(1L, 2L));
        assertEquals(List.of(1L, 2L), fixture.eventVersions());
        assertEquals(2L, fixture.currentEventVersion());
    }

    @Test
    void blockingSubscriberCannotDelayAfterCommitBusinessReturn() throws Exception {
        subscription.dispose();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch callbackExited = new CountDownLatch(1);
        subscription = fixture.eventBroker.stream(new TaskScope(
                        AgentTaskEventTestFixture.TENANT,
                        AgentTaskEventTestFixture.CLIENT,
                        AgentTaskEventTestFixture.TASK))
                .subscribe(wakeup -> {
                    callbackEntered.countDown();
                    try {
                        await(releaseCallback);
                    } finally {
                        callbackExited.countDown();
                    }
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AgentTaskEventWriteResult> append = executor.submit(
                    () -> fixture.writer.append(fixture.command("evt-c02-blocking")));
            await(callbackEntered);

            AgentTaskEventWriteResult result = append.get(1, TimeUnit.SECONDS);
            assertEquals(1L, result.getEventVersion());
            assertEquals(List.of(1L), fixture.eventVersions());
            assertEquals(1L, fixture.currentEventVersion());
        } finally {
            releaseCallback.countDown();
            await(callbackExited);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void outerRollbackRemovesDurableWritesAndPublishesNothing() {
        TransactionTemplate outer = new TransactionTemplate(fixture.transactionManager);
        outer.executeWithoutResult(status -> {
            fixture.writer.append(fixture.command("evt-c02-rollback"));
            status.setRollbackOnly();
        });

        assertTrue(received.isEmpty());
        assertEquals(0, fixture.eventCount());
        assertEquals(0L, fixture.currentEventVersion());
    }

    @Test
    void appendFailureQueuesNoPhantomWakeupAndLeavesNoVersionGap() {
        fixture.writer.append(fixture.command("evt-c02-existing"));
        awaitReceived(List.of(1L));
        received.clear();

        assertThrows(RuntimeException.class,
                () -> fixture.writer.append(fixture.command("evt-c02-existing")));

        assertTrue(received.isEmpty());
        assertEquals(List.of(1L), fixture.eventVersions());
        assertEquals(1L, fixture.currentEventVersion());
    }

    @Test
    void malformedPaddedScopeFailsClosedAndRollsBackBeforePublication() {
        var command = fixture.command("evt-c02-padding")
                .setTaskId("\u00a0" + AgentTaskEventTestFixture.TASK);

        assertThrows(IllegalArgumentException.class, () -> fixture.writer.append(command));

        assertTrue(received.isEmpty());
        assertEquals(0, fixture.eventCount());
        assertEquals(0L, fixture.currentEventVersion());
    }

    private void awaitReceived(List<Long> expected) {
        awaitCondition(() -> received.equals(expected));
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
}
