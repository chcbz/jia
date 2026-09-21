package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentService;
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
    private final AgentService agents;
    private final AgentTaskMetaDao tasks;
    private final ObjectProvider<TaskService> taskServices;

    public HallTaskCreationServiceImpl(AgentService agents, AgentTaskMetaDao tasks,
            ObjectProvider<TaskService> taskServices) {
        this.agents = Objects.requireNonNull(agents);
        this.tasks = Objects.requireNonNull(tasks);
        this.taskServices = Objects.requireNonNull(taskServices);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public HallRequestDraftService.TaskReference create(HallRequestDraftService.OwnerScope scope,
            String title, String description) {
        authenticated(scope);
        var context = EsContextHolder.getContext();
        if (context == null || !scope.ownerJiacn().equals(context.getJiacn())
                || !scope.clientId().equals(context.getClientId())) {
            throw new HallRequestDraftService.Failure(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE,
                    Map.of("field", "scope", "sourceType", "TASK_CREATE"));
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw storage();
        // Evidence: AgentService.taskPlanFor truncates UTF-16 to 30/200; task_plan has those widths.
        // Reject lossy mapping, rather than silently changing the confirmed request.
        if (title == null || title.isBlank() || title.length() > 30
                || (description != null && description.length() > 200)) {
            throw new HallRequestDraftService.Failure(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE,
                    Map.of("field", "title/instruction", "sourceType", "TASK_CREATE"));
        }
        TaskService plans = requirePlans(); // no metadata-only optional-service fallback for Hall
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle(title); request.setDescription(description);
        request.setRequiredAbilities(List.of());
        // No reward, funding, assignment, skill payment or execution intent is inferred.
        AgentTaskDTO created = agents.createTask(request);
        if (created == null || created.getId() == null) throw storage();
        HallRequestDraftService.TaskReference ref = get(scope, created.getId());
        TaskPlanEntity plan = ownedPlan(plans, scope, created.getId());
        if (!title.equals(plan.getName()) || !Objects.equals(description, plan.getDescription())
                || plan.getAmount() != null) throw storage();
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
