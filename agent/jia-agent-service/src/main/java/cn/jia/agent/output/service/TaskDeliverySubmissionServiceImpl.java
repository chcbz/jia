package cn.jia.agent.output.service;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.config.OutputDeliveryProperties;
import cn.jia.agent.dao.AgentTaskArtifactDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskArtifactDTO;
import cn.jia.agent.entity.AgentTaskArtifactEntity;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputDeliveryException;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.TaskDeliveryHttpResult;
import cn.jia.agent.output.TaskDeliverySubmissionService;
import cn.jia.agent.output.dao.OutputRunBindingDao;
import cn.jia.agent.output.dao.OutputUploadDao;
import cn.jia.agent.output.dao.TaskDeliveryDao;
import cn.jia.agent.output.dto.TaskDeliveryItemDTO;
import cn.jia.agent.output.dto.TaskDeliverySubmitDTO;
import cn.jia.agent.output.entity.OutputRunBindingEntity;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Service
public final class TaskDeliverySubmissionServiceImpl implements TaskDeliverySubmissionService {
    private static final String ACTOR_KIND = "RUN_TICKET";
    private static final String OPERATION = "submitDelivery";
    private static final long REVIEW_RECEIPT_GRACE_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_ITEMS = 100;
    private static final int MANIFEST_VERSION = 1;
    private static final Set<String> REQUIREMENT_FIELDS = Set.of(
            "mode", "minFiles", "requiredNames", "instructions", "maxReviewRevisions");
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Comparator<RequiredItem> ARTIFACT_LOCK_ORDER =
            Comparator.comparing(RequiredItem::artifactId,
                            TaskDeliverySubmissionServiceImpl::compareUtf8)
                    .thenComparingInt(RequiredItem::artifactVersion);

    private final OutputRunAuthorizationService authorization;
    private final AgentTaskMutationTransaction taskTransactions;
    private final AgentTaskMetaDao taskDao;
    private final AgentTaskWorkItemDao workItemDao;
    private final AgentTaskArtifactDao artifactDao;
    private final OutputRunBindingDao runDao;
    private final OutputUploadDao outputDao;
    private final TaskDeliveryDao deliveryDao;
    private final AgentTaskEventWriter eventWriter;
    private final boolean writesPaused;

    public TaskDeliverySubmissionServiceImpl(
            OutputRunAuthorizationService authorization,
            AgentTaskMutationTransaction taskTransactions,
            AgentTaskMetaDao taskDao,
            AgentTaskWorkItemDao workItemDao,
            AgentTaskArtifactDao artifactDao,
            OutputRunBindingDao runDao,
            OutputUploadDao outputDao,
            TaskDeliveryDao deliveryDao,
            AgentTaskEventWriter eventWriter,
            OutputDeliveryProperties properties) {
        this.authorization = Objects.requireNonNull(authorization);
        this.taskTransactions = Objects.requireNonNull(taskTransactions);
        this.taskDao = Objects.requireNonNull(taskDao);
        this.workItemDao = Objects.requireNonNull(workItemDao);
        this.artifactDao = Objects.requireNonNull(artifactDao);
        this.runDao = Objects.requireNonNull(runDao);
        this.outputDao = Objects.requireNonNull(outputDao);
        this.deliveryDao = Objects.requireNonNull(deliveryDao);
        this.eventWriter = Objects.requireNonNull(eventWriter);
        this.writesPaused = Objects.requireNonNull(properties).writesPaused();
    }

    @Override
    public TaskDeliveryHttpResult submit(
            OutputTicketAuthorization projected, String rawBearerHeader,
            String idempotencyKey, String taskId, TaskDeliverySubmitDTO request,
            String requestId) {
        requireProjected(projected, taskId);
        requireKey(idempotencyKey);
        requireRequestId(requestId);
        RequiredRequest required = requireRequest(taskId, request);
        byte[] requestHash = sha256(canonical(taskId, request));
        String bearer = rawBearer(rawBearerHeader);
        try {
            TaskDeliveryHttpResult result = taskTransactions.executeWithLockedTaskRoot(
                    projected.tenantId(), projected.clientId(), taskId,
                    root -> submitLocked(root, projected, bearer, idempotencyKey,
                            taskId, required, requestHash, requestId));
            if (result == null) throw unavailable("OUTPUT_SUBMISSION_TRANSACTION_EMPTY");
            return result;
        } catch (AgentTaskCollaborationException missing) {
            if (missing.getReason() == AgentTaskCollaborationException.Reason.NOT_FOUND) {
                return error(404, "OUTPUT_DELIVERY_NOT_FOUND",
                        "Formal delivery task is unavailable", requestId, Map.of());
            }
            throw unavailable("OUTPUT_SUBMISSION_ROOT_INVALID");
        }
    }

