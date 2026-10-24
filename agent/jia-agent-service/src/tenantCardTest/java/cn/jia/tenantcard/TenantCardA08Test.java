package cn.jia.tenantcard;

import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskNoteEntity;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.agent.service.AgentScopePublicationCoordinator;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.impl.AgentLegacyTaskCompatibilityService;
import cn.jia.agent.service.impl.AgentServiceImpl;
import cn.jia.agent.service.impl.AgentTaskMutationTransactionImpl;
import cn.jia.agent.config.AgentSceneFeatureFlags;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.task.mapper.TaskPlanMapper;
import cn.jia.task.service.TaskService;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantCardA08Test {
    private static final String TENANT = "1";
    private static final String CLIENT = "client-1";
    private static final String OWNER = "owner-1";

    @BeforeEach
    void setUpContext() {
        EsContext context = new EsContext();
        context.setClientId(CLIENT);
        context.setJiacn(OWNER);
        EsContextHolder.setContext(context);
    }

    @AfterEach
    void clearContext() {
        TrustedTenantContext.clear();
        EsContextHolder.clearContext();
    }

    @Test
    void tenantOneOwnerTransactionLocksExactScopedRoot() {
        AgentTaskMetaDao dao = mock(AgentTaskMetaDao.class);
        AgentTaskMetaEntity root = root("1");
        when(dao.findByTaskIdForUpdateInOwnerScope(
                "1", "client-1", "owner-1", "task-1")).thenReturn(root);
        AgentTaskMutationTransaction transaction =
                new AgentTaskMutationTransactionImpl(dao, transactionManager());

        String result = transaction.executeWithLockedTaskRootInOwnerScope(
                "1", "client-1", "owner-1", "task-1", ignored -> "ok");

        assertEquals("ok", result);
        verify(dao).findByTaskIdForUpdateInOwnerScope(
                "1", "client-1", "owner-1", "task-1");
    }

    @Test
    void tenantOneTransactionRejectsTenantZeroRootAndMalformedTenant() {
        AgentTaskMetaDao dao = mock(AgentTaskMetaDao.class);
        when(dao.findByTaskIdForUpdateInOwnerScope(
                "1", "client-1", "owner-1", "task-1")).thenReturn(root("0"));
        AgentTaskMutationTransaction transaction =
                new AgentTaskMutationTransactionImpl(dao, transactionManager());

        AgentTaskCollaborationException drift = assertThrows(
                AgentTaskCollaborationException.class,
                () -> transaction.executeWithLockedTaskRootInOwnerScope(
                        "1", "client-1", "owner-1", "task-1", ignored -> "bad"));
        assertEquals(AgentTaskCollaborationException.Reason.INVALID_PERSISTED_STATE,
                drift.getReason());
        assertThrows(IllegalArgumentException.class,
                () -> transaction.executeWithLockedTaskRootInOwnerScope(
                        "01", "client-1", "owner-1", "task-1", ignored -> "bad"));
    }

    @Test
    void ordinaryTaskPlanInheritsTrustedTenantClientAndOwner() throws Exception {
        AgentServiceImpl service = fixture().service();
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle("tenant one task");
        request.setDescription("scoped description");
        BusinessScope scope = new BusinessScope(
                TenantId.of("1"), DataDomainId.of("cyf"), "owner-1", "client-1");
        Method method = AgentServiceImpl.class.getDeclaredMethod(
                "taskPlanFor", AgentTaskCreateDTO.class, BusinessScope.class);
        method.setAccessible(true);

        TaskPlanEntity plan = (TaskPlanEntity) method.invoke(service, request, scope);

        assertEquals("1", plan.getTenantId());
        assertEquals("client-1", plan.getClientId());
        assertEquals("owner-1", plan.getJiacn());
    }

    @Test
    void numericTaskPlanLookupUsesCompleteExactRootScope() {
        Fixture fixture = fixture();
        AgentTaskMetaEntity root = root(TENANT);
        root.setTaskId("42");
        when(fixture.taskMetaDao().findByTaskIdInOwnerScope(TENANT, CLIENT, OWNER, "42"))
                .thenReturn(root);
        TaskPlanEntity plan = plan(42L, TENANT, CLIENT, OWNER);
        plan.setName("tenant one title");
        plan.setDescription("tenant one description");
        plan.setAmount(BigDecimal.valueOf(73));
        when(fixture.taskService().findExactInOwnerScope(42L, TENANT, CLIENT, OWNER))
                .thenReturn(plan);

        AgentTaskDTO task = inTenantScope(() -> fixture.service().getTask("42"));

        assertEquals("tenant one title", task.getTitle());
        assertEquals("tenant one description", task.getDescription());
        assertEquals(73, task.getReward());
        verify(fixture.taskService()).findExactInOwnerScope(42L, TENANT, CLIENT, OWNER);
        verify(fixture.taskService(), never()).get(anyLong());
    }

    @Test
    void taskPlanProjectionDriftIsRejectedBeforeAnyFieldsAreCopied() {
        for (Consumer<TaskPlanEntity> drift : List.<Consumer<TaskPlanEntity>>of(
                plan -> plan.setId(43L),
                plan -> plan.setTenantId("0"),
                plan -> plan.setClientId("Client-1"),
                plan -> plan.setJiacn("owner-1 "),
                plan -> plan.setJiacn("owner-1\0"))) {
            Fixture fixture = fixture();
            AgentTaskMetaEntity root = root(TENANT);
            root.setTaskId("42");
            when(fixture.taskMetaDao().findByTaskIdInOwnerScope(TENANT, CLIENT, OWNER, "42"))
                    .thenReturn(root);
            TaskPlanEntity plan = plan(42L, TENANT, CLIENT, OWNER);
            plan.setName("foreign title");
            plan.setDescription("foreign description");
            plan.setAmount(BigDecimal.valueOf(999));
            drift.accept(plan);
            when(fixture.taskService().findExactInOwnerScope(42L, TENANT, CLIENT, OWNER))
                    .thenReturn(plan);

            assertThrows(IllegalArgumentException.class,
                    () -> inTenantScope(() -> fixture.service().getTask("42")));
        }
    }

    @Test
    void externalStringTaskIdSkipsPlanLookupAndKeepsCompatibilityTitle() {
        Fixture fixture = fixture();
        AgentTaskMetaEntity root = root(TENANT);
        root.setTaskId("external-ABC");
        when(fixture.taskMetaDao().findByTaskIdInOwnerScope(
                TENANT, CLIENT, OWNER, "external-ABC")).thenReturn(root);

        AgentTaskDTO task = inTenantScope(() -> fixture.service().getTask("external-ABC"));

        assertEquals("external-ABC", task.getTitle());
        verifyNoInteractions(fixture.taskService());
    }

    @Test
    void scopedTaskNotesMapOnlyExactRows() {
        Fixture fixture = fixture();
        AgentTaskMetaEntity root = root(TENANT);
        when(fixture.taskMetaDao().findByTaskIdInOwnerScope(
                TENANT, CLIENT, OWNER, "task-1")).thenReturn(root);
        AgentTaskNoteEntity note = note(TENANT, CLIENT, OWNER, "task-1");
        note.setContent("tenant-one-note");
        when(fixture.taskNoteDao().findByTaskIdInOwnerScope(
                TENANT, CLIENT, OWNER, "task-1")).thenReturn(List.of(note));

        var notes = inTenantScope(() -> fixture.service().listTaskNotes("task-1"));

        assertEquals("tenant-one-note", notes.getFirst().getContent());
    }

    @Test
    void taskNoteProjectionRejectsForeignCaseTrailingAndControlVariants() {
        List<Consumer<AgentTaskNoteEntity>> drifts = List.of(
                note -> note.setTenantId("0"),
                note -> note.setClientId("client-2"),
                note -> note.setOwnerJiacn("owner-2"),
                note -> note.setTaskId("task-2"),
                note -> note.setClientId("Client-1"),
                note -> note.setOwnerJiacn("owner-1 "),
                note -> note.setTaskId("task-1\0"));
        for (Consumer<AgentTaskNoteEntity> drift : drifts) {
            Fixture fixture = fixture();
            when(fixture.taskMetaDao().findByTaskIdInOwnerScope(
                    TENANT, CLIENT, OWNER, "task-1")).thenReturn(root(TENANT));
            AgentTaskNoteEntity note = note(TENANT, CLIENT, OWNER, "task-1");
            drift.accept(note);
            when(fixture.taskNoteDao().findByTaskIdInOwnerScope(
                    TENANT, CLIENT, OWNER, "task-1")).thenReturn(List.of(note));

            assertThrows(IllegalArgumentException.class,
                    () -> inTenantScope(() -> fixture.service().listTaskNotes("task-1")));
        }
    }

    @Test
    void taskPlanMapperQueryIsByteExactForTenantClientAndOwner() throws Exception {
        Method method = TaskPlanMapper.class.getDeclaredMethod(
                "findExactInOwnerScope", Long.class, String.class, String.class, String.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertTrue(sql.contains("id = #{id}"), sql);
        for (String column : List.of("tenant_id", "client_id", "jiacn")) {
            assertTrue(sql.contains("CAST(" + column + " AS BINARY)"), sql);
            assertTrue(sql.contains("OCTET_LENGTH(" + column + ")"), sql);
        }
    }

    private static AgentTaskMetaEntity root(String tenant) {
        AgentTaskMetaEntity root = new AgentTaskMetaEntity();
        root.setTenantId(tenant);
        root.setClientId("client-1");
        root.setOwnerJiacn("owner-1");
        root.setTaskId("task-1");
        root.setTaskVersion(0L);
        root.setCurrentEventVersion(0L);
        return root;
    }

    private static TaskPlanEntity plan(
            Long id, String tenant, String client, String owner) {
        TaskPlanEntity plan = new TaskPlanEntity();
        plan.setId(id);
        plan.setTenantId(tenant);
        plan.setClientId(client);
        plan.setJiacn(owner);
        return plan;
    }

    private static AgentTaskNoteEntity note(
            String tenant, String client, String owner, String taskId) {
        AgentTaskNoteEntity note = new AgentTaskNoteEntity();
        note.setTenantId(tenant);
        note.setClientId(client);
        note.setOwnerJiacn(owner);
        note.setTaskId(taskId);
        return note;
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };
    }

    private static Fixture fixture() {
        AgentTaskMetaDao taskMetaDao = mock(AgentTaskMetaDao.class);
        AgentTaskNoteDao taskNoteDao = mock(AgentTaskNoteDao.class);
        TaskService taskService = mock(TaskService.class);
        ObjectProvider<TaskService> taskServiceProvider = provider();
        when(taskServiceProvider.getIfAvailable()).thenReturn(taskService);
        AgentServiceImpl service = new AgentServiceImpl(
                mock(AgentRuntimeDao.class), mock(AgentIdentityService.class),
                mock(AgentPersonaDao.class), mock(AgentPersonaBindingDao.class),
                taskMetaDao, mock(AgentTaskMemberDao.class),
                mock(AgentLegacyTaskCompatibilityService.class), taskNoteDao,
                mock(DialogueTemplateDao.class), provider(), taskServiceProvider, provider(), provider(),
                new AgentScopePublicationCoordinator(), new AgentSceneFeatureFlags(true, true),
                mock(AgentTaskMutationTransaction.class), mock(AgentTaskEventWriter.class));
        return new Fixture(service, taskMetaDao, taskNoteDao, taskService);
    }

    private static <T> T inTenantScope(Supplier<T> work) {
        return TrustedTenantContext.withScope(new BusinessScope(
                TenantId.of(TENANT), DataDomainId.of("cyf"), OWNER, CLIENT), work);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }

    private record Fixture(
            AgentServiceImpl service,
            AgentTaskMetaDao taskMetaDao,
            AgentTaskNoteDao taskNoteDao,
            TaskService taskService) {
    }
}
