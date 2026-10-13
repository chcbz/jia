package cn.jia.agent.service;

import java.util.Map;

/** Adapter for Chat-owned source lineage/retention. Never trusts sourceIdentity shape alone. */
public interface SkillSourceAccessService {
    void requireAuthorized(PersonalWorkspaceExecutionService.OwnerScope scope,String taskId,
            String conversationId,long conversationGeneration,Map<String,Object> frozenInput);
    PersonalWorkspaceExecutionService.RuntimeContent read(PersonalWorkspaceExecutionService.OwnerScope scope,
            String taskId,String conversationId,long conversationGeneration,Map<String,Object> frozenInput);
}