    private TaskDeliveryHttpResult submitLocked(
            AgentTaskMetaEntity root, OutputTicketAuthorization projected,
            String bearer, String key, String taskId, RequiredRequest request,
            byte[] requestHash, String requestId) {
        OutputTicketAuthorization receiptAuth = authorization.authorizeTicket(
                bearer, OutputConstants.OP_STATUS, true);
        requireSameRoute(projected, receiptAuth, taskId, request);
        OutputUploadDao.Receipt receipt = outputDao.lockReceipt(
                receiptAuth.tenantId(), receiptAuth.clientId(), ACTOR_KIND,
                receiptAuth.runId(), OPERATION, key);
        if (receipt != null) {
            if (!MessageDigest.isEqual(requestHash, receipt.requestHash())) {
                return error(409, "OUTPUT_IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key is already bound to another request",
                        requestId, Map.of());
            }
            requireValidReceipt(receipt, taskId);
            return new TaskDeliveryHttpResult(receipt.httpStatus(), receipt.responseJson());
        }
        if (writesPaused) throw unavailable("OUTPUT_WRITES_PAUSED");

        OutputTicketAuthorization current = authorization.authorizeTicket(
                bearer, OutputConstants.OP_SUBMIT, false);
        requireSameRoute(receiptAuth, current, taskId, request);
        OutputRunBindingEntity run = requireActiveRun(current, request);
        long now = System.currentTimeMillis();
        try {
            PreparedSubmission prepared = prepare(
                    root, current, run, taskId, request, now);
            return persist(prepared, key, requestHash, requestId, now);
        } catch (BusinessRejection rejected) {
            TaskDeliveryHttpResult response = error(rejected.status, rejected.code,
                    rejected.getMessage(), requestId, rejected.details);
            return persistReceipt(current, key, requestHash, response,
                    receiptRetainUntil(current, now), now);
        }
    }

    private PreparedSubmission prepare(
            AgentTaskMetaEntity root, OutputTicketAuthorization auth,
            OutputRunBindingEntity run, String taskId, RequiredRequest request, long now) {
        requireTaskRoot(root, auth, taskId);
        if (root.getTaskVersion() != request.expectedTaskVersion()) {
            throw conflict("OUTPUT_TASK_VERSION_CONFLICT", "Task version changed",
                    Map.of("currentVersion", Long.toString(root.getTaskVersion())));
        }
        if (root.getCurrentDeliveryId() != null) {
            throw conflict("OUTPUT_DELIVERY_CONFLICT",
                    "Task already has a current formal delivery", Map.of());
        }
        DeliveryRequirements requirements = requireRequirements(root.getDeliveryRequirementJson());
        long priorRevision = root.getDeliveryRevision();
        long revision;
        try { revision = Math.addExact(priorRevision, 1L); }
        catch (ArithmeticException overflow) { throw unavailable("OUTPUT_DELIVERY_REVISION_INVALID"); }
        if (revision > requirements.maxReviewRevisions()) {
            throw conflict("OUTPUT_DELIVERY_REVISION_LIMIT",
                    "Formal delivery revision limit is exhausted", Map.of());
        }

        List<AgentTaskWorkItemEntity> workItems = workItemDao.listByTaskForUpdate(
                auth.tenantId(), auth.clientId(), taskId, 3);
        if (workItems == null || workItems.size() != 1
                || !request.workItemId().equals(workItems.getFirst().getWorkItemId())
                || !Boolean.TRUE.equals(workItems.getFirst().getRequiredItem())) {
            throw conflict("OUTPUT_DELIVERY_TOPOLOGY_CONFLICT",
                    "Formal delivery requires exactly one required work item", Map.of());
        }
        AgentTaskWorkItemEntity workItem = workItems.getFirst();
        requireWorkItem(workItem, auth, request, now);

        String supersedesDeliveryId = null;
        if (priorRevision > 0) {
            TaskDeliveryDao.DeliveryRow prior = deliveryDao.findTaskRevision(
                    auth.tenantId(), auth.clientId(), taskId, priorRevision, true);
            if (prior == null || !"CHANGES_REQUESTED".equals(prior.state())
                    || !request.workItemId().equals(prior.workItemId())) {
                throw unavailable("OUTPUT_PRIOR_DELIVERY_INVALID");
            }
            supersedesDeliveryId = prior.deliveryId();
        }

        List<RequiredItem> lockedItems = new ArrayList<>(request.items());
        lockedItems.sort(ARTIFACT_LOCK_ORDER);
        Map<ItemKey, PreparedItem> byKey = new LinkedHashMap<>();
        Map<String, OutputUploadDao.ObjectRow> objects = new TreeMap<>(
                TaskDeliverySubmissionServiceImpl::compareUtf8);
        for (RequiredItem item : lockedItems) {
            AgentTaskArtifactEntity artifact = artifactDao.findVersionForUpdate(
                    auth.tenantId(), auth.clientId(), taskId,
                    item.artifactId(), item.artifactVersion());
            PreparedItem prepared = requireArtifact(artifact, auth, request, item, now);
            byKey.put(new ItemKey(item.artifactId(), item.artifactVersion()), prepared);
            if (prepared.objectId() != null) objects.putIfAbsent(prepared.objectId(), null);
        }
        for (String objectId : new ArrayList<>(objects.keySet())) {
            OutputUploadDao.ObjectRow object = outputDao.findObject(
                    auth.tenantId(), auth.clientId(), objectId, true);
            objects.put(objectId, requireReadyObject(object, auth, byKey.values(), objectId));
        }

        List<PreparedItem> orderedItems = new ArrayList<>();
        for (RequiredItem item : request.items()) {
            PreparedItem prepared = byKey.get(new ItemKey(item.artifactId(), item.artifactVersion()));
            if (prepared == null) throw unavailable("OUTPUT_ARTIFACT_LOCK_SET_INVALID");
            orderedItems.add(prepared);
        }
        requireDeliveryContents(requirements, orderedItems);
        String deliveryId = UUID.randomUUID().toString().replace("-", "");
        String manifestArtifactId = "delivery-manifest-" + deliveryId;
        return new PreparedSubmission(root, auth, run, workItem, requirements,
                request, orderedItems, objects, deliveryId, manifestArtifactId,
                revision, supersedesDeliveryId);
    }

