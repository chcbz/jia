package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.service.AgentTaskEventBroker.TaskScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

        assertEquals(List.of(1L, 2L), received);
        assertEquals(List.of(1L, 2L), fixture.eventVersions());
        assertEquals(2L, fixture.currentEventVersion());
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
        assertEquals(List.of(1L), received);
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
}
