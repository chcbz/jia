package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.config.AgentSceneFeatureFlags;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.agent.service.AgentScopePublicationCoordinator;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.HostingRentAdmissionException;
import cn.jia.agent.service.HostingRentAdmissionService;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AgentServiceHostingRentAdmissionTest {
    private AgentRuntimeDao runtimeDao;
    private AgentIdentityService identityService;
    private AgentPersonaDao personaDao;
    private AgentPersonaBindingDao bindingDao;
    private ObjectProvider<ApiKeyService> apiKeyServiceProvider;

    @BeforeEach
    void setUp() {
        runtimeDao = mock(AgentRuntimeDao.class);
        identityService = mock(AgentIdentityService.class);
        personaDao = mock(AgentPersonaDao.class);
        bindingDao = mock(AgentPersonaBindingDao.class);
        apiKeyServiceProvider = mock(ObjectProvider.class);
    }

    @Test
    void directServerBindRejectsUnconfiguredRentBeforeAnyDaoIdentityRuntimeOrProfileDependency() {
        AgentServiceImpl service = service(HostingRentAdmissionService.unconfigured());

        HostingRentAdmissionException failure = assertThrows(HostingRentAdmissionException.class,
                () -> service.bindPersona("wuyong", " SERVER "));

        assertEquals(HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_CONFIGURED,
                failure.reason());
        verifyNoBindInteractions();
    }

    @Test
    void configuredRentStillRejectsServerBindBeforeAnyLegacyMutation() {
        HostingRentAdmissionService admission = new HostingRentAdmissionService(
                new AgentHostingRentProperties(true, "rent-v1", "1000000", "86400"));
        AgentServiceImpl service = service(admission);

        HostingRentAdmissionException failure = assertThrows(HostingRentAdmissionException.class,
                () -> service.bindPersona("wuyong", "server"));

        assertEquals(HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_READY,
                failure.reason());
        verifyNoBindInteractions();
    }

    @Test
    void unknownModeRejectsBeforeAnyLegacyMutation() {
        AgentServiceImpl service = service(HostingRentAdmissionService.unconfigured());

        assertThrows(IllegalArgumentException.class,
                () -> service.bindPersona("wuyong", "server-now"));

        verifyNoBindInteractions();
    }

    private AgentServiceImpl service(HostingRentAdmissionService admission) {
        return new AgentServiceImpl(
                runtimeDao,
                identityService,
                personaDao,
                bindingDao,
                mock(AgentTaskMetaDao.class),
                mock(AgentTaskMemberDao.class),
                mock(AgentLegacyTaskCompatibilityService.class),
                mock(AgentTaskNoteDao.class),
                mock(DialogueTemplateDao.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                apiKeyServiceProvider,
                mock(ObjectProvider.class),
                new AgentScopePublicationCoordinator(),
                new AgentSceneFeatureFlags(false, false),
                mock(AgentTaskMutationTransaction.class),
                mock(AgentTaskEventWriter.class),
                AgentCommandTransportCapture.disabledForLegacyConstruction(),
                admission);
    }

    private void verifyNoBindInteractions() {
        verifyNoInteractions(runtimeDao, identityService, personaDao, bindingDao,
                apiKeyServiceProvider);
    }
}
