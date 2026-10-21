package cn.jia.agent.service;

/** The task owns data; its frozen target owns execution credentials. No client supplied owner. */
public interface TaskAssigneeAuthorityService {
    record Target(String tenantId, String clientId, String ownerJiacn, String agentId,
                  long bindingId, Long externalPolicyRevision) { }
    Target resolveForAssignment(String tenantId, String clientId, String taskOwner, String agentId);
    Target lockForAssignment(String tenantId, String clientId, String taskOwner, String agentId);
    Target requireGrantTarget(String tenantId, String clientId, String taskOwner,
                              String taskId, String grantId, long grantVersion);
}
