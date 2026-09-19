package cn.jia.agent.service;

/**
 * Owner-only bridge from one terminal changes-requested decision to one explicit TASK execution.
 * The source is an immutable, already-published workspace output version; no lease or storage URI
 * crosses this browser-facing boundary.
 */
public interface AgentTaskReworkExecutionService {
    PersonalWorkspaceExecutionService.ExecutionView create(
            PersonalWorkspaceExecutionService.OwnerScope scope,
            ReworkCommand command,
            String idempotencyKey);

    record ReworkCommand(String taskId, String formalDeliveryId, long expectedDecisionVersion,
            String conversationId, String targetAgentId, String sourceOutputId,
            String sourceFileId, int sourceFileVersion, String instruction,
            String outputContentMimeType) { }
}
