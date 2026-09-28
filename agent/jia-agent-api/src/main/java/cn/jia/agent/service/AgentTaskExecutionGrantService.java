package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskExecutionGrantDTO;

/** Admission boundary for v2 task-scoped execution grants; it never starts an execution. */
public interface AgentTaskExecutionGrantService {
    AgentTaskExecutionGrantDTO assignAndGrant(Scope scope, String taskId,
            String idempotencyKey, AgentTaskAssignDTO request);

    AgentTaskExecutionGrantDTO revoke(Scope scope, String taskId, String grantId,
            String idempotencyKey, long expectedGrantVersion);

    Admission admit(Scope scope, String taskId, String grantId, long expectedGrantVersion,
            long expectedAssignmentRevision, String targetAgentId, String operation,
            boolean paidExecution);

    /** Server-side lookup for Chat/application orchestration; browser grant identifiers are not trusted. */
    Admission resolveAndAdmit(Scope scope, String taskId, long expectedAssignmentRevision,
            String targetAgentId, String operation, boolean paidExecution);

    record Scope(String tenantId, String clientId, String ownerJiacn) { }
    record Admission(String grantId, long grantVersion, long assignmentRevision,
            String targetAgentId, String operation, boolean paidExecutionAuthorized) { }
}
