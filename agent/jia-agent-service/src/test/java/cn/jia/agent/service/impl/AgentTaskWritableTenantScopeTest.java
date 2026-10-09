package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real writable-membership ACL, not just the conversation wrapper. */
class AgentTaskWritableTenantScopeTest {
    private final AgentTaskMetaDao tasks = mock(AgentTaskMetaDao.class);
    private final AgentTaskMemberDao members = mock(AgentTaskMemberDao.class);
    private final AgentServiceImpl service = service();

    private AgentServiceImpl service() {
        AgentServiceImpl real = mock(AgentServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(real, "agentTaskMetaDao", tasks);
        ReflectionTestUtils.setField(real, "agentTaskMemberDao", members);
        return real;
    }

    @AfterEach
    void clearContext() { EsContextHolder.clearContext(); }

    @Test
    void bothTenantsHaveUsableExactOwnerMembershipWithoutAmbientIdentity() {
        EsContext stale = new EsContext();
        stale.setTenantId("0"); stale.setJiacn("stale-owner"); stale.setClientId("stale-client");
        EsContextHolder.setContext(stale);
        for (String tenant : List.of("0", "1")) {
            when(tasks.findByTaskIdInOwnerScope(tenant, "client-a", "owner-not-tenant", "task-a"))
                    .thenReturn(task(tenant, "client-a", "owner-not-tenant"));
            when(members.listByTask(tenant, "client-a", "owner-not-tenant", "task-a"))
                    .thenReturn(List.of(member(tenant, "client-a", "owner-not-tenant")));
            assertEquals(List.of("agent-a"), service.listTaskWritableMemberAgentIds(
                    tenant, "client-a", "owner-not-tenant", "task-a"));
            verify(tasks).findByTaskIdInOwnerScope(tenant, "client-a", "owner-not-tenant", "task-a");
        }
    }

    @Test
    void foreignTenantClientOrOwnerTaskCannotAuthorizeSameId() {
        for (AgentTaskMetaEntity foreign : List.of(task("0", "client-a", "owner-a"),
                task("1", "other-client", "owner-a"), task("1", "client-a", "other-owner"))) {
            when(tasks.findByTaskIdInOwnerScope("1", "client-a", "owner-a", "task-a"))
                    .thenReturn(foreign);
            assertEquals(List.of(), service.listTaskWritableMemberAgentIds(
                    "1", "client-a", "owner-a", "task-a"));
        }
        verifyNoInteractions(members);
    }

    @Test
    void foreignPersistedMemberCannotOverrideTrustedTaskScope() {
        when(tasks.findByTaskIdInOwnerScope("1", "client-a", "owner-a", "task-a"))
                .thenReturn(task("1", "client-a", "owner-a"));
        when(members.listByTask("1", "client-a", "owner-a", "task-a"))
                .thenReturn(List.of(member("0", "client-a", "owner-a")));
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class, () -> service.listTaskWritableMemberAgentIds(
                "1", "client-a", "owner-a", "task-a"));
        assertEquals("Persisted task member is outside the writable task scope", denied.getMessage());
    }

    @Test
    void malformedTenantFailsBeforeQuery() {
        assertThrows(IllegalArgumentException.class, () -> service.listTaskWritableMemberAgentIds(
                "01", "client-a", "owner-a", "task-a"));
        verifyNoInteractions(tasks, members);
    }

    private AgentTaskMetaEntity task(String tenant, String client, String owner) {
        AgentTaskMetaEntity row = new AgentTaskMetaEntity();
        row.setTenantId(tenant); row.setClientId(client); row.setOwnerJiacn(owner);
        row.setTaskId("task-a"); row.setRewardStatus("open");
        return row;
    }

    private AgentTaskMemberEntity member(String tenant, String client, String owner) {
        AgentTaskMemberEntity row = new AgentTaskMemberEntity();
        row.setTenantId(tenant); row.setClientId(client); row.setOwnerJiacn(owner);
        row.setTaskId("task-a"); row.setAgentId("agent-a");
        row.setMemberRole("worker"); row.setAssignmentSource("manual"); row.setMemberStatus("accepted");
        return row;
    }
}
