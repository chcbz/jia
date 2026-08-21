package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskWorkspaceDao;
import cn.jia.agent.entity.AgentTaskWorkspaceDTO;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.ArtifactRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.EventRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.MemberRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.RequestRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.TaskRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.WorkItemRow;
import cn.jia.agent.exception.AgentTaskWorkspaceException;
import cn.jia.agent.exception.AgentTaskWorkspaceException.Reason;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskWorkspaceService;
import cn.jia.agent.service.AgentTaskWorkspaceService.AuthorizedSubject;
import cn.jia.agent.state.AgentTaskMemberStatus;
import cn.jia.agent.state.AgentTaskRequestStatus;
import cn.jia.agent.state.AgentTaskStatus;
import cn.jia.agent.state.AgentTaskWorkItemStatus;
import cn.jia.core.util.JsonUtil;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Atomic C04 workspace snapshot. Every database read participates in this one transaction. */
@Named
public class AgentTaskWorkspaceServiceImpl implements AgentTaskWorkspaceService {
    static final int COMPLETE_COLLECTION_SENTINEL = 500;
    static final int RECENT_ARTIFACT_LIMIT = 100;
    static final int RECENT_EVENT_LIMIT = 100;

    private static final Set<AgentTaskMemberStatus> ACTOR_STATUSES = Set.of(
            AgentTaskMemberStatus.ACCEPTED, AgentTaskMemberStatus.WORKING,
            AgentTaskMemberStatus.BLOCKED, AgentTaskMemberStatus.DONE,
            AgentTaskMemberStatus.FAILED);
    private static final Set<String> MEMBER_ROLES = Set.of(
            "coordinator", "worker", "reviewer", "observer");
    private static final Set<String> ASSIGNMENT_SOURCES = Set.of(
            "manual", "auto", "migration", "legacy");
    private static final Set<String> REQUEST_TYPES = Set.of(
            "help", "clarification", "dependency", "review", "resource",
            "reassignment", "approval");
    private static final Set<String> TARGET_TYPES = Set.of("agent", "role");
    private static final Set<String> COLLABORATION_MODES = Set.of("single", "team");
    private static final Set<String> RISK_LEVELS = Set.of("low", "medium", "high");
    private static final Set<String> VISIBILITIES = Set.of("task_members", "reviewer", "private");
    private static final ObjectMapper STRICT_EVENT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private final AgentIdentityService agentIdentityService;
    private final AgentTaskWorkspaceDao workspaceDao;

