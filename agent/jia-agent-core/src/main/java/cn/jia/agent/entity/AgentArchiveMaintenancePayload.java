package cn.jia.agent.entity;

/** Exact, server-authorized run reference; never an arbitrary Codex prompt. */
public record AgentArchiveMaintenancePayload(
        int schemaVersion, String jobId, String runId, String executionEpoch,
        String appointmentId, String appointmentRevision, String managerAuthorizationRevision,
        String bindingVersion, String grantRef, String executionRef, String dispatchKey,
        String skillInstallationId, String skillPackageSha256,
        String contextRef) implements AgentCommandPayload { }