    private TaskDeliveryHttpResult persist(
            PreparedSubmission prepared, String key, byte[] requestHash,
            String requestId, long now) {
        String manifest = manifest(prepared);
        byte[] manifestBytes = manifest.getBytes(StandardCharsets.UTF_8);
        if (manifestBytes.length > 262_144) throw unavailable("OUTPUT_MANIFEST_TOO_LARGE");
        byte[] manifestHash = sha256(manifestBytes);
        long retainUntil = Math.addExact(now, OutputConstants.OUTPUT_RETENTION_MILLIS);
        AgentTaskArtifactDTO manifestArtifact = new AgentTaskArtifactDTO();
        manifestArtifact.setArtifactId(prepared.manifestArtifactId());
        manifestArtifact.setTaskId(prepared.request().taskId());
        manifestArtifact.setWorkItemId(prepared.request().workItemId());
        manifestArtifact.setProducerAgentId(prepared.auth().producerAgentId());
        manifestArtifact.setArtifactType("summary");
        manifestArtifact.setTitle("Formal delivery manifest revision " + prepared.revision());
        manifestArtifact.setContent(manifest);
        manifestArtifact.setRunId(prepared.auth().runId());
        manifestArtifact.setContentByteLength((long) manifestBytes.length);
        manifestArtifact.setMimeType("application/json");
        manifestArtifact.setRetainUntil(retainUntil);
        manifestArtifact.setContentHash(HexFormat.of().formatHex(manifestHash));
        manifestArtifact.setArtifactVersion(MANIFEST_VERSION);
        manifestArtifact.setVisibility("private");
        manifestArtifact.setCreatedAt(now);

        try {
            if (artifactDao.insert(prepared.auth().tenantId(), prepared.auth().clientId(),
                    manifestArtifact) != 1) throw unavailable("OUTPUT_MANIFEST_PERSIST_FAILED");
            TaskDeliveryDao.DeliveryRow delivery = new TaskDeliveryDao.DeliveryRow(
                    prepared.auth().tenantId(), prepared.auth().clientId(),
                    prepared.deliveryId(), prepared.request().taskId(),
                    prepared.request().workItemId(), prepared.revision(),
                    prepared.supersedesDeliveryId(), prepared.auth().producerAgentId(),
                    prepared.auth().runId(), prepared.request().summary(), "SUBMITTED", now,
                    null, prepared.manifestArtifactId(), MANIFEST_VERSION, 0L);
            if (deliveryDao.insertDelivery(delivery, now) != 1) {
                throw unavailable("OUTPUT_DELIVERY_PERSIST_FAILED");
            }
            for (int index = 0; index < prepared.items().size(); index++) {
                PreparedItem item = prepared.items().get(index);
                if (deliveryDao.insertItem(new TaskDeliveryDao.ItemRow(
                        prepared.auth().tenantId(), prepared.auth().clientId(),
                        prepared.deliveryId(), item.artifactId(), item.artifactVersion(),
                        item.contentHash(), item.objectId(), item.purpose(), index, 0L), now) != 1) {
                    throw unavailable("OUTPUT_DELIVERY_ITEM_PERSIST_FAILED");
                }
            }
            insertDeliveryPins(prepared, retainUntil, now);
        } catch (DataIntegrityViolationException conflict) {
            throw unavailable("OUTPUT_DELIVERY_UNIQUE_CONFLICT");
        }

        if (workItemDao.submitActiveLeaseByVersion(
                prepared.auth().tenantId(), prepared.auth().clientId(),
                prepared.request().taskId(), prepared.request().workItemId(),
                prepared.auth().producerAgentId(), prepared.auth().runId(),
                prepared.request().leaseToken(), prepared.workItem().getLeaseUntil(),
                prepared.request().expectedWorkItemVersion(), now,
                prepared.deliveryId(), prepared.manifestArtifactId()) != 1) {
            throw unavailable("OUTPUT_WORK_ITEM_SUBMIT_CAS_FAILED");
        }
        if (taskDao.submitDeliveryByVersion(
                prepared.auth().tenantId(), prepared.auth().clientId(),
                prepared.request().taskId(), prepared.auth().producerAgentId(),
                prepared.request().expectedTaskVersion(),
                prepared.root().getDeliveryRevision(), prepared.deliveryId(), now) != 1) {
            throw unavailable("OUTPUT_TASK_SUBMIT_CAS_FAILED");
        }
        appendEvents(prepared, now);
        if (runDao.markResultSubmitted(prepared.run(), now) != 1) {
            throw unavailable("OUTPUT_RUN_SUBMIT_CAS_FAILED");
        }

        TaskDeliveryHttpResult response = success(prepared);
        long receiptRetainUntil = Math.addExact(maxRetainUntil(prepared, retainUntil),
                REVIEW_RECEIPT_GRACE_MILLIS);
        return persistReceipt(
                prepared.auth(), key, requestHash, response, receiptRetainUntil, now);
    }

