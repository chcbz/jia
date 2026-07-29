package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AbilityComparisonDao;
import cn.jia.agent.dao.AbilityEvaluationDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AbilityEvaluationRequestDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class AbilityEvaluationServiceImplTest extends BaseMockTest {
    @Mock
    AbilityEvaluationDao abilityEvaluationDao;
    @Mock
    AbilityComparisonDao abilityComparisonDao;
    @Mock
    AgentPersonaDao agentPersonaDao;
    @Mock
    AgentTaskMetaDao agentTaskMetaDao;

    private AbilityEvaluationServiceImpl service;

    @BeforeEach
    void setUp() {
        EsContext context = new EsContext();
        context.setJiacn("tenant-a");
        context.setClientId("client-a");
        EsContextHolder.setContext(context);
        service = new AbilityEvaluationServiceImpl(
                abilityEvaluationDao, abilityComparisonDao, agentPersonaDao, agentTaskMetaDao);
    }

    @Test
    void evaluationPushesExactScopeAndAgentIntoBoundedTaskQuery() {
        AgentTaskMetaEntity task = scopedTask("task-a", "agent-a");
        when(agentTaskMetaDao.findByAgentId("tenant-a", "client-a", "agent-a", 500))
                .thenReturn(List.of(task));

        service.evaluate(request("agent-a"));

        verify(agentTaskMetaDao).findByAgentId("tenant-a", "client-a", "agent-a", 500);
        verify(abilityEvaluationDao).insert(any());
    }

    @Test
    void evaluationRejectsTruncatedTaskSnapshotBeforeWriting() {
        AgentTaskMetaEntity task = scopedTask("task-a", "agent-a");
        when(agentTaskMetaDao.findByAgentId("tenant-a", "client-a", "agent-a", 500))
                .thenReturn(Collections.nCopies(500, task));

        assertThrows(IllegalArgumentException.class,
                () -> service.evaluate(request("agent-a")));

        verify(abilityEvaluationDao, never()).insert(any());
    }

    @Test
    void evaluationRejectsMapperScopeAgentOrTaskIdDriftBeforeWriting() {
        AgentTaskMetaEntity drifted = scopedTask("task-a ", "agent-b");
        drifted.setTenantId("TENANT-A");
        when(agentTaskMetaDao.findByAgentId("tenant-a", "client-a", "agent-a", 500))
                .thenReturn(List.of(drifted));

        assertThrows(IllegalArgumentException.class,
                () -> service.evaluate(request("agent-a")));

        verify(abilityEvaluationDao, never()).insert(any());
    }

    private AbilityEvaluationRequestDTO request(String agentName) {
        AbilityEvaluationRequestDTO request = new AbilityEvaluationRequestDTO();
        request.setAgentName(agentName);
        return request;
    }

    private AgentTaskMetaEntity scopedTask(String taskId, String agentId) {
        AgentTaskMetaEntity task = new AgentTaskMetaEntity();
        task.setTaskId(taskId);
        task.setTenantId("tenant-a");
        task.setClientId("client-a");
        task.setAssignedAgentId(agentId);
        task.setRewardStatus("completed");
        return task;
    }
}
