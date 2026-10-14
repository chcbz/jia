package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskExecutionGrantDao;
import cn.jia.agent.service.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import java.util.Objects;

/** Read-only policy: assigned/running tasks are not tested with unassigned-only point policy. */
@Named
public final class TaskSkillPolicyServiceImpl implements TaskSkillPolicyService {
    private final AgentTaskMetaDao tasks;
    private final AgentTaskExecutionGrantDao grants;
    private final AgentIdentityService identities;
    private final AgentTaskRequirementSnapshotService requirements;
    private final AgentRuntimeAuthenticationService runtimes;
    private static final tools.jackson.databind.ObjectMapper JSON=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    @Inject public TaskSkillPolicyServiceImpl(AgentTaskMetaDao tasks,AgentTaskExecutionGrantDao grants,
            AgentIdentityService identities,AgentTaskRequirementSnapshotService requirements,
            AgentRuntimeAuthenticationService runtimes) {
        this.tasks=Objects.requireNonNull(tasks);this.grants=Objects.requireNonNull(grants);
        this.identities=Objects.requireNonNull(identities);this.requirements=Objects.requireNonNull(requirements);
        this.runtimes=Objects.requireNonNull(runtimes);
    }
    @Override public Snapshot read(SkillCapabilityLookup.Scope scope,String taskId) {
        if(scope==null||taskId==null||!taskId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"))throw new IllegalArgumentException();
        try {
            var task=tasks.findByTaskIdInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId);
            if(task==null)throw missing();
            if(!scope.tenantId().equals(task.getTenantId())||!scope.clientId().equals(task.getClientId())
                    ||!scope.ownerJiacn().equals(task.getOwnerJiacn())||!taskId.equals(task.getTaskId()))throw integrity();
            if(!scope.agentId().equals(task.getAssignedAgentId())||java.util.Set.of("cancelled","done","closed").contains(task.getRewardStatus()))return denied("TASK_NOT_ASSIGNED",0);
            var grant=grants.findActiveByTask(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId);
            if(grant==null)return denied("TASK_GRANT_MISSING",0);
            if(!scope.tenantId().equals(grant.getTenantId())||!scope.clientId().equals(grant.getClientId())
                    ||!scope.ownerJiacn().equals(grant.getOwnerJiacn())||!taskId.equals(grant.getTaskId()))throw integrity();
            if(grant.getAssignmentRevision()==null||grant.getGrantVersion()==null||grant.getRequirementRevision()==null)throw integrity();
            long revision=grant.getAssignmentRevision();
            if(!scope.agentId().equals(grant.getTargetAgentId())||!"ACTIVE".equals(grant.getState())||task.getTaskVersion()==null||task.getTaskVersion()<revision)return denied("TASK_GRANT_STALE",revision);
            var event=JSON.readTree(grants.latestAssignmentEventJson(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId));
            var assigned=event==null?null:event.get("resultVersion");
            if(assigned==null||!assigned.isIntegralNumber()||!assigned.canConvertToLong()||assigned.longValue()<0||assigned.longValue()>revision)return denied("ASSIGNMENT_EPOCH_UNPROVEN",revision);
            var current=requirements.requireCurrent(new AgentTaskExecutionGrantService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),taskId,grant.getRequirementRevision());
            if(current==null||current.revision()!=grant.getRequirementRevision()||!scope.ownerJiacn().equals(current.ownerJiacn())||!scope.tenantId().equals(current.tenantId())||!scope.clientId().equals(current.clientId())||!taskId.equals(current.taskId()))return denied("REQUIREMENT_STALE",revision);
            var operations=JSON.readTree(grant.getPermittedOperationsJson());
            if(!operations.isArray()||!"SKILLS_V1".equals(grant.getPermittedToolPolicyRef()))return denied("SKILL_BASELINE_REQUIRED",revision);
            boolean skill=false;for(var op:operations){if(!op.isTextual())throw integrity();if("EXECUTE_SKILL".equals(op.textValue()))skill=true;}
            if(!skill)return denied("SKILL_BASELINE_REQUIRED",revision);
            AgentRuntimeAuthenticationService.Proof proof;
            try {proof=runtimes.currentRegisteredProof(scope.tenantId(),scope.clientId(),scope.agentId());}
            catch(IllegalArgumentException offline){return denied("RUNTIME_UNAVAILABLE",revision);}
            // Task ownership and Agent ownership are independent. Only the native proof owns
            // the runtime subject; a delegated task cannot borrow its owner's installation.
            if(proof==null||proof.scope()==null||!scope.tenantId().equals(proof.scope().tenantId())
                    ||!scope.clientId().equals(proof.scope().clientId())||!scope.agentId().equals(proof.scope().agentId()))
                return denied("RUNTIME_FENCED",revision);
            var runtimeScope=new SkillCapabilityLookup.Scope(proof.scope().tenantId(),proof.scope().clientId(),
                    proof.scope().ownerJiacn(),proof.scope().agentId());
            if(!scope.agentId().equals(identities.requireCanonicalAgentIdInScope(runtimeScope.tenantId(),
                    runtimeScope.clientId(),runtimeScope.ownerJiacn(),runtimeScope.agentId())))throw integrity();
            return new Snapshot(true,null,revision,new AgentManagedSessionLookup.SessionFence(proof.installationId(),proof.hostId(),proof.scope().runtimeInstanceId(),proof.sessionGeneration()),runtimeScope);
        } catch(Failure known){throw known;}
        catch(AgentTaskCollaborationException|AgentServiceImpl.AgentBizException denied){throw missing();}
        catch(RuntimeException unavailable){throw new Failure(Failure.Reason.SOURCE_UNAVAILABLE,unavailable);}
    }
    private static Snapshot denied(String code,long revision){return new Snapshot(false,code,revision,null,null);}
    private static Failure missing(){return new Failure(Failure.Reason.NOT_FOUND);}
    private static Failure integrity(){return new Failure(Failure.Reason.INTEGRITY_ERROR);}
}
