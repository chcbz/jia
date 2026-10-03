package cn.jia.agent.archive;

import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchiveAgentExecutionAdapterTest {
    private static final String SHA = "a".repeat(64);
    private static final byte[] REGISTRATION = new byte[32];

    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void admissionPersistsExactControlledPayloadAndRejectsWrongProofOrTransaction() {
        AgentIdentityService identities = mock(AgentIdentityService.class);
        AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
        AgentRuntimeAuthenticationService authentication = mock(AgentRuntimeAuthenticationService.class);
        InstalledSkillResolver skills = mock(InstalledSkillResolver.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentCommandTransportWriter> writers = mock(ObjectProvider.class);
        AgentCommandTransportWriter writer = mock(AgentCommandTransportWriter.class);
        AgentCommandTransportDao deliveries = mock(AgentCommandTransportDao.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentManagedSessionLookup> sessions = mock(ObjectProvider.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        var identity = new AgentIdentityRegistryEntity().setCanonicalAgentId("agent-a");
        var runtime = new AgentRuntimeEntity().setAgentId("agent-a").setOwnerJiacn("owner-a").setBindingId(7L);
        runtime.setTenantId("0"); runtime.setClientId("client-a");
        var target = new AgentRuntimeAuthenticationService.ControlledTarget("runtime-a", "key-a", REGISTRATION);
        var proof = new InstalledSkillResolver.Proof("installation-a", 3,
                "archive-maintainer", "1.0.0", SHA);
        when(identities.lockPersistedIdentityForBinding("0", "client-a", "owner-a", 7, "agent-a"))
                .thenReturn(identity);
        when(identities.requireActiveIdentityForBinding("0", "client-a", "owner-a", 7, "agent-a"))
                .thenReturn(identity);
        when(runtimes.findByAgentIdForUpdate("agent-a")).thenReturn(runtime);
        when(authentication.requireControlledTarget("0", "client-a", "owner-a", "agent-a", 7,
                ArchiveAgentExecutionPort.REQUIRED_PROTOCOL)).thenReturn(target);
        when(skills.resolve(any())).thenReturn(new InstalledSkillResolver.Resolution(
                InstalledSkillResolver.State.VERIFIED, proof));
        when(writers.getIfAvailable()).thenReturn(writer);
        AtomicReference<AgentCommandDraft> written = new AtomicReference<>();
        AtomicReference<String> deliveryStatus = new AtomicReference<>("PENDING");
        when(writer.write(any())).thenAnswer(call -> {
            AgentCommandDraft draft = call.getArgument(0);
            written.set(draft);
            return new AgentCommandTransportWriteResult(1, draft.commandId(), "message-a", "event-a", false);
        });
        when(deliveries.lockDelivery(eq("0"), eq("client-a"), eq("owner-a"), anyString()))
                .thenAnswer(call -> delivery(written.get(), call.getArgument(3))
                        .setStatus(deliveryStatus.get()));
        var adapter = new ArchiveAgentExecutionAdapter(identities, runtimes, authentication, skills,
                writers, deliveries, sessions, manager,
                Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC));
        ArchiveAgentExecutionPort.Request request = request();

        assertEquals("ARCHIVE_EXECUTION_TRANSACTION_REQUIRED", assertThrows(
                ArchiveAgentExecutionPort.Denied.class, () -> adapter.lockIdentityRoot(request.target())).code());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        ArchiveAgentExecutionPort.LockedIdentityRoot root = adapter.lockIdentityRoot(request.target());
        ArchiveAgentExecutionPort.LockedTarget lockedTarget = adapter.requireControlledTarget(request.target(), root);
        ArchiveAgentExecutionPort.Grant grant = adapter.ensureExecution(request, lockedTarget);
        assertEquals("runtime-a", grant.runtimeInstanceId());
        assertEquals("installation-a", grant.skillProof().installationRef());
        ArchiveAgentExecutionPort.Expected expected = expected(request, grant, grant.activeAttempt());
        deliveryStatus.set("CONSUMED");
        assertEquals("message-a", adapter.inspectDispatch(expected, lockedTarget).activeMessageId());
        assertEquals("ARCHIVE_EXECUTION_DELIVERY_FENCED", assertThrows(
                ArchiveAgentExecutionPort.Denied.class,
                () -> adapter.inspectExecution(expected, lockedTarget)).code());
        for (String preDispatchState : new String[] { "PENDING", "PUBLISHED", "DEAD" }) {
            deliveryStatus.set(preDispatchState);
            assertEquals("ARCHIVE_EXECUTION_DELIVERY_FENCED", assertThrows(
                    ArchiveAgentExecutionPort.Denied.class,
                    () -> adapter.inspectDispatch(expected, lockedTarget)).code());
        }
        deliveryStatus.set("CONSUMED");
        ArchiveAgentExecutionPort.Expected staleAttempt = expected(request, grant, grant.activeAttempt() + 1);
        assertEquals("ARCHIVE_EXECUTION_DELIVERY_FENCED", assertThrows(
                ArchiveAgentExecutionPort.Denied.class,
                () -> adapter.inspectDispatch(staleAttempt, lockedTarget)).code());
        deliveryStatus.set("STARTED");
        assertEquals("message-a", adapter.inspectExecution(expected, lockedTarget).activeMessageId());
        deliveryStatus.set("FAILED");
        assertEquals("FAILED", adapter.inspectResult(expected, lockedTarget).deliveryState());
        assertEquals("ARCHIVE_EXECUTION_DELIVERY_FENCED", assertThrows(
                ArchiveAgentExecutionPort.Denied.class,
                () -> adapter.inspectExecution(expected, lockedTarget)).code());
        byte[] rotatedRegistration = REGISTRATION.clone();
        rotatedRegistration[0] = 1;
        ArchiveAgentExecutionPort.LockedTarget rotated = new ArchiveAgentExecutionPort.LockedTarget(
                "0", "client-a", "owner-a", "agent-a", 7, "runtime-a", rotatedRegistration);
        assertEquals("ARCHIVE_EXECUTION_TARGET_FENCED", assertThrows(
                ArchiveAgentExecutionPort.Denied.class,
                () -> adapter.inspectResult(expected, rotated)).code());
        var lateAdapter = new ArchiveAgentExecutionAdapter(identities, runtimes, authentication, skills,
                writers, deliveries, sessions, manager,
                Clock.fixed(Instant.ofEpochMilli(expected.expiresAt() + 1), ZoneOffset.UTC));
        deliveryStatus.set("STARTED");
        assertEquals("ARCHIVE_EXECUTION_EXPIRED", assertThrows(
                ArchiveAgentExecutionPort.Denied.class,
                () -> lateAdapter.inspectExecution(expected, lockedTarget)).code());
        deliveryStatus.set("FAILED");
        assertEquals("FAILED", lateAdapter.inspectResult(expected, lockedTarget).deliveryState());
        AgentArchiveMaintenancePayload payload = (AgentArchiveMaintenancePayload) written.get().payload();
        assertEquals("grant-a", payload.grantRef());
        assertEquals("execution-a", payload.executionRef());
        assertEquals("dispatch-a", payload.dispatchKey());
        assertEquals("3", payload.managerAuthorizationRevision());
        assertEquals("installation-a", payload.skillInstallationId());
        verify(identities).lockPersistedIdentityForBinding(
                "0", "client-a", "owner-a", 7, "agent-a");

        when(skills.resolve(any())).thenReturn(new InstalledSkillResolver.Resolution(
                InstalledSkillResolver.State.PENDING, proof));
        assertEquals("ARCHIVE_EXECUTION_SKILL_NOT_VERIFIED", assertThrows(
                ArchiveAgentExecutionPort.Denied.class,
                () -> adapter.ensureExecution(request, lockedTarget)).code());
    }

    @Test
    void readinessMapsExactAuthenticatedSessionFactsWithoutLocksWritesOrTransport() {
        AgentIdentityService identities = mock(AgentIdentityService.class);
        AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
        AgentRuntimeAuthenticationService authentication = mock(AgentRuntimeAuthenticationService.class);
        InstalledSkillResolver skills = mock(InstalledSkillResolver.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentCommandTransportWriter> writers = mock(ObjectProvider.class);
        AgentCommandTransportDao deliveries = mock(AgentCommandTransportDao.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentManagedSessionLookup> sessions = mock(ObjectProvider.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        var adapter = new ArchiveAgentExecutionAdapter(identities, runtimes, authentication, skills,
                writers, deliveries, sessions, manager,
                Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC));
        ArchiveAgentExecutionPort.TargetRequest target = request().target();
        var controlled = new AgentRuntimeAuthenticationService.ControlledTarget(
                "runtime-a", "key-a", REGISTRATION);

        when(authentication.inspectControlledTarget("0", "client-a", "owner-a", "agent-a", 7,
                ArchiveAgentExecutionPort.REQUIRED_PROTOCOL)).thenReturn(
                        AgentRuntimeAuthenticationService.ControlledReadiness.ready(controlled));
        assertEquals(ArchiveAgentExecutionPort.Readiness.ready(), adapter.observeReadiness(target));

        for (var state : new Object[][] {
                { AgentRuntimeAuthenticationService.ControlledReadiness.offline(), "AGENT_OFFLINE" },
                { AgentRuntimeAuthenticationService.ControlledReadiness.bindingChanged(), "BINDING_CHANGED" },
                { AgentRuntimeAuthenticationService.ControlledReadiness.clientUpdateRequired(), "CLIENT_UPDATE_REQUIRED" },
                { AgentRuntimeAuthenticationService.ControlledReadiness.authenticationChanged(), "AUTHENTICATION_CHANGED" }
        }) {
            when(authentication.inspectControlledTarget("0", "client-a", "owner-a", "agent-a", 7,
                    ArchiveAgentExecutionPort.REQUIRED_PROTOCOL)).thenReturn(
                            (AgentRuntimeAuthenticationService.ControlledReadiness) state[0]);
            assertEquals(ArchiveAgentExecutionPort.Readiness.blocked((String) state[1]),
                    adapter.observeReadiness(target));
        }
        verifyNoInteractions(identities, runtimes, skills, writers, deliveries, sessions, manager);
    }

    @Test
    void persistedRootLockSurvivesOfflineRuntimeButPositiveControlledAdmissionStillFailsClosed() {
        AgentIdentityService identities = mock(AgentIdentityService.class);
        AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
        AgentRuntimeAuthenticationService authentication = mock(AgentRuntimeAuthenticationService.class);
        InstalledSkillResolver skills = mock(InstalledSkillResolver.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentCommandTransportWriter> writers = mock(ObjectProvider.class);
        AgentCommandTransportDao deliveries = mock(AgentCommandTransportDao.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentManagedSessionLookup> sessions = mock(ObjectProvider.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        var identity = new AgentIdentityRegistryEntity().setCanonicalAgentId("agent-a");
        when(identities.lockPersistedIdentityForBinding("0", "client-a", "owner-a", 7, "agent-a"))
                .thenReturn(identity);
        when(runtimes.findByAgentIdForUpdate("agent-a")).thenReturn(null);
        when(identities.requireActiveIdentityForBinding("0", "client-a", "owner-a", 7, "agent-a"))
                .thenThrow(new IllegalArgumentException("offline"));
        var adapter = new ArchiveAgentExecutionAdapter(identities, runtimes, authentication, skills,
                writers, deliveries, sessions, manager,
                Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC));
        TransactionSynchronizationManager.setActualTransactionActive(true);

        ArchiveAgentExecutionPort.LockedIdentityRoot root = adapter.lockIdentityRoot(request().target());
        assertEquals("agent-a", root.canonicalAgent());
        assertEquals("ARCHIVE_EXECUTION_TARGET_FENCED", assertThrows(
                ArchiveAgentExecutionPort.Denied.class,
                () -> adapter.requireControlledTarget(request().target(), root)).code());
        verifyNoInteractions(authentication);
    }

    @Test
    void historicalRetiredBindingCanLockMovedRuntimeRootButCannotPassPositiveOrForeignAdmission() {
        AgentIdentityService identities = mock(AgentIdentityService.class);
        AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
        AgentRuntimeAuthenticationService authentication = mock(AgentRuntimeAuthenticationService.class);
        InstalledSkillResolver skills = mock(InstalledSkillResolver.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentCommandTransportWriter> writers = mock(ObjectProvider.class);
        AgentCommandTransportDao deliveries = mock(AgentCommandTransportDao.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentManagedSessionLookup> sessions = mock(ObjectProvider.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        var historicalIdentity = new AgentIdentityRegistryEntity().setCanonicalAgentId("agent-a");
        var movedRuntime = new AgentRuntimeEntity().setAgentId("agent-a")
                .setOwnerJiacn("owner-b").setBindingId(8L);
        movedRuntime.setTenantId("0");
        movedRuntime.setClientId("client-b");
        when(identities.lockPersistedIdentityForBinding("0", "client-a", "owner-a", 7, "agent-a"))
                .thenReturn(historicalIdentity);
        when(identities.requireActiveIdentityForBinding("0", "client-a", "owner-a", 7, "agent-a"))
                .thenReturn(historicalIdentity);
        when(identities.lockPersistedIdentityForBinding(
                "0", "client-a", "owner-foreign", 7, "agent-a"))
                .thenReturn(null);
        when(runtimes.findByAgentIdForUpdate("agent-a")).thenReturn(movedRuntime);
        var adapter = new ArchiveAgentExecutionAdapter(identities, runtimes, authentication, skills,
                writers, deliveries, sessions, manager,
                Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC));
        TransactionSynchronizationManager.setActualTransactionActive(true);

        ArchiveAgentExecutionPort.TargetRequest historical = request().target();
        ArchiveAgentExecutionPort.LockedIdentityRoot root = adapter.lockIdentityRoot(historical);
        assertEquals(7, root.binding());
        assertEquals("ARCHIVE_EXECUTION_TARGET_FENCED", assertThrows(
                ArchiveAgentExecutionPort.Denied.class,
                () -> adapter.requireControlledTarget(historical, root)).code());

        ArchiveAgentExecutionPort.TargetRequest foreign = new ArchiveAgentExecutionPort.TargetRequest(
                "0", "client-a", "owner-foreign", "agent-a", 7);
        assertEquals("ARCHIVE_EXECUTION_TARGET_FENCED", assertThrows(
                ArchiveAgentExecutionPort.Denied.class,
                () -> adapter.lockIdentityRoot(foreign)).code());
        verify(runtimes, times(2)).findByAgentIdForUpdate("agent-a");
        verifyNoInteractions(authentication);
    }
    @Test
    void springSelectsAnnotatedProductionConstructorWhenFeatureEnabled() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("archive.maintenance.execution-enabled", "true")));
            context.getBeanFactory().registerSingleton("agentIdentityService", mock(AgentIdentityService.class));
            context.getBeanFactory().registerSingleton("agentRuntimeDao", mock(AgentRuntimeDao.class));
            context.getBeanFactory().registerSingleton("agentRuntimeAuthenticationService",
                    mock(AgentRuntimeAuthenticationService.class));
            context.getBeanFactory().registerSingleton("installedSkillResolver", mock(InstalledSkillResolver.class));
            context.getBeanFactory().registerSingleton("agentCommandTransportDao", mock(AgentCommandTransportDao.class));
            context.getBeanFactory().registerSingleton("platformTransactionManager",
                    mock(PlatformTransactionManager.class));
            context.register(ArchiveAgentExecutionAdapter.class);
            context.refresh();
            assertNotNull(context.getBean(ArchiveAgentExecutionPort.class));
        }
        long annotated = java.util.Arrays.stream(ArchiveAgentExecutionAdapter.class.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(
                        org.springframework.beans.factory.annotation.Autowired.class)).count();
        assertEquals(1, annotated);
    }

    private static ArchiveAgentExecutionPort.Expected expected(
            ArchiveAgentExecutionPort.Request request, ArchiveAgentExecutionPort.Grant grant, int attempt) {
        return new ArchiveAgentExecutionPort.Expected(
                request.tenant(), request.client(), request.owner(), request.canonicalAgent(),
                request.binding(), request.jobId(), request.runId(), request.appointmentId(),
                request.appointmentRevision(), request.managerAuthorizationRevision(),
                request.grantRef(), request.executionRef(), request.executionEpoch(),
                request.dispatchKey(), grant.commandId(), attempt, grant.expiresAt(),
                grant.runtimeInstanceId(), grant.registrationHash(), request.skillOrigin(),
                grant.skillProof(), request.contextRef());
    }

    private static ArchiveAgentExecutionPort.Request request() {
        return new ArchiveAgentExecutionPort.Request("0", "client-a", "owner-a", "agent-a", 7,
                "job-a", "run-a", "appointment-a", 2, 3, "grant-a", "execution-a", 1,
                "dispatch-a", InstalledSkillResolver.Origin.PLATFORM_PROVISIONED,
                "archive-maintainer", "1.0.0", SHA,
                "/internal/archive/v1/jobs/job-a/runs/run-a/context");
    }

    private static AgentCommandDeliveryEntity delivery(AgentCommandDraft draft, String commandId) {
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft);
        return new AgentCommandDeliveryEntity().setCommandId(commandId).setOwnerJiacn("owner-a")
                .setTaskId("job-a").setWorkItemId(null).setTargetAgentId("agent-a")
                .setCommandType(ArchiveAgentExecutionPort.COMMAND_TYPE).setCommandPayload(business)
                .setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(business)).setStatus("PENDING")
                .setActiveMessageId("message-a").setActiveAttempt(1).setExpiresAt(draft.expiresAt());
    }
}
