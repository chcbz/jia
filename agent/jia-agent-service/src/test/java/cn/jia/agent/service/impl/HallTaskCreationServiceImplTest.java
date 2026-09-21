package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentService;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HallTaskCreationServiceImplTest {
    private static final HallRequestDraftService.OwnerScope SCOPE = new HallRequestDraftService.OwnerScope("0", "c", "o");
    private AgentService agents;
    private AgentTaskMetaDao tasks;
    private TaskService plans;
    private ObjectProvider<TaskService> provider;
    private HallTaskCreationServiceImpl service;
    @BeforeEach @SuppressWarnings("unchecked") void setUp() {
        agents = mock(AgentService.class); tasks = mock(AgentTaskMetaDao.class); plans = mock(TaskService.class);
        provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(plans);
        service = new HallTaskCreationServiceImpl(agents, tasks, provider);
        identity("o", "c");
        TransactionSynchronizationManager.setActualTransactionActive(true); // unit marker only, real DB test is separate
        var task = new AgentTaskDTO(); task.setId("42"); when(agents.createTask(any())).thenReturn(task);
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
        assertEquals(new HallRequestDraftService.TaskReference("42", "0"), service.create(SCOPE, "title", "body"));
        assertEquals(new HallRequestDraftService.TaskReference("42", "0"), service.get(SCOPE, "42"));
        var request = ArgumentCaptor.forClass(AgentTaskCreateDTO.class); verify(agents).createTask(request.capture());
        assertEquals("title", request.getValue().getTitle()); assertEquals("body", request.getValue().getDescription());
        assertNull(request.getValue().getReward()); assertNull(request.getValue().getGrossBountyAmountMicro());
        assertNull(request.getValue().getSettlementPolicy()); assertNull(request.getValue().getRequiredSkillRequirements());
    }
    @Test void noTransactionOrTaskServiceCannotFallBackToMetadataOnlyCreation() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertReason(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE, () -> service.create(SCOPE, "title", "body"));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(provider.getIfAvailable()).thenReturn(null);
        assertReason(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE, () -> service.create(SCOPE, "title", "body"));
        verifyNoInteractions(agents);
    }
    @Test void identityContextMismatchIsRejectedBeforeAnyTaskMutation() {
        identity("foreign", "c");
        assertReason(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, () -> service.create(SCOPE, "title", "body"));
        identity("o", "c"); EsContextHolder.setContext(new EsContext());
        assertReason(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, () -> service.create(SCOPE, "title", "body"));
        verifyNoInteractions(agents);
    }
    @Test void lossyTextAndForeignOrAbsentPlanAreNotSuccessfulTasks() {
        assertReason(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, () -> service.create(SCOPE, "x".repeat(31), "body"));
        assertReason(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, () -> service.create(SCOPE, "title", "x".repeat(201)));
        verifyNoInteractions(agents);
        when(plans.get(42L)).thenReturn(plan("foreign"));
        assertReason(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE, () -> service.create(SCOPE, "title", "body"));
        when(plans.get(42L)).thenReturn(null);
        assertReason(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE, () -> service.get(SCOPE, "42"));
    }
    @Test void revokedTaskAclIs404AndDoesNotReadPlan() {
        when(tasks.findByTaskIdInOwnerScope("0", "c", "o", "42")).thenReturn(null);
        assertReason(HallRequestDraftService.Reason.NOT_FOUND, () -> service.get(SCOPE, "42"));
        verifyNoInteractions(plans, agents);
    }
    private static TaskPlanEntity plan(String owner) {
        TaskPlanEntity plan = new TaskPlanEntity().setId(42L).setJiacn(owner).setName("title").setDescription("body");
        plan.setClientId("c"); plan.setTenantId("0"); return plan;
    }
    private static void identity(String owner, String client) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("fixture")
                .header("alg", "none").subject(owner).claim("jiacn", owner).claim("client_id", client)
                .issuedAt(Instant.ofEpochSecond(1)).expiresAt(Instant.ofEpochSecond(2)).build()));
        EsContext context = new EsContext(); context.setJiacn(owner); context.setClientId(client); EsContextHolder.setContext(context);
    }
    private static void assertReason(HallRequestDraftService.Reason reason, Runnable call) {
        assertEquals(reason, assertThrows(HallRequestDraftService.Failure.class, call::run).reason());
    }
}
