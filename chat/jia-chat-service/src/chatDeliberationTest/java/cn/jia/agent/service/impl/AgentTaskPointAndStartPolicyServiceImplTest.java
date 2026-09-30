package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskPointAndStartPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTaskPointAndStartPolicyServiceImplTest {
    private final AgentTaskMetaDao tasks=mock(AgentTaskMetaDao.class);
    private final AgentIdentityService identities=mock(AgentIdentityService.class);
    private final AgentTaskPointAndStartPolicyServiceImpl service=new AgentTaskPointAndStartPolicyServiceImpl(tasks,identities);
    private final AgentTaskExecutionGrantService.Scope scope=new AgentTaskExecutionGrantService.Scope("0","client-a","owner-a");

    @Test
    void exactOpenOrPlanningUnassignedTaskAndCanonicalTargetAreEligible() {
        when(identities.requireCanonicalAgentIdInScope("0","client-a","owner-a","agent-a")).thenReturn("agent-a");
        when(tasks.findByTaskIdInOwnerScope("0","client-a","owner-a","task-a"))
                .thenReturn(task("open",null),task("planning",null));
        var open=service.read(scope,"task-a","agent-a");
        assertTrue(open.eligible());assertNull(open.blockingReason());assertEquals("open",open.taskState());
        var planning=service.read(scope,"task-a","agent-a");
        assertTrue(planning.eligible());assertNull(planning.blockingReason());assertEquals("planning",planning.taskState());
    }

    @Test
    void assignedAndTerminalTasksAreStructuredIneligibleNotMissing() {
        when(identities.requireCanonicalAgentIdInScope(anyString(),anyString(),anyString(),anyString())).thenReturn("agent-a");
        when(tasks.findByTaskIdInOwnerScope(anyString(),anyString(),anyString(),anyString()))
                .thenReturn(task("open","agent-b"),task("completed",null));
        assertEquals("TASK_ALREADY_ASSIGNED",service.read(scope,"task-a","agent-a").blockingReason());
        assertEquals("TASK_NOT_OPEN",service.read(scope,"task-a","agent-a").blockingReason());
    }

    @Test
    void unknownOrCrossOwnerCanonicalTargetIsNonleakingNotFound() {
        when(identities.requireCanonicalAgentIdInScope("0","client-a","owner-a","agent-a"))
                .thenThrow(new AgentServiceImpl.AgentBizException("AGENT_FORBIDDEN","wrong scope"));
        assertEquals(AgentTaskPointAndStartPolicyService.Failure.Reason.NOT_FOUND,
                assertThrows(AgentTaskPointAndStartPolicyService.Failure.class,
                        ()->service.read(scope,"task-a","agent-a")).reason());
        verifyNoInteractions(tasks);
    }

    @Test
    void missingScopeIs404ButUnreadableStorageIs503Category() {
        when(identities.requireCanonicalAgentIdInScope(anyString(),anyString(),anyString(),anyString())).thenReturn("agent-a");
        assertEquals(AgentTaskPointAndStartPolicyService.Failure.Reason.NOT_FOUND,
                assertThrows(AgentTaskPointAndStartPolicyService.Failure.class,()->service.read(scope,"task-a","agent-a")).reason());
        when(tasks.findByTaskIdInOwnerScope(anyString(),anyString(),anyString(),anyString())).thenThrow(new DataAccessResourceFailureException("down"));
        assertEquals(AgentTaskPointAndStartPolicyService.Failure.Reason.SOURCE_UNAVAILABLE,
                assertThrows(AgentTaskPointAndStartPolicyService.Failure.class,()->service.read(scope,"task-a","agent-a")).reason());
    }

    private static AgentTaskMetaEntity task(String state,String assigned){
        AgentTaskMetaEntity value=new AgentTaskMetaEntity().setTaskId("task-a").setOwnerJiacn("owner-a").setRewardStatus(state).setAssignedAgentId(assigned).setTaskVersion(3L);
        value.setTenantId("0");value.setClientId("client-a");return value;
    }
}
