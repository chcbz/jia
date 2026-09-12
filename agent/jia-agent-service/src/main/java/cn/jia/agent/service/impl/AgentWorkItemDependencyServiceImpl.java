package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.entity.AgentWorkItemDependencyResolutionDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentWorkItemDependencyException;
import cn.jia.agent.exception.AgentWorkItemDependencyException.Reason;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentWorkItemDependencyService;
import cn.jia.agent.state.AgentTaskStatus;
import cn.jia.agent.state.AgentTaskWorkItemStatus;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * E04 task-scoped dependency resolver.
 *
 * <p>Frozen lock order: task root -&gt; every task work item by unsigned UTF-8 work-item ID and row
 * ID -&gt; pending-to-ready CAS in that same order -&gt; existing bounded WORK_ITEM_READY event append.
 * The event writer only re-enters the already-held task-root lock. The graph, all CAS updates, and
 * all events share one REQUIRED rollback boundary.</p>
 */
@Named
public class AgentWorkItemDependencyServiceImpl implements AgentWorkItemDependencyService {
    static final int MAX_WORK_ITEMS = 500;
    private static final int FETCH_LIMIT = MAX_WORK_ITEMS + 1;
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Comparator<AgentTaskWorkItemEntity> WORK_ITEM_ORDER =
            Comparator.nullsLast((left, right) -> {
                int byId = compareNullableUtf8Unsigned(
                        left.getWorkItemId(), right.getWorkItemId());
                if (byId != 0) {
                    return byId;
                }
                return Comparator.nullsLast(Long::compareTo).compare(left.getId(), right.getId());
            });

    private final AgentTaskWorkItemDao workItemDao;
    private final AgentTaskMutationTransaction mutationTransaction;
    private final AgentTaskEventWriter eventWriter;
    private final LongSupplier clock;

    @Inject
    public AgentWorkItemDependencyServiceImpl(
            AgentTaskWorkItemDao workItemDao,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter) {
        this(workItemDao, mutationTransaction, eventWriter, System::currentTimeMillis);
    }

