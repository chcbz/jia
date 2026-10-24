package cn.jia.tenantcard;

import cn.jia.agent.api.AgentTaskCancellationController;
import cn.jia.agent.dao.AgentTaskCancellationDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentTaskExecutionGrantDaoImpl;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskStateTransitionDTO;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.mapper.AgentTaskExecutionGrantMapper;
import cn.jia.agent.service.AgentTaskCancellationService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentWorkItemDependencyService;
import cn.jia.agent.service.impl.AgentTaskCancellationServiceImpl;
import cn.jia.agent.service.impl.AgentTaskStateServiceImpl;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantCardA11Test {
    private static final String TENANT = "1";
    private static final String CLIENT = "client-1";
    private static final String OWNER = "owner-1";
    private static final String ACTOR = "owner-subject-1";
    private static final String TASK = "task-1";
    private static final BusinessScope AUTHORITY = new BusinessScope(
            TenantId.of(TENANT), DataDomainId.of("cyf"), OWNER, CLIENT);

    @AfterEach
    void clearScope() {
        TrustedTenantContext.clear();
    }

    @Test
    void tenantOneControllerCancelsAndReplaysThroughRealServices() {
        Fixture fixture = fixture(root(TENANT, "open", 4L));
        AgentTaskCancellationController controller =
                new AgentTaskCancellationController(fixture.service());

        var first = TrustedTenantContext.withScope(AUTHORITY,
                () -> controller.cancel(TASK, request(4L), authentication(
                        TENANT, CLIENT, OWNER, null, ACTOR)));
        var replay = TrustedTenantContext.withScope(AUTHORITY,
                () -> controller.cancel(TASK, request(4L), authentication(
                        TENANT, CLIENT, OWNER, "cyf", ACTOR)));

        assertEquals("cancelled", first.getBody().status());
        assertEquals(5L, first.getBody().taskVersion());
        assertEquals(first.getBody(), replay.getBody());
        verify(fixture.meta()).updateStatusByVersionInOwnerScope(
                TENANT, CLIENT, OWNER, TASK, 4L, "cancelled", null, null, null);
        verify(fixture.cancellation()).releaseTaskOccupation(
                org.mockito.ArgumentMatchers.eq(TENANT),
                org.mockito.ArgumentMatchers.eq(CLIENT),
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(TASK), anyLong());
    }

    @Test
    void controllerRejectsMissingContextAndEveryScopeMismatchBeforeService() {
        AgentTaskCancellationService service = mock(AgentTaskCancellationService.class);
        AgentTaskCancellationController controller = new AgentTaskCancellationController(service);

        assertThrows(RuntimeException.class, () -> controller.cancel(TASK, request(4L),
                authentication(TENANT, CLIENT, OWNER, null, ACTOR)));
        for (JwtAuthenticationToken authentication : List.of(
                authentication("0", CLIENT, OWNER, "cyf", ACTOR),
                authentication("01", CLIENT, OWNER, "cyf", ACTOR),
                authentication(TENANT, "client-2", OWNER, "cyf", ACTOR),
                authentication(TENANT, CLIENT, "owner-2", "cyf", ACTOR),
                authentication(TENANT, CLIENT, OWNER, "chat", ACTOR),
                authenticationWithNameMismatch(TENANT, CLIENT, OWNER, "cyf", ACTOR, "foreign-subject"))) {
            assertThrows(RuntimeException.class, () -> TrustedTenantContext.withScope(AUTHORITY,
                    () -> controller.cancel(TASK, request(4L), authentication)));
        }
        verifyNoInteractions(service);
    }

    @Test
    void tenantOneStateCancellationDoesNotResolveDependencies() {
        AgentTaskMetaEntity root = root(TENANT, "open", 2L);
        AgentTaskMetaDao meta = mock(AgentTaskMetaDao.class);
        AgentTaskWorkItemDao workItems = mock(AgentTaskWorkItemDao.class);
        AgentWorkItemDependencyService dependencies = mock(AgentWorkItemDependencyService.class);
        AgentTaskWorkItemEntity item = item(TENANT, "pending", 7L);
        when(workItems.findByWorkItemId(TENANT, CLIENT, OWNER, "item-1")).thenReturn(item);
        when(workItems.updateByVersion(anyString(), anyString(), anyString(), anyString(),
                anyLong(), any(AgentTaskWorkItemDTO.class))).thenReturn(1);
        AgentTaskStateServiceImpl states = new AgentTaskStateServiceImpl(
                meta, mock(AgentTaskMemberDao.class), workItems, new LocalTransactions(root),
                mock(AgentTaskEventWriter.class), dependencies);
        AgentTaskStateTransitionDTO transition = new AgentTaskStateTransitionDTO();
        transition.setTargetStatus("cancelled");
        transition.setExpectedVersion(7L);

        var result = states.transitionWorkItem(
                TENANT, CLIENT, OWNER, "item-1", transition);

        assertEquals("cancelled", result.getStatus());
        assertEquals(8L, result.getVersion());
        verifyNoInteractions(dependencies);
    }

    @Test
    void executionGrantDaoAcceptsTenantOneAndRejectsNoncanonicalOrIncompleteScope() {
        AgentTaskExecutionGrantMapper mapper = mock(AgentTaskExecutionGrantMapper.class);
        AgentTaskExecutionGrantDaoImpl dao = new AgentTaskExecutionGrantDaoImpl(mapper);

        dao.findActiveByTask(TENANT, CLIENT, OWNER, TASK);

        verify(mapper).selectActiveByTask(TENANT, CLIENT, OWNER, TASK);
        assertThrows(IllegalArgumentException.class,
                () -> dao.findActiveByTask("01", CLIENT, OWNER, TASK));
        assertThrows(IllegalArgumentException.class,
                () -> dao.findActiveByTask(TENANT, " client-1", OWNER, TASK));
        assertThrows(IllegalArgumentException.class,
                () -> dao.findActiveByTask(TENANT, CLIENT, "0", TASK));
    }

    @Test
    void fundedAndExecutedTasksRemainUnsupported() {
        Fixture funded = fixture(root(TENANT, "open", 4L));
        when(funded.cancellation().hasMoneyFacts(TENANT, CLIENT, OWNER, TASK)).thenReturn(true);
        AgentTaskStateException money = assertThrows(AgentTaskStateException.class,
                () -> funded.service().cancel(TENANT, CLIENT, OWNER, ACTOR, TASK, 4L));
        assertEquals(AgentTaskStateException.Reason.INVALID_TRANSITION, money.getReason());

        Fixture executed = fixture(root(TENANT, "open", 4L));
        when(executed.cancellation().hasExecutionFacts(TENANT, CLIENT, OWNER, TASK))
                .thenReturn(true);
        AgentTaskStateException execution = assertThrows(AgentTaskStateException.class,
                () -> executed.service().cancel(TENANT, CLIENT, OWNER, ACTOR, TASK, 4L));
        assertEquals(AgentTaskStateException.Reason.INVALID_TRANSITION, execution.getReason());
        verify(funded.meta(), never()).updateStatusByVersionInOwnerScope(
                anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), any(), any(), any());
        verify(executed.meta(), never()).updateStatusByVersionInOwnerScope(
                anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), any(), any(), any());
    }

    @Test
    void foreignResourceProjectionAndMalformedServiceScopeFailClosed() {
        Fixture foreign = fixture(root("0", "open", 4L));
        AgentTaskStateException drift = assertThrows(AgentTaskStateException.class,
                () -> foreign.service().cancel(TENANT, CLIENT, OWNER, ACTOR, TASK, 4L));
        assertEquals(AgentTaskStateException.Reason.INVALID_PERSISTED_STATE, drift.getReason());

        Fixture malformed = fixture(root(TENANT, "open", 4L));
        for (String tenant : List.of("01", " 1", "tenant-1")) {
            AgentTaskStateException rejected = assertThrows(AgentTaskStateException.class,
                    () -> malformed.service().cancel(
                            tenant, CLIENT, OWNER, ACTOR, TASK, 4L));
            assertEquals(AgentTaskStateException.Reason.INVALID_REQUEST, rejected.getReason());
        }
    }

    private static Fixture fixture(AgentTaskMetaEntity root) {
        AgentTaskMetaDao meta = mock(AgentTaskMetaDao.class);
        AgentTaskMemberDao members = mock(AgentTaskMemberDao.class);
        AgentTaskWorkItemDao workItems = mock(AgentTaskWorkItemDao.class);
        AgentTaskCancellationDao cancellation = mock(AgentTaskCancellationDao.class);
        AgentTaskExecutionGrantMapper grantMapper = mock(AgentTaskExecutionGrantMapper.class);
        LocalTransactions transactions = new LocalTransactions(root);
        when(meta.findByTaskIdInOwnerScope(TENANT, CLIENT, OWNER, TASK)).thenReturn(root);
        when(workItems.listByTaskForUpdate(TENANT, CLIENT, OWNER, TASK, 501))
                .thenReturn(List.of());
        when(cancellation.lockMembers(TENANT, CLIENT, OWNER, TASK)).thenReturn(List.of());
        when(cancellation.lockGrants(TENANT, CLIENT, OWNER, TASK)).thenReturn(List.of());
        when(cancellation.lockBootstraps(TENANT, CLIENT, OWNER, TASK)).thenReturn(List.of());
        doAnswer(invocation -> {
            root.setRewardStatus(invocation.getArgument(5));
            root.setTaskVersion((Long) invocation.getArgument(4) + 1);
            return 1;
        }).when(meta).updateStatusByVersionInOwnerScope(
                anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), any(), any(), any());
        AgentTaskStateServiceImpl states = new AgentTaskStateServiceImpl(
                meta, members, workItems, transactions, mock(AgentTaskEventWriter.class),
                mock(AgentWorkItemDependencyService.class));
        AgentTaskCancellationServiceImpl service = new AgentTaskCancellationServiceImpl(
                transactions, states, workItems,
                new AgentTaskExecutionGrantDaoImpl(grantMapper), cancellation);
        return new Fixture(service, meta, cancellation);
    }

    private static AgentTaskMetaEntity root(String tenant, String status, long version) {
        AgentTaskMetaEntity root = new AgentTaskMetaEntity();
        root.setTenantId(tenant);
        root.setClientId(CLIENT);
        root.setOwnerJiacn(OWNER);
        root.setTaskId(TASK);
        root.setRewardStatus(status);
        root.setReward(0);
        root.setTaskVersion(version);
        root.setCurrentEventVersion(0L);
        return root;
    }

    private static AgentTaskWorkItemEntity item(String tenant, String status, long version) {
        AgentTaskWorkItemEntity item = new AgentTaskWorkItemEntity();
        item.setTenantId(tenant);
        item.setClientId(CLIENT);
        item.setOwnerJiacn(OWNER);
        item.setTaskId(TASK);
        item.setWorkItemId("item-1");
        item.setStatus(status);
        item.setAttemptCount(0);
        item.setVersion(version);
        return item;
    }

    private static MockHttpServletRequest request(long expectedVersion) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(("{\"expectedTaskVersion\":" + expectedVersion + "}")
                .getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static JwtAuthenticationToken authentication(String tenant, String client,
            String owner, String domain, String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none")
                .claim("tenant_id", tenant).claim("client_id", client).claim("jiacn", owner)
                .subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (domain != null) builder.claim("data_domain_id", domain);
        return new JwtAuthenticationToken(builder.build(), List.of());
    }

    private static JwtAuthenticationToken authenticationWithNameMismatch(
            String tenant, String client, String owner, String domain,
            String subject, String authenticationName) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none")
                .claim("tenant_id", tenant).claim("client_id", client).claim("jiacn", owner)
                .subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (domain != null) builder.claim("data_domain_id", domain);
        return new JwtAuthenticationToken(builder.build(), List.of(), authenticationName);
    }

    private record Fixture(AgentTaskCancellationServiceImpl service, AgentTaskMetaDao meta,
                           AgentTaskCancellationDao cancellation) { }

    private static final class LocalTransactions implements AgentTaskMutationTransaction {
        private final AgentTaskMetaEntity root;

        private LocalTransactions(AgentTaskMetaEntity root) {
            this.root = root;
        }

        @Override public <T> T executeWithLockedTaskRoot(String tenant, String client,
                String task, LockedTaskMutation<T> mutation) { return mutation.apply(root); }
        @Override public <T> T executeWithLockedTaskRootInOwnerScope(String tenant, String client,
                String owner, String task, LockedTaskMutation<T> mutation) {
            return mutation.apply(root);
        }
        @Override public <T> T executeWithLockedTaskRootForWorkItem(String tenant, String client,
                String item, LockedTaskMutation<T> mutation) { return mutation.apply(root); }
        @Override public <T> T executeWithLockedTaskRootForWorkItemInOwnerScope(
                String tenant, String client, String owner, String item,
                LockedTaskMutation<T> mutation) { return mutation.apply(root); }
        @Override public <T> T executeAfterTaskRootReservation(String tenant, String client,
                String task, TaskRootReservation reservation, ReservedTaskMutation<T> mutation) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> T executeAfterTaskRootReservationInOwnerScope(
                String tenant, String client, String owner, String task,
                TaskRootReservation reservation, ReservedTaskMutation<T> mutation) {
            throw new UnsupportedOperationException();
        }
    }
}
