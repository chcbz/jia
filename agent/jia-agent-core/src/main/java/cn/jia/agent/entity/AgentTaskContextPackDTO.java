package cn.jia.agent.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** Bounded, metadata-only F01 Context Pack. Arbitrary source payloads are never exposed. */
@Data
public class AgentTaskContextPackDTO {
    private String schemaVersion;
    private Provenance provenance;
    private TaskDescriptionSection taskDescription;
    private MembersSection members;
    private WorkItemsSection workItems;
    private ArtifactsSection authoritativeArtifacts;
    private RequestsSection openRequests;
    private EventsSection recentEvents;
    private ConversationSection conversation;
    private String digestAlgorithm;
    private String digest;

    @Data
    public static class Provenance {
        private String tenantId;
        private String clientId;
        private String taskId;
        private String actorAgentId;
        private String taskVersion;
        private String currentEventVersion;
    }

    @Data
    public static class SafeText {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        private String value;
        private boolean redacted;
        private boolean truncated;
    }

    @Data
    public abstract static class Section {
        private String status;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String reason;
        private boolean truncated;
    }

    @Data
    public static class TaskDescriptionSection extends Section {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private SafeText title;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private SafeText description;
    }

    @Data
    public static class MembersSection extends Section {
        private List<Member> items;
    }

    @Data
    public static class Member {
        private String agentId;
        private String role;
        private String status;
        private String version;
    }

    @Data
    public static class WorkItemsSection extends Section {
        private List<WorkItem> items;
    }

    @Data
    public static class WorkItem {
        private String workItemId;
        private SafeText title;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private SafeText description;
        private String workType;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String assigneeAgentId;
        private String status;
        private Integer priority;
        private Boolean requiredItem;
        private List<String> dependencyIds;
        private boolean dependenciesTruncated;
        private String version;
    }

    @Data
    public static class ArtifactsSection extends Section {
        private List<Artifact> items;
    }

    @Data
    public static class Artifact {
        private String artifactId;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String workItemId;
        private String producerAgentId;
        private String artifactType;
        private SafeText title;
        private String contentHash;
        private String artifactVersion;
        private String visibility;
        private String createdAt;
        private String outcomeState;
        private String outcomeVersion;
        private String decisionId;
        private String decidedByAgentId;
        private String decidedAt;
    }

    @Data
    public static class RequestsSection extends Section {
        private List<Request> items;
    }

    @Data
    public static class Request {
        private String requestId;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String workItemId;
        private String requesterAgentId;
        private String targetType;
        private String targetId;
        private String requestType;
        private String status;
        private Integer priority;
        private SafeText title;
        private SafeText description;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String dueAt;
        private String version;
    }

    @Data
    public static class EventsSection extends Section {
        private List<Event> items;
    }

    @Data
    public static class Event {
        private String version;
        private Boolean redacted;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String eventType;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String actorType;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String actorId;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String aggregateType;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String aggregateId;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String occurredAt;
    }

    @Data
    public static class ConversationSection extends Section {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String conversationId;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Integer recentMessageCount;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String latestMessageAt;
        private boolean contentOmitted;
    }
}
