package cn.jia.agent.entity;

import lombok.Data;

/** Safe, explicit database projections used only by the C04 workspace snapshot. */
public final class AgentTaskWorkspaceRows {
    private AgentTaskWorkspaceRows() {
    }

    @Data
    public static class TaskRow {
        private String tenantId;
        private String clientId;
        private String taskId;
        private String rewardStatus;
        private String assignedAgentId;
        private String requiredAbilities;
        private Integer reward;
        private Long assignedAt;
        private Long startedAt;
        private Long completedAt;
        private String collaborationMode;
        private String riskLevel;
        private Integer maxAgents;
        private String coordinatorAgentId;
        private Boolean reviewRequired;
        private Long taskVersion;
        private Long currentEventVersion;
    }

    @Data
    public static class MemberRow {
        private String tenantId;
        private String clientId;
        private String taskId;
        private String agentId;
        private String memberRole;
        private String memberStatus;
        private String assignmentSource;
        private Long joinedAt;
        private Long acceptedAt;
        private Long startedAt;
        private Long completedAt;
        private Long lastHeartbeatAt;
        private Long version;
    }

    @Data
    public static class WorkItemRow {
        private String tenantId;
        private String clientId;
        private String taskId;
        private String workItemId;
        private String title;
        private String description;
        private String workType;
        private String requiredAbilities;
        private String assigneeAgentId;
        private String status;
        private Integer priority;
        private Boolean requiredItem;
        private String dependencyJson;
        private Long leaseUntil;
        private Integer attemptCount;
        private Integer maxAttempts;
        private String resultArtifactId;
        private Long submittedAt;
        private Long completedAt;
        private Long version;
    }

    @Data
    public static class RequestRow {
        private String tenantId;
        private String clientId;
        private String taskId;
        private String requestId;
        private String workItemId;
        private String requesterAgentId;
        private String targetType;
        private String targetId;
        private String requestType;
        private String status;
        private Integer priority;
        private String title;
        private String description;
        private Long dueAt;
        private Long acknowledgedAt;
        private Long version;
    }

    @Data
    public static class ArtifactRow {
        private String tenantId;
        private String clientId;
        private String taskId;
        private String artifactId;
        private String workItemId;
        private String producerAgentId;
        private String artifactType;
        private String title;
        private Integer artifactVersion;
        private String visibility;
        private Long createdAt;
    }

    @Data
    public static class EventRow {
        private String tenantId;
        private String clientId;
        private String taskId;
        private Long eventVersion;
        private String eventType;
        private String actorType;
        private String actorId;
        private String aggregateType;
        private String aggregateId;
        private String eventJson;
        private Long occurredAt;
    }
}
