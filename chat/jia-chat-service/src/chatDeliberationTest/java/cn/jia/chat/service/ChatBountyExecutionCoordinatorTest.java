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

    @Test void refusesMissingOrForeignStepWithoutOpeningProviderOrGrant() {
        assertEquals("NOT_PENDING",coordinator.coordinate(candidate));
        when(steps.findStep("0","owner","client","req",1,1))
                .thenReturn(new ChatInteractionStepStore.Step("step","0","someone-else","client",
                        "req",1,1,"42",1,"task",2,"grant",1,"agent","EXECUTE","ADMITTED",
                        0,"a".repeat(64),1,1));
        assertEquals("NOT_PENDING",coordinator.coordinate(candidate));
        verifyNoInteractions(grants,executions);
    }

    @Test void orphanRequestCannotStartOrClaimAnExecution() {
        when(steps.findStep("0","owner","client","req",1,1))
                .thenReturn(new ChatInteractionStepStore.Step("step","0","owner","client",
                        "req",1,1,"42",1,"task",2,"grant",1,"agent","EXECUTE","ADMITTED",
                        0,"a".repeat(64),1,1));
        assertThrows(IllegalStateException.class,()->coordinator.coordinate(candidate));
        verifyNoInteractions(grants,executions,conversations,requirements);
    }

    @Test void candidateLimitIsBoundedBeforeAnyDatabaseRead() {
        assertThrows(IllegalArgumentException.class,()->coordinator.pending(0));
        assertThrows(IllegalArgumentException.class,()->coordinator.pending(101));
        verifyNoInteractions(jdbc);
    }
}
