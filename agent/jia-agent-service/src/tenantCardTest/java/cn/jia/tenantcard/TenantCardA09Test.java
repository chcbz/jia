package cn.jia.tenantcard;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.api.AgentTaskEventStreamController;
import cn.jia.agent.api.AgentTaskWorkspaceController;
import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.dao.AgentTaskWorkspaceDao;
import cn.jia.agent.dao.impl.AgentTaskWorkspaceDaoImpl;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.MemberRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.TaskRow;
import cn.jia.agent.entity.AgentTaskWorkspaceDTO;
import cn.jia.agent.mapper.AgentTaskWorkspaceMapper;
import cn.jia.agent.service.AgentTaskEventAccessService;
import cn.jia.agent.service.AgentTaskEventBroker;
import cn.jia.agent.service.AgentTaskEventReplayService;
import cn.jia.agent.service.AgentTaskWorkspaceService;
import cn.jia.agent.service.AgentTaskEventReplayService.TaskScope;
import cn.jia.agent.service.impl.AgentIdentityServiceImpl;
import cn.jia.agent.service.impl.AgentTaskEventReplayServiceImpl;
import cn.jia.agent.service.impl.AgentTaskWorkspaceServiceImpl;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.test.StepVerifier;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantCardA09Test {
    private static final String TENANT = "1";
    private static final String CLIENT = "client-1";
    private static final String OWNER = "owner-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TASK = "task-1";
    private static final BusinessScope AUTHORITY = new BusinessScope(
            TenantId.of(TENANT), DataDomainId.of("cyf"), OWNER, CLIENT);

    @AfterEach
    void clearScope() {
        TrustedTenantContext.clear();
    }

    @Test
    void tenantOneOpaqueIdentityInheritsPersistedBindingTenant() {
        AgentIdentityRegistryDao registries = mock(AgentIdentityRegistryDao.class);
        AgentIdentityServiceImpl service = new AgentIdentityServiceImpl(
                registries, mock(AgentIdentityAliasDao.class), mock(AgentPersonaBindingDao.class));
        AgentPersonaBindingEntity binding = binding(TENANT);
        AtomicReference<AgentIdentityRegistryEntity> inserted = new AtomicReference<>();
        when(registries.insert(any())).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });

        AgentIdentityRegistryEntity identity = service.provisionOpaqueIdentity(binding, "approved");

        assertEquals(TENANT, identity.getTenantId());
        assertEquals(TENANT, inserted.get().getTenantId());
        verify(registries).findExactByBindingInScope(TENANT, CLIENT, OWNER, 41L);
        assertThrows(RuntimeException.class,
                () -> service.provisionOpaqueIdentity(binding(null), "approved"));
    }

    @Test
    void tenantOneIdentityRequiresExactTenantClientOwnerAndBindingProjection() {
        AgentIdentityRegistryDao registries = mock(AgentIdentityRegistryDao.class);
        AgentPersonaBindingDao bindings = mock(AgentPersonaBindingDao.class);
        AgentIdentityServiceImpl service = new AgentIdentityServiceImpl(
                registries, mock(AgentIdentityAliasDao.class), bindings);
        AgentPersonaBindingEntity binding = binding(TENANT);
        AgentIdentityRegistryEntity identity = identity(TENANT);
        when(registries.findExactByCanonicalInScope(TENANT, CLIENT, OWNER, ACTOR))
                .thenReturn(identity);
        when(bindings.selectById(41L)).thenReturn(binding);

        assertEquals(ACTOR, service.requireCanonicalAgentIdInScope(
                TENANT, CLIENT, OWNER, ACTOR));
        binding.setTenantId(null);
        assertThrows(RuntimeException.class, () -> service.requireCanonicalAgentIdInScope(
                TENANT, CLIENT, OWNER, ACTOR));
        assertThrows(RuntimeException.class, () -> service.requireCanonicalAgentIdInScope(
                "01", CLIENT, OWNER, ACTOR));
        verify(registries, never()).findExactByCanonicalInScope("01", CLIENT, OWNER, ACTOR);
    }

    @Test
    void tenantOneWorkspaceAuthorizationCarriesExactScopeAndRejectsForeignProjection() {
        var identity = mock(cn.jia.agent.service.AgentIdentityService.class);
        AgentTaskWorkspaceDao dao = mock(AgentTaskWorkspaceDao.class);
        AgentTaskWorkspaceServiceImpl service = new AgentTaskWorkspaceServiceImpl(identity, dao);
        when(identity.requireCanonicalAgentIdInScope(TENANT, CLIENT, OWNER, ACTOR))
                .thenReturn(ACTOR);
        when(dao.findTask(TENANT, CLIENT, OWNER, TASK)).thenReturn(task(TENANT));
        when(dao.findActorMember(TENANT, CLIENT, OWNER, TASK, ACTOR))
                .thenReturn(member(TENANT));

        AgentTaskEventAccessService.AuthorizedSubject subject = service.authorize(
                TENANT, CLIENT, OWNER, TASK, ACTOR);

        assertEquals(TENANT, subject.tenantId());
        assertEquals(CLIENT, subject.clientId());
        assertEquals(OWNER, subject.ownerJiacn());
        when(dao.findTask(TENANT, CLIENT, OWNER, TASK)).thenReturn(task("0"));
        assertThrows(RuntimeException.class,
                () -> service.authorize(TENANT, CLIENT, OWNER, TASK, ACTOR));
    }

    @Test
    void workspaceDaoAcceptsCanonicalTenantOneAndRejectsMalformedTenantBeforeQuery() {
        AgentTaskWorkspaceMapper mapper = mock(AgentTaskWorkspaceMapper.class);
        AgentTaskWorkspaceDaoImpl dao = new AgentTaskWorkspaceDaoImpl(mapper);
        TaskRow row = task(TENANT);
        when(mapper.findTask(TENANT, CLIENT, OWNER, TASK)).thenReturn(row);

        assertEquals(row, dao.findTask(TENANT, CLIENT, OWNER, TASK));
        assertThrows(IllegalArgumentException.class,
                () -> dao.findTask("01", CLIENT, OWNER, TASK));
        verify(mapper, never()).findTask("01", CLIENT, OWNER, TASK);
    }

    @Test
    void workspaceControllerUsesTrustedTenantAndAllowsBasicJwtWithoutDomainClaim() {
        AgentTaskWorkspaceService workspace = mock(AgentTaskWorkspaceService.class);
        AgentTaskEventsGate gate = mock(AgentTaskEventsGate.class);
        AgentTaskWorkspaceController controller = new AgentTaskWorkspaceController(workspace, gate);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("actorAgentId", ACTOR);
        when(gate.allows(TENANT, CLIENT)).thenReturn(true);
        when(workspace.snapshot(TENANT, CLIENT, OWNER, TASK, ACTOR))
                .thenReturn(new AgentTaskWorkspaceDTO());

        TrustedTenantContext.withScope(AUTHORITY,
                () -> controller.workspace(TASK, request, authentication(TENANT, false)));

        verify(workspace).snapshot(TENANT, CLIENT, OWNER, TASK, ACTOR);
        assertThrows(RuntimeException.class, () -> TrustedTenantContext.withScope(AUTHORITY,
                () -> controller.workspace(TASK, request, authentication("0", true))));
    }

    @Test
    void eventControllerReauthorizesAndStartsReplayWithExactTrustedScope() {
        AgentTaskEventAccessService access = mock(AgentTaskEventAccessService.class);
        AgentTaskEventReplayService replay = mock(AgentTaskEventReplayService.class);
        AgentTaskEventsGate gate = mock(AgentTaskEventsGate.class);
        AgentTaskEventStreamController controller = new AgentTaskEventStreamController(
                access, replay, gate);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("actorAgentId", ACTOR);
        TaskScope scope = new TaskScope(TENANT, CLIENT, OWNER, TASK);
        AgentTaskEventAccessService.AuthorizedSubject subject =
                new AgentTaskEventAccessService.AuthorizedSubject(
                        TENANT, CLIENT, OWNER, TASK, ACTOR, "worker", null);
        when(gate.allows(TENANT, CLIENT)).thenReturn(true);
        when(access.authorize(TENANT, CLIENT, OWNER, TASK, ACTOR)).thenReturn(subject);
        when(replay.replay(scope, 0L)).thenReturn(Flux.never());

        var response = TrustedTenantContext.withScope(AUTHORITY,
                () -> controller.events(TASK, request, authentication(TENANT, false)));
        response.getBody().complete();

        verify(access).authorize(TENANT, CLIENT, OWNER, TASK, ACTOR);
        verify(replay).replay(scope, 0L);
        assertThrows(RuntimeException.class, () -> TrustedTenantContext.withScope(AUTHORITY,
                () -> controller.events(TASK, request, authentication("0", true))));
    }

    @Test
    void replayWorkerInstallsCapturedTenantAndRestoresPooledThread() throws Exception {
        AgentTaskEventDao dao = mock(AgentTaskEventDao.class);
        AgentTaskEventBroker broker = new AgentTaskEventBroker();
        AgentTaskEventReplayServiceImpl service = new AgentTaskEventReplayServiceImpl(dao, broker);
        TaskScope scope = new TaskScope(TENANT, CLIENT, OWNER, TASK);
        when(dao.findCurrentVersion(TENANT, CLIENT, OWNER, TASK)).thenAnswer(invocation -> {
            assertEquals(AUTHORITY, TrustedTenantContext.requireScope());
            return 1L;
        });
        when(dao.findEarliestVersion(TENANT, CLIENT, OWNER, TASK)).thenReturn(1L);
        when(dao.findAfterVersion(TENANT, CLIENT, OWNER, TASK, 0L, 256))
                .thenReturn(List.of(event()));
        try {
            StepVerifier.create(TrustedTenantContext.withScope(AUTHORITY,
                            () -> service.replay(scope, 0L)))
                    .assertNext(signal -> assertEquals(scope,
                            ((cn.jia.agent.service.AgentTaskEventReplayService.DurableEvent) signal).scope()))
                    .thenCancel()
                    .verify(Duration.ofSeconds(5));

            ExecutorService worker = executor(service, "catchUpExecutor");
            assertFalse(worker.submit(() -> TrustedTenantContext.currentScope().isPresent())
                    .get(5, TimeUnit.SECONDS));
        } finally {
            service.close();
            broker.close();
        }
    }

    @Test
    void replayRegistrationRejectsMissingOrMismatchedTrustedScope() {
        AgentTaskEventReplayServiceImpl service = new AgentTaskEventReplayServiceImpl(
                mock(AgentTaskEventDao.class), new AgentTaskEventBroker());
        TaskScope scope = new TaskScope(TENANT, CLIENT, OWNER, TASK);
        try {
            assertThrows(IllegalStateException.class, () -> service.replay(scope, 0L));
            BusinessScope foreign = new BusinessScope(
                    TenantId.of("0"), DataDomainId.of("cyf"), OWNER, CLIENT);
            assertThrows(IllegalArgumentException.class,
                    () -> TrustedTenantContext.withScope(foreign,
                            () -> service.replay(scope, 0L)));
        } finally {
            service.close();
        }
    }

    private static AgentPersonaBindingEntity binding(String tenant) {
        AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity();
        binding.setId(41L);
        binding.setTenantId(tenant);
        binding.setClientId(CLIENT);
        binding.setJiacn(OWNER);
        binding.setAgentId(ACTOR);
        binding.setPersonaCode("wuyong");
        binding.setStatus(AgentConstants.BINDING_STATUS_ACTIVE);
        binding.setBoundAt(100L);
        return binding;
    }

    private static AgentIdentityRegistryEntity identity(String tenant) {
        AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity();
        identity.setId(51L);
        identity.setTenantId(tenant);
        identity.setClientId(CLIENT);
        identity.setOwnerJiacn(OWNER);
        identity.setCanonicalAgentId(ACTOR);
        identity.setCanonicalType(AgentConstants.IDENTITY_TYPE_OPAQUE);
        identity.setLifecycleStatus(AgentConstants.IDENTITY_STATUS_ACTIVE);
        identity.setBindingId(41L);
        return identity;
    }

    private static TaskRow task(String tenant) {
        TaskRow row = new TaskRow();
        row.setTenantId(tenant);
        row.setClientId(CLIENT);
        row.setOwnerJiacn(OWNER);
        row.setTaskId(TASK);
        row.setCoordinatorAgentId(ACTOR);
        return row;
    }

    private static MemberRow member(String tenant) {
        MemberRow row = new MemberRow();
        row.setTenantId(tenant);
        row.setClientId(CLIENT);
        row.setOwnerJiacn(OWNER);
        row.setTaskId(TASK);
        row.setAgentId(ACTOR);
        row.setMemberRole("coordinator");
        row.setMemberStatus("accepted");
        row.setAssignmentSource("manual");
        row.setVersion(0L);
        return row;
    }

    private static AgentTaskEventEntity event() {
        AgentTaskEventEntity event = new AgentTaskEventEntity();
        event.setTenantId(TENANT);
        event.setClientId(CLIENT);
        event.setOwnerJiacn(OWNER);
        event.setTaskId(TASK);
        event.setEventVersion(1L);
        event.setEventId("evt-1");
        event.setEventType("task_created");
        event.setActorType("system");
        event.setAggregateType("task");
        event.setAggregateId(TASK);
        event.setEventJson("{}");
        event.setOccurredAt(100L);
        return event;
    }

    private static ExecutorService executor(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (ExecutorService) field.get(target);
    }

    private static JwtAuthenticationToken authentication(String tenant, boolean includeDomain) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("tenant_id", tenant)
                .claim("jiacn", OWNER)
                .claim("client_id", CLIENT)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (includeDomain) {
            builder.claim("data_domain_id", "cyf");
        }
        return new JwtAuthenticationToken(builder.build(), List.of());
    }
}
