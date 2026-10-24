package cn.jia.tenantcard;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.config.AgentSceneFeatureFlags;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskNoteEntity;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.agent.service.AgentScopePublicationCoordinator;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.impl.AgentLegacyTaskCompatibilityService;
import cn.jia.agent.service.impl.AgentServiceImpl;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.task.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantCardA07Test {
    private static final String TENANT = "1";
    private static final String CLIENT = "client-1";
    private static final String OWNER = "owner-1";
    private static final String AGENT = "agent-1";

    private AgentRuntimeDao runtimeDao;
    private AgentTaskMetaDao taskMetaDao;
    private AgentTaskNoteDao taskNoteDao;
    private AgentServiceImpl service;

    @BeforeEach
    void setUp() {
        EsContext context = new EsContext();
        context.setClientId(CLIENT);
        context.setJiacn(OWNER);
        EsContextHolder.setContext(context);

        runtimeDao = mock(AgentRuntimeDao.class);
        taskMetaDao = mock(AgentTaskMetaDao.class);
        taskNoteDao = mock(AgentTaskNoteDao.class);
        service = new AgentServiceImpl(
                runtimeDao,
                mock(AgentIdentityService.class),
                mock(AgentPersonaDao.class),
                mock(AgentPersonaBindingDao.class),
                taskMetaDao,
                mock(AgentTaskMemberDao.class),
                mock(AgentLegacyTaskCompatibilityService.class),
                taskNoteDao,
                mock(DialogueTemplateDao.class),
                provider(), provider(), provider(), provider(),
                new AgentScopePublicationCoordinator(),
                new AgentSceneFeatureFlags(true, true),
                mock(AgentTaskMutationTransaction.class),
                mock(AgentTaskEventWriter.class));
    }

    @AfterEach
    void tearDown() {
        TrustedTenantContext.clear();
        EsContextHolder.clearContext();
    }

    @Test
    void capabilitiesQueryTaskStatsInRuntimeTenantWithoutTenantZeroFallback() {
        AgentRuntimeEntity runtime = runtime(TENANT);
        when(runtimeDao.findRosterByOwner(TENANT, CLIENT, OWNER, null, null))
                .thenReturn(List.of(runtime));
        when(taskMetaDao.findByAgentId(TENANT, CLIENT, AGENT, 500))
                .thenReturn(List.of());

        var capabilities = inTenantScope(service::listCapabilities);

        assertEquals(0.75D, capabilities.getFirst().getSuccessRate());
        assertEquals(75, capabilities.getFirst().getRecentScore());
        verify(taskMetaDao).findByAgentId(TENANT, CLIENT, AGENT, 500);
        verify(taskMetaDao, never()).findByAgentId(eq("0"), anyString(), anyString(), anyInt());
    }

    @Test
    void capabilitiesRejectTenantZeroTaskReturnedForTenantOneRuntime() {
        when(runtimeDao.findRosterByOwner(TENANT, CLIENT, OWNER, null, null))
                .thenReturn(List.of(runtime(TENANT)));
        when(taskMetaDao.findByAgentId(TENANT, CLIENT, AGENT, 500))
                .thenReturn(List.of(task("0", "task-foreign", AgentConstants.TASK_STATUS_COMPLETED)));

        assertThrows(IllegalArgumentException.class,
                () -> inTenantScope(service::listCapabilities));

        verify(taskMetaDao).findByAgentId(TENANT, CLIENT, AGENT, 500);
    }

    @Test
    void capabilitiesCalculateTenantOneCompletedAndFailedStats() {
        when(runtimeDao.findRosterByOwner(TENANT, CLIENT, OWNER, null, null))
                .thenReturn(List.of(runtime(TENANT)));
        when(taskMetaDao.findByAgentId(TENANT, CLIENT, AGENT, 500))
                .thenReturn(List.of(
                        task(TENANT, "task-completed", AgentConstants.TASK_STATUS_COMPLETED),
                        task(TENANT, "task-failed", AgentConstants.TASK_STATUS_FAILED)));

        var capability = inTenantScope(service::listCapabilities).getFirst();

        assertEquals(0.5D, capability.getSuccessRate());
        assertEquals(67, capability.getRecentScore());
    }

    @Test
    void capabilitiesRejectRuntimeWithoutCanonicalTenantBeforeTaskQuery() {
        when(runtimeDao.findRosterByOwner(TENANT, CLIENT, OWNER, null, null))
                .thenReturn(List.of(runtime(null)));

        assertThrows(RuntimeException.class,
                () -> inTenantScope(service::listCapabilities));

        verifyNoInteractions(taskMetaDao);
    }

    @Test
    void listTaskNotesAuthorizesAndQueriesOnlyTenantOne() {
        AgentTaskMetaEntity root = task(TENANT, "task-1", AgentConstants.TASK_STATUS_OPEN);
        when(taskMetaDao.findByTaskIdInOwnerScope(TENANT, CLIENT, OWNER, "task-1"))
                .thenReturn(root);
        AgentTaskNoteEntity note = new AgentTaskNoteEntity();
        note.setTenantId(TENANT);
        note.setClientId(CLIENT);
        note.setOwnerJiacn(OWNER);
        note.setTaskId("task-1");
        note.setContent("tenant-one-note");
        when(taskNoteDao.findByTaskIdInOwnerScope(TENANT, CLIENT, OWNER, "task-1"))
                .thenReturn(List.of(note));

        var notes = inTenantScope(() -> service.listTaskNotes("task-1"));

        assertEquals("tenant-one-note", notes.getFirst().getContent());
        verify(taskNoteDao).findByTaskIdInOwnerScope(TENANT, CLIENT, OWNER, "task-1");
        verify(taskNoteDao, never()).findByTaskIdInOwnerScope(
                eq("0"), anyString(), anyString(), anyString());
    }

    private AgentRuntimeEntity runtime(String tenantId) {
        AgentRuntimeEntity runtime = new AgentRuntimeEntity();
        runtime.setTenantId(tenantId);
        runtime.setClientId(CLIENT);
        runtime.setOwnerJiacn(OWNER);
        runtime.setAgentId(AGENT);
        runtime.setName("Tenant One Agent");
        runtime.setAbilities("[]");
        runtime.setStatus(AgentConstants.STATUS_ONLINE);
        return runtime;
    }

    private AgentTaskMetaEntity task(String tenantId, String taskId, String status) {
        AgentTaskMetaEntity task = new AgentTaskMetaEntity();
        task.setTenantId(tenantId);
        task.setClientId(CLIENT);
        task.setOwnerJiacn(OWNER);
        task.setTaskId(taskId);
        task.setAssignedAgentId(AGENT);
        task.setRewardStatus(status);
        return task;
    }

    private <T> T inTenantScope(Supplier<T> work) {
        return TrustedTenantContext.withScope(new BusinessScope(
                TenantId.of(TENANT), DataDomainId.of("cyf"), OWNER, CLIENT), work);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }
}
