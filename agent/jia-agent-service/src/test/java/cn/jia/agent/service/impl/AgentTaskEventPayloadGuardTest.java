package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.service.AgentTaskEventAfterCommitPublisher;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class AgentTaskEventPayloadGuardTest extends BaseMockTest {
    @Mock
    AgentTaskEventDao eventDao;
    @Mock
    PlatformTransactionManager transactionManager;
    @Mock
    AgentTaskEventAfterCommitPublisher afterCommitPublisher;

    AgentTaskEventWriterImpl writer;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        writer = new AgentTaskEventWriterImpl(eventDao, transactionManager, afterCommitPublisher);
    }

    @Test
    void writerNormalizesAllowedPayloadBeforePersistence() {
        when(eventDao.lockAndAllocateVersion("tenant-a", "client-a", "task-1"))
                .thenReturn(0L);
        when(eventDao.insertEvent(any())).thenReturn(1);
        when(eventDao.commitEventVersion(
                any(), any(), any(), anyLong(), anyLong(), anyLong())).thenReturn(1);

        writer.append(command(" { \"toStatus\" : \"done\", \"fromStatus\" : \"working\" } "));

        ArgumentCaptor<AgentTaskEventEntity> event =
                ArgumentCaptor.forClass(AgentTaskEventEntity.class);
        verify(eventDao).insertEvent(event.capture());
        assertEquals("{\"fromStatus\":\"working\",\"toStatus\":\"done\"}",
                event.getValue().getEventJson());
    }

    @Test
    void writerRejectsSensitiveOrFullContentBeforeLockOrVersionAllocation() {
        for (String unsafe : new String[] {
                "{\"leaseToken\":\"raw-token\"}",
                "{\"authorization\":\"Bearer secret\"}",
                "{\"messageText\":\"full message\"}",
                "{\"metadata\":{\"cookie\":\"secret\"}}"
        }) {
            assertThrows(IllegalArgumentException.class, () -> writer.append(command(unsafe)));
        }
        verifyNoInteractions(eventDao);
    }

    @Test
    void digestPayloadDoesNotPersistOriginalBody() {
        String body = "do-not-store-this-body";
        String payload = TaskEventPayload.builder()
                .putContentDigest(TaskEventPayload.ContentDigest.fromUtf8(body))
                .toJson();
        assertFalse(payload.contains(body));
    }

    private AgentTaskEventWriteCommand command(String eventJson) {
        return new AgentTaskEventWriteCommand()
                .setTenantId("tenant-a")
                .setClientId("client-a")
                .setTaskId("task-1")
                .setEventId("evt-1")
                .setEventType(TaskEventType.TASK_COMPLETED)
                .setActorType(TaskEventType.ActorType.AGENT)
                .setActorId("agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .setAggregateType(TaskEventType.Aggregate.TASK)
                .setAggregateId("task-1")
                .setEventJson(eventJson)
                .setOccurredAt(10_000L);
    }
}