    @Inject
    public AgentTaskWorkspaceServiceImpl(
            AgentIdentityService agentIdentityService, AgentTaskWorkspaceDao workspaceDao) {
        this.agentIdentityService = Objects.requireNonNull(
                agentIdentityService, "agentIdentityService");
        this.workspaceDao = Objects.requireNonNull(workspaceDao, "workspaceDao");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true,
            rollbackFor = Exception.class)
    public AuthorizedSubject authorize(
            String tenantId, String clientId, String taskId, String actorAgentId) {
        return authorizeRows(tenantId, clientId, taskId, actorAgentId).subject();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.REPEATABLE_READ,
            readOnly = true, rollbackFor = Exception.class)
    public AgentTaskWorkspaceDTO snapshot(
            String tenantId, String clientId, String taskId, String actorAgentId) {
        Authorization authorization = authorizeRows(
                tenantId, clientId, taskId, actorAgentId);
        TaskRow task = authorization.task();
        MemberRow actor = authorization.actor();
        AuthorizedSubject subject = authorization.subject();
        validateTaskIntegrity(task);

        boolean reviewerAccess = subject.reviewerAccess();
        boolean coordinatorAccess = subject.coordinatorAccess();

        List<MemberRow> memberRows = complete(
                workspaceDao.findMembers(tenantId, clientId, taskId));
        if (memberRows.stream().noneMatch(row -> sameActorMember(row, actor))) {
            throw unavailable();
        }
        List<WorkItemRow> workItemRows = complete(
                workspaceDao.findWorkItems(tenantId, clientId, taskId));
        List<RequestRow> requestRows = complete(
                workspaceDao.findOpenRequests(tenantId, clientId, taskId));
        List<ArtifactRow> artifactRows = requiredList(workspaceDao.findVisibleArtifacts(
                tenantId, clientId, taskId, actorAgentId,
                reviewerAccess, coordinatorAccess));
        if (artifactRows.size() > RECENT_ARTIFACT_LIMIT + 1) {
            throw unavailable();
        }
        boolean artifactsTruncated = artifactRows.size() > RECENT_ARTIFACT_LIMIT;
        List<ArtifactRow> returnedArtifacts = artifactRows.subList(
                0, Math.min(RECENT_ARTIFACT_LIMIT, artifactRows.size()));

        List<EventRow> latestEvents = requiredList(
                workspaceDao.findLatestEvents(tenantId, clientId, taskId));
        Timeline timeline = timeline(tenantId, clientId, task, actor, latestEvents,
                reviewerAccess, coordinatorAccess);

        AgentTaskWorkspaceDTO result = new AgentTaskWorkspaceDTO();
        result.setTask(taskDto(task));
        result.setMembers(memberDtos(tenantId, clientId, taskId, memberRows));
        result.setWorkItems(workItemDtos(tenantId, clientId, taskId, workItemRows));
        result.setOpenRequests(requestDtos(tenantId, clientId, taskId, requestRows));
        result.setRecentArtifacts(artifactDtos(
                tenantId, clientId, taskId, returnedArtifacts));
        result.setRecentArtifactsTruncated(artifactsTruncated);
        result.setConversationId(null);
        result.setRecentEvents(timeline.events());
        result.setTimelineTruncated(timeline.truncated());
        result.setCurrentVersion(decimal(task.getCurrentEventVersion()));
        return result;
    }


    private Authorization authorizeRows(
            String tenantId, String clientId, String taskId, String actorAgentId) {
        requireId(tenantId, "tenantId", 50);
        requireId(clientId, "clientId", 50);
        requireId(taskId, "taskId", 100);
        requireId(actorAgentId, "actorAgentId", 100);

        try {
            String canonicalAgentId = agentIdentityService.requireCanonicalAgentIdInScope(
                    tenantId, clientId, tenantId, actorAgentId);
            if (!actorAgentId.equals(canonicalAgentId)) {
                throw notFound();
            }
        } catch (AgentServiceImpl.AgentBizException exception) {
            throw notFound();
        }

        TaskRow task = workspaceDao.findTask(tenantId, clientId, taskId);
        if (!isExactTaskRow(task, tenantId, clientId, taskId)) {
            throw notFound();
        }
        MemberRow actor = workspaceDao.findActorMember(
                tenantId, clientId, taskId, actorAgentId);
        if (!validActorMember(actor, tenantId, clientId, taskId, actorAgentId)) {
            throw notFound();
        }
        AuthorizedSubject subject = new AuthorizedSubject(
                tenantId, clientId, taskId, actorAgentId,
                actor.getMemberRole(), task.getCoordinatorAgentId());
        return new Authorization(task, actor, subject);
    }

    private Timeline timeline(String tenantId, String clientId, TaskRow task,
            MemberRow actor, List<EventRow> descending, boolean reviewerAccess,
            boolean coordinatorAccess) {
        long currentVersion = task.getCurrentEventVersion();
        if (descending.size() > RECENT_EVENT_LIMIT + 1) {
            throw unavailable();
        }
        if (currentVersion == 0) {
            if (!descending.isEmpty()) {
                throw unavailable();
            }
            return new Timeline(List.of(), false);
        }
        if (descending.isEmpty()) {
            throw unavailable();
        }

        long expected = currentVersion;
        List<AgentTaskWorkspaceDTO.Event> projectedDescending =
                new ArrayList<>(descending.size());
        for (EventRow row : descending) {
            validateEventRow(tenantId, clientId, task.getTaskId(), row);
            if (row.getEventVersion() != expected) {
                throw unavailable();
            }
            projectedDescending.add(eventDto(tenantId, clientId, task, actor, row,
                    reviewerAccess, coordinatorAccess));
            expected--;
        }

        int count = Math.min(RECENT_EVENT_LIMIT, projectedDescending.size());
        List<AgentTaskWorkspaceDTO.Event> events =
                new ArrayList<>(projectedDescending.subList(0, count));
        Collections.reverse(events);
        if (events.isEmpty()
                || !decimal(currentVersion).equals(events.get(events.size() - 1).getVersion())) {
            throw unavailable();
        }
        boolean truncated = !"1".equals(events.get(0).getVersion());
        return new Timeline(List.copyOf(events), truncated);
    }

