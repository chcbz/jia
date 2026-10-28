package cn.jia.chat.service;

import cn.jia.agent.service.NativeSkillScriptSessionLookup;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.SkillCapabilityLookup;
import cn.jia.agent.service.TaskSkillPolicyService;
import cn.jia.chat.handler.TypedDeliberationSessionRegistry;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Join the same authenticated skill fence and the live typed session declaration. */
@Service
public final class NativeSkillScriptSessionLookupImpl implements NativeSkillScriptSessionLookup {
    private final TaskSkillPolicyService policies;
    private final SkillCapabilityLookup runtimes;
    private final TypedDeliberationSessionRegistry sessions;
    public NativeSkillScriptSessionLookupImpl(TaskSkillPolicyService policies,SkillCapabilityLookup runtimes,TypedDeliberationSessionRegistry sessions) {
        this.policies=policies;this.runtimes=runtimes;this.sessions=sessions;
    }
    @Override public boolean supports(PersonalWorkspaceExecutionService.RuntimeScope scope,String taskId) {
        var key=new SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.agentId());
        var policy=policies.read(key,taskId);
        if(policy==null||policy.decision()!=TaskSkillPolicyService.Decision.ALLOWED||policy.fence()==null||policy.runtimeScope()==null
                ||!Objects.equals(policy.runtimeScope().ownerJiacn(),scope.executorOwnerJiacn())
                ||!Objects.equals(policy.fence().runtimeInstanceId(),scope.runtimeInstanceId()))return false;
        var live=runtimes.lookup(policy.runtimeScope(),policy.fence());
        if(live==null||live.state()!=SkillCapabilityLookup.State.AVAILABLE)return false;
        try {
            var ready=sessions.requireSingleReady(new TypedDeliberationSessionRegistry.Scope(scope.tenantId(),scope.executorOwnerJiacn(),scope.clientId()),scope.agentId());
            if(!Objects.equals(live.sessionId(),ready.sessionId())||!(ready.frozenDeclaration().get("nativeExecution") instanceof Map<?,?> nativeExecution)
                    ||!(nativeExecution.get("supportedToolPolicyRefs") instanceof List<?> refs)||!refs.contains("NATIVE_SKILL_SCRIPT_V1"))return false;
            return policy.equals(policies.read(key,taskId))&&live.equals(runtimes.lookup(policy.runtimeScope(),policy.fence()));
        }catch(IllegalStateException unavailable){return false;}
    }
}