    private void insertDeliveryPins(
            PreparedSubmission prepared, long manifestRetainUntil, long now) {
        for (PreparedItem item : prepared.items()) {
            if (item.objectId() == null) continue;
            OutputUploadDao.ObjectRow object = prepared.objects().get(item.objectId());
            if (object == null || !"READY".equals(object.lifecycleStatus())) {
                throw unavailable("OUTPUT_OBJECT_LOCK_LOST");
            }
            long retainUntil = Math.max(item.retainUntil(), manifestRetainUntil);
            byte[] referenceKey = referenceKey(
                    prepared.auth().tenantId(), prepared.auth().clientId(),
                    OutputConstants.SOURCE_TASK, prepared.request().taskId(),
                    item.artifactId(), item.artifactVersion(), "DELIVERY_PIN",
                    prepared.deliveryId());
            OutputUploadDao.ReferenceRow pin = new OutputUploadDao.ReferenceRow(
                    prepared.auth().tenantId(), prepared.auth().clientId(), referenceKey,
                    item.objectId(), OutputConstants.SOURCE_TASK,
                    prepared.request().taskId(), item.artifactId(), item.artifactVersion(),
                    "DELIVERY_PIN", prepared.deliveryId(), "ACTIVE", retainUntil,
                    true, "DELIVERY_SUBMITTED", null);
            if (outputDao.insertReference(pin, now) != 1) {
                throw unavailable("OUTPUT_DELIVERY_PIN_FAILED");
            }
        }
    }

    private void appendEvents(PreparedSubmission prepared, long now) {
        long workResultVersion = Math.addExact(
                prepared.request().expectedWorkItemVersion(), 1L);
        TaskEventPayload.Builder workPayload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.WORK_ITEM_ID, prepared.request().workItemId())
                .put(TaskEventPayload.Key.ARTIFACT_ID, prepared.manifestArtifactId())
                .put(TaskEventPayload.Key.ARTIFACT_VERSION, MANIFEST_VERSION)
                .put(TaskEventPayload.Key.FROM_STATUS, "running")
                .put(TaskEventPayload.Key.TO_STATUS, "submitted")
                .put(TaskEventPayload.Key.EXPECTED_VERSION,
                        prepared.request().expectedWorkItemVersion())
                .put(TaskEventPayload.Key.RESULT_VERSION, workResultVersion);
        eventWriter.append(event(prepared, TaskEventType.WORK_ITEM_SUBMITTED,
                TaskEventType.Aggregate.WORK_ITEM, prepared.request().workItemId(),
                workPayload, now, workResultVersion));