    private AgentTaskWorkspaceDTO.Event eventDto(String tenantId, String clientId,
            TaskRow task, MemberRow actor, EventRow row, boolean reviewerAccess,
            boolean coordinatorAccess) {
        Map<String, Object> payload = normalizedPayload(row.getEventJson());
        final AgentTaskWorkspaceEventValidator.ArtifactClaim artifactClaim;
        try {
            artifactClaim = AgentTaskWorkspaceEventValidator.validate(
                    row.getEventType(), row.getActorType(), row.getActorId(),
                    row.getAggregateType(), row.getAggregateId(), payload,
                    task.getTaskId());
        } catch (IllegalArgumentException exception) {
            throw unavailable(exception);
        }
        if (artifactClaim != null) {
            ArtifactRow artifact = workspaceDao.findArtifactVersion(
                    tenantId, clientId, task.getTaskId(), artifactClaim.artifactId(),
                    artifactClaim.artifactVersion());
            if (!validArtifact(artifact, tenantId, clientId, task.getTaskId())
                    || !artifactClaim.artifactId().equals(artifact.getArtifactId())
                    || artifactClaim.artifactVersion() != artifact.getArtifactVersion()
                    || !artifactClaim.producerAgentId().equals(artifact.getProducerAgentId())
                    || !artifactClaim.artifactType().equals(artifact.getArtifactType())
                    || !artifactClaim.visibility().equals(artifact.getVisibility())
                    || !Objects.equals(artifactClaim.workItemId(), artifact.getWorkItemId())) {
                throw unavailable();
            }
            if (!canReadArtifact(actor.getAgentId(), actor.getMemberRole(),
                    task.getCoordinatorAgentId(), artifact,
                    reviewerAccess, coordinatorAccess)) {
                AgentTaskWorkspaceDTO.Event redacted = new AgentTaskWorkspaceDTO.Event();
                redacted.setVersion(decimal(row.getEventVersion()));
                redacted.setRedacted(Boolean.TRUE);
                return redacted;
            }
        }
        AgentTaskWorkspaceDTO.Event event = new AgentTaskWorkspaceDTO.Event();
        event.setVersion(decimal(row.getEventVersion()));
        event.setRedacted(Boolean.FALSE);
        event.setEventType(row.getEventType());
        event.setActorType(row.getActorType());
        event.setActorId(row.getActorId());
        event.setAggregateType(row.getAggregateType());
        event.setAggregateId(row.getAggregateId());
        event.setOccurredAt(decimalNullable(row.getOccurredAt()));
        return event;
    }

    private void validateEventRow(
            String tenantId, String clientId, String taskId, EventRow row) {
        if (row == null || !scope(row.getTenantId(), row.getClientId(), row.getTaskId(),
                tenantId, clientId, taskId)
                || row.getEventVersion() == null || row.getEventVersion() <= 0
                || row.getOccurredAt() == null || row.getOccurredAt() <= 0) {
            throw unavailable();
        }
        try {
            TaskEventType.requireKnown(row.getEventType());
            TaskEventType.ActorType.requireKnown(row.getActorType());
            TaskEventType.Aggregate.requireKnown(row.getAggregateType());
            requireId(row.getAggregateId(), "aggregateId", 100);
            if (!TaskEventType.ActorType.SYSTEM.equals(row.getActorType())) {
                requireId(row.getActorId(), "actorId", 100);
            } else if (row.getActorId() != null) {
                requireId(row.getActorId(), "actorId", 100);
            }
            if (TaskEventType.Aggregate.TASK.equals(row.getAggregateType())
                    && !taskId.equals(row.getAggregateId())) {
                throw new IllegalArgumentException("wrong task aggregate");
            }
            normalizedPayload(row.getEventJson());
        } catch (IllegalArgumentException exception) {
            throw unavailable(exception);
        }
    }

