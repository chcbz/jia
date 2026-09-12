package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentSceneFeatureFlags;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.agent.service.AgentScopePublicationCoordinator;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.funding.FundedBountyException;
import cn.jia.agent.service.funding.FundedBountyLegacyGuard;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.task.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentServiceFundedBountyGuardTest {
    @AfterEach
    void clearContext() {
        cn.jia.core.context.EsContextHolder.clearContext();
    }

    @Test
    void manualSingleAndGroupAssignmentFailBeforeLegacyIdentityOrTaskMutation() {
        Fixture fixture = fixture();
        doThrow(new FundedBountyException(org.springframework.http.HttpStatus.CONFLICT,
                "QUOTE_REQUIRED", "quote required")).when(fixture.guard)
                .requireAssignmentAllowed(anyString(), anyString(), anyString(),
                        org.mockito.ArgumentMatchers.eq(false), anyInt(),
                        org.mockito.ArgumentMatchers.eq(false));
        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentIds(List.of("agent-1"));

        FundedBountyException failure = assertThrows(FundedBountyException.class,
                () -> fixture.service.assignTask("task-1", request));

        assertEquals("QUOTE_REQUIRED", failure.code());
        verifyNoInteractions(fixture.legacy);
    }

    @Test
    void autoAssignmentFailsAsUnsupportedBeforeRecommendationOrMutation() {
        Fixture fixture = fixture();
        doThrow(new FundedBountyException(org.springframework.http.HttpStatus.CONFLICT,
                "FUNDED_TEAM_NOT_SUPPORTED", "unsupported")).when(fixture.guard)
                .requireAssignmentAllowed(anyString(), anyString(), anyString(),
                        org.mockito.ArgumentMatchers.eq(true), anyInt(),
                        org.mockito.ArgumentMatchers.eq(false));

        FundedBountyException failure = assertThrows(FundedBountyException.class,
                () -> fixture.service.autoAssignTask("task-1", new AgentTaskAssignDTO()));

        assertEquals("FUNDED_TEAM_NOT_SUPPORTED", failure.code());
        verifyNoInteractions(fixture.legacy);
    }

    @Test
    void reportFailsBeforeResolvingOrMutatingAgent() {
        Fixture fixture = fixture();
        doThrow(new FundedBountyException(org.springframework.http.HttpStatus.CONFLICT,
                "QUOTE_REQUIRED", "funded lifecycle")).when(fixture.guard)
                .requireLifecycleAllowed(anyString(), anyString(), anyString(),
                        org.mockito.ArgumentMatchers.eq(false));
        AgentTaskReportDTO request = new AgentTaskReportDTO();
        request.setAgentId("agent-1");

        FundedBountyException failure = assertThrows(FundedBountyException.class,
                () -> fixture.service.reportTask("task-1", request));

        assertEquals("QUOTE_REQUIRED", failure.code());
        verifyNoInteractions(fixture.legacy);
    }

    private static Fixture fixture() {
        AgentLegacyTaskCompatibilityService legacy = mock(AgentLegacyTaskCompatibilityService.class);
        AgentTaskMutationTransaction mutation = mock(AgentTaskMutationTransaction.class);
        @SuppressWarnings("unchecked") ObjectProvider<TaskService> tasks = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<ApiKeyService> keys = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentSceneService> scenes = mock(ObjectProvider.class);
        AgentServiceImpl service = new AgentServiceImpl(mock(AgentRuntimeDao.class),
                mock(AgentIdentityService.class), mock(AgentPersonaDao.class),
                mock(AgentPersonaBindingDao.class), mock(AgentTaskMetaDao.class),
                mock(AgentTaskMemberDao.class), legacy, mock(AgentTaskNoteDao.class),
                mock(DialogueTemplateDao.class), mock(ObjectProvider.class), tasks, keys, scenes,
                new AgentScopePublicationCoordinator(), new AgentSceneFeatureFlags(false, false),
                mutation, mock(AgentTaskEventWriter.class));
        FundedBountyLegacyGuard guard = mock(FundedBountyLegacyGuard.class);
        @SuppressWarnings("unchecked") ObjectProvider<FundedBountyLegacyGuard> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(guard);
        service.configureFundedBountyLegacyGuard(provider);
        return new Fixture(service, guard, legacy);
    }

    private record Fixture(AgentServiceImpl service, FundedBountyLegacyGuard guard,
            AgentLegacyTaskCompatibilityService legacy) { }
}