        long taskResultVersion = Math.addExact(prepared.request().expectedTaskVersion(), 1L);
        TaskEventPayload.Builder taskPayload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.ARTIFACT_ID, prepared.manifestArtifactId())
                .put(TaskEventPayload.Key.ARTIFACT_VERSION, MANIFEST_VERSION)
                .put(TaskEventPayload.Key.FROM_STATUS, prepared.root().getRewardStatus())
                .put(TaskEventPayload.Key.TO_STATUS, "reviewing")
                .put(TaskEventPayload.Key.EXPECTED_VERSION,
                        prepared.request().expectedTaskVersion())
                .put(TaskEventPayload.Key.RESULT_VERSION, taskResultVersion);
        eventWriter.append(event(prepared, TaskEventType.TASK_REVIEWING,
                TaskEventType.Aggregate.TASK, prepared.request().taskId(),
                taskPayload, now, taskResultVersion));
    }

    private AgentTaskEventWriteCommand event(
            PreparedSubmission prepared, String eventType, String aggregateType,
            String aggregateId, TaskEventPayload.Builder payload, long now,
            long resultVersion) {
        String seed = String.join("\0", prepared.auth().tenantId(),
                prepared.auth().clientId(), prepared.request().taskId(), eventType,
                aggregateType, aggregateId, Long.toString(resultVersion));
        return new AgentTaskEventWriteCommand()
                .setTenantId(prepared.auth().tenantId())
                .setClientId(prepared.auth().clientId())
                .setTaskId(prepared.request().taskId())
                .setEventId("evt_" + HexFormat.of().formatHex(sha256(
                        seed.getBytes(StandardCharsets.UTF_8))))
                .setEventType(eventType)
                .setActorType(TaskEventType.ActorType.AGENT)
                .setActorId(prepared.auth().producerAgentId())
                .setAggregateType(aggregateType)
                .setAggregateId(aggregateId)
                .setEventJson(payload.toJson())
                .setOccurredAt(now);
    }

    private OutputRunBindingEntity requireActiveRun(
            OutputTicketAuthorization auth, RequiredRequest request) {
        OutputRunBindingEntity run = runDao.findExactByRun(
                auth.tenantId(), auth.clientId(), auth.runId(), true);
        if (run == null || !OutputConstants.RUN_ACTIVE.equals(run.getState())
                || !Integer.valueOf(1).equals(run.getPolicyVersion())
                || !OutputConstants.SOURCE_TASK.equals(run.getSourceType())
                || !request.taskId().equals(run.getSourceId())
                || !request.workItemId().equals(run.getWorkItemId())
                || !auth.producerAgentId().equals(run.getProducerAgentId())
                || !auth.bindingId().equals(run.getBindingId())
                || run.getRowVersion() == null || run.getRowVersion() < 0
                || run.getRecoveryUntil() == null
                || run.getRecoveryUntil() < System.currentTimeMillis()) {
            throw forbidden("Output run cannot submit this formal delivery");
        }
        return run;
    }

    private static void requireTaskRoot(
            AgentTaskMetaEntity root, OutputTicketAuthorization auth, String taskId) {
        if (root == null || !auth.tenantId().equals(root.getTenantId())
                || !auth.clientId().equals(root.getClientId())
                || !taskId.equals(root.getTaskId())
                || !Integer.valueOf(1).equals(root.getDeliveryPolicyVersion())
                || root.getTaskVersion() == null || root.getTaskVersion() < 0
                || root.getTaskVersion() == Long.MAX_VALUE
                || root.getDeliveryRevision() == null || root.getDeliveryRevision() < 0
                || !Set.of("assigned", "running").contains(root.getRewardStatus())
                || !auth.producerAgentId().equals(root.getAssignedAgentId())) {
            throw conflict("OUTPUT_DELIVERY_TASK_CONFLICT",
                    "Task does not permit a formal delivery", Map.of());
        }
    }

    private static void requireWorkItem(
            AgentTaskWorkItemEntity item, OutputTicketAuthorization auth,
            RequiredRequest request, long now) {
        if (!auth.tenantId().equals(item.getTenantId())
                || !auth.clientId().equals(item.getClientId())
                || !request.taskId().equals(item.getTaskId())
                || !request.workItemId().equals(item.getWorkItemId())
                || item.getVersion() == null || item.getVersion() < 0) {
            throw unavailable("OUTPUT_WORK_ITEM_DATA_INVALID");
        }
        if (item.getVersion() != request.expectedWorkItemVersion()) {
            throw conflict("OUTPUT_WORK_ITEM_VERSION_CONFLICT", "Work item version changed",
                    Map.of("currentVersion", Long.toString(item.getVersion())));
        }
        if (!"running".equals(item.getStatus())
                || !auth.producerAgentId().equals(item.getAssigneeAgentId())
                || !auth.runId().equals(item.getExecutionRunId())
                || !auth.runId().equals(item.getDispatchedRunId())
                || item.getLeaseToken() == null
                || !constantTimeEquals(request.leaseToken(), item.getLeaseToken())
                || item.getLeaseUntil() == null || item.getLeaseUntil() <= now
                || item.getResultArtifactId() != null || item.getResultDeliveryId() != null
                || item.getSubmittedAt() != null || item.getCompletedAt() != null) {
            throw conflict("OUTPUT_DELIVERY_LEASE_CONFLICT",
                    "Work item lease no longer permits formal submission", Map.of());
        }
    }

    private static PreparedItem requireArtifact(
            AgentTaskArtifactEntity artifact, OutputTicketAuthorization auth,
            RequiredRequest request, RequiredItem item, long now) {
        if (artifact == null) {
            throw new BusinessRejection(404, "OUTPUT_ARTIFACT_NOT_FOUND",
                    "Formal delivery artifact is unavailable", Map.of());
        }
        if (!auth.tenantId().equals(artifact.getTenantId())
                || !auth.clientId().equals(artifact.getClientId())
                || !request.taskId().equals(artifact.getTaskId())
                || !request.workItemId().equals(artifact.getWorkItemId())
                || !auth.producerAgentId().equals(artifact.getProducerAgentId())
                || !auth.runId().equals(artifact.getRunId())
                || !item.artifactId().equals(artifact.getArtifactId())
                || artifact.getArtifactVersion() == null
                || artifact.getArtifactVersion() != item.artifactVersion()) {
            throw conflict("OUTPUT_ARTIFACT_SCOPE_CONFLICT",
                    "Artifact does not belong to this producer run", Map.of());
        }
        if (artifact.getRetainUntil() == null || artifact.getRetainUntil() <= now
                || artifact.getContentByteLength() == null
                || artifact.getContentByteLength() < 0 || artifact.getMimeType() == null
                || artifact.getStorageUri() != null
                || (artifact.getContent() == null) == (artifact.getObjectId() == null)) {
            throw unavailable("OUTPUT_ARTIFACT_DATA_INVALID");
        }
        byte[] contentHash;
        try {
            if (artifact.getContentHash() == null
                    || !artifact.getContentHash().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException();
            }
            contentHash = HexFormat.of().parseHex(artifact.getContentHash());
        } catch (RuntimeException invalid) {
            throw unavailable("OUTPUT_ARTIFACT_DATA_INVALID");
        }
        boolean inlineConclusion = false;
        if (artifact.getContent() != null) {
            byte[] content = artifact.getContent().getBytes(StandardCharsets.UTF_8);
            if (content.length != artifact.getContentByteLength()
                    || !MessageDigest.isEqual(contentHash, sha256(content))) {
                throw unavailable("OUTPUT_ARTIFACT_DATA_INVALID");
            }
            inlineConclusion = "summary".equals(artifact.getArtifactType())
                    && !artifact.getContent().isBlank();
        }
        return new PreparedItem(item.artifactId(), item.artifactVersion(), item.purpose(),
                contentHash, artifact.getObjectId(), artifact.getFileName(),
                artifact.getMimeType(), artifact.getContentByteLength(),
                artifact.getRetainUntil(), inlineConclusion);
    }

    private static OutputUploadDao.ObjectRow requireReadyObject(
            OutputUploadDao.ObjectRow object, OutputTicketAuthorization auth,
            Iterable<PreparedItem> items, String objectId) {
        if (object == null || !auth.tenantId().equals(object.tenantId())
                || !auth.clientId().equals(object.clientId())
                || !auth.runId().equals(object.runId())
                || !"PASSED".equals(object.verificationStatus())
                || !"READY".equals(object.lifecycleStatus())
                || object.storageKey() == null || object.storageKey().isBlank()
                || object.actualSha256() == null || object.actualSha256().length != 32
                || object.actualSize() == null || object.actualSize() < 0
                || object.actualMime() == null) {
            throw conflict("OUTPUT_OBJECT_NOT_READY",
                    "Formal delivery object is not ready", Map.of());
        }
        for (PreparedItem item : items) {
            if (!objectId.equals(item.objectId())) continue;
            if (!MessageDigest.isEqual(item.contentHash(), object.actualSha256())
                    || item.contentByteLength() != object.actualSize()
                    || !item.mimeType().equals(object.actualMime())) {
                throw unavailable("OUTPUT_OBJECT_METADATA_MISMATCH");
            }
        }
        return object;
    }

    private static void requireDeliveryContents(
            DeliveryRequirements requirements, List<PreparedItem> items) {
        long inlineConclusions = items.stream().filter(PreparedItem::inlineConclusion).count();
        List<PreparedItem> files = items.stream()
                .filter(item -> item.objectId() != null).toList();
        if (("text".equals(requirements.mode()) || "mixed".equals(requirements.mode()))
                && inlineConclusions == 0) {
            throw unprocessable("OUTPUT_DELIVERY_TEXT_REQUIRED",
                    "A published nonempty inline summary artifact is required");
        }
        if (("files".equals(requirements.mode()) || "mixed".equals(requirements.mode()))
                && files.size() < requirements.minFiles()) {
            throw unprocessable("OUTPUT_DELIVERY_FILES_REQUIRED",
                    "Required downloadable artifacts are missing");
        }
        Set<String> names = new HashSet<>();
        for (PreparedItem file : files) {
            if (file.fileName() == null || file.fileName().isBlank()) {
                throw unprocessable("OUTPUT_DELIVERY_FILE_NAME_REQUIRED",
                        "A downloadable artifact has no file name");
            }
            names.add(file.fileName());
        }
        if (!names.containsAll(requirements.requiredNames())) {
            throw unprocessable("OUTPUT_DELIVERY_REQUIRED_NAME_MISSING",
                    "A required named artifact is missing");
        }
    }

    private static DeliveryRequirements requireRequirements(String json) {
        try {
            if (json == null || json.isBlank()) throw new IllegalArgumentException();
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isObject()
                    || !REQUIREMENT_FIELDS.containsAll(new HashSet<>(root.propertyNames()))) {
                throw new IllegalArgumentException();
            }
            JsonNode modeNode = root.get("mode");
            JsonNode minFilesNode = root.get("minFiles");
            JsonNode namesNode = root.get("requiredNames");
            JsonNode maxRevisionsNode = root.get("maxReviewRevisions");
            if (modeNode == null || !modeNode.isString()
                    || minFilesNode == null || !minFilesNode.isIntegralNumber()
                    || namesNode == null || !namesNode.isArray()
                    || maxRevisionsNode == null || !maxRevisionsNode.isIntegralNumber()) {
                throw new IllegalArgumentException();
            }
            String mode = modeNode.asText();
            int minFiles = minFilesNode.intValue();
            int maxRevisions = maxRevisionsNode.intValue();
            if (!Set.of("text", "files", "mixed").contains(mode)
                    || minFiles < 0 || minFiles > MAX_ITEMS || maxRevisions != 3
                    || ("text".equals(mode) ? minFiles != 0 : minFiles < 1)) {
                throw new IllegalArgumentException();
            }
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (JsonNode value : namesNode) {
                if (!value.isString() || !exact(value.asText(), 255)
                        || !names.add(value.asText())) throw new IllegalArgumentException();
            }
            if (names.size() > MAX_ITEMS || "text".equals(mode) && !names.isEmpty()) {
                throw new IllegalArgumentException();
            }
            if (root.has("instructions") && !root.get("instructions").isNull()) {
                JsonNode instructions = root.get("instructions");
                if (!instructions.isString() || instructions.asText().length() > 16_384) {
                    throw new IllegalArgumentException();
                }
            }
            return new DeliveryRequirements(mode, minFiles, List.copyOf(names), maxRevisions);
        } catch (Exception invalid) {
            throw unavailable("OUTPUT_DELIVERY_REQUIREMENTS_INVALID");
        }
    }

    private static RequiredRequest requireRequest(String taskId, TaskDeliverySubmitDTO request) {
        if (request == null || request.runId() == null
                || !request.runId().matches("[0-9a-f]{32}")
                || !exact(request.workItemId(), 100)
                || !exact(request.leaseToken(), 100)
                || request.summary() == null || request.summary().isBlank()
                || request.summary().length() > 16_384
                || invalidMultilineText(request.summary())
                || request.items() == null || request.items().isEmpty()
                || request.items().size() > MAX_ITEMS) {
            throw bad("OUTPUT_REQUEST_INVALID");
        }
        long taskVersion = decimal(request.expectedTaskVersion(), true, Long.MAX_VALUE - 1,
                "expectedTaskVersion");
        long workVersion = decimal(request.expectedWorkItemVersion(), true, Long.MAX_VALUE - 1,
                "expectedWorkItemVersion");
        List<RequiredItem> items = new ArrayList<>();
        Set<ItemKey> keys = new HashSet<>();
        for (TaskDeliveryItemDTO item : request.items()) {
            if (item == null || !exact(item.artifactId(), 100)
                    || item.purpose() != null && (!exact(item.purpose(), 255))) {
                throw bad("OUTPUT_REQUEST_INVALID");
            }
            int version = Math.toIntExact(decimal(
                    item.version(), false, Integer.MAX_VALUE - 1L, "artifactVersion"));
            ItemKey itemKey = new ItemKey(item.artifactId(), version);
            if (!keys.add(itemKey)) throw bad("OUTPUT_DELIVERY_ITEM_DUPLICATE");
            String purpose = item.purpose() == null ? "deliverable" : item.purpose();
            items.add(new RequiredItem(item.artifactId(), version, purpose));
        }
        return new RequiredRequest(taskId, request.runId(), request.workItemId(), taskVersion,
                workVersion, request.leaseToken(), request.summary(), List.copyOf(items));
    }

    private static byte[] canonical(String taskId, TaskDeliverySubmitDTO request) {
        Map<String, Object> root = new TreeMap<>();
        root.put("expectedTaskVersion", request.expectedTaskVersion());
        root.put("expectedWorkItemVersion", request.expectedWorkItemVersion());
        List<Map<String, Object>> items = new ArrayList<>();
        for (TaskDeliveryItemDTO item : request.items()) {
            Map<String, Object> value = new TreeMap<>();
            value.put("artifactId", item.artifactId());
            value.put("purpose", item.purpose());
            value.put("version", item.version());
            items.add(value);
        }
        root.put("items", items);
        root.put("leaseToken", request.leaseToken());
        root.put("runId", request.runId());
        root.put("summary", request.summary());
        root.put("taskId", taskId);
        root.put("workItemId", request.workItemId());
        try { return JSON.writeValueAsBytes(root); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String manifest(PreparedSubmission prepared) {
        Map<String, Object> root = new TreeMap<>();
        root.put("deliveryId", prepared.deliveryId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < prepared.items().size(); index++) {
            PreparedItem item = prepared.items().get(index);
            Map<String, Object> value = new TreeMap<>();
            value.put("artifactId", item.artifactId());
            value.put("contentByteLength", Long.toString(item.contentByteLength()));
            value.put("contentSha256", HexFormat.of().formatHex(item.contentHash()));
            value.put("fileName", item.fileName());
            value.put("itemOrder", index);
            value.put("mime", item.mimeType());
            value.put("purpose", item.purpose());
            value.put("version", Integer.toString(item.artifactVersion()));
            items.add(value);
        }
        root.put("items", items);
        root.put("revision", Long.toString(prepared.revision()));
        root.put("runId", prepared.auth().runId());
        root.put("schemaVersion", 1);
        root.put("summarySha256", HexFormat.of().formatHex(sha256(
                prepared.request().summary().getBytes(StandardCharsets.UTF_8))));
        root.put("taskId", prepared.request().taskId());
        root.put("workItemId", prepared.request().workItemId());
        try { return JSON.writeValueAsString(root); }
        catch (Exception impossible) { throw unavailable("OUTPUT_MANIFEST_SERIALIZATION_FAILED"); }
    }

    private static TaskDeliveryHttpResult success(PreparedSubmission prepared) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryId", prepared.deliveryId());
        data.put("taskId", prepared.request().taskId());
        data.put("revision", Long.toString(prepared.revision()));
        data.put("version", "0");
        data.put("taskVersion", Long.toString(prepared.request().expectedTaskVersion() + 1));
        data.put("state", "SUBMITTED");
        data.put("summary", prepared.request().summary());
        List<Map<String, Object>> items = new ArrayList<>();
        for (PreparedItem item : prepared.items()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("artifactId", item.artifactId());
            value.put("version", Integer.toString(item.artifactVersion()));
            value.put("purpose", item.purpose());
            items.add(value);
        }
        data.put("items", items);
        data.put("reviewActions", List.of());
        return new TaskDeliveryHttpResult(200, json(Map.of("code", "E0", "data", data)));
    }

    private TaskDeliveryHttpResult persistReceipt(
            OutputTicketAuthorization auth, String key, byte[] hash,
            TaskDeliveryHttpResult response, long retainUntil, long now) {
        if (retainUntil <= now || outputDao.insertReceipt(
                auth.tenantId(), auth.clientId(), ACTOR_KIND, auth.runId(), OPERATION,
                key, hash, response.status(), response.responseJson(), retainUntil, now) != 1) {
            throw unavailable("OUTPUT_SUBMISSION_RECEIPT_FAILED");
        }
        OutputUploadDao.Receipt persisted = outputDao.lockReceipt(
                auth.tenantId(), auth.clientId(), ACTOR_KIND, auth.runId(), OPERATION, key);
        if (persisted == null || persisted.httpStatus() != response.status()
                || !MessageDigest.isEqual(hash, persisted.requestHash())) {
            throw unavailable("OUTPUT_SUBMISSION_RECEIPT_FAILED");
        }
        requireValidReceipt(persisted, auth.sourceId());
        return new TaskDeliveryHttpResult(persisted.httpStatus(), persisted.responseJson());
    }

    private static long receiptRetainUntil(
            OutputTicketAuthorization auth, long now) {
        long base = Math.max(auth.recoveryUntil(),
                Math.addExact(now, OutputConstants.OUTPUT_RETENTION_MILLIS));
        return Math.addExact(base, REVIEW_RECEIPT_GRACE_MILLIS);
    }

    private static long maxRetainUntil(PreparedSubmission prepared, long initial) {
        long value = initial;
        for (PreparedItem item : prepared.items()) value = Math.max(value, item.retainUntil());
        return value;
    }

    private static void requireValidReceipt(
            OutputUploadDao.Receipt receipt, String taskId) {
        try {
            if (receipt.httpStatus() < 200 || receipt.httpStatus() >= 500
                    || receipt.responseJson() == null) throw new IllegalArgumentException();
            JsonNode body = JSON.readTree(receipt.responseJson());
            if (body == null || !body.isObject() || !body.has("code")) {
                throw new IllegalArgumentException();
            }
            if (receipt.httpStatus() == 200) {
                JsonNode data = body.get("data");
                if (!"E0".equals(body.path("code").asText()) || data == null
                        || !taskId.equals(data.path("taskId").asText())
                        || !"SUBMITTED".equals(data.path("state").asText())) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (Exception invalid) {
            throw unavailable("OUTPUT_SUBMISSION_RECEIPT_INVALID");
        }
    }

    private static void requireSameRoute(
            OutputTicketAuthorization expected, OutputTicketAuthorization actual,
            String taskId, RequiredRequest request) {
        if (expected == null || actual == null
                || !Objects.equals(expected.tenantId(), actual.tenantId())
                || !Objects.equals(expected.clientId(), actual.clientId())
                || !Objects.equals(expected.runId(), actual.runId())
                || !Objects.equals(expected.sourceType(), actual.sourceType())
                || !Objects.equals(expected.sourceId(), actual.sourceId())
                || !Objects.equals(expected.producerAgentId(), actual.producerAgentId())
                || !Objects.equals(expected.bindingId(), actual.bindingId())
                || !Objects.equals(expected.runtimeInstanceId(), actual.runtimeInstanceId())
                || !Objects.equals(expected.workItemId(), actual.workItemId())
                || expected.policyVersion() != actual.policyVersion()
                || !OutputConstants.SOURCE_TASK.equals(actual.sourceType())
                || !taskId.equals(actual.sourceId())
                || !request.runId().equals(actual.runId())
                || !request.workItemId().equals(actual.workItemId())
                || actual.policyVersion() != 1) {
            throw forbidden("Output ticket does not authorize this formal delivery");
        }
    }

    private static void requireProjected(
            OutputTicketAuthorization projected, String taskId) {
        if (projected == null || !exact(taskId, 100)
                || !OutputConstants.SOURCE_TASK.equals(projected.sourceType())
                || !taskId.equals(projected.sourceId()) || projected.policyVersion() != 1) {
            throw forbidden("Output ticket route is unavailable");
        }
    }

    private static TaskDeliveryHttpResult error(
            int status, String code, String message, String requestId,
            Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("retryable", status == 503);
        body.put("requestId", requestId);
        if (details != null && !details.isEmpty()) body.put("details", details);
        return new TaskDeliveryHttpResult(status, json(body));
    }

    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception impossible) { throw unavailable("OUTPUT_RESPONSE_SERIALIZATION_FAILED"); }
    }

    private static byte[] referenceKey(
            String tenant, String client, String type, String source, String output,
            long version, String kind, String nonce) {
        return sha256(String.join("\0", tenant, client, type, source, output,
                Long.toString(version), kind, nonce).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static long decimal(
            String value, boolean zeroAllowed, long max, String field) {
        try {
            String pattern = zeroAllowed ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}";
            if (value == null || !value.matches(pattern)) throw new NumberFormatException();
            long parsed = Long.parseLong(value);
            if (parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw bad("OUTPUT_" + field.toUpperCase() + "_INVALID");
        }
    }

    private static boolean exact(String value, int max) {
        return value != null && !value.isEmpty() && value.length() <= max
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean invalidMultilineText(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && codePoint != '\n' && codePoint != '\r' && codePoint != '\t');
    }

    private static void requireKey(String value) {
        if (value == null || !value.matches("[\\x21-\\x7e]{16,100}")) {
            throw bad("IDEMPOTENCY_KEY_INVALID");
        }
    }

    private static void requireRequestId(String value) {
        if (!exact(value, 100)) throw bad("OUTPUT_REQUEST_INVALID");
    }

    private static String rawBearer(String value) {
        if (value == null || !value.startsWith("Bearer ") || value.length() < 48) {
            throw new OutputAuthorizationException(
                    "OUTPUT_AUTH_UNAUTHORIZED", "Output ticket is required");
        }
        return value.substring(7);
    }

    private static BusinessRejection conflict(
            String code, String message, Map<String, Object> details) {
        return new BusinessRejection(409, code, message, details);
    }

    private static BusinessRejection unprocessable(String code, String message) {
        return new BusinessRejection(422, code, message, Map.of());
    }

    private static OutputDeliveryException bad(String code) {
        return new OutputDeliveryException(code, "Invalid formal delivery request", 400, false);
    }

    private static OutputAuthorizationException forbidden(String message) {
        return new OutputAuthorizationException("OUTPUT_AUTH_FORBIDDEN", message);
    }

    private static OutputDeliveryException unavailable(String code) {
        return new OutputDeliveryException(code, "Formal delivery unavailable", 503, true);
    }

    private record RequiredRequest(
            String taskId, String runId, String workItemId, long expectedTaskVersion,
            long expectedWorkItemVersion, String leaseToken, String summary,
            List<RequiredItem> items) { }

    private record RequiredItem(String artifactId, int artifactVersion, String purpose) { }

    private record ItemKey(String artifactId, int artifactVersion) { }

    private record DeliveryRequirements(
            String mode, int minFiles, List<String> requiredNames, int maxReviewRevisions) { }

    private record PreparedItem(
            String artifactId, int artifactVersion, String purpose, byte[] contentHash,
            String objectId, String fileName, String mimeType, long contentByteLength,
            long retainUntil, boolean inlineConclusion) { }

    private record PreparedSubmission(
            AgentTaskMetaEntity root, OutputTicketAuthorization auth,
            OutputRunBindingEntity run, AgentTaskWorkItemEntity workItem,
            DeliveryRequirements requirements, RequiredRequest request,
            List<PreparedItem> items, Map<String, OutputUploadDao.ObjectRow> objects,
            String deliveryId, String manifestArtifactId, long revision,
            String supersedesDeliveryId) { }

    private static final class BusinessRejection extends RuntimeException {
        private final int status;
        private final String code;
        private final Map<String, Object> details;

        private BusinessRejection(
                int status, String code, String message, Map<String, Object> details) {
            super(message);
            this.status = status;
            this.code = code;
            this.details = details == null ? Map.of() : Map.copyOf(details);
        }
    }
}
