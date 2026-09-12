package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskArtifactDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskRequestDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskArtifactContentDTO;
import cn.jia.agent.entity.AgentTaskArtifactDTO;
import cn.jia.agent.entity.AgentTaskArtifactEntity;
import cn.jia.agent.entity.AgentTaskArtifactPublishDTO;
import cn.jia.agent.entity.AgentTaskArtifactQueryDTO;
import cn.jia.agent.entity.AgentTaskArtifactViewDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskRequestCreateDTO;
import cn.jia.agent.entity.AgentTaskRequestDTO;
import cn.jia.agent.entity.AgentTaskRequestEntity;
import cn.jia.agent.entity.AgentTaskRequestQueryDTO;
import cn.jia.agent.entity.AgentTaskRequestTransitionDTO;
import cn.jia.agent.entity.AgentTaskRequestViewDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentTaskArtifactStorageException;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentTaskArtifactContentService;
import cn.jia.agent.service.AgentTaskArtifactService;
import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.agent.service.AgentTaskRequestService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.state.AgentTaskMemberStatus;
import cn.jia.agent.state.AgentTaskRequestStatus;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

@Named
@Transactional(rollbackFor = Exception.class)
public class AgentTaskCollaborationServiceImpl
        implements AgentTaskRequestService, AgentTaskArtifactService,
        AgentTaskArtifactContentService {
    private static final Set<String> MEMBER_ROLES =
            Set.of("coordinator", "worker", "reviewer", "observer");
    private static final Set<String> REQUEST_TYPES = Set.of(
            "help", "clarification", "dependency", "review", "resource", "reassignment", "approval");
    private static final Set<String> TARGET_TYPES = Set.of("agent", "role");
    private static final Set<String> ARTIFACT_TYPES = Set.of(
            "summary", "document", "patch", "commit", "test_report", "analysis", "dataset", "link");
    private static final Set<String> VISIBILITIES = Set.of("task_members", "reviewer", "private");
    private static final Set<String> STORAGE_SCHEMES = Set.of("https", "s3", "oss", "cos");
    private static final Set<AgentTaskMemberStatus> READABLE_MEMBER_STATUSES = Set.of(
            AgentTaskMemberStatus.ACCEPTED, AgentTaskMemberStatus.WORKING,
            AgentTaskMemberStatus.BLOCKED, AgentTaskMemberStatus.DONE);
    private static final Set<AgentTaskMemberStatus> WRITABLE_MEMBER_STATUSES = Set.of(
            AgentTaskMemberStatus.ACCEPTED, AgentTaskMemberStatus.WORKING,
            AgentTaskMemberStatus.BLOCKED, AgentTaskMemberStatus.DONE);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_INLINE_CONTENT_BYTES = 262_144;
    private static final int MAX_TEXT_BYTES = 65_535;
    private static final int MAX_MEDIUMTEXT_BYTES = 16_777_215;
    private static final int MAX_STORAGE_URI_CHARS = 1_000;
    private static final int MAX_LIST_LIMIT = 500;
    private static final String MANAGED_STORAGE_METADATA = "_cyfArtifactStorageV1";
    private static final String INLINE_CONTENT_MIME = "text/plain";

    private final AgentTaskMetaDao taskMetaDao;
    private final AgentTaskMemberDao memberDao;
    private final AgentTaskWorkItemDao workItemDao;
    private final AgentTaskRequestDao requestDao;
    private final AgentTaskArtifactDao artifactDao;
    private final AgentTaskArtifactStorage artifactStorage;
    private final AgentTaskMutationTransaction mutationTransaction;
    private final AgentTaskEventWriter eventWriter;
    private final LongSupplier clock;

    @Inject
    public AgentTaskCollaborationServiceImpl(
            AgentTaskMetaDao taskMetaDao, AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao, AgentTaskRequestDao requestDao,
            AgentTaskArtifactDao artifactDao, AgentTaskArtifactStorage artifactStorage,
            AgentTaskMutationTransaction mutationTransaction, AgentTaskEventWriter eventWriter) {
        this(taskMetaDao, memberDao, workItemDao, requestDao, artifactDao, artifactStorage,
                mutationTransaction, eventWriter, System::currentTimeMillis);
    }

    /** Retains the B06 construction contract with managed file storage fail-closed. */
    public AgentTaskCollaborationServiceImpl(
            AgentTaskMetaDao taskMetaDao, AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao, AgentTaskRequestDao requestDao,
            AgentTaskArtifactDao artifactDao, AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter) {
        this(taskMetaDao, memberDao, workItemDao, requestDao, artifactDao,
                new DisabledAgentTaskArtifactStorage(), mutationTransaction, eventWriter,
                System::currentTimeMillis);
    }

    AgentTaskCollaborationServiceImpl(
            AgentTaskMetaDao taskMetaDao, AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao, AgentTaskRequestDao requestDao,
            AgentTaskArtifactDao artifactDao) {
        this(taskMetaDao, memberDao, workItemDao, requestDao, artifactDao,
                new DisabledAgentTaskArtifactStorage(), System::currentTimeMillis);
    }

    AgentTaskCollaborationServiceImpl(
            AgentTaskMetaDao taskMetaDao, AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao, AgentTaskRequestDao requestDao,
            AgentTaskArtifactDao artifactDao, LongSupplier clock) {
        this(taskMetaDao, memberDao, workItemDao, requestDao, artifactDao,
                new DisabledAgentTaskArtifactStorage(), clock);
    }

    AgentTaskCollaborationServiceImpl(
            AgentTaskMetaDao taskMetaDao, AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao, AgentTaskRequestDao requestDao,
            AgentTaskArtifactDao artifactDao, AgentTaskArtifactStorage artifactStorage,
            LongSupplier clock) {
        this(taskMetaDao, memberDao, workItemDao, requestDao, artifactDao, artifactStorage,
                directTransaction(taskMetaDao), command -> null, clock);
    }

    AgentTaskCollaborationServiceImpl(
            AgentTaskMetaDao taskMetaDao, AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao, AgentTaskRequestDao requestDao,
            AgentTaskArtifactDao artifactDao, AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter, LongSupplier clock) {
        this(taskMetaDao, memberDao, workItemDao, requestDao, artifactDao,
                new DisabledAgentTaskArtifactStorage(), mutationTransaction, eventWriter, clock);
    }

    AgentTaskCollaborationServiceImpl(
            AgentTaskMetaDao taskMetaDao, AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao, AgentTaskRequestDao requestDao,
            AgentTaskArtifactDao artifactDao, AgentTaskArtifactStorage artifactStorage,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter, LongSupplier clock) {
        this.taskMetaDao = Objects.requireNonNull(taskMetaDao, "taskMetaDao");
        this.memberDao = Objects.requireNonNull(memberDao, "memberDao");
        this.workItemDao = Objects.requireNonNull(workItemDao, "workItemDao");
        this.requestDao = Objects.requireNonNull(requestDao, "requestDao");
        this.artifactDao = Objects.requireNonNull(artifactDao, "artifactDao");
        this.artifactStorage = Objects.requireNonNull(artifactStorage, "artifactStorage");
        this.mutationTransaction = Objects.requireNonNull(mutationTransaction, "mutationTransaction");
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskRequestViewDTO create(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskRequestCreateDTO command) {
        requireScope(tenantId, clientId, taskId, actorAgentId);
        return mutationTransaction.executeWithLockedTaskRoot(tenantId, clientId, taskId,
                taskRoot -> createLocked(tenantId, clientId, taskId, actorAgentId, command, taskRoot));
    }

    private AgentTaskRequestViewDTO createLocked(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskRequestCreateDTO command, AgentTaskMetaEntity taskRoot) {
        Access access = requireAccess(tenantId, clientId, taskId, actorAgentId, true, taskRoot);
        if (command == null) {
            throw invalid("request command is required");
        }
        requireId(command.getRequestId(), "requestId", 100);
        requireId(command.getRequesterAgentId(), "requesterAgentId", 100);
        if (!actorAgentId.equals(command.getRequesterAgentId())) {
            throw forbidden();
        }
        String targetType = canonical(command.getTargetType(), "targetType");
        if (!TARGET_TYPES.contains(targetType)) {
            throw invalid("targetType must be agent or role");
        }
        requireId(command.getTargetId(), "targetId", 100);
        String targetId = command.getTargetId();
        validateTarget(tenantId, clientId, taskId, targetType, targetId, taskRoot);
        String requestType = canonical(command.getRequestType(), "requestType");
        if (!REQUEST_TYPES.contains(requestType)) {
            throw invalid("requestType is not supported");
        }
        requireText(command.getTitle(), "title", 255);
        requireUtf8Text(command.getDescription(), "description", MAX_TEXT_BYTES);
        if (command.getDueAt() != null && command.getDueAt() < 0) {
            throw invalid("dueAt must not be negative");
        }
        String workItemId = requireWorkItem(
                tenantId, clientId, taskId, command.getWorkItemId());

        AgentTaskRequestDTO insert = new AgentTaskRequestDTO();
        insert.setRequestId(command.getRequestId());
        insert.setTaskId(taskId);
        insert.setWorkItemId(workItemId);
        insert.setRequesterAgentId(actorAgentId);
        insert.setTargetType(targetType);
        insert.setTargetId(targetId);
        insert.setRequestType(requestType);
        insert.setStatus(AgentTaskRequestStatus.OPEN.value());
        insert.setPriority(command.getPriority() == null ? 0 : command.getPriority());
        insert.setTitle(command.getTitle().trim());
        insert.setDescription(command.getDescription());
        insert.setDueAt(command.getDueAt());
        try {
            requireSingleInsert(requestDao.insert(tenantId, clientId, insert));
        } catch (RuntimeException e) {
            if (isDuplicateConflict(e)) {
                throw conflict("Request changed or already exists", null);
            }
            if (isPersistenceValidationFailure(e)) {
                throw invalidPersisted("Request could not be persisted");
            }
            throw e;
        }
        AgentTaskRequestEntity stored = requestDao.findByRequestId(
                tenantId, clientId, taskId, insert.getRequestId());
        if (stored == null) {
            throw invalidPersisted("Inserted request could not be read in its scope");
        }
        if (stored.getVersion() == null || stored.getVersion() != 0L) {
            throw invalidPersisted("Inserted request has an unexpected initial version");
        }
        appendRequestEvent(tenantId, clientId, taskId, actorAgentId, stored,
                requestCreateEvent(requestType), null, AgentTaskRequestStatus.OPEN.value(),
                0L, 0L, now());
        return requestView(stored);
    }

    @Override
    public AgentTaskRequestViewDTO get(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId) {
        requireAccess(tenantId, clientId, taskId, actorAgentId, false);
        return requestView(requireRequest(tenantId, clientId, taskId, requestId));
    }

    @Override
    public List<AgentTaskRequestViewDTO> list(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskRequestQueryDTO query) {
        requireAccess(tenantId, clientId, taskId, actorAgentId, false);
        String status = query == null ? null : trimToNull(query.getStatus());
        if (status != null) {
            try {
                AgentTaskRequestStatus.fromPersistedValue(status);
            } catch (IllegalArgumentException e) {
                throw invalid("status is not supported");
            }
        }
        String workItemId = requireWorkItem(tenantId, clientId, taskId,
                query == null ? null : query.getWorkItemId());
        int limit = boundedLimit(query == null ? null : query.getLimit());
        return requestDao.listByTask(
                        tenantId, clientId, taskId, status, workItemId, limit).stream()
                .map(this::requestView)
                .toList();
    }

    @Override
    public AgentTaskRequestViewDTO acknowledge(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command) {
        return transitionRequest(tenantId, clientId, taskId, actorAgentId, requestId,
                AgentTaskRequestStatus.ACKNOWLEDGED, command);
    }

    @Override
    public AgentTaskRequestViewDTO resolve(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command) {
        return transitionRequest(tenantId, clientId, taskId, actorAgentId, requestId,
                AgentTaskRequestStatus.RESOLVED, command);
    }

    @Override
    public AgentTaskRequestViewDTO reject(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command) {
        return transitionRequest(tenantId, clientId, taskId, actorAgentId, requestId,
                AgentTaskRequestStatus.REJECTED, command);
    }

    @Override
    public AgentTaskRequestViewDTO cancel(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command) {
        return transitionRequest(tenantId, clientId, taskId, actorAgentId, requestId,
                AgentTaskRequestStatus.CANCELLED, command);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskArtifactViewDTO publish(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskArtifactPublishDTO command) {
        requireScope(tenantId, clientId, taskId, actorAgentId);
        return mutationTransaction.executeWithLockedTaskRoot(tenantId, clientId, taskId,
                taskRoot -> publishLocked(tenantId, clientId, taskId, actorAgentId, command, taskRoot));
    }

    private AgentTaskArtifactViewDTO publishLocked(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskArtifactPublishDTO command, AgentTaskMetaEntity taskRoot) {
        // Frozen order: task-root lock -> ACL/work-item validation -> artifact-version lock ->
        // bounded filesystem write -> artifact row -> ARTIFACT_PUBLISHED event.
        requireAccess(tenantId, clientId, taskId, actorAgentId, true, taskRoot);
        if (command == null) {
            throw invalid("artifact command is required");
        }
        requireId(command.getArtifactId(), "artifactId", 100);
        requireId(command.getProducerAgentId(), "producerAgentId", 100);
        if (!actorAgentId.equals(command.getProducerAgentId())) {
            throw forbidden();
        }
        String workItemId = requireWorkItem(
                tenantId, clientId, taskId, command.getWorkItemId());
        String artifactType = canonical(command.getArtifactType(), "artifactType");
        if (!ARTIFACT_TYPES.contains(artifactType)) {
            throw invalid("artifactType is not supported");
        }
        requireText(command.getTitle(), "title", 255);
        String visibility = canonicalDefault(command.getVisibility(), "task_members");
        if (!VISIBILITIES.contains(visibility)) {
            throw invalid("visibility is not supported");
        }
        ArtifactPayloadMode payloadMode = validateArtifactPayloadShape(command);
        int expectedPrevious = requireArtifactVersions(command);

        AgentTaskArtifactEntity latest = artifactDao.findLatestVersionForUpdate(
                tenantId, clientId, taskId, command.getArtifactId());
        int persistedLatest = latest == null ? 0 : requirePersistedArtifactVersion(latest);
        if (persistedLatest != expectedPrevious) {
            throw conflict("Artifact version changed concurrently", null);
        }
        if (latest != null && !taskId.equals(latest.getTaskId())) {
            throw notFound();
        }

        ArtifactPayload payload = materializeArtifactPayload(
                tenantId, clientId, taskId, command, payloadMode);
        AgentTaskArtifactDTO insert = new AgentTaskArtifactDTO();
        insert.setArtifactId(command.getArtifactId());
        insert.setTaskId(taskId);
        insert.setWorkItemId(workItemId);
        insert.setProducerAgentId(actorAgentId);
        insert.setArtifactType(artifactType);
        insert.setTitle(command.getTitle().trim());
        insert.setContent(payload.content());
        insert.setStorageUri(payload.storageUri());
        insert.setContentHash(payload.sha256());
        insert.setArtifactVersion(command.getArtifactVersion());
        insert.setVisibility(visibility);
        insert.setMetadataJson(payload.metadataJson());
        insert.setCreatedAt(now());
        try {
            requireSingleInsert(artifactDao.insert(tenantId, clientId, insert));
        } catch (RuntimeException e) {
            // Managed bytes are already an immutable scoped digest at this point. If the database
            // transaction fails, retaining that digest is safer than deleting content another
            // transaction/version may reference; it is reusable and safe for reference-scan GC.
            if (isDuplicateConflict(e)) {
                throw conflict("Artifact version changed concurrently", null);
            }
            if (isPersistenceValidationFailure(e)) {
                throw invalidPersisted("Artifact could not be persisted");
            }
            throw e;
        }
        AgentTaskArtifactEntity stored = artifactDao.findVersion(
                tenantId, clientId, taskId, insert.getArtifactId(), insert.getArtifactVersion());
        if (stored == null) {
            throw invalidPersisted("Inserted artifact could not be read in its scope");
        }
        if (!taskId.equals(stored.getTaskId())
                || !insert.getArtifactId().equals(stored.getArtifactId())
                || !artifactType.equals(stored.getArtifactType())
                || !command.getArtifactVersion().equals(stored.getArtifactVersion())
                || !visibility.equals(stored.getVisibility())
                || !payload.sha256().equals(stored.getContentHash())
                || !Objects.equals(payload.storageUri(), stored.getStorageUri())
                || !Objects.equals(payload.content(), stored.getContent())) {
            throw invalidPersisted("Inserted artifact does not match its persisted event metadata");
        }
        ArtifactMaterial persistedMaterial = artifactMaterial(stored);
        if (!Objects.equals(persistedMaterial.byteLength(), payload.byteLength())
                || persistedMaterial.managedStorage() != payload.managedStorage()
                || !Objects.equals(persistedMaterial.mimeType(), payload.mimeType())) {
            throw invalidPersisted("Inserted artifact storage metadata does not match its content");
        }
        appendArtifactEvent(tenantId, clientId, taskId, actorAgentId, stored,
                new ArtifactEventDigest(payload.sha256(), payload.byteLength()), now());
        return artifactView(stored);
    }

    @Override
    public AgentTaskArtifactViewDTO getLatest(String tenantId, String clientId, String taskId,
            String actorAgentId, String artifactId) {
        Access access = requireAccess(tenantId, clientId, taskId, actorAgentId, false);
        requireId(artifactId, "artifactId", 100);
        AgentTaskArtifactEntity entity = artifactDao.findLatestVersion(
                tenantId, clientId, taskId, artifactId);
        if (entity == null || !canReadArtifact(access, entity)) {
            throw notFound();
        }
        return artifactView(entity);
    }

    @Override
    public AgentTaskArtifactViewDTO getVersion(String tenantId, String clientId, String taskId,
            String actorAgentId, String artifactId, int artifactVersion) {
        Access access = requireAccess(tenantId, clientId, taskId, actorAgentId, false);
        requireId(artifactId, "artifactId", 100);
        if (artifactVersion < 1) {
            throw invalid("artifactVersion must be positive");
        }
        AgentTaskArtifactEntity entity = artifactDao.findVersion(
                tenantId, clientId, taskId, artifactId, artifactVersion);
        if (entity == null || !canReadArtifact(access, entity)) {
            throw notFound();
        }
        return artifactView(entity);
    }

    @Override
    public AgentTaskArtifactContentDTO readContent(String tenantId, String clientId, String taskId,
            String actorAgentId, String artifactId, int artifactVersion) {
        Access access = requireAccess(tenantId, clientId, taskId, actorAgentId, false);
        requireId(artifactId, "artifactId", 100);
        if (artifactVersion < 1) {
            throw invalid("artifactVersion must be positive");
        }
        AgentTaskArtifactEntity entity = artifactDao.findVersion(
                tenantId, clientId, taskId, artifactId, artifactVersion);
        if (entity == null || !canReadArtifact(access, entity)) {
            throw notFound();
        }
        ArtifactMaterial material = artifactMaterial(entity);
        byte[] content;
        if (material.inlineContent() != null) {
            content = material.inlineContent();
        } else if (material.managedStorage()) {
            try {
                AgentTaskArtifactStorage.StoredContent stored = artifactStorage.read(
                        new AgentTaskArtifactStorage.Scope(tenantId, clientId, taskId),
                        entity.getStorageUri(), entity.getContentHash(), material.byteLength(),
                        material.mimeType());
                content = stored == null ? null : stored.content();
                if (content == null || !entity.getContentHash().equals(stored.sha256())
                        || material.byteLength() != stored.byteLength()
                        || !material.mimeType().equals(stored.mimeType())
                        || content.length != stored.byteLength()
                        || !stored.sha256().equals(sha256(content))) {
                    throw invalidPersisted(
                            "Managed artifact storage returned inconsistent content");
                }
            } catch (AgentTaskArtifactStorageException failure) {
                throw invalidPersisted(
                        "Managed artifact storage is unavailable or corrupt");
            }
        } else {
            // Legacy external URIs are metadata-only. This service intentionally performs no
            // outbound fetch, so they cannot become an SSRF or arbitrary local-file read surface.
            throw invalid("Artifact content is not managed by this service");
        }
        return new AgentTaskArtifactContentDTO(entity.getArtifactId(), entity.getArtifactVersion(),
                entity.getContentHash(), material.byteLength(), material.mimeType(), content);
    }

    @Override
    public List<AgentTaskArtifactViewDTO> list(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskArtifactQueryDTO query) {
        Access access = requireAccess(tenantId, clientId, taskId, actorAgentId, false);
        String workItemId = requireWorkItem(tenantId, clientId, taskId,
                query == null ? null : query.getWorkItemId());
        int limit = boundedLimit(query == null ? null : query.getLimit());
        List<AgentTaskArtifactEntity> entities = artifactDao.listVisibleByTask(
                tenantId, clientId, taskId, workItemId, actorAgentId,
                "reviewer".equals(access.role()), access.coordinator(), limit);
        return entities.stream()
                .filter(entity -> canReadArtifact(access, entity))
                .limit(limit)
                .map(this::artifactView)
                .toList();
    }

    @Override
    public List<AgentTaskArtifactViewDTO> listVersions(String tenantId, String clientId, String taskId,
            String actorAgentId, String artifactId) {
        Access access = requireAccess(tenantId, clientId, taskId, actorAgentId, false);
        requireId(artifactId, "artifactId", 100);
        return artifactDao.listVersions(tenantId, clientId, taskId, artifactId).stream()
                .filter(entity -> canReadArtifact(access, entity))
                .map(this::artifactView)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    AgentTaskRequestViewDTO transitionRequest(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId, AgentTaskRequestStatus target,
            AgentTaskRequestTransitionDTO command) {
        requireScope(tenantId, clientId, taskId, actorAgentId);
        return mutationTransaction.executeWithLockedTaskRoot(tenantId, clientId, taskId,
                taskRoot -> transitionRequestLocked(tenantId, clientId, taskId, actorAgentId,
                        requestId, target, command, taskRoot));
    }

    private AgentTaskRequestViewDTO transitionRequestLocked(
            String tenantId, String clientId, String taskId, String actorAgentId,
            String requestId, AgentTaskRequestStatus target, AgentTaskRequestTransitionDTO command,
            AgentTaskMetaEntity taskRoot) {
        Access access = requireAccess(tenantId, clientId, taskId, actorAgentId, true, taskRoot);
        AgentTaskRequestEntity current = requireRequest(tenantId, clientId, taskId, requestId);
        long expectedVersion = requireExpectedVersion(command);
        if (current.getVersion() == null || current.getVersion() < 0) {
            throw invalidPersisted("Persisted request version is invalid");
        }
        if (current.getVersion() != expectedVersion) {
            throw conflict("Request changed concurrently", null);
        }
        AgentTaskRequestStatus currentStatus = requestStatus(current.getStatus());
        if (!currentStatus.canTransitionTo(target)) {
            throw new AgentTaskCollaborationException(Reason.INVALID_TRANSITION,
                    "Request transition is not allowed");
        }
        if (target == AgentTaskRequestStatus.CANCELLED) {
            if (!actorAgentId.equals(current.getRequesterAgentId()) && !access.coordinator()) {
                throw forbidden();
            }
        } else if (!isRequestTarget(access, actorAgentId, current)) {
            throw forbidden();
        }

        Map<String, Object> response = command.getResponse();
        if ((target == AgentTaskRequestStatus.RESOLVED || target == AgentTaskRequestStatus.REJECTED)
                && (response == null || response.isEmpty())) {
            throw invalid("A structured response is required for resolve/reject");
        }
        long changedAt = now();
        AgentTaskRequestDTO update = copyRequest(current);
        update.setStatus(target.value());
        if (response != null) {
            update.setResponseJson(serializeObject(response, "response", MAX_MEDIUMTEXT_BYTES));
        }
        if (target == AgentTaskRequestStatus.ACKNOWLEDGED && update.getAcknowledgedAt() == null) {
            update.setAcknowledgedAt(changedAt);
        }
        if ((target == AgentTaskRequestStatus.RESOLVED || target == AgentTaskRequestStatus.REJECTED)
                && update.getResolvedAt() == null) {
            update.setResolvedAt(changedAt);
        }
        int updated;
        try {
            updated = requestDao.updateByVersion(
                    tenantId, clientId, taskId, requestId, expectedVersion, update);
        } catch (RuntimeException e) {
            if (isDuplicateConflict(e)) {
                throw conflict("Request changed concurrently", null);
            }
            if (isPersistenceValidationFailure(e)) {
                throw invalidPersisted("Request could not be persisted");
            }
            throw e;
        }
        requireSingleCas(updated);
        current.setStatus(update.getStatus());
        current.setResponseJson(update.getResponseJson());
        current.setAcknowledgedAt(update.getAcknowledgedAt());
        current.setResolvedAt(update.getResolvedAt());
        current.setVersion(expectedVersion + 1);
        current.setUpdateTime(changedAt);
        appendRequestEvent(tenantId, clientId, taskId, actorAgentId, current,
                requestTransitionEvent(target), currentStatus.value(), target.value(),
                expectedVersion, expectedVersion + 1, changedAt);
        return requestView(current);
    }

    private String requestCreateEvent(String requestType) {
        return switch (requestType) {
            case "help" -> TaskEventType.HELP_REQUESTED;
            case "review" -> TaskEventType.REVIEW_REQUESTED;
            default -> TaskEventType.REQUEST_CREATED;
        };
    }

    private String requestTransitionEvent(AgentTaskRequestStatus status) {
        return switch (status) {
            case ACKNOWLEDGED -> TaskEventType.REQUEST_ACKNOWLEDGED;
            case RESOLVED -> TaskEventType.REQUEST_RESOLVED;
            case REJECTED -> TaskEventType.REQUEST_REJECTED;
            case CANCELLED -> TaskEventType.REQUEST_CANCELLED;
            default -> throw invalidPersisted("Request status has no canonical mutation event");
        };
    }

    private void appendRequestEvent(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskRequestEntity request, String eventType,
            String fromStatus, String toStatus, long expectedVersion, long resultVersion,
            long occurredAt) {
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.REQUEST_ID, request.getRequestId())
                .put(TaskEventPayload.Key.REQUEST_TYPE, request.getRequestType())
                .put(TaskEventPayload.Key.TARGET_TYPE, request.getTargetType())
                .put(TaskEventPayload.Key.TARGET_ID, request.getTargetId())
                .put(TaskEventPayload.Key.TO_STATUS, toStatus)
                .put(TaskEventPayload.Key.EXPECTED_VERSION, expectedVersion)
                .put(TaskEventPayload.Key.RESULT_VERSION, resultVersion);
        if (fromStatus != null) payload.put(TaskEventPayload.Key.FROM_STATUS, fromStatus);
        if (request.getWorkItemId() != null) {
            payload.put(TaskEventPayload.Key.WORK_ITEM_ID, request.getWorkItemId());
        }
        eventWriter.append(AgentTaskMutationEventSupport.command(tenantId, clientId, taskId,
                eventType, TaskEventType.ActorType.AGENT, actorAgentId,
                TaskEventType.Aggregate.REQUEST, request.getRequestId(), payload, occurredAt, resultVersion));
    }

    private void appendArtifactEvent(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskArtifactEntity artifact,
            ArtifactEventDigest digest, long occurredAt) {
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.ARTIFACT_ID, artifact.getArtifactId())
                .put(TaskEventPayload.Key.ARTIFACT_TYPE, artifact.getArtifactType())
                .put(TaskEventPayload.Key.ARTIFACT_VERSION, artifact.getArtifactVersion().longValue())
                .put(TaskEventPayload.Key.VISIBILITY, artifact.getVisibility())
                .put(TaskEventPayload.Key.CONTENT_SHA256, digest.sha256());
        if (digest.byteLength() != null) {
            payload.put(TaskEventPayload.Key.CONTENT_BYTE_LENGTH, digest.byteLength());
        }
        if (artifact.getWorkItemId() != null) {
            payload.put(TaskEventPayload.Key.WORK_ITEM_ID, artifact.getWorkItemId());
        }
        eventWriter.append(AgentTaskMutationEventSupport.command(tenantId, clientId, taskId,
                TaskEventType.ARTIFACT_PUBLISHED, TaskEventType.ActorType.AGENT, actorAgentId,
                TaskEventType.Aggregate.ARTIFACT, artifact.getArtifactId(), payload, occurredAt,
                artifact.getArtifactVersion().longValue()));
    }

    private Access requireAccess(String tenantId, String clientId, String taskId,
            String actorAgentId, boolean write) {
        requireScope(tenantId, clientId, taskId, actorAgentId);
        return requireAccess(tenantId, clientId, taskId, actorAgentId, write,
                taskMetaDao.findByTaskId(tenantId, clientId, taskId));
    }

    private Access requireAccess(String tenantId, String clientId, String taskId,
            String actorAgentId, boolean write, AgentTaskMetaEntity task) {
        if (task == null) {
            throw notFound();
        }
        boolean coordinator = actorAgentId.equals(task.getCoordinatorAgentId());
        AgentTaskMemberEntity member = memberDao.findByTaskAndAgent(
                tenantId, clientId, taskId, actorAgentId);
        if (coordinator) {
            return new Access(actorAgentId, "coordinator", true);
        }
        if (member == null) {
            throw forbidden();
        }
        String role = member.getMemberRole();
        if (!MEMBER_ROLES.contains(role)) {
            throw invalidPersisted("Persisted member role is invalid");
        }
        AgentTaskMemberStatus status;
        try {
            status = AgentTaskMemberStatus.fromPersistedValue(member.getMemberStatus());
        } catch (IllegalArgumentException e) {
            throw invalidPersisted("Persisted member status is invalid");
        }
        Set<AgentTaskMemberStatus> allowed = write ? WRITABLE_MEMBER_STATUSES : READABLE_MEMBER_STATUSES;
        if (!allowed.contains(status) || (write && "observer".equals(role))) {
            throw forbidden();
        }
        return new Access(actorAgentId, role, coordinator || "coordinator".equals(role));
    }

    private void validateTarget(String tenantId, String clientId, String taskId,
            String targetType, String targetId, AgentTaskMetaEntity taskRoot) {
        if ("agent".equals(targetType)) {
            AgentTaskMemberEntity target = memberDao.findByTaskAndAgent(
                    tenantId, clientId, taskId, targetId);
            if (target == null) {
                if (taskRoot == null || !targetId.equals(taskRoot.getCoordinatorAgentId())) {
                    throw notFound();
                }
                return;
            }
            requireEligibleTargetMember(target);
            return;
        }
        if (!MEMBER_ROLES.contains(targetId)) {
            throw invalid("target role is not supported");
        }
        boolean present = memberDao.listByTask(tenantId, clientId, taskId).stream()
                .anyMatch(member -> targetId.equals(member.getMemberRole()) && eligibleTargetMember(member));
        if (!present && "coordinator".equals(targetId)) {
            present = taskRoot != null && !StringUtil.isBlank(taskRoot.getCoordinatorAgentId());
        }
        if (!present) {
            throw notFound();
        }
    }

    private void requireEligibleTargetMember(AgentTaskMemberEntity member) {
        if (!eligibleTargetMember(member)) {
            throw forbidden();
        }
    }

    private boolean eligibleTargetMember(AgentTaskMemberEntity member) {
        if (!MEMBER_ROLES.contains(member.getMemberRole())) {
            throw invalidPersisted("Persisted member role is invalid");
        }
        try {
            return READABLE_MEMBER_STATUSES.contains(
                    AgentTaskMemberStatus.fromPersistedValue(member.getMemberStatus()));
        } catch (IllegalArgumentException e) {
            throw invalidPersisted("Persisted member status is invalid");
        }
    }

    private boolean isRequestTarget(Access access, String actorAgentId, AgentTaskRequestEntity request) {
        if (access.coordinator()) {
            return true;
        }
        return switch (request.getTargetType()) {
            case "agent" -> actorAgentId.equals(request.getTargetId());
            case "role" -> access.role().equals(request.getTargetId());
            default -> throw invalidPersisted("Persisted request target type is invalid");
        };
    }

    private AgentTaskRequestEntity requireRequest(
            String tenantId, String clientId, String taskId, String requestId) {
        requireId(requestId, "requestId", 100);
        AgentTaskRequestEntity request = requestDao.findByRequestId(
                tenantId, clientId, taskId, requestId);
        if (request == null) {
            throw notFound();
        }
        if (!taskId.equals(request.getTaskId())) {
            throw notFound();
        }
        return request;
    }

    private String requireWorkItem(
            String tenantId, String clientId, String taskId, String workItemId) {
        if (workItemId == null || workItemId.isEmpty()) {
            return null;
        }
        requireId(workItemId, "workItemId", 100);
        AgentTaskWorkItemEntity item = workItemDao.findByTaskAndWorkItemId(
                tenantId, clientId, taskId, workItemId);
        if (item == null || !taskId.equals(item.getTaskId())) {
            throw notFound();
        }
        return workItemId;
    }

    private ArtifactPayloadMode validateArtifactPayloadShape(
            AgentTaskArtifactPublishDTO command) {
        boolean hasInline = command.getContent() != null && !command.getContent().isEmpty();
        boolean hasManagedBytes = command.hasContentBytes();
        boolean hasExternalStorage = !StringUtil.isBlank(command.getStorageUri());
        int modeCount = (hasInline ? 1 : 0) + (hasManagedBytes ? 1 : 0)
                + (hasExternalStorage ? 1 : 0);
        if (modeCount != 1) {
            throw invalid("Exactly one of content, contentBytes, or storageUri is required");
        }
        String expectedHash = command.getContentHash();
        if (expectedHash == null || !SHA256.matcher(expectedHash).matches()) {
            throw invalid("contentHash must be lowercase SHA-256 hex");
        }
        if (command.getContentByteLength() != null && command.getContentByteLength() < 0) {
            throw invalid("contentByteLength must not be negative");
        }
        if (hasManagedBytes) {
            if (StringUtil.isBlank(command.getContentMimeType())) {
                throw invalid("contentMimeType is required for managed artifact content");
            }
        } else if (!StringUtil.isBlank(command.getContentMimeType())) {
            throw invalid("contentMimeType is only valid with managed artifact contentBytes");
        }
        if (command.getMetadata() != null) {
            if (command.getMetadata().containsKey(MANAGED_STORAGE_METADATA)) {
                throw invalid("metadata contains a reserved artifact storage key");
            }
            serializeObject(command.getMetadata(), "metadata", MAX_TEXT_BYTES);
        }
        if (hasInline) return ArtifactPayloadMode.INLINE;
        if (hasManagedBytes) return ArtifactPayloadMode.MANAGED;
        return ArtifactPayloadMode.LEGACY_EXTERNAL;
    }

    private ArtifactPayload materializeArtifactPayload(String tenantId, String clientId,
            String taskId, AgentTaskArtifactPublishDTO command, ArtifactPayloadMode mode) {
        return switch (mode) {
            case INLINE -> inlinePayload(command);
            case LEGACY_EXTERNAL -> legacyExternalPayload(command);
            case MANAGED -> managedPayload(tenantId, clientId, taskId, command);
        };
    }

    private ArtifactPayload inlinePayload(AgentTaskArtifactPublishDTO command) {
        byte[] contentBytes = utf8Bytes(command.getContent(), "content");
        if (contentBytes.length > MAX_INLINE_CONTENT_BYTES) {
            throw invalid("Inline artifact content exceeds the B06 limit; use managed storage");
        }
        String actualHash = sha256(contentBytes);
        requireMatchingExpectation(command.getContentHash(), actualHash,
                "contentHash does not match inline content");
        requireMatchingLength(command.getContentByteLength(), contentBytes.length,
                "contentByteLength does not match inline content");
        return new ArtifactPayload(command.getContent(), null, actualHash,
                (long) contentBytes.length, INLINE_CONTENT_MIME, false,
                serializeObject(command.getMetadata(), "metadata", MAX_TEXT_BYTES));
    }

    private ArtifactPayload legacyExternalPayload(AgentTaskArtifactPublishDTO command) {
        String storageUri = command.getStorageUri().trim();
        validateStorageUri(storageUri);
        return new ArtifactPayload(null, storageUri, command.getContentHash(),
                command.getContentByteLength(), null, false,
                serializeObject(command.getMetadata(), "metadata", MAX_TEXT_BYTES));
    }

    private ArtifactPayload managedPayload(String tenantId, String clientId, String taskId,
            AgentTaskArtifactPublishDTO command) {
        byte[] content = command.getContentBytes();
        String actualHash = sha256(content);
        long actualLength = content.length;
        requireMatchingExpectation(command.getContentHash(), actualHash,
                "contentHash does not match managed content");
        requireMatchingLength(command.getContentByteLength(), actualLength,
                "contentByteLength does not match managed content");
        String metadataJson = serializeManagedMetadata(
                command.getMetadata(), actualHash, actualLength, command.getContentMimeType());
        AgentTaskArtifactStorage.Scope scope =
                new AgentTaskArtifactStorage.Scope(tenantId, clientId, taskId);
        AgentTaskArtifactStorage.StoredObject stored;
        try {
            stored = artifactStorage.store(scope, content, command.getContentMimeType());
        } catch (AgentTaskArtifactStorageException failure) {
            throw storageFailure(failure);
        }
        if (stored == null || !actualHash.equals(stored.sha256())
                || actualLength != stored.byteLength()
                || !command.getContentMimeType().equals(stored.mimeType())
                || !artifactStorage.owns(stored.storageUri())
                || !artifactStorage.matches(scope, stored.storageUri(), actualHash)) {
            throw invalidPersisted("Managed artifact storage returned inconsistent metadata");
        }
        return new ArtifactPayload(null, stored.storageUri(), actualHash,
                actualLength, stored.mimeType(), true, metadataJson);
    }

    private String serializeManagedMetadata(Map<String, Object> userMetadata,
            String sha256, long byteLength, String mimeType) {
        Map<String, Object> combined = new LinkedHashMap<>();
        if (userMetadata != null) {
            combined.putAll(userMetadata);
        }
        Map<String, Object> internal = new LinkedHashMap<>();
        internal.put("schemaVersion", 1);
        internal.put("sha256", sha256);
        internal.put("byteLength", byteLength);
        internal.put("mimeType", mimeType);
        combined.put(MANAGED_STORAGE_METADATA, internal);
        return serializeObject(combined, "metadata", MAX_TEXT_BYTES);
    }

    private void requireMatchingExpectation(String expected, String actual, String message) {
        if (expected != null && !expected.equals(actual)) {
            throw invalid(message);
        }
    }

    private void requireMatchingLength(Long expected, long actual, String message) {
        if (expected != null && expected != actual) {
            throw invalid(message);
        }
    }

    private AgentTaskCollaborationException storageFailure(
            AgentTaskArtifactStorageException failure) {
        if (failure.getReason() == AgentTaskArtifactStorageException.Reason.INVALID_REQUEST) {
            return invalid(failure.getMessage());
        }
        return invalidPersisted("Managed artifact storage is unavailable or corrupt");
    }

    private record ArtifactPayload(String content, String storageUri, String sha256,
            Long byteLength, String mimeType, boolean managedStorage, String metadataJson) {
    }

    private enum ArtifactPayloadMode {
        INLINE,
        MANAGED,
        LEGACY_EXTERNAL
    }

    private record ArtifactEventDigest(String sha256, Long byteLength) {
        private ArtifactEventDigest {
            if (sha256 == null || !SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("sha256 must be lowercase SHA-256 hex");
            }
            if (byteLength != null && byteLength < 0) {
                throw new IllegalArgumentException("byteLength must not be negative");
            }
        }
    }

    private void validateStorageUri(String value) {
        if (characterLength(value, "storageUri") > MAX_STORAGE_URI_CHARS) {
            throw invalid("storageUri exceeds the schema character limit");
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!STORAGE_SCHEMES.contains(scheme) || uri.getUserInfo() != null) {
                throw invalid("storageUri scheme is not allowed");
            }
            if ("https".equals(scheme) && StringUtil.isBlank(uri.getHost())) {
                throw invalid("https storageUri requires a host");
            }
            if (!"https".equals(scheme) && StringUtil.isBlank(uri.getSchemeSpecificPart())) {
                throw invalid("storageUri is incomplete");
            }
        } catch (AgentTaskCollaborationException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw invalid("storageUri is invalid");
        }
    }

    private int requireArtifactVersions(AgentTaskArtifactPublishDTO command) {
        Integer expected = command.getExpectedPreviousVersion();
        Integer version = command.getArtifactVersion();
        if (expected == null || expected < 0 || expected == Integer.MAX_VALUE) {
            throw invalid("expectedPreviousVersion must be nonnegative and incrementable");
        }
        if (version == null || version != expected + 1) {
            throw invalid("artifactVersion must equal expectedPreviousVersion + 1");
        }
        return expected;
    }

    private int requirePersistedArtifactVersion(AgentTaskArtifactEntity latest) {
        Integer version = latest.getArtifactVersion();
        if (version == null || version < 1 || version == Integer.MAX_VALUE) {
            throw invalidPersisted("Persisted artifact version is invalid");
        }
        return version;
    }

    private boolean canReadArtifact(Access access, AgentTaskArtifactEntity entity) {
        return switch (entity.getVisibility()) {
            case "task_members" -> true;
            case "reviewer" -> access.coordinator() || "reviewer".equals(access.role())
                    || access.actorAgentId().equals(entity.getProducerAgentId());
            case "private" -> access.coordinator()
                    || access.actorAgentId().equals(entity.getProducerAgentId());
            default -> throw invalidPersisted("Persisted artifact visibility is invalid");
        };
    }

    private AgentTaskRequestDTO copyRequest(AgentTaskRequestEntity entity) {
        AgentTaskRequestDTO dto = new AgentTaskRequestDTO();
        dto.setRequestId(entity.getRequestId());
        dto.setTaskId(entity.getTaskId());
        dto.setWorkItemId(entity.getWorkItemId());
        dto.setRequesterAgentId(entity.getRequesterAgentId());
        dto.setTargetType(entity.getTargetType());
        dto.setTargetId(entity.getTargetId());
        dto.setRequestType(entity.getRequestType());
        dto.setStatus(entity.getStatus());
        dto.setPriority(entity.getPriority());
        dto.setTitle(entity.getTitle());
        dto.setDescription(entity.getDescription());
        dto.setResponseJson(entity.getResponseJson());
        dto.setDueAt(entity.getDueAt());
        dto.setAcknowledgedAt(entity.getAcknowledgedAt());
        dto.setResolvedAt(entity.getResolvedAt());
        dto.setVersion(entity.getVersion());
        return dto;
    }

    private AgentTaskRequestViewDTO requestView(AgentTaskRequestEntity entity) {
        requestStatus(entity.getStatus());
        AgentTaskRequestViewDTO dto = new AgentTaskRequestViewDTO();
        dto.setRequestId(entity.getRequestId());
        dto.setTaskId(entity.getTaskId());
        dto.setWorkItemId(entity.getWorkItemId());
        dto.setRequesterAgentId(entity.getRequesterAgentId());
        dto.setTargetType(entity.getTargetType());
        dto.setTargetId(entity.getTargetId());
        dto.setRequestType(entity.getRequestType());
        dto.setStatus(entity.getStatus());
        dto.setPriority(entity.getPriority());
        dto.setTitle(entity.getTitle());
        dto.setDescription(entity.getDescription());
        dto.setResponse(parseObject(entity.getResponseJson(), "response"));
        dto.setDueAt(entity.getDueAt());
        dto.setAcknowledgedAt(entity.getAcknowledgedAt());
        dto.setResolvedAt(entity.getResolvedAt());
        dto.setVersion(entity.getVersion());
        dto.setCreatedAt(entity.getCreateTime());
        dto.setUpdatedAt(entity.getUpdateTime());
        return dto;
    }

    private AgentTaskArtifactViewDTO artifactView(AgentTaskArtifactEntity entity) {
        requirePersistedArtifactVersion(entity);
        if (!VISIBILITIES.contains(entity.getVisibility())) {
            throw invalidPersisted("Persisted artifact visibility is invalid");
        }
        ArtifactMaterial material = artifactMaterial(entity);
        AgentTaskArtifactViewDTO dto = new AgentTaskArtifactViewDTO();
        dto.setArtifactId(entity.getArtifactId());
        dto.setTaskId(entity.getTaskId());
        dto.setWorkItemId(entity.getWorkItemId());
        dto.setProducerAgentId(entity.getProducerAgentId());
        dto.setArtifactType(entity.getArtifactType());
        dto.setTitle(entity.getTitle());
        dto.setContent(entity.getContent());
        dto.setStorageUri(entity.getStorageUri());
        dto.setContentHash(entity.getContentHash());
        dto.setContentByteLength(material.byteLength());
        dto.setContentMimeType(material.mimeType());
        dto.setManagedStorage(material.managedStorage());
        dto.setArtifactVersion(entity.getArtifactVersion());
        dto.setVisibility(entity.getVisibility());
        dto.setMetadata(material.userMetadata());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    private ArtifactMaterial artifactMaterial(AgentTaskArtifactEntity entity) {
        if (entity.getContentHash() == null || !SHA256.matcher(entity.getContentHash()).matches()) {
            throw invalidPersisted("Persisted artifact content hash is invalid");
        }
        Map<String, Object> persistedMetadata = parseArtifactMetadata(entity.getMetadataJson());
        boolean hasInline = entity.getContent() != null;
        boolean hasStorage = !StringUtil.isBlank(entity.getStorageUri());
        if (hasInline == hasStorage) {
            throw invalidPersisted("Persisted artifact content location is invalid");
        }
        if (hasInline) {
            if (entity.getContent().isEmpty()) {
                throw invalidPersisted("Persisted inline artifact metadata is invalid");
            }
            byte[] content = persistedUtf8Bytes(
                    entity.getContent(), "persisted artifact content");
            Map<String, Object> userMetadata = persistedMetadata.isEmpty()
                    ? null : new LinkedHashMap<>(persistedMetadata);
            if (content.length > MAX_INLINE_CONTENT_BYTES) {
                throw invalidPersisted("Persisted inline artifact exceeds the supported limit");
            }
            if (!entity.getContentHash().equals(sha256(content))) {
                throw invalidPersisted("Persisted inline artifact failed integrity verification");
            }
            return new ArtifactMaterial(content, (long) content.length,
                    INLINE_CONTENT_MIME, false, userMetadata);
        }
        String storageUri = entity.getStorageUri().trim();
        if (artifactStorage.owns(storageUri)) {
            Object internalStorage = persistedMetadata.remove(MANAGED_STORAGE_METADATA);
            Map<String, Object> userMetadata = persistedMetadata.isEmpty()
                    ? null : new LinkedHashMap<>(persistedMetadata);
            ManagedStorageMetadata managed = managedStorageMetadata(internalStorage, entity);
            AgentTaskArtifactStorage.Scope scope = new AgentTaskArtifactStorage.Scope(
                    entity.getTenantId(), entity.getClientId(), entity.getTaskId());
            if (!artifactStorage.matches(scope, storageUri, entity.getContentHash())) {
                throw invalidPersisted("Persisted managed artifact URI escaped its exact scope");
            }
            return new ArtifactMaterial(null, managed.byteLength(), managed.mimeType(),
                    true, userMetadata);
        }
        if (storageUri.startsWith(FileSystemAgentTaskArtifactStorage.URI_SCHEME + ":")) {
            throw invalidPersisted("Persisted managed artifact metadata is invalid");
        }
        try {
            validateStorageUri(storageUri);
        } catch (AgentTaskCollaborationException invalid) {
            throw invalidPersisted("Persisted external artifact URI is invalid");
        }
        Map<String, Object> userMetadata = persistedMetadata.isEmpty()
                ? null : new LinkedHashMap<>(persistedMetadata);
        return new ArtifactMaterial(null, null, null, false, userMetadata);
    }

    private Map<String, Object> parseArtifactMetadata(String metadataJson) {
        Map<String, Object> parsed = parseObject(metadataJson, "metadata");
        return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
    }

    private ManagedStorageMetadata managedStorageMetadata(
            Object rawMetadata, AgentTaskArtifactEntity entity) {
        if (!(rawMetadata instanceof Map<?, ?> metadata) || metadata.size() != 4
                || !metadata.keySet().equals(Set.of(
                        "schemaVersion", "sha256", "byteLength", "mimeType"))) {
            throw invalidPersisted("Persisted managed artifact metadata is invalid");
        }
        Object schemaVersion = metadata.get("schemaVersion");
        Object rawByteLength = metadata.get("byteLength");
        Object rawHash = metadata.get("sha256");
        Object rawMime = metadata.get("mimeType");
        if (!(schemaVersion instanceof Integer version) || version != 1
                || !(rawByteLength instanceof Integer || rawByteLength instanceof Long)
                || !(rawHash instanceof String hash) || !entity.getContentHash().equals(hash)
                || !(rawMime instanceof String mimeType) || mimeType.isEmpty()
                || mimeType.length() > 129 || !mimeType.equals(mimeType.trim())
                || mimeType.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidPersisted("Persisted managed artifact metadata is invalid");
        }
        long byteLength = ((Number) rawByteLength).longValue();
        if (byteLength < 0) {
            throw invalidPersisted("Persisted managed artifact byte length is invalid");
        }
        return new ManagedStorageMetadata(byteLength, mimeType);
    }

    private record ManagedStorageMetadata(long byteLength, String mimeType) {
    }

    private record ArtifactMaterial(byte[] inlineContent, Long byteLength, String mimeType,
            boolean managedStorage, Map<String, Object> userMetadata) {
        private ArtifactMaterial {
            inlineContent = inlineContent == null ? null : inlineContent.clone();
        }

        @Override
        public byte[] inlineContent() {
            return inlineContent == null ? null : inlineContent.clone();
        }
    }

    private AgentTaskRequestStatus requestStatus(String status) {
        try {
            return AgentTaskRequestStatus.fromPersistedValue(status);
        } catch (IllegalArgumentException e) {
            throw invalidPersisted("Persisted or requested request status is invalid");
        }
    }

    private long requireExpectedVersion(AgentTaskRequestTransitionDTO command) {
        if (command == null || command.getExpectedVersion() == null
                || command.getExpectedVersion() < 0 || command.getExpectedVersion() == Long.MAX_VALUE) {
            throw invalid("A nonnegative incrementable expectedVersion is required");
        }
        return command.getExpectedVersion();
    }

    private String serializeObject(Map<String, Object> value, String name, int maxBytes) {
        if (value == null) {
            return null;
        }
        String json = JsonUtil.toJson(value);
        if (json == null || utf8Bytes(json, name).length > maxBytes) {
            throw invalid(name + " is not valid bounded JSON");
        }
        return json;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObject(String value, String name) {
        if (StringUtil.isBlank(value)) {
            return null;
        }
        try {
            Object parsed = JsonUtil.getMapper().readValue(value, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw invalidPersisted("Persisted " + name + " is not a JSON object");
            }
            return (Map<String, Object>) map;
        } catch (AgentTaskCollaborationException e) {
            throw e;
        } catch (Exception e) {
            throw invalidPersisted("Persisted " + name + " is invalid JSON");
        }
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private boolean isDuplicateConflict(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DuplicateKeyException) {
                return true;
            }
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if ("23505".equals(state) || sql.getErrorCode() == 1062) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isPersistenceValidationFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException) {
                return true;
            }
            if (current instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("22")
                    || sql.getSQLState().startsWith("23"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private int boundedLimit(Integer requested) {
        return Math.max(1, Math.min(requested == null ? 100 : requested, MAX_LIST_LIMIT));
    }

    private void requireScope(String tenantId, String clientId, String taskId, String actorAgentId) {
        requireId(tenantId, "tenantId", 50);
        requireId(clientId, "clientId", 50);
        requireId(taskId, "taskId", 100);
        requireId(actorAgentId, "actorAgentId", 100);
    }

    private void requireId(String value, String name, int maxLength) {
        if (value == null || value.isEmpty()) {
            throw invalid(name + " is required");
        }
        int length = characterLength(value, name);
        if (length > maxLength) {
            throw invalid(name + " is too long");
        }
        if (value.codePoints().allMatch(AgentTaskCollaborationServiceImpl::isPadding)
                || isPadding(value.codePointAt(0))
                || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(name + " must be canonical and contain no surrounding whitespace");
        }
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private String requiredTrimmed(String value, String name, int maxLength) {
        if (StringUtil.isBlank(value)) {
            throw invalid(name + " is required");
        }
        String normalized = value.trim();
        if (characterLength(normalized, name) > maxLength) {
            throw invalid(name + " is too long");
        }
        return normalized;
    }

    private void requireText(String value, String name, int maxLength) {
        if (StringUtil.isBlank(value) || characterLength(value, name) > maxLength) {
            throw invalid(name + " is required and must be within its size limit");
        }
    }

    private void requireUtf8Text(String value, String name, int maxBytes) {
        if (StringUtil.isBlank(value) || utf8Bytes(value, name).length > maxBytes) {
            throw invalid(name + " is required and must be within its UTF-8 byte limit");
        }
    }

    private int characterLength(String value, String name) {
        utf8Bytes(value, name);
        return value.codePointCount(0, value.length());
    }

    private byte[] utf8Bytes(String value, String name) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalid(name + " contains invalid Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw invalid(name + " contains invalid Unicode");
            }
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] persistedUtf8Bytes(String value, String name) {
        try {
            return utf8Bytes(value, name);
        } catch (AgentTaskCollaborationException invalid) {
            throw invalidPersisted("Persisted " + name + " contains invalid Unicode");
        }
    }

    private String canonical(String value, String name) {
        return requiredTrimmed(value, name, 30).toLowerCase(Locale.ROOT);
    }

    private String canonicalDefault(String value, String fallback) {
        return StringUtil.isBlank(value) ? fallback : value.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return StringUtil.isBlank(value) ? null : value.trim();
    }

    private long now() {
        long value = clock.getAsLong();
        if (value < 0) {
            throw invalidPersisted("Clock returned a negative timestamp");
        }
        return value;
    }

    private void requireSingleInsert(int inserted) {
        if (inserted != 1) {
            throw invalidPersisted("Scoped insert affected an unexpected row count");
        }
    }

    private void requireSingleCas(int updated) {
        if (updated == 0) {
            throw conflict("Request changed concurrently", null);
        }
        if (updated != 1) {
            throw invalidPersisted("Scoped CAS affected an unexpected row count");
        }
    }

    private static AgentTaskMutationTransaction directTransaction(AgentTaskMetaDao taskMetaDao) {
        return new AgentTaskMutationTransaction() {
            @Override
            public <T> T executeWithLockedTaskRoot(String tenantId, String clientId, String taskId,
                    LockedTaskMutation<T> mutation) {
                return mutation.apply(taskMetaDao.findByTaskIdForUpdate(tenantId, clientId, taskId));
            }
            @Override
            public <T> T executeWithLockedTaskRootForWorkItem(String tenantId, String clientId,
                    String workItemId, LockedTaskMutation<T> mutation) {
                throw new UnsupportedOperationException();
            }
            @Override
            public <T> T executeAfterTaskRootReservation(String tenantId, String clientId,
                    String taskId, TaskRootReservation reservation, ReservedTaskMutation<T> mutation) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private AgentTaskCollaborationException invalid(String message) {
        return new AgentTaskCollaborationException(Reason.INVALID_REQUEST, message);
    }

    private AgentTaskCollaborationException invalidPersisted(String message) {
        return new AgentTaskCollaborationException(Reason.INVALID_PERSISTED_STATE, message);
    }

    private AgentTaskCollaborationException notFound() {
        return new AgentTaskCollaborationException(
                Reason.NOT_FOUND, "Resource was not found in the requested scope");
    }

    private AgentTaskCollaborationException forbidden() {
        return new AgentTaskCollaborationException(
                Reason.FORBIDDEN, "Operation is not permitted in the requested scope");
    }

    private AgentTaskCollaborationException conflict(String message, Throwable cause) {
        return cause == null
                ? new AgentTaskCollaborationException(Reason.VERSION_CONFLICT, message)
                : new AgentTaskCollaborationException(Reason.VERSION_CONFLICT, message, cause);
    }

    private record Access(String actorAgentId, String role, boolean coordinator) {
    }
}
