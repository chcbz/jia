package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.entity.AgentWorkItemPlanConfirmRequestDTO;
import cn.jia.agent.entity.AgentWorkItemPlanItemDTO;
import cn.jia.agent.entity.AgentWorkItemPlanItemViewDTO;
import cn.jia.agent.entity.AgentWorkItemPlanSuggestRequestDTO;
import cn.jia.agent.entity.AgentWorkItemPlanViewDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentWorkItemPlanException;
import cn.jia.agent.exception.AgentWorkItemPlanException.Reason;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentWorkItemPlanService;
import cn.jia.agent.state.AgentTaskMemberStatus;
import cn.jia.agent.state.AgentTaskStatus;
import cn.jia.agent.state.AgentTaskWorkItemStatus;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * E03 provider-free decomposition and manual confirmation boundary.
 *
 * <p>Lock order for confirmation is task root -> actor identity lock/member read -> existing
 * work-item/event probes -> child inserts in deterministic ID order -> created events in the same
 * order -> task version CAS. The event writer only re-enters the already-held task-root lock.
 * Suggestion never
 * writes. Confirmation creates only unassigned pending/ready rows; it never claims, dispatches or
 * calls a model/provider.</p>
 */
@Named
public class AgentWorkItemPlanServiceImpl implements AgentWorkItemPlanService {
    static final int MAX_ITEMS = 8;
    private static final int DEFAULT_MAX_ITEMS = 4;
    private static final int MAX_OBJECTIVE_UTF8 = 8 * 1024;
    private static final int MAX_DESCRIPTION_UTF8 = 65_535;
    private static final int MAX_ABILITIES = 32;
    private static final int MAX_ATTEMPTS = 10;
    private static final Set<String> DEPENDENCY_MODES = Set.of("parallel", "sequential");
    private static final Set<String> WORK_TYPES = Set.of(
            "analysis", "implementation", "verification", "review",
            "coordination", "documentation");
    private static final Pattern ITEM_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern BULLET_PREFIX = Pattern.compile(
            "^(?:(?:[-*•]|[0-9]{1,4}[.)、])\\s*)+");
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9._~:/+\\-]{8,128}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DECIMAL = Pattern.compile("0|[1-9][0-9]{0,18}");
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final AgentTaskMetaDao taskMetaDao;
    private final AgentTaskEventDao eventDao;
    private final AgentTaskMemberDao memberDao;
    private final AgentTaskWorkItemDao workItemDao;
    private final AgentIdentityService identityService;
    private final AgentTaskMutationTransaction mutationTransaction;
    private final AgentTaskEventWriter eventWriter;
    private final LongSupplier clock;

    @Inject
    public AgentWorkItemPlanServiceImpl(
            AgentTaskMetaDao taskMetaDao,
            AgentTaskEventDao eventDao,
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao,
            AgentIdentityService identityService,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter) {
        this(taskMetaDao, eventDao, memberDao, workItemDao, identityService,
                mutationTransaction, eventWriter, System::currentTimeMillis);
    }

    AgentWorkItemPlanServiceImpl(
            AgentTaskMetaDao taskMetaDao,
            AgentTaskEventDao eventDao,
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao,
            AgentIdentityService identityService,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter,
            LongSupplier clock) {
        this.taskMetaDao = Objects.requireNonNull(taskMetaDao, "taskMetaDao");
        this.eventDao = Objects.requireNonNull(eventDao, "eventDao");
        this.memberDao = Objects.requireNonNull(memberDao, "memberDao");
        this.workItemDao = Objects.requireNonNull(workItemDao, "workItemDao");
        this.identityService = Objects.requireNonNull(identityService, "identityService");
        this.mutationTransaction = Objects.requireNonNull(mutationTransaction, "mutationTransaction");
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true, rollbackFor = Exception.class)
    public AgentWorkItemPlanViewDTO suggest(
            String tenantId, String clientId, String taskId, String actorAgentId,
            AgentWorkItemPlanSuggestRequestDTO request) {
        requireScope(tenantId, clientId, taskId, actorAgentId);
        if (request == null) {
            throw invalid("Suggestion request is required");
        }
        requireUtf8Text(request.getObjective(), "objective", MAX_OBJECTIVE_UTF8, false);
        int maxItems = request.getMaxItems() == null ? DEFAULT_MAX_ITEMS : request.getMaxItems();
        if (maxItems < 1 || maxItems > MAX_ITEMS) {
            throw invalid("maxItems must be between 1 and " + MAX_ITEMS);
        }
        String dependencyMode = request.getDependencyMode() == null
                ? "sequential" : request.getDependencyMode();
        if (!DEPENDENCY_MODES.contains(dependencyMode)) {
            throw invalid("dependencyMode must be parallel or sequential");
        }

        AgentTaskMetaEntity task = requireReadableTask(
                tenantId, clientId, taskId, actorAgentId);
        List<String> taskAbilities = parseAbilities(task.getRequiredAbilities(), "task abilities");
        List<AgentWorkItemPlanItemDTO> suggested = decompose(
                request.getObjective(), maxItems, dependencyMode,
                Boolean.TRUE.equals(task.getReviewRequired()), taskAbilities);
        List<NormalizedItem> normalized = normalizeItems(suggested);
        String digest = planDigest(normalized);
        long taskVersion = requireTaskVersion(task);

        AgentWorkItemPlanViewDTO result = baseView(taskId, normalized);
        result.setSourcePlanDigest(digest);
        result.setSourcePlanId(sourcePlanId(
                tenantId, clientId, taskId, actorAgentId, taskVersion, digest));
        result.setExpectedTaskVersion(Long.toString(taskVersion));
        result.setConfirmationRequired(true);
        result.setConfirmed(false);
        result.setIdempotentReplay(false);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentWorkItemPlanViewDTO confirm(
            String tenantId, String clientId, String taskId, String actorAgentId,
            String idempotencyKey, AgentWorkItemPlanConfirmRequestDTO request) {
        requireScope(tenantId, clientId, taskId, actorAgentId);
        if (!validIdempotencyKey(idempotencyKey) || request == null
                || !Boolean.TRUE.equals(request.getConfirmed())) {
            throw invalid("Explicit confirmation and a valid idempotency key are required");
        }
        long expectedTaskVersion = parseVersion(request.getExpectedTaskVersion());
        requireDigest(request.getSourcePlanDigest(), "sourcePlanDigest");
        String expectedSourcePlanId = sourcePlanId(tenantId, clientId, taskId, actorAgentId,
                expectedTaskVersion, request.getSourcePlanDigest());
        if (!expectedSourcePlanId.equals(request.getSourcePlanId())) {
            throw notFound();
        }
        List<NormalizedItem> normalized = normalizeItems(request.getItems());
        String confirmedDigest = planDigest(normalized);
        String requestDigest = confirmationRequestDigest(request, confirmedDigest);

        return withLockedTaskRoot(tenantId, clientId, taskId,
                taskRoot -> confirmLocked(tenantId, clientId, taskId, actorAgentId,
                        idempotencyKey, request, expectedTaskVersion,
                        confirmedDigest, requestDigest, normalized, taskRoot));
    }

    private <T> T withLockedTaskRoot(
            String tenantId, String clientId, String taskId,
            AgentTaskMutationTransaction.LockedTaskMutation<T> mutation) {
        try {
            return mutationTransaction.executeWithLockedTaskRoot(
                    tenantId, clientId, taskId, mutation);
        } catch (AgentTaskCollaborationException failure) {
            if (failure.getReason() == AgentTaskCollaborationException.Reason.NOT_FOUND) {
                throw notFound();
            }
            throw invalidPersisted("Locked task root failed validation");
        }
    }

    private AgentWorkItemPlanViewDTO confirmLocked(
            String tenantId, String clientId, String taskId, String actorAgentId,
            String idempotencyKey, AgentWorkItemPlanConfirmRequestDTO request,
            long expectedTaskVersion, String confirmedDigest, String requestDigest,
            List<NormalizedItem> items, AgentTaskMetaEntity task) {
        requireCoordinator(tenantId, clientId, taskId, actorAgentId, task, true);
        Map<String, String> workItemIds = new LinkedHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            NormalizedItem item = items.get(index);
            workItemIds.put(item.itemKey(), workItemId(
                    tenantId, clientId, taskId, idempotencyKey, item.itemKey(), index == 0));
        }
        List<PersistedPlanItem> planned = items.stream()
                .map(item -> persisted(item, taskId, workItemIds))
                .toList();

        int existingCount = 0;
        List<AgentTaskWorkItemEntity> existingRows = new ArrayList<>(planned.size());
        for (PersistedPlanItem item : planned) {
            AgentTaskWorkItemEntity existing = workItemDao.findByTaskAndWorkItemId(
                    tenantId, clientId, taskId, item.workItemId());
            existingRows.add(existing);
            if (existing != null) {
                existingCount++;
                requireExactPersistedScope(existing, tenantId, clientId, taskId, item.workItemId());
                if (!sameImmutablePlan(existing, item)) {
                    throw idempotencyConflict();
                }
                requireValidRuntimeSnapshot(existing);
            }
        }
        if (existingCount != 0 && existingCount != planned.size()) {
            throw idempotencyConflict();
        }
        List<AgentTaskEventEntity> existingEvents = new ArrayList<>(planned.size());
        for (PersistedPlanItem item : planned) {
            String eventId = createdEventId(tenantId, clientId, taskId, item.workItemId());
            existingEvents.add(eventDao.findByEventId(tenantId, clientId, eventId));
        }
        if (existingCount == planned.size()) {
            for (int index = 0; index < planned.size(); index++) {
                PersistedPlanItem item = planned.get(index);
                String eventId = createdEventId(tenantId, clientId, taskId, item.workItemId());
                requireConfirmationEvent(existingEvents.get(index), tenantId, clientId, taskId,
                        eventId, item, requestDigest);
            }
            return confirmedView(taskId, request, confirmedDigest, items,
                    planned, existingRows, true);
        }
        if (existingEvents.stream().anyMatch(Objects::nonNull)) {
            throw invalidPersisted("Confirmation event exists without its complete work-item plan");
        }
        if (requireTaskVersion(task) != expectedTaskVersion) {
            throw conflict();
        }

        long occurredAt = now();
        List<PersistedPlanItem> writeOrder = planned.stream()
                .sorted(Comparator.comparing(PersistedPlanItem::workItemId))
                .toList();
        for (PersistedPlanItem item : writeOrder) {
            try {
                int inserted = workItemDao.insert(
                        tenantId, clientId, insertDto(taskId, item));
                if (inserted != 1) {
                    throw invalidPersisted("Work item insert did not affect exactly one row");
                }
            } catch (DuplicateKeyException duplicate) {
                throw new AgentWorkItemPlanException(Reason.IDEMPOTENCY_CONFLICT,
                        "Idempotency key collides with an existing work item", duplicate);
            } catch (DataIntegrityViolationException invalidRow) {
                throw new AgentWorkItemPlanException(Reason.INVALID_PERSISTED_STATE,
                        "Confirmed work item could not be persisted", invalidRow);
            }
        }
        for (PersistedPlanItem item : writeOrder) {
            appendCreatedEvent(tenantId, clientId, taskId, item, requestDigest, occurredAt);
        }
        int taskUpdated = taskMetaDao.updateStatusByVersion(tenantId, clientId, taskId,
                expectedTaskVersion, task.getRewardStatus(), task.getStartedAt(),
                task.getCompletedAt(), task.getFailureReason());
        if (taskUpdated != 1) {
            throw conflict();
        }

        List<AgentTaskWorkItemEntity> insertedRows = new ArrayList<>(planned.size());
        for (PersistedPlanItem item : planned) {
            AgentTaskWorkItemEntity stored = workItemDao.findByTaskAndWorkItemId(
                    tenantId, clientId, taskId, item.workItemId());
            requireExactPersistedScope(stored, tenantId, clientId, taskId, item.workItemId());
            if (!sameImmutablePlan(stored, item) || stored.getVersion() == null
                    || stored.getVersion() != 0L) {
                throw invalidPersisted("Inserted work item does not match the confirmed plan");
            }
            insertedRows.add(stored);
        }
        return confirmedView(taskId, request, confirmedDigest, items,
                planned, insertedRows, false);
    }

    private AgentTaskMetaEntity requireReadableTask(
            String tenantId, String clientId, String taskId, String actorAgentId) {
        AgentTaskMetaEntity task = taskMetaDao.findByTaskId(tenantId, clientId, taskId);
        requireCoordinator(tenantId, clientId, taskId, actorAgentId, task, false);
        return task;
    }

    private void requireCoordinator(
            String tenantId, String clientId, String taskId, String actorAgentId,
            AgentTaskMetaEntity task, boolean lockIdentity) {
        if (task == null) {
            throw notFound();
        }
        if (!tenantId.equals(task.getTenantId()) || !clientId.equals(task.getClientId())
                || !taskId.equals(task.getTaskId())) {
            throw invalidPersisted("Task lookup returned a mismatched scope");
        }
        AgentTaskStatus status;
        try {
            status = AgentTaskStatus.fromPersistedValue(task.getRewardStatus());
        } catch (IllegalArgumentException invalidStatus) {
            throw invalidPersisted("Task status is not canonical");
        }
        if (status.isOperationalTerminal()) {
            throw conflict();
        }
        if (!actorAgentId.equals(task.getCoordinatorAgentId())) {
            throw notFound();
        }
        try {
            if (lockIdentity) {
                List<String> locked = identityService.lockActiveCanonicalAgentIdsInScope(
                        tenantId, clientId, tenantId, List.of(actorAgentId));
                if (!List.of(actorAgentId).equals(locked)) {
                    throw notFound();
                }
            } else {
                String canonical = identityService.requireCanonicalAgentIdInScope(
                        tenantId, clientId, tenantId, actorAgentId);
                if (!actorAgentId.equals(canonical)) {
                    throw notFound();
                }
            }
        } catch (AgentServiceImpl.AgentBizException denied) {
            throw notFound();
        }
        AgentTaskMemberEntity member = memberDao.findByTaskAndAgent(
                tenantId, clientId, taskId, actorAgentId);
        if (member == null || !tenantId.equals(member.getTenantId())
                || !clientId.equals(member.getClientId())
                || !taskId.equals(member.getTaskId())
                || !actorAgentId.equals(member.getAgentId())) {
            throw notFound();
        }
        if (!"coordinator".equals(member.getMemberRole())) {
            throw notFound();
        }
        AgentTaskMemberStatus memberStatus;
        try {
            memberStatus = AgentTaskMemberStatus.fromPersistedValue(member.getMemberStatus());
        } catch (IllegalArgumentException invalidStatus) {
            throw invalidPersisted("Coordinator member status is not canonical");
        }
        if (!Set.of(AgentTaskMemberStatus.ACCEPTED, AgentTaskMemberStatus.WORKING,
                AgentTaskMemberStatus.BLOCKED).contains(memberStatus)) {
            throw notFound();
        }
        if (member.getVersion() == null || member.getVersion() < 0
                || !Set.of("manual", "auto", "migration", "legacy")
                .contains(member.getAssignmentSource())) {
            throw invalidPersisted("Coordinator member metadata is invalid");
        }
        requireTaskVersion(task);
        if (task.getCurrentEventVersion() == null || task.getCurrentEventVersion() < 0
                || task.getReviewRequired() == null) {
            throw invalidPersisted("Task planning metadata is incomplete");
        }
    }

    private List<AgentWorkItemPlanItemDTO> decompose(
            String objective, int maxItems, String dependencyMode,
            boolean reviewRequired, List<String> abilities) {
        List<String> clauses = clauses(objective);
        List<AgentWorkItemPlanItemDTO> result = new ArrayList<>();
        if (clauses.size() == 1) {
            result.add(item("item-1", shortTitle(clauses.getFirst()), clauses.getFirst(),
                    "implementation", abilities, List.of()));
            if (maxItems >= 2) {
                result.add(item("item-2", "Verify confirmed outcome", clauses.getFirst(),
                        "verification", abilities, List.of("item-1")));
            }
            if (reviewRequired && maxItems >= 3) {
                result.add(item("item-3", "Review confirmed deliverables", clauses.getFirst(),
                        "review", abilities, List.of("item-2")));
            }
            return result;
        }

        int clauseLimit = reviewRequired && maxItems > 1 ? maxItems - 1 : maxItems;
        List<String> bounded = mergeOverflow(clauses, clauseLimit);
        for (int index = 0; index < bounded.size(); index++) {
            String key = "item-" + (index + 1);
            List<String> dependencies = "sequential".equals(dependencyMode) && index > 0
                    ? List.of("item-" + index) : List.of();
            result.add(item(key, shortTitle(bounded.get(index)), bounded.get(index),
                    "implementation", abilities, dependencies));
        }
        if (reviewRequired && result.size() < maxItems) {
            List<String> all = result.stream().map(AgentWorkItemPlanItemDTO::getItemKey).toList();
            result.add(item("item-" + (result.size() + 1), "Review confirmed deliverables",
                    objective, "review", abilities, all));
        }
        return result;
    }

    private AgentWorkItemPlanItemDTO item(
            String key, String title, String description, String type,
            List<String> abilities, List<String> dependencies) {
        AgentWorkItemPlanItemDTO item = new AgentWorkItemPlanItemDTO();
        item.setItemKey(key);
        item.setTitle(title);
        item.setDescription(description);
        item.setWorkType(type);
        item.setRequiredAbilities(List.copyOf(abilities));
        item.setPriority(0);
        item.setRequiredItem(true);
        item.setDependsOn(List.copyOf(dependencies));
        item.setMaxAttempts(3);
        return item;
    }

    private List<String> clauses(String objective) {
        List<String> clauses = new ArrayList<>();
        for (String line : objective.replace('；', ';').split("[\\r\\n;]+")) {
            String clean = stripBullet(line);
            if (!clean.isEmpty()) {
                clauses.add(clean);
            }
        }
        if (clauses.isEmpty()) {
            throw invalid("objective must contain visible text");
        }
        return clauses;
    }

    private String stripBullet(String value) {
        return BULLET_PREFIX.matcher(value.strip()).replaceFirst("").stripLeading();
    }

    private List<String> mergeOverflow(List<String> clauses, int limit) {
        if (clauses.size() <= limit) {
            return List.copyOf(clauses);
        }
        List<String> result = new ArrayList<>(clauses.subList(0, limit - 1));
        result.add(String.join("; ", clauses.subList(limit - 1, clauses.size())));
        return result;
    }

    private String shortTitle(String value) {
        int end = value.offsetByCodePoints(0, Math.min(80, value.codePointCount(0, value.length())));
        return value.substring(0, end);
    }

    private List<NormalizedItem> normalizeItems(List<AgentWorkItemPlanItemDTO> input) {
        if (input == null || input.isEmpty() || input.size() > MAX_ITEMS) {
            throw invalid("items must contain between 1 and " + MAX_ITEMS + " entries");
        }
        Map<String, Integer> keyOrder = new LinkedHashMap<>();
        for (int index = 0; index < input.size(); index++) {
            AgentWorkItemPlanItemDTO item = input.get(index);
            if (item == null || item.getItemKey() == null
                    || !ITEM_KEY.matcher(item.getItemKey()).matches()
                    || keyOrder.put(item.getItemKey(), index) != null) {
                throw invalid("itemKey must be unique and canonical");
            }
        }

        List<NormalizedItem> normalized = new ArrayList<>(input.size());
        for (AgentWorkItemPlanItemDTO item : input) {
            requireText(item.getTitle(), "title", 255);
            requireUtf8Text(item.getDescription(), "description", MAX_DESCRIPTION_UTF8, true);
            if (!WORK_TYPES.contains(item.getWorkType())) {
                throw invalid("workType is not supported");
            }
            List<String> abilities = normalizeAbilities(item.getRequiredAbilities());
            int priority = item.getPriority() == null ? 0 : item.getPriority();
            if (priority < -1000 || priority > 1000) {
                throw invalid("priority is out of range");
            }
            boolean required = item.getRequiredItem() == null || item.getRequiredItem();
            int maxAttempts = item.getMaxAttempts() == null ? 3 : item.getMaxAttempts();
            if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS) {
                throw invalid("maxAttempts is out of range");
            }
            List<String> dependencies = item.getDependsOn() == null
                    ? List.of() : new ArrayList<>(item.getDependsOn());
            if (dependencies.size() > MAX_ITEMS || new HashSet<>(dependencies).size()
                    != dependencies.size()) {
                throw invalid("dependsOn contains too many or duplicate entries");
            }
            for (String dependency : dependencies) {
                if (!keyOrder.containsKey(dependency) || item.getItemKey().equals(dependency)) {
                    throw invalid("dependsOn must reference another item in this plan");
                }
            }
            dependencies.sort(Comparator.comparingInt(keyOrder::get));
            normalized.add(new NormalizedItem(item.getItemKey(), item.getTitle(),
                    item.getDescription(), item.getWorkType(), abilities, priority,
                    required, List.copyOf(dependencies), maxAttempts));
        }
        requireAcyclic(normalized);
        return List.copyOf(normalized);
    }

    private void requireAcyclic(List<NormalizedItem> items) {
        Map<String, NormalizedItem> byKey = new HashMap<>();
        items.forEach(item -> byKey.put(item.itemKey(), item));
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (NormalizedItem item : items) {
            visit(item.itemKey(), byKey, visiting, visited);
        }
    }

    private void visit(String key, Map<String, NormalizedItem> byKey,
            Set<String> visiting, Set<String> visited) {
        if (visited.contains(key)) {
            return;
        }
        if (!visiting.add(key)) {
            throw invalid("dependsOn must not contain a cycle");
        }
        for (String dependency : byKey.get(key).dependsOn()) {
            visit(dependency, byKey, visiting, visited);
        }
        visiting.remove(key);
        visited.add(key);
    }

    private List<String> normalizeAbilities(List<String> abilities) {
        if (abilities == null) {
            return List.of();
        }
        if (abilities.size() > MAX_ABILITIES) {
            throw invalid("requiredAbilities contains too many entries");
        }
        List<String> result = new ArrayList<>(abilities.size());
        Set<String> unique = new HashSet<>();
        for (String ability : abilities) {
            requireExact(ability, "requiredAbility", 100);
            if (!unique.add(ability)) {
                throw invalid("requiredAbilities contains a duplicate");
            }
            result.add(ability);
        }
        return List.copyOf(result);
    }

    private List<String> parseAbilities(String json, String field) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode node = STRICT_JSON.readTree(json);
            if (node == null || !node.isArray()) {
                throw invalidPersisted(field + " is not a JSON array");
            }
            List<String> values = new ArrayList<>();
            for (JsonNode value : node) {
                if (!value.isTextual()) {
                    throw invalidPersisted(field + " contains a non-string entry");
                }
                values.add(value.textValue());
            }
            try {
                return normalizeAbilities(values);
            } catch (AgentWorkItemPlanException invalidValues) {
                if (invalidValues.getReason() == Reason.INVALID_REQUEST) {
                    throw invalidPersisted(field + " contains invalid ability metadata");
                }
                throw invalidValues;
            }
        } catch (AgentWorkItemPlanException failure) {
            throw failure;
        } catch (Exception malformed) {
            throw invalidPersisted(field + " is malformed");
        }
    }

    private PersistedPlanItem persisted(
            NormalizedItem item, String taskId, Map<String, String> workItemIds) {
        List<String> dependencyIds = item.dependsOn().stream().map(workItemIds::get).toList();
        String abilityJson = json(item.requiredAbilities(), "requiredAbilities");
        String dependencyJson = json(dependencyIds, "dependsOn");
        String status = dependencyIds.isEmpty()
                ? AgentTaskWorkItemStatus.READY.value()
                : AgentTaskWorkItemStatus.PENDING.value();
        return new PersistedPlanItem(item, workItemIds.get(item.itemKey()), taskId,
                abilityJson, dependencyJson, status);
    }

    private AgentTaskWorkItemDTO insertDto(String taskId, PersistedPlanItem item) {
        AgentTaskWorkItemDTO dto = new AgentTaskWorkItemDTO();
        dto.setWorkItemId(item.workItemId());
        dto.setTaskId(taskId);
        dto.setTitle(item.item().title());
        dto.setDescription(item.item().description());
        dto.setWorkType(item.item().workType());
        dto.setRequiredAbilities(item.abilityJson());
        dto.setAssigneeAgentId(null);
        dto.setStatus(item.status());
        dto.setPriority(item.item().priority());
        dto.setRequiredItem(item.item().requiredItem());
        dto.setDependencyJson(item.dependencyJson());
        dto.setLeaseToken(null);
        dto.setLeaseUntil(null);
        dto.setAttemptCount(0);
        dto.setMaxAttempts(item.item().maxAttempts());
        dto.setResultArtifactId(null);
        dto.setSubmittedAt(null);
        dto.setCompletedAt(null);
        return dto;
    }

    private boolean sameImmutablePlan(AgentTaskWorkItemEntity row, PersistedPlanItem item) {
        try {
            AgentTaskWorkItemStatus.fromPersistedValue(row.getStatus());
        } catch (IllegalArgumentException invalidStatus) {
            throw invalidPersisted("Persisted work item status is not canonical");
        }
        return Objects.equals(row.getTitle(), item.item().title())
                && Objects.equals(row.getDescription(), item.item().description())
                && Objects.equals(row.getWorkType(), item.item().workType())
                && Objects.equals(row.getRequiredAbilities(), item.abilityJson())
                && Objects.equals(row.getDependencyJson(), item.dependencyJson())
                && Objects.equals(row.getPriority(), item.item().priority())
                && Objects.equals(row.getRequiredItem(), item.item().requiredItem())
                && Objects.equals(row.getMaxAttempts(), item.item().maxAttempts());
    }

    private void requireValidRuntimeSnapshot(AgentTaskWorkItemEntity row) {
        if (row.getVersion() == null || row.getVersion() < 0
                || row.getAttemptCount() == null || row.getAttemptCount() < 0
                || row.getMaxAttempts() == null || row.getMaxAttempts() < 1
                || row.getAttemptCount() > row.getMaxAttempts()) {
            throw invalidPersisted("Persisted work item runtime metadata is invalid");
        }
    }

    private void requireExactPersistedScope(AgentTaskWorkItemEntity row,
            String tenantId, String clientId, String taskId, String workItemId) {
        if (row == null || !tenantId.equals(row.getTenantId())
                || !clientId.equals(row.getClientId()) || !taskId.equals(row.getTaskId())
                || !workItemId.equals(row.getWorkItemId())) {
            throw invalidPersisted("Work item lookup returned an incomplete or mismatched row");
        }
    }

    private void appendCreatedEvent(String tenantId, String clientId, String taskId,
            PersistedPlanItem item, String requestDigest, long occurredAt) {
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.WORK_ITEM_ID, item.workItemId())
                .put(TaskEventPayload.Key.TO_STATUS, item.status())
                .put(TaskEventPayload.Key.SOURCE, "manual_confirmation")
                .put(TaskEventPayload.Key.DECISION_CODE, requestDigest)
                .put(TaskEventPayload.Key.ATTEMPT_COUNT, 0L)
                .put(TaskEventPayload.Key.MAX_ATTEMPTS, item.item().maxAttempts().longValue())
                .put(TaskEventPayload.Key.RESULT_VERSION, 0L)
                .put(TaskEventPayload.Key.CREATED_AT, occurredAt);
        eventWriter.append(AgentTaskMutationEventSupport.command(
                tenantId, clientId, taskId, TaskEventType.WORK_ITEM_CREATED,
                TaskEventType.ActorType.SYSTEM, null, TaskEventType.Aggregate.WORK_ITEM,
                item.workItemId(), payload, occurredAt, 0L));
    }

    private String confirmationRequestDigest(
            AgentWorkItemPlanConfirmRequestDTO request, String confirmedDigest) {
        return sha256(request.getSourcePlanId() + '\0' + request.getSourcePlanDigest() + '\0'
                + request.getExpectedTaskVersion() + '\0' + confirmedDigest);
    }

    private String createdEventId(
            String tenantId, String clientId, String taskId, String workItemId) {
        String seed = tenantId + '\0' + clientId + '\0' + taskId + '\0'
                + TaskEventType.WORK_ITEM_CREATED + '\0' + TaskEventType.Aggregate.WORK_ITEM
                + '\0' + workItemId + '\0' + 0L;
        return "evt_" + sha256(seed);
    }

    private void requireConfirmationEvent(
            AgentTaskEventEntity event, String tenantId, String clientId, String taskId,
            String eventId, PersistedPlanItem item, String requestDigest) {
        if (event == null || !tenantId.equals(event.getTenantId())
                || !clientId.equals(event.getClientId()) || !taskId.equals(event.getTaskId())
                || !eventId.equals(event.getEventId())
                || !TaskEventType.WORK_ITEM_CREATED.equals(event.getEventType())
                || !TaskEventType.ActorType.SYSTEM.equals(event.getActorType())
                || event.getActorId() != null
                || !TaskEventType.Aggregate.WORK_ITEM.equals(event.getAggregateType())
                || !item.workItemId().equals(event.getAggregateId())
                || event.getEventVersion() == null || event.getEventVersion() <= 0
                || event.getOccurredAt() == null || event.getOccurredAt() <= 0) {
            throw invalidPersisted("Confirmation event is missing or mismatched");
        }
        final JsonNode payload;
        try {
            STRICT_JSON.readTree(event.getEventJson());
            payload = STRICT_JSON.readTree(
                    TaskEventPayload.normalizeAllowedJson(event.getEventJson()));
        } catch (Exception malformed) {
            throw invalidPersisted("Confirmation event payload is malformed");
        }
        if (payload == null || !payload.isObject()
                || !textEquals(payload, TaskEventPayload.Key.WORK_ITEM_ID, item.workItemId())
                || !textEquals(payload, TaskEventPayload.Key.SOURCE, "manual_confirmation")) {
            throw invalidPersisted("Confirmation event payload is mismatched");
        }
        JsonNode storedDigest = payload.get(TaskEventPayload.Key.DECISION_CODE);
        if (storedDigest == null || !storedDigest.isTextual()
                || !SHA256.matcher(storedDigest.textValue()).matches()) {
            throw invalidPersisted("Confirmation event digest is invalid");
        }
        if (!requestDigest.equals(storedDigest.textValue())) {
            throw idempotencyConflict();
        }
        if (!textEquals(payload, TaskEventPayload.Key.TO_STATUS, item.status())
                || !longEquals(payload, TaskEventPayload.Key.ATTEMPT_COUNT, 0L)
                || !longEquals(payload, TaskEventPayload.Key.MAX_ATTEMPTS,
                        item.item().maxAttempts().longValue())
                || !longEquals(payload, TaskEventPayload.Key.RESULT_VERSION, 0L)
                || !longEquals(payload, TaskEventPayload.Key.CREATED_AT, event.getOccurredAt())) {
            throw invalidPersisted("Confirmation event metadata is inconsistent");
        }
    }

    private boolean textEquals(JsonNode object, String field, String expected) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() && expected.equals(value.textValue());
    }

    private boolean longEquals(JsonNode object, String field, long expected) {
        JsonNode value = object.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() == expected;
    }

    private AgentWorkItemPlanViewDTO confirmedView(
            String taskId, AgentWorkItemPlanConfirmRequestDTO request,
            String confirmedDigest, List<NormalizedItem> normalized,
            List<PersistedPlanItem> planned, List<AgentTaskWorkItemEntity> rows,
            boolean replay) {
        AgentWorkItemPlanViewDTO result = new AgentWorkItemPlanViewDTO();
        result.setTaskId(taskId);
        result.setSourcePlanId(request.getSourcePlanId());
        result.setSourcePlanDigest(request.getSourcePlanDigest());
        result.setConfirmedPlanDigest(confirmedDigest);
        result.setExpectedTaskVersion(request.getExpectedTaskVersion());
        result.setConfirmationRequired(false);
        result.setConfirmed(true);
        result.setIdempotentReplay(replay);
        List<AgentWorkItemPlanItemViewDTO> views = new ArrayList<>(normalized.size());
        for (int index = 0; index < normalized.size(); index++) {
            AgentWorkItemPlanItemViewDTO view = itemView(normalized.get(index));
            view.setWorkItemId(planned.get(index).workItemId());
            AgentTaskWorkItemEntity row = rows.get(index);
            view.setStatus(row.getStatus());
            view.setVersion(row.getVersion() == null ? null : Long.toString(row.getVersion()));
            views.add(view);
        }
        result.setItems(List.copyOf(views));
        return result;
    }

    private AgentWorkItemPlanViewDTO baseView(String taskId, List<NormalizedItem> items) {
        AgentWorkItemPlanViewDTO result = new AgentWorkItemPlanViewDTO();
        result.setTaskId(taskId);
        result.setItems(items.stream().map(this::itemView).toList());
        return result;
    }

    private AgentWorkItemPlanItemViewDTO itemView(NormalizedItem item) {
        AgentWorkItemPlanItemViewDTO view = new AgentWorkItemPlanItemViewDTO();
        view.setItemKey(item.itemKey());
        view.setTitle(item.title());
        view.setDescription(item.description());
        view.setWorkType(item.workType());
        view.setRequiredAbilities(item.requiredAbilities());
        view.setPriority(item.priority());
        view.setRequiredItem(item.requiredItem());
        view.setDependsOn(item.dependsOn());
        view.setMaxAttempts(item.maxAttempts());
        view.setStatus(item.dependsOn().isEmpty() ? "ready" : "pending");
        return view;
    }

    private String sourcePlanId(String tenantId, String clientId, String taskId,
            String actorAgentId, long taskVersion, String digest) {
        return "wpp1." + shortHash(tenantId + '\0' + clientId) + "."
                + shortHash(taskId) + "." + shortHash(actorAgentId) + "."
                + taskVersion + "." + digest.substring(0, 24);
    }

    private String workItemId(String tenantId, String clientId, String taskId,
            String idempotencyKey, String itemKey, boolean confirmationAnchor) {
        String seed = tenantId + '\0' + clientId + '\0' + taskId + '\0' + idempotencyKey;
        if (!confirmationAnchor) {
            seed += '\0' + itemKey;
        }
        return "wip_" + sha256(seed).substring(0, 40);
    }

    private String shortHash(String value) {
        return sha256(value).substring(0, 24);
    }

    private String planDigest(List<NormalizedItem> items) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(items.size());
            for (NormalizedItem item : items) {
                write(out, item.itemKey());
                write(out, item.title());
                write(out, item.description());
                write(out, item.workType());
                out.writeInt(item.requiredAbilities().size());
                for (String ability : item.requiredAbilities()) write(out, ability);
                out.writeInt(item.priority());
                out.writeBoolean(item.requiredItem());
                out.writeInt(item.dependsOn().size());
                for (String dependency : item.dependsOn()) write(out, dependency);
                out.writeInt(item.maxAttempts());
            }
            out.flush();
            return sha256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory plan digest failed", impossible);
        }
    }

    private void write(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private String json(Object value, String field) {
        try {
            return STRICT_JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw invalidPersisted(field + " could not be serialized");
        }
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private long parseVersion(String value) {
        if (value == null || !DECIMAL.matcher(value).matches()) {
            throw invalid("expectedTaskVersion must be an unsigned decimal string");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw invalid("expectedTaskVersion is out of range");
        }
    }

    private long requireTaskVersion(AgentTaskMetaEntity task) {
        if (task.getTaskVersion() == null || task.getTaskVersion() < 0
                || task.getTaskVersion() == Long.MAX_VALUE) {
            throw invalidPersisted("Task version is invalid");
        }
        return task.getTaskVersion();
    }

    private void requireDigest(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw invalid(field + " must be lowercase SHA-256 hex");
        }
    }

    private void requireScope(String tenantId, String clientId,
            String taskId, String actorAgentId) {
        requireExact(tenantId, "tenantId", 50);
        requireExact(clientId, "clientId", 50);
        requireExact(taskId, "taskId", 100);
        requireExact(actorAgentId, "actorAgentId", 100);
    }

    private void requireText(String value, String field, int maxCodePoints) {
        requireExact(value, field, maxCodePoints);
    }

    private void requireExact(String value, String field, int maxCodePoints) {
        if (value == null || value.isEmpty() || hasUnpairedSurrogate(value)
                || value.codePointCount(0, value.length()) > maxCodePoints
                || isPadding(value.codePointAt(0))
                || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(field + " is invalid");
        }
    }

    private void requireUtf8Text(String value, String field, int maxBytes, boolean nullable) {
        if (value == null && nullable) {
            return;
        }
        if (value == null || value.isEmpty() || hasUnpairedSurrogate(value)
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || value.codePoints().allMatch(AgentWorkItemPlanServiceImpl::isPadding)
                || value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                    && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')) {
            throw invalid(field + " is invalid");
        }
    }

    private boolean validIdempotencyKey(String value) {
        return value != null && IDEMPOTENCY_KEY.matcher(value).matches();
    }

    private long now() {
        long value = clock.getAsLong();
        if (value <= 0) {
            throw invalidPersisted("Planning clock returned a non-positive timestamp");
        }
        return value;
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
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

    private AgentWorkItemPlanException invalid(String message) {
        return new AgentWorkItemPlanException(Reason.INVALID_REQUEST, message);
    }

    private AgentWorkItemPlanException notFound() {
        return new AgentWorkItemPlanException(
                Reason.NOT_FOUND_OR_FORBIDDEN, "Task planning access is unavailable");
    }

    private AgentWorkItemPlanException conflict() {
        return new AgentWorkItemPlanException(
                Reason.VERSION_CONFLICT, "Task planning state changed");
    }

    private AgentWorkItemPlanException idempotencyConflict() {
        return new AgentWorkItemPlanException(
                Reason.IDEMPOTENCY_CONFLICT, "Idempotency key was used for another plan");
    }

    private AgentWorkItemPlanException invalidPersisted(String message) {
        return new AgentWorkItemPlanException(Reason.INVALID_PERSISTED_STATE, message);
    }

    private record NormalizedItem(
            String itemKey,
            String title,
            String description,
            String workType,
            List<String> requiredAbilities,
            Integer priority,
            Boolean requiredItem,
            List<String> dependsOn,
            Integer maxAttempts) {
    }

    private record PersistedPlanItem(
            NormalizedItem item,
            String workItemId,
            String taskId,
            String abilityJson,
            String dependencyJson,
            String status) {
    }
}