    private static Map<String, Object> normalizedPayload(String eventJson) {
        try {
            // Parse once with duplicate detection before canonical normalization; otherwise
            // a corrupt payload such as {"artifactId":"a","artifactId":"b"} is last-wins.
            STRICT_EVENT_JSON.readTree(eventJson);
            String normalized = TaskEventPayload.normalizeAllowedJson(eventJson);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = JsonUtil.getMapper().readValue(normalized, Map.class);
            return payload;
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    private static boolean canReadArtifact(String actorAgentId, String actorRole,
            String coordinatorAgentId, ArtifactRow artifact,
            boolean reviewerAccess, boolean coordinatorAccess) {
        if (coordinatorAccess || actorAgentId.equals(coordinatorAgentId)) {
            return true;
        }
        if (actorAgentId.equals(artifact.getProducerAgentId())) {
            return true;
        }
        return switch (artifact.getVisibility()) {
            case "task_members" -> true;
            case "reviewer" -> reviewerAccess || "reviewer".equals(actorRole);
            case "private" -> false;
            default -> throw unavailable();
        };
    }

    private static AgentTaskWorkspaceDTO.Task taskDto(TaskRow row) {
        AgentTaskWorkspaceDTO.Task dto = new AgentTaskWorkspaceDTO.Task();
        dto.setTaskId(row.getTaskId());
        dto.setStatus(row.getRewardStatus());
        dto.setAssignedAgentId(row.getAssignedAgentId());
        dto.setRequiredAbilities(row.getRequiredAbilities());
        dto.setReward(row.getReward());
        dto.setAssignedAt(decimalNullable(row.getAssignedAt()));
        dto.setStartedAt(decimalNullable(row.getStartedAt()));
        dto.setCompletedAt(decimalNullable(row.getCompletedAt()));
        dto.setCollaborationMode(row.getCollaborationMode());
        dto.setRiskLevel(row.getRiskLevel());
        dto.setMaxAgents(row.getMaxAgents());
        dto.setCoordinatorAgentId(row.getCoordinatorAgentId());
        dto.setReviewRequired(row.getReviewRequired());
        dto.setVersion(decimal(row.getTaskVersion()));
        return dto;
    }

    private static List<AgentTaskWorkspaceDTO.Member> memberDtos(String tenantId,
            String clientId, String taskId, List<MemberRow> rows) {
        Set<String> ids = new HashSet<>();
        List<AgentTaskWorkspaceDTO.Member> result = new ArrayList<>(rows.size());
        for (MemberRow row : rows) {
            if (!validMember(row, tenantId, clientId, taskId) || !ids.add(row.getAgentId())) {
                throw unavailable();
            }
            AgentTaskWorkspaceDTO.Member dto = new AgentTaskWorkspaceDTO.Member();
            dto.setAgentId(row.getAgentId());
            dto.setRole(row.getMemberRole());
            dto.setStatus(row.getMemberStatus());
            dto.setAssignmentSource(row.getAssignmentSource());
            dto.setJoinedAt(decimalNullable(row.getJoinedAt()));
            dto.setAcceptedAt(decimalNullable(row.getAcceptedAt()));
            dto.setStartedAt(decimalNullable(row.getStartedAt()));
            dto.setCompletedAt(decimalNullable(row.getCompletedAt()));
            dto.setLastHeartbeatAt(decimalNullable(row.getLastHeartbeatAt()));
            dto.setVersion(decimal(row.getVersion()));
            result.add(dto);
        }
        return List.copyOf(result);
    }

    private static List<AgentTaskWorkspaceDTO.WorkItem> workItemDtos(String tenantId,
            String clientId, String taskId, List<WorkItemRow> rows) {
        Set<String> ids = new HashSet<>();
        List<AgentTaskWorkspaceDTO.WorkItem> result = new ArrayList<>(rows.size());
        for (WorkItemRow row : rows) {
            if (!validWorkItem(row, tenantId, clientId, taskId)
                    || !ids.add(row.getWorkItemId())) {
                throw unavailable();
            }
            AgentTaskWorkspaceDTO.WorkItem dto = new AgentTaskWorkspaceDTO.WorkItem();
            dto.setWorkItemId(row.getWorkItemId());
            dto.setTitle(row.getTitle());
            dto.setDescription(row.getDescription());
            dto.setWorkType(row.getWorkType());
            dto.setRequiredAbilities(row.getRequiredAbilities());
            dto.setAssigneeAgentId(row.getAssigneeAgentId());
            dto.setStatus(row.getStatus());
            dto.setPriority(row.getPriority());
            dto.setRequiredItem(row.getRequiredItem());
            dto.setDependencyJson(row.getDependencyJson());
            dto.setLeaseUntil(decimalNullable(row.getLeaseUntil()));
            dto.setAttemptCount(row.getAttemptCount());
            dto.setMaxAttempts(row.getMaxAttempts());
            dto.setResultArtifactId(row.getResultArtifactId());
            dto.setSubmittedAt(decimalNullable(row.getSubmittedAt()));
            dto.setCompletedAt(decimalNullable(row.getCompletedAt()));
            dto.setVersion(decimal(row.getVersion()));
            result.add(dto);
        }
        return List.copyOf(result);
    }

    private static List<AgentTaskWorkspaceDTO.Request> requestDtos(String tenantId,
            String clientId, String taskId, List<RequestRow> rows) {
        Set<String> ids = new HashSet<>();
        List<AgentTaskWorkspaceDTO.Request> result = new ArrayList<>(rows.size());
        for (RequestRow row : rows) {
            if (!validRequest(row, tenantId, clientId, taskId)
                    || !ids.add(row.getRequestId())) {
                throw unavailable();
            }
            AgentTaskWorkspaceDTO.Request dto = new AgentTaskWorkspaceDTO.Request();
            dto.setRequestId(row.getRequestId());
            dto.setWorkItemId(row.getWorkItemId());
            dto.setRequesterAgentId(row.getRequesterAgentId());
            dto.setTargetType(row.getTargetType());
            dto.setTargetId(row.getTargetId());
            dto.setRequestType(row.getRequestType());
            dto.setStatus(row.getStatus());
            dto.setPriority(row.getPriority());
            dto.setTitle(row.getTitle());
            dto.setDescription(row.getDescription());
            dto.setDueAt(decimalNullable(row.getDueAt()));
            dto.setAcknowledgedAt(decimalNullable(row.getAcknowledgedAt()));
            dto.setVersion(decimal(row.getVersion()));
            result.add(dto);
        }
        return List.copyOf(result);
    }

    private static List<AgentTaskWorkspaceDTO.Artifact> artifactDtos(String tenantId,
            String clientId, String taskId, List<ArtifactRow> rows) {
        List<AgentTaskWorkspaceDTO.Artifact> result = new ArrayList<>(rows.size());
        Set<String> versions = new HashSet<>();
        for (ArtifactRow row : rows) {
            if (!validArtifact(row, tenantId, clientId, taskId)
                    || !versions.add(row.getArtifactId() + "\u0000" + row.getArtifactVersion())) {
                throw unavailable();
            }
            AgentTaskWorkspaceDTO.Artifact dto = new AgentTaskWorkspaceDTO.Artifact();
            dto.setArtifactId(row.getArtifactId());
            dto.setWorkItemId(row.getWorkItemId());
            dto.setProducerAgentId(row.getProducerAgentId());
            dto.setArtifactType(row.getArtifactType());
            dto.setTitle(row.getTitle());
            dto.setArtifactVersion(decimal(row.getArtifactVersion().longValue()));
            dto.setVisibility(row.getVisibility());
            dto.setCreatedAt(decimalNullable(row.getCreatedAt()));
            result.add(dto);
        }
        return List.copyOf(result);
    }

    private static void validateTaskIntegrity(TaskRow row) {
        try {
            AgentTaskStatus.fromPersistedValue(row.getRewardStatus());
        } catch (IllegalArgumentException exception) {
            throw unavailable(exception);
        }
        if (row.getTaskVersion() == null || row.getTaskVersion() < 0
                || row.getCurrentEventVersion() == null || row.getCurrentEventVersion() < 0
                || !COLLABORATION_MODES.contains(row.getCollaborationMode())
                || !RISK_LEVELS.contains(row.getRiskLevel())
                || row.getMaxAgents() == null || row.getMaxAgents() <= 0
                || row.getReviewRequired() == null
                || row.getReward() != null && row.getReward() < 0
                || !validNullableTime(row.getAssignedAt())
                || !validNullableTime(row.getStartedAt())
                || !validNullableTime(row.getCompletedAt())
                || row.getAssignedAgentId() != null && !validId(row.getAssignedAgentId(), 100)
                || row.getCoordinatorAgentId() != null
                        && !validId(row.getCoordinatorAgentId(), 100)) {
            throw unavailable();
        }
    }

    private static boolean validNullableTime(Long value) {
        return value == null || value >= 0;
    }

    private static boolean sameActorMember(MemberRow candidate, MemberRow actor) {
        return candidate != null
                && Objects.equals(candidate.getTenantId(), actor.getTenantId())
                && Objects.equals(candidate.getClientId(), actor.getClientId())
                && Objects.equals(candidate.getTaskId(), actor.getTaskId())
                && Objects.equals(candidate.getAgentId(), actor.getAgentId())
                && Objects.equals(candidate.getMemberRole(), actor.getMemberRole())
                && Objects.equals(candidate.getMemberStatus(), actor.getMemberStatus())
                && Objects.equals(candidate.getAssignmentSource(), actor.getAssignmentSource())
                && Objects.equals(candidate.getVersion(), actor.getVersion());
    }

    private static boolean isExactTaskRow(
            TaskRow row, String tenantId, String clientId, String taskId) {
        return row != null && scope(row.getTenantId(), row.getClientId(), row.getTaskId(),
                tenantId, clientId, taskId);
    }

    private static boolean validActorMember(MemberRow row, String tenantId, String clientId,
            String taskId, String actorAgentId) {
        return validMember(row, tenantId, clientId, taskId)
                && actorAgentId.equals(row.getAgentId())
                && ACTOR_STATUSES.contains(
                        AgentTaskMemberStatus.fromPersistedValue(row.getMemberStatus()));
    }

    private static boolean validMember(
            MemberRow row, String tenantId, String clientId, String taskId) {
        if (row == null || !scope(row.getTenantId(), row.getClientId(), row.getTaskId(),
                tenantId, clientId, taskId)
                || !validId(row.getAgentId(), 100)
                || !MEMBER_ROLES.contains(row.getMemberRole())
                || !ASSIGNMENT_SOURCES.contains(row.getAssignmentSource())
                || row.getVersion() == null || row.getVersion() < 0) {
            return false;
        }
        try {
            AgentTaskMemberStatus.fromPersistedValue(row.getMemberStatus());
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean validWorkItem(
            WorkItemRow row, String tenantId, String clientId, String taskId) {
        if (row == null || !scope(row.getTenantId(), row.getClientId(), row.getTaskId(),
                tenantId, clientId, taskId)
                || !validId(row.getWorkItemId(), 100)
                || !validText(row.getTitle(), 255) || !validId(row.getWorkType(), 30)
                || (row.getAssigneeAgentId() != null
                        && !validId(row.getAssigneeAgentId(), 100))
                || (row.getResultArtifactId() != null
                        && !validId(row.getResultArtifactId(), 100))
                || row.getPriority() == null || row.getRequiredItem() == null
                || row.getAttemptCount() == null || row.getAttemptCount() < 0
                || row.getMaxAttempts() == null || row.getMaxAttempts() <= 0
                || row.getVersion() == null || row.getVersion() < 0) {
            return false;
        }
        try {
            AgentTaskWorkItemStatus.fromPersistedValue(row.getStatus());
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean validRequest(
            RequestRow row, String tenantId, String clientId, String taskId) {
        if (row == null || !scope(row.getTenantId(), row.getClientId(), row.getTaskId(),
                tenantId, clientId, taskId)
                || !validId(row.getRequestId(), 100)
                || !validId(row.getRequesterAgentId(), 100)
                || !TARGET_TYPES.contains(row.getTargetType())
                || !validId(row.getTargetId(), 100)
                || ("role".equals(row.getTargetType())
                        && !MEMBER_ROLES.contains(row.getTargetId()))
                || (row.getWorkItemId() != null && !validId(row.getWorkItemId(), 100))
                || !REQUEST_TYPES.contains(row.getRequestType())
                || !validText(row.getTitle(), 255) || row.getDescription() == null
                || row.getPriority() == null || row.getVersion() == null || row.getVersion() < 0) {
            return false;
        }
        try {
            AgentTaskRequestStatus status = AgentTaskRequestStatus.fromPersistedValue(row.getStatus());
            return status == AgentTaskRequestStatus.OPEN
                    || status == AgentTaskRequestStatus.ACKNOWLEDGED;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean validArtifact(
            ArtifactRow row, String tenantId, String clientId, String taskId) {
        return row != null
                && scope(row.getTenantId(), row.getClientId(), row.getTaskId(),
                        tenantId, clientId, taskId)
                && validId(row.getArtifactId(), 100)
                && (row.getWorkItemId() == null || validId(row.getWorkItemId(), 100))
                && validId(row.getProducerAgentId(), 100)
                && validId(row.getArtifactType(), 30)
                && validText(row.getTitle(), 255)
                && row.getArtifactVersion() != null && row.getArtifactVersion() > 0
                && VISIBILITIES.contains(row.getVisibility())
                && row.getCreatedAt() != null && row.getCreatedAt() > 0;
    }

    private static boolean scope(String rowTenant, String rowClient, String rowTask,
            String tenantId, String clientId, String taskId) {
        return tenantId.equals(rowTenant) && clientId.equals(rowClient) && taskId.equals(rowTask);
    }

    private static <T> List<T> complete(List<T> rows) {
        List<T> required = requiredList(rows);
        if (required.size() >= COMPLETE_COLLECTION_SENTINEL) {
            throw unavailable();
        }
        return required;
    }

    private static <T> List<T> requiredList(List<T> rows) {
        if (rows == null) {
            throw unavailable();
        }
        return rows;
    }

    private static String decimal(Long value) {
        if (value == null || value < 0) {
            throw unavailable();
        }
        return Long.toString(value);
    }

    private static String decimalNullable(Long value) {
        if (value == null) {
            return null;
        }
        return decimal(value);
    }

    private static boolean validId(String value, int maxLength) {
        return value != null && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= maxLength
                && !value.codePoints().allMatch(AgentTaskWorkspaceServiceImpl::isPadding)
                && !isPadding(value.codePointAt(0))
                && !isPadding(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean validText(String value, int maxLength) {
        return value != null && !value.isBlank() && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= maxLength
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static void requireId(String value, String name, int maxLength) {
        if (!validId(value, maxLength)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static AgentTaskWorkspaceException notFound() {
        return new AgentTaskWorkspaceException(Reason.NOT_FOUND_OR_FORBIDDEN);
    }

    private static AgentTaskWorkspaceException unavailable() {
        return new AgentTaskWorkspaceException(Reason.SNAPSHOT_UNAVAILABLE);
    }

    private static AgentTaskWorkspaceException unavailable(Throwable cause) {
        return new AgentTaskWorkspaceException(Reason.SNAPSHOT_UNAVAILABLE, cause);
    }

    private record Authorization(
            TaskRow task, MemberRow actor, AuthorizedSubject subject) {
    }

    private record Timeline(List<AgentTaskWorkspaceDTO.Event> events, boolean truncated) {
    }
}