    AgentWorkItemDependencyServiceImpl(
            AgentTaskWorkItemDao workItemDao,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter,
            LongSupplier clock) {
        this.workItemDao = Objects.requireNonNull(workItemDao, "workItemDao");
        this.mutationTransaction = Objects.requireNonNull(mutationTransaction, "mutationTransaction");
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentWorkItemDependencyResolutionDTO resolveReady(
            String tenantId, String clientId, String taskId) {
        requireScopeId(tenantId, "tenantId", 50);
        requireScopeId(clientId, "clientId", 50);
        requireScopeId(taskId, "taskId", 100);
        try {
            return mutationTransaction.executeWithLockedTaskRoot(
                    tenantId, clientId, taskId,
                    root -> resolveLocked(tenantId, clientId, taskId, root));
        } catch (AgentTaskCollaborationException failure) {
            if (failure.getReason() == AgentTaskCollaborationException.Reason.NOT_FOUND) {
                throw new AgentWorkItemDependencyException(
                        Reason.NOT_FOUND, "Task was not found in the requested scope");
            }
            throw invalidPersisted("Locked task root failed validation", failure);
        }
    }

    private AgentWorkItemDependencyResolutionDTO resolveLocked(
            String tenantId, String clientId, String taskId, AgentTaskMetaEntity root) {
        AgentTaskStatus taskStatus = validateRoot(root, tenantId, clientId, taskId);
        List<AgentTaskWorkItemEntity> fetched = workItemDao.listByTaskForUpdate(
                tenantId, clientId, taskId, FETCH_LIMIT);
        if (fetched == null) {
            throw invalidPersisted("Work-item graph query returned null");
        }
        if (fetched.size() > MAX_WORK_ITEMS) {
            throw invalidPersisted("Work-item dependency graph exceeds the bounded limit");
        }

        List<AgentTaskWorkItemEntity> rows = new ArrayList<>(fetched);
        rows.sort(WORK_ITEM_ORDER);
        Map<String, AgentTaskWorkItemEntity> byId = new LinkedHashMap<>();
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        int pendingCount = 0;
        for (AgentTaskWorkItemEntity row : rows) {
            AgentTaskWorkItemStatus status = validateRow(
                    row, tenantId, clientId, taskId);
            if (byId.put(row.getWorkItemId(), row) != null) {
                throw invalidPersisted("Work-item graph contains duplicate exact IDs");
            }
            dependencies.put(row.getWorkItemId(), parseDependencies(row));
            if (status == AgentTaskWorkItemStatus.PENDING) {
                pendingCount++;
            }
        }
        validateDependencyReferences(byId, dependencies);
        requireAcyclic(dependencies);

        List<String> readyIds = new ArrayList<>();
        if (!taskStatus.isOperationalTerminal()) {
            Long changedAt = null;
            for (AgentTaskWorkItemEntity row : rows) {
                if (AgentTaskWorkItemStatus.fromPersistedValue(row.getStatus())
                        != AgentTaskWorkItemStatus.PENDING) {
                    continue;
                }
                List<String> dependencyIds = dependencies.get(row.getWorkItemId());
                if (!allDependenciesAuthoritativelyCompleted(dependencyIds, byId)) {
                    continue;
                }
                if (changedAt == null) {
                    changedAt = now();
                }
                AgentTaskWorkItemDTO update = copy(row);
                update.setStatus(AgentTaskWorkItemStatus.READY.value());
                int updated = workItemDao.readyPendingByVersion(
                        tenantId, clientId, taskId, row.getWorkItemId(),
                        row.getVersion(), changedAt, update);
                if (updated == 0) {
                    throw new AgentWorkItemDependencyException(
                            Reason.VERSION_CONFLICT,
                            "Work-item dependency state changed concurrently; retry from a fresh snapshot");
                }
                if (updated != 1) {
                    throw invalidPersisted("Pending-to-ready CAS affected an unexpected row count");
                }
                appendReadyEvent(tenantId, clientId, taskId, row, changedAt);
                readyIds.add(row.getWorkItemId());
            }
        }

        AgentWorkItemDependencyResolutionDTO result = new AgentWorkItemDependencyResolutionDTO();
        result.setTaskId(taskId);
        result.setInspectedWorkItemCount(rows.size());
        result.setPendingWorkItemCount(pendingCount);
        result.setReadyWorkItemIds(List.copyOf(readyIds));
        result.setChanged(!readyIds.isEmpty());
        return result;
    }

    private AgentTaskStatus validateRoot(
            AgentTaskMetaEntity root, String tenantId, String clientId, String taskId) {
        if (root == null || !tenantId.equals(root.getTenantId())
                || !clientId.equals(root.getClientId()) || !taskId.equals(root.getTaskId())
                || root.getTaskVersion() == null || root.getTaskVersion() < 0
                || root.getCurrentEventVersion() == null || root.getCurrentEventVersion() < 0) {
            throw invalidPersisted("Locked task root is incomplete or out of scope");
        }
        try {
            return AgentTaskStatus.fromPersistedValue(root.getRewardStatus());
        } catch (IllegalArgumentException invalidStatus) {
            throw invalidPersisted("Task status is non-canonical", invalidStatus);
        }
    }

    private AgentTaskWorkItemStatus validateRow(
            AgentTaskWorkItemEntity row, String tenantId, String clientId, String taskId) {
        if (row == null || !tenantId.equals(row.getTenantId())
                || !clientId.equals(row.getClientId()) || !taskId.equals(row.getTaskId())) {
            throw invalidPersisted("Work-item graph contains an out-of-scope row");
        }
        requirePersistedId(row.getWorkItemId(), "workItemId");
        if (!validText(row.getTitle(), 255) || !validPersistedId(row.getWorkType())
                || row.getPriority() == null || row.getRequiredItem() == null
                || (row.getAssigneeAgentId() != null
                && !validPersistedId(row.getAssigneeAgentId()))) {
            throw invalidPersisted("Work-item graph contains an incomplete runtime snapshot");
        }
        if (row.getVersion() == null || row.getVersion() < 0
                || row.getVersion() == Long.MAX_VALUE
                || row.getAttemptCount() == null || row.getAttemptCount() < 0
                || row.getMaxAttempts() == null || row.getMaxAttempts() <= 0
                || row.getAttemptCount() > row.getMaxAttempts()) {
            throw invalidPersisted("Work-item runtime version or attempt metadata is invalid");
        }
        AgentTaskWorkItemStatus status;
        try {
            status = AgentTaskWorkItemStatus.fromPersistedValue(row.getStatus());
        } catch (IllegalArgumentException invalidStatus) {
            throw invalidPersisted("Work-item status is non-canonical", invalidStatus);
        }
        boolean activeLease = status == AgentTaskWorkItemStatus.CLAIMED
                || status == AgentTaskWorkItemStatus.RUNNING;
        if (activeLease) {
            if (!validPersistedId(row.getAssigneeAgentId())
                    || !validPersistedId(row.getLeaseToken())
                    || row.getLeaseUntil() == null || row.getLeaseUntil() <= 0) {
                throw invalidPersisted("Active work-item lease metadata is incomplete");
            }
        } else if (row.getLeaseToken() != null || row.getLeaseUntil() != null) {
            throw invalidPersisted("Non-active work item retains stale lease metadata");
        }
        if ((status == AgentTaskWorkItemStatus.PENDING
                || status == AgentTaskWorkItemStatus.READY)
                && row.getAttemptCount() >= row.getMaxAttempts()) {
            throw invalidPersisted("Schedulable work item has exhausted maxAttempts");
        }
        if (status == AgentTaskWorkItemStatus.COMPLETED
                && (!validPersistedId(row.getResultArtifactId())
                || row.getCompletedAt() == null || row.getCompletedAt() <= 0)) {
            throw invalidPersisted(
                    "Completed work item does not satisfy the accepted-result terminal contract");
        }
        boolean beforeSubmission = status == AgentTaskWorkItemStatus.PENDING
                || status == AgentTaskWorkItemStatus.READY
                || status == AgentTaskWorkItemStatus.CLAIMED
                || status == AgentTaskWorkItemStatus.RUNNING
                || status == AgentTaskWorkItemStatus.BLOCKED;
        if (beforeSubmission && (row.getResultArtifactId() != null
                || row.getSubmittedAt() != null || row.getCompletedAt() != null)) {
            throw invalidPersisted("Pre-submission work item retains result metadata");
        }
        return status;
    }

    private List<String> parseDependencies(AgentTaskWorkItemEntity row) {
        String json = row.getDependencyJson();
        if (json == null) {
            return List.of();
        }
        if (json.isEmpty() || !json.equals(json.strip())) {
            throw invalidPersisted("dependency_json must be an exact JSON array");
        }
        try {
            JsonNode node = STRICT_JSON.readTree(json);
            if (node == null || !node.isArray()) {
                throw invalidPersisted("dependency_json must be a JSON array");
            }
            if (node.size() > MAX_WORK_ITEMS) {
                throw invalidPersisted("dependency_json contains too many entries");
            }
            List<String> result = new ArrayList<>(node.size());
            Set<String> unique = new HashSet<>();
            for (JsonNode value : node) {
                if (!value.isTextual()) {
                    throw invalidPersisted("dependency_json contains a non-string entry");
                }
                String dependencyId = value.textValue();
                requirePersistedId(dependencyId, "dependency workItemId");
                if (row.getWorkItemId().equals(dependencyId)) {
                    throw invalidPersisted("Work item cannot depend on itself");
                }
                if (!unique.add(dependencyId)) {
                    throw invalidPersisted("dependency_json contains a duplicate exact ID");
                }
                result.add(dependencyId);
            }
            result.sort(AgentWorkItemDependencyServiceImpl::compareUtf8Unsigned);
            return List.copyOf(result);
        } catch (AgentWorkItemDependencyException failure) {
            throw failure;
        } catch (Exception malformed) {
            throw invalidPersisted("dependency_json is malformed", malformed);
        }
    }

    private void validateDependencyReferences(
            Map<String, AgentTaskWorkItemEntity> byId,
            Map<String, List<String>> dependencies) {
        for (Map.Entry<String, List<String>> entry : dependencies.entrySet()) {
            for (String dependencyId : entry.getValue()) {
                if (!byId.containsKey(dependencyId)) {
                    throw invalidPersisted(
                            "dependency_json references an unknown or cross-task work item");
                }
            }
        }
    }

    private void requireAcyclic(Map<String, List<String>> dependencies) {
        Map<String, VisitState> states = new HashMap<>();
        for (String workItemId : dependencies.keySet()) {
            visit(workItemId, dependencies, states);
        }
    }

    private void visit(String workItemId, Map<String, List<String>> dependencies,
            Map<String, VisitState> states) {
        VisitState state = states.get(workItemId);
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            throw invalidPersisted("Work-item dependency graph contains a cycle");
        }
        states.put(workItemId, VisitState.VISITING);
        for (String dependencyId : dependencies.get(workItemId)) {
            visit(dependencyId, dependencies, states);
        }
        states.put(workItemId, VisitState.VISITED);
    }

