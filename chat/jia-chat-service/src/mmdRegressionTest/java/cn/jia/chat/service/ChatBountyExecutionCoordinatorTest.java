package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Retired queues must never re-admit historical work or consume its paid authorization. */
class ChatBountyExecutionCoordinatorTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ChatInteractionStepStore steps=mock(ChatInteractionStepStore.class);
    private final ChatDeliberationDao requests=mock(ChatDeliberationDao.class);
    private final ChatConversationDao conversations=mock(ChatConversationDao.class);
    private final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
    private final AgentTaskRequirementSnapshotService requirements=mock(AgentTaskRequirementSnapshotService.class);
    private final PersonalWorkspaceExecutionService executions=mock(PersonalWorkspaceExecutionService.class);
    private final ChatBountyExecutionCoordinator coordinator=new ChatBountyExecutionCoordinator(jdbc,steps,
            requests,conversations,grants,requirements,executions,new ObjectMapper());
    private final ChatBountyExecutionCoordinator.Pending candidate=
            new ChatBountyExecutionCoordinator.Pending("0","owner","client","req","step");

    @Test void retiredQueueIsEmptyWithoutReadingOrMutatingHistoricalRows() {
        assertEquals(java.util.List.of(),coordinator.pending(1));
        assertEquals(java.util.List.of(),coordinator.pending(100));
        verifyNoInteractions(jdbc,steps,requests,conversations,grants,requirements,executions);
    }

    @Test void staleCandidateAndRepeatedDeliveryNeverReadmitOrRewriteHistory() {
        assertEquals("RETIRED",coordinator.coordinate(candidate));
        assertEquals("RETIRED",coordinator.coordinate(candidate));
        assertEquals("RETIRED",coordinator.coordinate(new ChatBountyExecutionCoordinator.Pending(
                "foreign-tenant","foreign-owner","foreign-client","req","step")));
        verifyNoInteractions(jdbc,steps,requests,conversations,grants,requirements,executions);
    }

    @Test void invalidInvocationCannotTouchExecutionOrPaidFacts() {
        assertThrows(IllegalArgumentException.class,()->coordinator.pending(0));
        assertThrows(IllegalArgumentException.class,()->coordinator.pending(101));
        assertThrows(IllegalArgumentException.class,()->coordinator.coordinate(null));
        verifyNoInteractions(jdbc,steps,requests,conversations,grants,requirements,executions);
    }
}
