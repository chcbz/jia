package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.service.AgentTaskCreationOperationService;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.HallRequestDraftService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.task.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HallTaskCreationServiceImplTest {
    private static final HallRequestDraftService.OwnerScope SCOPE = new HallRequestDraftService.OwnerScope("0", "c", "o");
    private AgentTaskCreationOperationService creations;
    private AgentTaskMetaDao tasks;
    private TaskService plans;
    private ObjectProvider<TaskService> provider;
    private HallTaskCreationServiceImpl service;
    @BeforeEach @SuppressWarnings("unchecked") void setUp() {
        creations = mock(AgentTaskCreationOperationService.class); tasks = mock(AgentTaskMetaDao.class); plans = mock(TaskService.class);
        provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(plans);
        service = new HallTaskCreationServiceImpl(creations, tasks, provider);
        identity("o", "c");
        TransactionSynchronizationManager.setActualTransactionActive(true); // unit marker only, real DB test is separate
        var task = new AgentTaskDTO(); task.setId("42"); when(creations.create(any(), anyString(), any())).thenAnswer(i -> new AgentTaskCreationOperationService.Result(
                new AgentTaskCreationOperationService.Receipt(2, "op", "42", 1L, "COMMITTED",
                        i.<AgentTaskCreationOperationService.CreateCommand>getArgument(2).inputRefs(), task), false));
        var root = new AgentTaskMetaEntity(); root.setTaskId("42"); root.setTaskVersion(0L);
        root.setTenantId("0"); root.setClientId("c"); root.setOwnerJiacn("o");
        when(tasks.findByTaskIdInOwnerScope("0", "c", "o", "42")).thenReturn(root);
        when(plans.get(42L)).thenReturn(plan("o"));
    }
    @AfterEach void clear() {
        TransactionSynchronizationManager.clear(); SecurityContextHolder.clearContext();
        EsContextHolder.setContext(new EsContext());
    }
    @Test void onlyRealUnfundedUnassignedCreationIsMappedAndReconciliationNeverCreates() {
        assertEquals(new HallRequestDraftService.TaskReference("42", "0"), service.create(SCOPE, "hall-task-key", "title", "body", List.of()));
        assertEquals(new HallRequestDraftService.TaskReference("42", "0"), service.get(SCOPE, "42"));
        var command = ArgumentCaptor.forClass(AgentTaskCreationOperationService.CreateCommand.class);
        verify(creations).create(eq(new AgentTaskCreationOperationService.Scope("0", "c", "o")), eq("hall-task-key"), command.capture());
        assertEquals(2, command.getValue().schemaVersion());
        assertEquals("title", command.getValue().title()); assertEquals("body", command.getValue().description());
        assertNull(command.getValue().reward()); assertTrue(command.getValue().inputRefs().isEmpty());
    }
    @Test void noTransactionOrTaskServiceCannotFallBackToMetadataOnlyCreation() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertReason(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE, () -> service.create(SCOPE, "hall-task-key", "title", "body", List.of()));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(provider.getIfAvailable()).thenReturn(null);
        assertReason(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE, () -> service.create(SCOPE, "hall-task-key", "title", "body", List.of()));
        verifyNoInteractions(creations);
    }
    @Test void identityContextMismatchIsRejectedBeforeAnyTaskMutation() {
        identity("foreign", "c");
        assertReason(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, () -> service.create(SCOPE, "hall-task-key", "title", "body", List.of()));
        identity("o", "c"); EsContextHolder.setContext(new EsContext());
        assertReason(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, () -> service.create(SCOPE, "hall-task-key", "title", "body", List.of()));
        verifyNoInteractions(creations);
    }
    @Test void fullRequirementTextAndFixedMaterialsUseV2WhilePlanAclRemainsRequired() {
        var inputs = List.of(new HallRequestDraftService.InputSelection("file-1", 7));
        assertEquals("42", service.create(SCOPE, "hall-task-long", "t".repeat(200), "b".repeat(201), inputs).taskId());
        var command = ArgumentCaptor.forClass(AgentTaskCreationOperationService.CreateCommand.class);
        verify(creations).create(any(), eq("hall-task-long"), command.capture());
        assertEquals("t".repeat(200), command.getValue().title());
        assertEquals("b".repeat(201), command.getValue().description());
        assertEquals(List.of(new AgentTaskCreationOperationService.InputReference("file-1", 7, "INPUT")), command.getValue().inputRefs());
        when(plans.get(42L)).thenReturn(plan("foreign"));
        assertReason(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE, () -> service.create(SCOPE, "hall-task-key", "title", "body", List.of()));
        when(plans.get(42L)).thenReturn(null);
        assertReason(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE, () -> service.get(SCOPE, "42"));
    }
    @Test void revokedTaskAclIs404AndDoesNotReadPlan() {
        when(tasks.findByTaskIdInOwnerScope("0", "c", "o", "42")).thenReturn(null);
        assertReason(HallRequestDraftService.Reason.NOT_FOUND, () -> service.get(SCOPE, "42"));
        verifyNoInteractions(plans, creations);
    }
    @Test void unauthenticatedJwtCannotCreateOrReconcileEvenWithMatchingOwnerContext() {
        SecurityContextHolder.getContext().getAuthentication().setAuthenticated(false);
        assertReason(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE,
                () -> service.create(SCOPE, "hall-task-key", "title", "body", List.of()));
        assertReason(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE,
                () -> service.get(SCOPE, "42"));
        verifyNoInteractions(creations, tasks, plans, provider);
    }
    private static TaskPlanEntity plan(String owner) {
        TaskPlanEntity plan = new TaskPlanEntity().setId(42L).setJiacn(owner).setName("title").setDescription("body");
        plan.setClientId("c"); plan.setTenantId("0"); return plan;
    }
    private static void identity(String owner, String client) {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(Jwt.withTokenValue("fixture").claim("tenant_id", "0")
                .header("alg", "none").subject(owner).claim("jiacn", owner).claim("client_id", client)
                .issuedAt(Instant.ofEpochSecond(1)).expiresAt(Instant.ofEpochSecond(2)).build());
        authentication.setAuthenticated(true); // fixture models the post-authentication principal
        SecurityContextHolder.getContext().setAuthentication(authentication);
        EsContext context = new EsContext(); context.setJiacn(owner); context.setClientId(client); EsContextHolder.setContext(context);
    }
    private static void assertReason(HallRequestDraftService.Reason reason, Runnable call) {
        assertEquals(reason, assertThrows(HallRequestDraftService.Failure.class, call::run).reason());
    }
}