    private boolean allDependenciesAuthoritativelyCompleted(
            List<String> dependencyIds, Map<String, AgentTaskWorkItemEntity> byId) {
        for (String dependencyId : dependencyIds) {
            AgentTaskWorkItemEntity dependency = byId.get(dependencyId);
            AgentTaskWorkItemStatus status = AgentTaskWorkItemStatus.fromPersistedValue(
                    dependency.getStatus());
            if (status != AgentTaskWorkItemStatus.COMPLETED) {
                return false;
            }
            if (!validPersistedId(dependency.getResultArtifactId())
                    || dependency.getCompletedAt() == null || dependency.getCompletedAt() <= 0) {
                throw invalidPersisted(
                        "Completed dependency does not satisfy the accepted-result terminal contract");
            }
        }
        return true;
    }

    private void appendReadyEvent(String tenantId, String clientId, String taskId,
            AgentTaskWorkItemEntity row, long changedAt) {
        long resultVersion = row.getVersion() + 1;
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.WORK_ITEM_ID, row.getWorkItemId())
                .put(TaskEventPayload.Key.FROM_STATUS, AgentTaskWorkItemStatus.PENDING.value())
                .put(TaskEventPayload.Key.TO_STATUS, AgentTaskWorkItemStatus.READY.value())
                .put(TaskEventPayload.Key.EXPECTED_VERSION, row.getVersion())
                .put(TaskEventPayload.Key.RESULT_VERSION, resultVersion)
                .put(TaskEventPayload.Key.SOURCE, "dependency_completion")
                .put(TaskEventPayload.Key.UPDATED_AT, changedAt);
        eventWriter.append(AgentTaskMutationEventSupport.command(
                tenantId, clientId, taskId, TaskEventType.WORK_ITEM_READY,
                TaskEventType.ActorType.SYSTEM, null, TaskEventType.Aggregate.WORK_ITEM,
                row.getWorkItemId(), payload, changedAt, resultVersion));
    }

    private AgentTaskWorkItemDTO copy(AgentTaskWorkItemEntity row) {
        AgentTaskWorkItemDTO update = new AgentTaskWorkItemDTO();
        update.setWorkItemId(row.getWorkItemId());
        update.setTaskId(row.getTaskId());
        update.setTitle(row.getTitle());
        update.setDescription(row.getDescription());
        update.setWorkType(row.getWorkType());
        update.setRequiredAbilities(row.getRequiredAbilities());
        update.setAssigneeAgentId(row.getAssigneeAgentId());
        update.setStatus(row.getStatus());
        update.setPriority(row.getPriority());
        update.setRequiredItem(row.getRequiredItem());
        update.setDependencyJson(row.getDependencyJson());
        update.setLeaseToken(row.getLeaseToken());
        update.setLeaseUntil(row.getLeaseUntil());
        update.setAttemptCount(row.getAttemptCount());
        update.setMaxAttempts(row.getMaxAttempts());
        update.setResultArtifactId(row.getResultArtifactId());
        update.setSubmittedAt(row.getSubmittedAt());
        update.setCompletedAt(row.getCompletedAt());
        update.setVersion(row.getVersion());
        return update;
    }

    private long now() {
        long value = clock.getAsLong();
        if (value <= 0) {
            throw invalidPersisted("Dependency scheduler clock returned a non-positive timestamp");
        }
        return value;
    }

    private void requireScopeId(String value, String name, int maxLength) {
        if (!validPersistedId(value) || value.length() > maxLength) {
            throw new AgentWorkItemDependencyException(
                    Reason.INVALID_REQUEST, name + " is invalid");
        }
    }

    private void requirePersistedId(String value, String name) {
        if (!validPersistedId(value)) {
            throw invalidPersisted(name + " is non-canonical");
        }
    }

    private boolean validPersistedId(String value) {
        return value != null && !value.isEmpty() && value.length() <= 100
                && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private boolean validText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength
                && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static int compareNullableUtf8Unsigned(String left, String right) {
        if (left == null) {
            return right == null ? 0 : 1;
        }
        if (right == null) {
            return -1;
        }
        return compareUtf8Unsigned(left, right);
    }

    static int compareUtf8Unsigned(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < length; index++) {
            int compared = Integer.compare(
                    Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    private AgentWorkItemDependencyException invalidPersisted(String message) {
        return new AgentWorkItemDependencyException(Reason.INVALID_PERSISTED_STATE, message);
    }

    private AgentWorkItemDependencyException invalidPersisted(
            String message, Throwable cause) {
        return new AgentWorkItemDependencyException(
                Reason.INVALID_PERSISTED_STATE, message, cause);
    }

    private enum VisitState {
        VISITING,
        VISITED
    }
}
