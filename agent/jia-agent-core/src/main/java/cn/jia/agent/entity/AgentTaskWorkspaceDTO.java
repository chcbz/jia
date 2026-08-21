package cn.jia.agent.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** Purpose-built, non-entity HTTP contract for the C04 task workspace snapshot. */
@Data
public class AgentTaskWorkspaceDTO {
    private Task task;
    private List<Member> members;
    private List<WorkItem> workItems;
    private List<Request> openRequests;
    private List<Artifact> recentArtifacts;
    private boolean recentArtifactsTruncated;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String conversationId;
    private List<Event> recentEvents;
    private boolean timelineTruncated;
    private String currentVersion;

    @Data
    public static class Task {
        private String taskId;
        private String status;
        private String assignedAgentId;
        private String requiredAbilities;
        private Integer reward;
        private String assignedAt;
        private String startedAt;
        private String completedAt;
        private String collaborationMode;
        private String riskLevel;
        private Integer maxAgents;
        private String coordinatorAgentId;
        private Boolean reviewRequired;
        private String version;
    }

    @Data
    public static class Member {
        private String agentId;
        private String role;
        private String status;
        private String assignmentSource;
        private String joinedAt;
        private String acceptedAt;
        private String startedAt;
        private String completedAt;
        private String lastHeartbeatAt;
        private String version;
    }

    @Data
    public static class WorkItem {
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
        private String leaseUntil;
        private Integer attemptCount;
        private Integer maxAttempts;
        private String resultArtifactId;
        private String submittedAt;
        private String completedAt;
        private String version;
    }

    @Data
    public static class Request {
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
        private String dueAt;
        private String acknowledgedAt;
        private String version;
    }

    @Data
    public static class Artifact {
        private String artifactId;
        private String workItemId;
        private String producerAgentId;
        private String artifactType;
        private String title;
        private String artifactVersion;
        private String visibility;
        private String createdAt;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Event {
        private String version;
        private Boolean redacted;
        private String eventType;
        private String actorType;
        private String actorId;
        private String aggregateType;
        private String aggregateId;
        private String occurredAt;
    }
}
