package cn.jia.agent.service.impl;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

class AgentTaskCollaborationAccessServiceImplTest extends BaseMockTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String AGENT = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Mock
    AgentTaskMetaDao taskMetaDao;
    @Mock
    AgentTaskMemberDao memberDao;

    @Test
    void statusPolicyExplicitlySeparatesActiveHistoricalAndFormerMembers() {
        AgentTaskCollaborationAccessServiceImpl service = service();
        when(taskMetaDao.findByTaskId(TENANT, CLIENT, TASK))
                .thenReturn(new AgentTaskMetaEntity().setTaskId(TASK));

        Map<String, AgentTaskAccessLevel> expected = Map.of(
                "accepted", AgentTaskAccessLevel.READ_WRITE,
                "working", AgentTaskAccessLevel.READ_WRITE,
                "blocked", AgentTaskAccessLevel.READ_WRITE,
                "done", AgentTaskAccessLevel.READ_ONLY,
                "failed", AgentTaskAccessLevel.READ_ONLY,
                "invited", AgentTaskAccessLevel.NONE,
                "rejected", AgentTaskAccessLevel.NONE,
                "left", AgentTaskAccessLevel.NONE,
                "WORKING", AgentTaskAccessLevel.NONE);

        expected.forEach((status, access) -> {
            when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, AGENT))
                    .thenReturn(new AgentTaskMemberEntity()
                            .setTaskId(TASK).setAgentId(AGENT)
                            .setMemberRole("observer").setMemberStatus(status));
            assertEquals(access,
                    service.resolveMemberAccess(TENANT, CLIENT, TASK, AGENT), status);
        });
    }

    @Test
    void missingTaskOrMemberAndCrossScopeAllFailClosed() {
        AgentTaskCollaborationAccessServiceImpl service = service();
        assertEquals(AgentTaskAccessLevel.NONE,
                service.resolveMemberAccess(TENANT, CLIENT, TASK, AGENT));

        when(taskMetaDao.findByTaskId(TENANT, CLIENT, TASK))
                .thenReturn(new AgentTaskMetaEntity().setTaskId(TASK));
        assertEquals(AgentTaskAccessLevel.NONE,
                service.resolveMemberAccess(TENANT, CLIENT, TASK, AGENT));

        assertEquals(AgentTaskAccessLevel.NONE,
                service.resolveMemberAccess("tenant-b", CLIENT, TASK, AGENT));
        assertEquals(AgentTaskAccessLevel.NONE,
                service.resolveMemberAccess(TENANT, "client-b", TASK, AGENT));
        assertEquals(AgentTaskAccessLevel.NONE,
                service.resolveMemberAccess(TENANT, CLIENT, "task-2", AGENT));
    }

    @Test
    void blankScopeIsRejectedBeforeDaoLookup() {
        assertThrows(IllegalArgumentException.class,
                () -> service().resolveMemberAccess(" ", CLIENT, TASK, AGENT));
    }

    private AgentTaskCollaborationAccessServiceImpl service() {
        return new AgentTaskCollaborationAccessServiceImpl(taskMetaDao, memberDao);
    }
}
