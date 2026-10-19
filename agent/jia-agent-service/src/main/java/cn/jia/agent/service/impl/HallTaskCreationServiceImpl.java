package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskCreationOperationService;
import cn.jia.agent.service.HallRequestDraftService;
import cn.jia.agent.service.HallTaskCreationService;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.core.context.EsContextHolder;
import cn.jia.task.service.TaskService;
import jakarta.inject.Named;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Joins the Hall draft transaction -> existing task-root reservation -> task plan/items/event. */
@Named
public class HallTaskCreationServiceImpl implements HallTaskCreationService {
    private final AgentTaskCreationOperationService creations;
    private final AgentTaskMetaDao tasks;
    private final ObjectProvider<TaskService> taskServices;

    public HallTaskCreationServiceImpl(AgentTaskCreationOperationService creations, AgentTaskMetaDao tasks,
            ObjectProvider<TaskService> taskServices) {
        this.creations = Objects.requireNonNull(creations);
        this.tasks = Objects.requireNonNull(tasks);
        this.taskServices = Objects.requireNonNull(taskServices);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public HallRequestDraftService.TaskReference create(HallRequestDraftService.OwnerScope scope,
            String creationKey, String title, String description, List<HallRequestDraftService.InputSelection> inputs) {
        authenticated(scope);
        var context = EsContextHolder.getContext();
        if (context == null || !scope.ownerJiacn().equals(context.getJiacn())
                || !scope.clientId().equals(context.getClientId())) {
            throw new HallRequestDraftService.Failure(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE,
                    Map.of("field", "scope", "sourceType", "TASK_CREATE"));
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw storage();
        if (title == null || title.isBlank() || title.length() > 200
                || (description != null && description.length() > 20000) || inputs == null) {
            throw new HallRequestDraftService.Failure(HallRequestDraftService.Reason.BAD_REQUEST);
        }
        TaskService plans = requirePlans();
        var refs = inputs.stream().map(input -> new AgentTaskCreationOperationService.InputReference(
                input.fileId(), input.version(), "INPUT"))
                .sorted((left,right) -> {
                    int file=java.util.Arrays.compareUnsigned(
                            left.fileId().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            right.fileId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return file!=0?file:Integer.compare(left.version(),right.version());
                }).toList();
        AgentTaskCreationOperationService.Result result;
        try {
            result = creations.create(new AgentTaskCreationOperationService.Scope(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn()), creationKey,
                    new AgentTaskCreationOperationService.CreateCommand(2, title, true, description,
                            true, List.of(), true, null, refs));
        } catch (AgentTaskCreationOperationService.Failure failure) {
            throw new HallRequestDraftService.Failure(switch (failure.reason()) {
                case BAD_REQUEST -> HallRequestDraftService.Reason.BAD_REQUEST;
                case IDEMPOTENCY_CONFLICT -> HallRequestDraftService.Reason.IDEMPOTENCY_CONFLICT;
                case NOT_FOUND, FORBIDDEN -> HallRequestDraftService.Reason.SOURCE_UNAVAILABLE;
                case UNAVAILABLE -> HallRequestDraftService.Reason.STORAGE_UNAVAILABLE;
            });
        }
        var receipt = result == null ? null : result.receipt();
        if (receipt == null || receipt.schemaVersion() != 2 || receipt.taskId() == null
                || !"COMMITTED".equals(receipt.state()) || receipt.requirementRevision() != 1
                || receipt.task() == null || !receipt.taskId().equals(receipt.task().getId())
                || !refs.equals(receipt.inputRefs())) throw storage();
        HallRequestDraftService.TaskReference ref = get(scope, receipt.taskId());
        TaskPlanEntity plan = ownedPlan(plans, scope, receipt.taskId());
        // The immutable v2 requirement snapshot owns full text/materials. The
        // task-plan's existing short summary is not the confirmed instruction.
        if (plan.getAmount() != null) throw storage();
        return ref;
    }

    @Override
    public HallRequestDraftService.TaskReference get(HallRequestDraftService.OwnerScope scope, String taskId) {
        authenticated(scope);
        AgentTaskMetaEntity task = tasks.findByTaskIdInOwnerScope(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId);
        if (task == null || !Objects.equals(taskId, task.getTaskId())
                || !scope.tenantId().equals(task.getTenantId())
                || !scope.clientId().equals(task.getClientId())
                || !scope.ownerJiacn().equals(task.getOwnerJiacn())) {
            throw new HallRequestDraftService.Failure(HallRequestDraftService.Reason.NOT_FOUND);
        }
        if (task.getTaskVersion() == null || task.getTaskVersion() < 0) throw storage();
        ownedPlan(requirePlans(), scope, taskId);
        // Like execution state in old receipts, this is a live authorized read, not a version snapshot.
        return new HallRequestDraftService.TaskReference(taskId, Long.toString(task.getTaskVersion()));
    }

    private TaskService requirePlans() {
        TaskService service = taskServices.getIfAvailable();
        if (service == null) throw storage();
        return service;
    }
    private static TaskPlanEntity ownedPlan(TaskService plans,
            HallRequestDraftService.OwnerScope scope, String id) {
        final long planId;
        try { planId = Long.parseLong(id); } catch (NumberFormatException invalid) { throw storage(); }
        if (planId <= 0 || !Long.toString(planId).equals(id)) throw storage();
        TaskPlanEntity plan = plans.get(planId);
        if (plan == null || !Long.valueOf(planId).equals(plan.getId())
                || !scope.ownerJiacn().equals(plan.getJiacn())
                || !scope.clientId().equals(plan.getClientId())
                || !scope.tenantId().equals(plan.getTenantId())) throw storage();
        return plan;
    }
    private static void authenticated(HallRequestDraftService.OwnerScope scope) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (scope == null || !"0".equals(scope.tenantId())
                || !(authentication instanceof JwtAuthenticationToken jwt)
                || !jwt.isAuthenticated() || scope.ownerJiacn() == null || scope.clientId() == null
                || scope.ownerJiacn().isBlank() || scope.clientId().isBlank()
                || "0".equals(scope.ownerJiacn())
                || !scope.ownerJiacn().equals(jwt.getToken().getClaims().get("jiacn"))
                || !scope.clientId().equals(jwt.getToken().getClaims().get("client_id"))) {
            throw new HallRequestDraftService.Failure(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE,
                    Map.of("field", "scope", "sourceType", "TASK_CREATE"));
        }
    }
    private static HallRequestDraftService.Failure storage() {
        return new HallRequestDraftService.Failure(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE);
    }
}
