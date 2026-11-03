package cn.jia.agent.entity;

/** Post-commit hint only: persisted execution and Chat authorization remain authoritative. */
public record WorkspaceExecutionProjectionHint(String tenantId, String clientId,
        String ownerJiacn, String executionId) { }
