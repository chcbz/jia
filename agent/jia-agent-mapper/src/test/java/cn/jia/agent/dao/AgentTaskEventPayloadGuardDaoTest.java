package cn.jia.agent.dao;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentTaskEventPayloadGuardDaoTest extends BaseMockTest {
    @Mock
    AgentTaskEventMapper mapper;

    AgentTaskEventDaoImpl dao;

    @BeforeEach
    void setUp() throws Exception {
        dao = new AgentTaskEventDaoImpl();
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(dao, mapper);
    }

    @Test
    void directDaoInsertNormalizesAllowedPayload() {
        when(mapper.insertEvent(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        AgentTaskEventEntity event = event(
                " { \"toStatus\" : \"done\", \"fromStatus\" : \"working\" } ");

        assertEquals(1, dao.insertEvent(event));

        ArgumentCaptor<AgentTaskEventEntity> persisted =
                ArgumentCaptor.forClass(AgentTaskEventEntity.class);
        verify(mapper).insertEvent(persisted.capture());
        assertEquals("{\"fromStatus\":\"working\",\"toStatus\":\"done\"}",
                persisted.getValue().getEventJson());
    }

    @Test
    void directDaoInsertRejectsSensitivePayloadBeforeMapper() {
        AgentTaskEventEntity event = event("{\"leaseToken\":\"raw-token\"}");

        assertThrows(IllegalArgumentException.class, () -> dao.insertEvent(event));
        verifyNoInteractions(mapper);
    }

    @Test
    void directDaoInsertCannotBypassTypeOrAggregateAllowlists() {
        AgentTaskEventEntity unknownType = event("{}");
        unknownType.setEventType("UNFROZEN_EVENT");
        assertThrows(IllegalArgumentException.class, () -> dao.insertEvent(unknownType));

        AgentTaskEventEntity unknownAggregate = event("{}");
        unknownAggregate.setAggregateType("raw_content");
        assertThrows(IllegalArgumentException.class, () -> dao.insertEvent(unknownAggregate));
        verifyNoInteractions(mapper);
    }

    private AgentTaskEventEntity event(String eventJson) {
        AgentTaskEventEntity event = new AgentTaskEventEntity();
        event.setTenantId("tenant-a");
        event.setClientId("client-a");
        event.setTaskId("task-1");
        event.setEventVersion(1L);
        event.setEventId("evt-1");
        event.setEventType(TaskEventType.TASK_COMPLETED);
        event.setActorType(TaskEventType.ActorType.AGENT);
        event.setActorId("agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        event.setAggregateType(TaskEventType.Aggregate.TASK);
        event.setAggregateId("task-1");
        event.setEventJson(eventJson);
        event.setOccurredAt(10_000L);
        return event;
    }
}
