package cn.jia.chat.archive.maintenance.service;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.exception.AgentTaskArtifactStorageException;
import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.ArchiveAgentExecutionPort;
import cn.jia.agent.service.InstalledSkillResolver;
import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.config.ArchiveReaderAccessPolicy;
import cn.jia.chat.archive.dto.*;
import cn.jia.chat.archive.maintenance.dto.*;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceProperties;
import cn.jia.chat.archive.maintenance.model.*;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.model.*;
import cn.jia.chat.archive.service.ArchiveReaderService;
import cn.jia.chat.archive.service.ArchiveReaderServiceImpl;
import cn.jia.chat.archive.service.ArchiveRepresentation;
import cn.jia.chat.archive.service.ArchiveTransactions;
import cn.jia.chat.archive.store.ArchiveContentStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class ArchiveMaintenanceServiceImpl implements ArchiveMaintenanceService {
    private static final String ROLE = "ARCHIVE_EDITOR";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Set<String> FAILURE_PHASES = Set.of("START", "CONTEXT", "SOURCE_READ",
            "DRAFT_READ", "DRAFT_WRITE", "VALIDATION", "RESULT_RECONCILIATION", "RUNNER");
    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    private final AgentTaskArtifactStorage sourceStorage;
    private final ArchiveMaintenanceStore store;
    private final ArchiveContentStore content;
    private final ArchiveReaderService reader;
    private final ArchiveTransactions transactions;
    private final AgentIdentityService identities;
    private final ObjectMapper mapper;
    private final Clock clock;
    private InstalledSkillResolver installedSkillResolver;
    private ArchiveAgentExecutionPort executionPort;
    private ArchiveReaderAccessPolicy readerAccessPolicy;
    private boolean executionEnabled;

    @Autowired
    public ArchiveMaintenanceServiceImpl(ArchiveMaintenanceStore store, ArchiveContentStore content,
            ArchiveTransactions transactions, AgentIdentityService identities, ObjectMapper mapper,
            AgentTaskArtifactStorage sourceStorage) {
        this(store, content, transactions, identities, mapper, sourceStorage, Clock.systemUTC());
    }

    ArchiveMaintenanceServiceImpl(ArchiveMaintenanceStore store, ArchiveContentStore content,
            ArchiveTransactions transactions, AgentIdentityService identities,
            ObjectMapper mapper, AgentTaskArtifactStorage sourceStorage, Clock clock) {
        this.sourceStorage = Objects.requireNonNull(sourceStorage);
        this.store = Objects.requireNonNull(store);
        this.content = Objects.requireNonNull(content);
        this.reader = new ArchiveReaderServiceImpl(content);
        this.transactions = Objects.requireNonNull(transactions);
        this.identities = Objects.requireNonNull(identities);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Autowired(required = false)
    void setInstalledSkillResolver(InstalledSkillResolver installedSkillResolver) {
        this.installedSkillResolver = Objects.requireNonNull(installedSkillResolver);
    }

    @Autowired(required = false)
    void setArchiveAgentExecutionPort(ArchiveAgentExecutionPort executionPort) {
        this.executionPort = Objects.requireNonNull(executionPort);
    }

    @Autowired(required = false)
    void setArchiveMaintenanceProperties(ArchiveMaintenanceProperties properties) {
        this.executionEnabled = Objects.requireNonNull(properties).isExecutionEnabled();
    }

    @Autowired(required = false)
    void setArchiveReaderAccessPolicy(ArchiveReaderAccessPolicy readerAccessPolicy) {
        this.readerAccessPolicy = Objects.requireNonNull(readerAccessPolicy);
    }

    @Override
    public ArchiveOperationAcceptedDTO prepareSource(ArchiveActorScope actor, String collectionId,
            String key, ArchiveSourcePrepareRequest request) {
        requireManager(actor, collectionId, "source.prepare", false);
        requireKey(key);
        if (request == null || !exact(request.sourceName(), 255) || !exact(request.sourceVersion(), 128)
                || !exact(request.rightsBasis(), 1000)
                || !SHA.matcher(String.valueOf(request.declaredSha256())).matches()
                || request.contentBase64() == null || request.contentBase64().length() > MAX_SOURCE_BYTES * 2) {
            invalid("An exact UTF-8 source and rights declaration are required");
        }
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(request.contentBase64()); }
        catch (IllegalArgumentException failure) { throw error(400, "INVALID_REQUEST", "Source bytes are not base64"); }
        if (bytes.length == 0 || bytes.length > MAX_SOURCE_BYTES
                || !Base64.getEncoder().encodeToString(bytes).equals(request.contentBase64())
                || !digestBytes(bytes).equals(request.declaredSha256())) {
            invalid("Source bytes and declared digest do not match");
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException failure) {
            invalid("Source must be exact UTF-8 text");
        }
        String requestSha = digest(request.sourceName() + "\0" + request.sourceVersion() + "\0"
                + request.rightsBasis() + "\0" + request.declaredSha256());
        String path = "/archive/admin/v1/collections/" + collectionId + "/source-snapshots";
        // Reserve the source identity before external storage I/O. A crash or lost response leaves
        // a durable PENDING operation whose exact key/content can be retried without a new object.
        ArchiveMaintenanceStore.Operation reserved = transactions.required(() -> {
            requireManager(actor, collectionId, "source.prepare", true);
            ArchiveMaintenanceStore.Operation op = store.beginOperation(actor, key, "POST", path,
                    requestSha, "SOURCE", newId("src"));
            assertOperationMatches(op, "POST", path, requestSha, "SOURCE");
            return op;
        });
        if ("COMMITTED".equals(reserved.state())) {
            requireSourceSnapshot(reserved.targetId(), actor, collectionId);
            return new ArchiveOperationAcceptedDTO(reserved.targetId(), null, "COMMITTED");
        }
        String sourceId = reserved.targetId();
        AgentTaskArtifactStorage.Scope storageScope = new AgentTaskArtifactStorage.Scope(
                actor.tenantId(), actor.clientId(), actor.ownerJiacn(), sourceId);
        AgentTaskArtifactStorage.StoredObject object;
        try {
            object = sourceStorage.store(storageScope, bytes, "text/plain");
        } catch (AgentTaskArtifactStorageException failure) {
            throw error(503, "DEPENDENCY_UNAVAILABLE", "Private source storage is unavailable");
        }
        if (!request.declaredSha256().equals(object.sha256()) || object.byteLength() != bytes.length
                || !sourceStorage.matches(storageScope, object.storageUri(), object.sha256())) {
            throw error(503, "DEPENDENCY_UNAVAILABLE", "Private source storage proof did not match");
        }
        ArchiveSourceSnapshotRecord prepared = new ArchiveSourceSnapshotRecord(sourceId,
                collectionId, actor.tenantId(), actor.clientId(), actor.ownerJiacn(), object.storageUri(),
                object.sha256(), object.byteLength(), request.sourceName(), request.sourceVersion(),
                request.rightsBasis(), "UTF8_EXACT_V1", "READY");
        return transactions.required(() -> {
            requireManager(actor, collectionId, "source.prepare", true);
            ArchiveMaintenanceStore.Operation op = store.beginOperation(actor, key, "POST", path,
                    requestSha, "SOURCE", sourceId);
            assertOperationMatches(op, "POST", path, requestSha, "SOURCE");
            if ("COMMITTED".equals(op.state())) {
                requireSourceSnapshot(op.targetId(), actor, collectionId);
                return new ArchiveOperationAcceptedDTO(op.targetId(), null, "COMMITTED");
            }
            if (!sourceId.equals(op.targetId())) {
                conflict("IDEMPOTENCY_CONFLICT", "Source reservation changed");
            }
            store.insertSource(prepared);
            store.commitOperation(actor, key, sourceId);
            return new ArchiveOperationAcceptedDTO(sourceId, null, "COMMITTED");
        });
    }

    @Override
    public ArchiveSourceSnapshotDTO source(ArchiveActorScope actor, String sourceId) {
        exactId(sourceId);
        ArchiveSourceSnapshotRecord found = store.findSource(sourceId);
        if (found == null || !same(found.tenantId(), actor.tenantId())
                || !same(found.clientId(), actor.clientId())
                || !same(found.ownerJiacn(), actor.ownerJiacn())) notFound();
        requireManager(actor, found.collectionId(), "source.prepare", false);
        return sourceDto(requireSourceSnapshot(sourceId, actor, found.collectionId()));
    }

    @Override
    public byte[] runtimeSourceContent(ArchiveRuntimeScope runtime, String jobId,
            String runId, String sourceId) {
        ArchiveMaintenanceJobRecord job = authorizeRuntime(runtime, jobId, runId);
        if (!sourceId.equals(job.sourceId())) notFound();
        ArchiveSourceSnapshotRecord source = requireSourceSnapshot(sourceId, actor(runtime), job.collectionId());
        return readSource(source);
    }
    @Override
    public ArchiveCapabilitiesDTO capabilities(ArchiveActorScope actor, String collectionId) {
        requireScope(actor);
        exactId(collectionId);
        ArchiveManagerGrantRecord grant = store.findManagerGrant(actor, collectionId, false);
        ArchiveAppointmentRecord appointment = store.findCurrentAppointment(collectionId, false);
        if (grant == null || !"ACTIVE".equals(grant.state())) {
            return new ArchiveCapabilitiesDTO(collectionId, List.of(),
                    appointment == null ? "VACANT" : "OCCUPIED",
                    appointment == null ? "WAITING_ASSIGNEE" : "UNAVAILABLE");
        }
        String readiness = appointment == null ? "WAITING_ASSIGNEE"
                : appointmentReadiness(actor, appointment, appointment).readiness();
        return new ArchiveCapabilitiesDTO(collectionId, permissions(grant.permissions()),
                appointment == null ? "VACANT" : "OCCUPIED", readiness);
    }

    @Override
    public ArchiveSlotDTO slot(ArchiveActorScope actor, String collectionId) {
        requireManager(actor, collectionId, "appoint", false);
        ArchiveMaintenanceStore.Slot slot = store.findSlot(collectionId, ROLE);
        String visibleAppointmentId = null;
        if (slot != null && slot.currentAppointmentId() != null) {
            ArchiveAppointmentRecord assigned = requireAppointment(slot.currentAppointmentId(), false);
            if (same(actor.tenantId(), assigned.tenantId())
                    && same(actor.clientId(), assigned.clientId())
                    && same(actor.ownerJiacn(), assigned.ownerJiacn())) {
                visibleAppointmentId = assigned.appointmentId();
            }
        }
        return new ArchiveSlotDTO(collectionId, Long.toString(slot == null ? 0 : slot.revision()),
                visibleAppointmentId);
    }

    @Override
    public ArchiveWorkStateDTO workState(ArchiveActorScope actor, String collectionId, String workId) {
        requireWorkVersionReader(actor, collectionId);
        exactId(workId);
        ArchiveMaintenanceStore.CollectionWork cw = store.findCollectionWork(collectionId, workId);
        ArchiveWorkRecord work = content.findWork(workId);
        if (cw == null || work == null) notFound();
        return new ArchiveWorkStateDTO(workId, Long.toString(cw.revision()), work.activeEditionId());
    }

    @Override
    public ArchiveEditionHistoryDTO editionHistory(ArchiveActorScope actor, String workId) {
        exactId(workId);
        List<ArchiveEditionVersionRecord> observed = store.listPublications(workId, false);
        if (observed.isEmpty()) notFound();
        String collectionId = exactVersionCollection(observed, workId);
        requireVersionReader(actor, collectionId, false);
        EditionHistorySnapshot snapshot = transactions.required(() ->
                lockedEditionHistorySnapshot(actor, collectionId, workId));
        for (ArchiveEditionVersionRecord version : snapshot.versions()) {
            verifyPublication(version.publicationId());
        }
        return transactions.required(() -> {
            EditionHistorySnapshot current = lockedEditionHistorySnapshot(actor, collectionId, workId);
            return new ArchiveEditionHistoryDTO(workId, Long.toString(current.workRevision()),
                    current.activeEditionId(), current.versions().stream().map(this::versionDto).toList());
        });
    }

    @Override
    public ArchiveEditionVersionDTO edition(ArchiveActorScope actor, String workId, String editionId) {
        exactId(workId);
        exactId(editionId);
        ArchiveEditionVersionRecord version = store.findPublication(workId, editionId, false);
        if (version == null || !same(workId, version.workId())) notFound();
        String collectionId = version.collectionId();
        requireVersionReader(actor, collectionId);
        verifyPublication(version.publicationId());
        return transactions.required(() -> {
            requireVersionReader(actor, collectionId, true);
            ArchiveMaintenanceStore.CollectionWork collectionWork = store.lockCollectionWork(
                    collectionId, workId);
            ArchiveWorkRecord work = content.lockWork(workId);
            ArchiveEditionVersionRecord current = store.findPublication(workId, editionId, true);
            if (collectionWork == null || work == null || current == null
                    || !same(workId, current.workId()) || !same(collectionId, current.collectionId())) {
                notFound();
            }
            return versionDto(current);
        });
    }

    private EditionHistorySnapshot lockedEditionHistorySnapshot(ArchiveActorScope actor,
            String collectionId, String workId) {
        requireVersionReader(actor, collectionId, true);
        ArchiveMaintenanceStore.CollectionWork collectionWork = store.lockCollectionWork(
                collectionId, workId);
        ArchiveWorkRecord work = content.lockWork(workId);
        List<ArchiveEditionVersionRecord> versions = store.listPublications(workId, true);
        if (collectionWork == null || work == null || versions.isEmpty()) notFound();
        if (!same(collectionId, exactVersionCollection(versions, workId))) notFound();
        return new EditionHistorySnapshot(collectionWork.revision(), work.activeEditionId(),
                List.copyOf(versions));
    }

    @Override
    public ArchiveWithdrawalDTO withdraw(ArchiveActorScope actor, String workId, String editionId,
            String key, long expectedWorkRevision, ArchiveWithdrawRequest request) {
        requireScope(actor);
        exactId(workId);
        exactId(editionId);
        requireKey(key);
        if (request == null || !exact(request.reason(), 1000)) {
            invalid("Withdrawal reason is required");
        }
        String replacement = request.replacementActiveEditionId();
        if (replacement != null) {
            exactId(replacement);
            if (same(replacement, editionId)) invalid("Replacement edition must differ from the withdrawn edition");
        }
        ArchiveEditionVersionRecord observed = store.findPublication(workId, editionId, false);
        if (observed == null || !same(workId, observed.workId())) notFound();
        requireWithdrawManager(actor, observed.collectionId(), false);
        String path = "/archive/admin/v1/works/" + workId + "/editions/" + editionId + "/withdraw";
        String requestSha = digest(expectedWorkRevision + "\0" + request.reason() + "\0"
                + String.valueOf(replacement));
        return transactions.required(() -> {
            ArchiveManagerGrantRecord manager = requireWithdrawManager(actor, observed.collectionId(), true);
            ArchiveMaintenanceJobRecord publicationJob = observed.jobId() == null ? null
                    : store.findJob(observed.jobId(), true);
            if (observed.jobId() != null && (publicationJob == null
                    || !same(publicationJob.jobId(), observed.jobId())
                    || !same(publicationJob.collectionId(), observed.collectionId())
                    || !same(publicationJob.workId(), workId)
                    || !same(publicationJob.publicationId(), observed.publicationId()))) {
                conflict("ARCHIVE_EDITION_CHANGED", "Archive publication job binding changed");
            }
            ArchiveMaintenanceStore.Operation op = operation(actor, key, "POST", path, requestSha,
                    "EDITION_WITHDRAWAL", newId("awd"));
            if (!op.created()) {
                ArchiveWithdrawalRecord replay = store.findWithdrawal(op.targetId());
                if (replay == null || !same(replay.workId(), workId)
                        || !same(replay.editionId(), editionId)) {
                    conflict("ARCHIVE_WITHDRAWAL_CONFLICT", "Withdrawal receipt is unavailable");
                }
                return withdrawalDto(replay);
            }
            ArchiveMaintenanceStore.CollectionWork collectionWork = store.lockCollectionWork(
                    observed.collectionId(), workId);
            ArchiveWorkRecord work = content.lockWork(workId);
            if (collectionWork == null || work == null) notFound();
            if (collectionWork.revision() != expectedWorkRevision) {
                revisionConflict(collectionWork.revision());
            }
            List<ArchiveEditionVersionRecord> locked = store.listPublications(workId, true);
            ArchiveEditionVersionRecord target = locked.stream()
                    .filter(value -> same(value.editionId(), editionId)).findFirst().orElse(null);
            if (target == null || !same(target.collectionId(), observed.collectionId())) notFound();
            if (!same(target.jobId(), observed.jobId())
                    || (publicationJob != null && (!same(publicationJob.publicationId(), target.publicationId())
                            || !same(publicationJob.collectionId(), target.collectionId())
                            || !same(publicationJob.workId(), target.workId())))) {
                conflict("ARCHIVE_EDITION_CHANGED", "Archive publication job binding changed");
            }
            if (!"PUBLISHED".equals(target.state()) || target.withdrawal() != null) {
                conflict("ARCHIVE_EDITION_NOT_PUBLISHED", "Archive edition is not published");
            }
            ArchiveEditionVersionRecord replacementPublication = null;
            if (replacement != null) {
                String replacementId = replacement;
                replacementPublication = locked.stream()
                        .filter(value -> same(value.editionId(), replacementId)).findFirst().orElse(null);
                if (replacementPublication == null || !same(replacementPublication.collectionId(), target.collectionId())
                        || !"PUBLISHED".equals(replacementPublication.state())) {
                    conflict("ARCHIVE_REPLACEMENT_NOT_PUBLISHED", "Replacement edition is not published for this work");
                }
            }
            List<String> editionLocks = new ArrayList<>();
            editionLocks.add(editionId);
            if (replacement != null) editionLocks.add(replacement);
            editionLocks.stream().distinct().sorted().forEach(id -> {
                ArchiveEditionRecord edition = content.lockEdition(id);
                if (edition == null || !same(edition.workId(), workId) || !"READY".equals(edition.importState())) {
                    conflict("ARCHIVE_EDITION_CHANGED", "Archive edition changed during withdrawal");
                }
            });
            boolean active = same(work.activeEditionId(), editionId);
            String resultingActive = active ? replacement : work.activeEditionId();
            if (store.withdrawPublication(target.publicationId()) != 1) {
                conflict("ARCHIVE_EDITION_CHANGED", "Archive edition changed during withdrawal");
            }
            if (active && content.switchActiveEdition(workId, editionId, replacement) != 1) {
                conflict("ACTIVE_EDITION_CHANGED", "Active archive edition changed");
            }
            if (store.bumpCollectionWork(target.collectionId(), workId, collectionWork.revision()) != 1) {
                conflict("ACTIVE_EDITION_CHANGED", "Archive work revision changed");
            }
            ArchiveWithdrawalRecord withdrawal = new ArchiveWithdrawalRecord(op.targetId(),
                    target.publicationId(), target.collectionId(), workId, editionId, request.reason(),
                    actor.tenantId(), actor.clientId(), actor.ownerJiacn(), "HUMAN", actor.ownerJiacn(),
                    manager.revision(), replacement, resultingActive, collectionWork.revision() + 1,
                    key, clock.instant(), "PENDING");
            store.insertWithdrawal(withdrawal);
            ArchiveWithdrawalRecord persisted = store.findWithdrawalByPublication(
                    target.publicationId(), true);
            if (persisted == null
                    || !same(persisted.withdrawalId(), withdrawal.withdrawalId())
                    || !same(persisted.publicationId(), target.publicationId())
                    || !same(persisted.collectionId(), target.collectionId())
                    || !same(persisted.workId(), workId)
                    || !same(persisted.editionId(), editionId)
                    || !same(persisted.operationKey(), key)) {
                throw new IllegalStateException("Persisted archive withdrawal changed");
            }
            store.commitOperation(actor, key, persisted.withdrawalId());
            return withdrawalDto(persisted);
        });
    }

    @Override
    public List<ArchiveAppointmentDTO> appointments(ArchiveActorScope actor, String collectionId) {
        requireManager(actor, collectionId, "appoint", false);
        ArchiveAppointmentRecord current = store.findCurrentAppointment(collectionId, false);
        return store.listAppointments(actor, collectionId).stream()
                .map(value -> appointmentDto(actor, value, current)).toList();
    }

    @Override
    public ArchiveAppointmentDTO createAppointment(ArchiveActorScope actor, String collectionId,
            String key, long expectedSlotRevision, ArchiveAppointmentCreateRequest request) {
        requireScope(actor);
        exactId(collectionId);
        requireKey(key);
        validateAppointmentRequest(request);
        long binding = parsePositive(request.expectedBindingVersion(), "expectedBindingVersion");
        AgentIdentityRegistryEntity identity = identities.requireActiveIdentityForBinding(
                actor.tenantId(), actor.clientId(), actor.ownerJiacn(), binding, request.agentId());
        String canonical = identity.getCanonicalAgentId();
        String requestSha = sha(request);
        ArchiveAppointmentRecord result = transactions.required(() -> {
            identities.lockActiveCanonicalAgentIdsInScope(actor.tenantId(), actor.clientId(),
                    actor.ownerJiacn(), List.of(canonical));
            identities.requireActiveIdentityForBinding(actor.tenantId(), actor.clientId(),
                    actor.ownerJiacn(), binding, canonical);
            requireManager(actor, collectionId, "appoint", true);
            String appointmentId = newId("apt");
            ArchiveMaintenanceStore.Operation operation = operation(actor, key, "POST",
                    "/archive/admin/v1/collections/" + collectionId + "/appointments",
                    requestSha, "APPOINTMENT", appointmentId);
            if (!operation.created()) {
                return requireAppointmentForActor(actor, operation.targetId(), false);
            }
            store.ensureSlot(collectionId, ROLE);
            ArchiveMaintenanceStore.Slot slot = store.lockSlot(collectionId, ROLE);
            if (slot == null || slot.revision() != expectedSlotRevision) {
                revisionConflict(slot == null ? 0 : slot.revision());
            }
            if (slot.currentAppointmentId() != null) {
                conflict("ARCHIVE_APPOINTMENT_CONFLICT", "Archive editor slot is already occupied");
            }
            ArchiveSkillRef skill = request.requiredSkill();
            ArchiveAppointmentRecord created = new ArchiveAppointmentRecord(appointmentId,
                    collectionId, ROLE, actor.tenantId(), actor.clientId(), actor.ownerJiacn(),
                    canonical, request.expectedBindingVersion(), request.workScopeMode(),
                    String.join(",", request.workIds()), request.permissionProfile(), skill.key(),
                    skill.version(), skill.packageSha256(), "ACTIVE", 1, clock.instant(), null);
            store.insertAppointment(created);
            if (store.activateSlot(collectionId, ROLE, expectedSlotRevision, appointmentId) != 1) {
                conflict("ARCHIVE_APPOINTMENT_CONFLICT", "Archive editor slot changed");
            }
            store.commitOperation(actor, key, appointmentId);
            return created;
        });
        return appointmentDto(actor, result, currentAppointmentFor(result));
    }

    @Override
    public ArchiveAppointmentDTO revokeAppointment(ArchiveActorScope actor, String appointmentId,
            String key, long expectedRevision, ArchiveAppointmentRevokeRequest request) {
        requireScope(actor);
        exactId(appointmentId);
        requireKey(key);
        String requestSha = sha(request == null ? new ArchiveAppointmentRevokeRequest(null) : request);
        ArchiveAppointmentRecord observed = requireAppointmentForActor(actor, appointmentId, false);
        ArchiveAppointmentRecord result = transactions.required(() -> {
            if (executionEnabled) {
                requireExecutionPort().lockIdentityRoot(target(observed));
            }
            requireManager(actor, observed.collectionId(), "appoint", true);
            ArchiveMaintenanceStore.Operation operation = operation(actor, key, "POST",
                    "/archive/admin/v1/appointments/" + appointmentId + "/revoke",
                    requestSha, "APPOINTMENT", appointmentId);
            if (!operation.created()) {
                return requireAppointmentForActor(actor, operation.targetId(), false);
            }
            ArchiveMaintenanceStore.Slot slot = store.lockSlot(observed.collectionId(), ROLE);
            ArchiveAppointmentRecord current = requireAppointmentForActor(actor, appointmentId, true);
            if (current.revision() != expectedRevision) revisionConflict(current.revision());
            if (!"ACTIVE".equals(current.status()) || slot == null
                    || !appointmentId.equals(slot.currentAppointmentId())) {
                conflict("ARCHIVE_APPOINTMENT_CHANGED", "Archive appointment is no longer active");
            }
            // Current locking reads avoid a REPEATABLE READ snapshot omission. Runs are locked
            // in deterministic order before fenceRun obtains each corresponding grant lock.
            for (String runId : store.lockRunIdsForAppointment(appointmentId)) {
                if (store.fenceRun(runId) != 1) {
                    conflict("ARCHIVE_EXECUTION_CHANGED", "Archive execution changed during revocation");
                }
            }
            if (store.revokeAppointment(appointmentId, expectedRevision) != 1
                    || store.clearSlot(current.collectionId(), ROLE, appointmentId, slot.revision()) != 1) {
                conflict("ARCHIVE_APPOINTMENT_CHANGED", "Archive appointment changed");
            }
            store.commitOperation(actor, key, appointmentId);
            return requireAppointmentForActor(actor, appointmentId, false);
        });
        return appointmentDto(actor, result, currentAppointmentFor(result));
    }

    @Override
    public ArchiveOperationDTO revokeManagerAuthorization(ArchiveActorScope actor, String collectionId,
            String key, long expectedRevision, ArchiveManagerRevokeRequest request) {
        requireScope(actor);
        exactId(collectionId);
        requireKey(key);
        if (request == null || !exact(request.reason(), 500)) invalid("Revocation reason is required");
        String path = "/archive/admin/v1/collections/" + collectionId + "/manager-authorization/revoke";
        String requestSha = digest(expectedRevision + ":" + sha(request));
        ArchiveMaintenanceStore.Operation replay = store.findOperation(actor, key);
        if (replay != null && "COMMITTED".equals(replay.state())) {
            assertOperationMatches(replay, "POST", path, requestSha, "MANAGER_AUTHORIZATION");
            return new ArchiveOperationDTO(key, replay.state(), replay.targetType(), replay.targetId());
        }
        // Exact actor authorization is checked before any collection-wide appointment/run read.
        ArchiveManagerGrantRecord preflight = requireManager(actor, collectionId, "appoint", false);
        if (preflight.revision() != expectedRevision) revisionConflict(preflight.revision());
        List<ArchiveMaintenanceStore.ManagerRunTarget> observedRuns =
                store.listUnfencedRunTargetsForManager(actor, collectionId, false);
        List<ArchiveAgentExecutionPort.TargetRequest> observedTargets = managerTargets(actor, observedRuns);
        return transactions.required(() -> {
            if (executionEnabled) {
                ArchiveAgentExecutionPort port = requireExecutionPort();
                for (ArchiveAgentExecutionPort.TargetRequest target : observedTargets) {
                    port.lockIdentityRoot(target);
                }
            }
            ArchiveManagerGrantRecord manager = requireManager(actor, collectionId, "appoint", true);
            if (manager.revision() != expectedRevision) revisionConflict(manager.revision());
            ArchiveMaintenanceStore.Operation operation = operation(actor, key, "POST", path,
                    requestSha, "MANAGER_AUTHORIZATION", collectionId);
            if (!operation.created()) {
                return new ArchiveOperationDTO(key, operation.state(), operation.targetType(), operation.targetId());
            }
            List<ArchiveMaintenanceStore.ManagerRunTarget> currentRuns =
                    store.listUnfencedRunTargetsForManager(actor, collectionId, true);
            Set<ArchiveAgentExecutionPort.TargetRequest> lockedTargets = new HashSet<>(observedTargets);
            if (executionEnabled && currentRuns.stream().map(run -> managerTarget(actor, run))
                    .anyMatch(target -> !lockedTargets.contains(target))) {
                conflict("ARCHIVE_EXECUTION_CHANGED", "Archive execution targets changed during manager revocation");
            }
            for (ArchiveMaintenanceStore.ManagerRunTarget run : currentRuns) {
                if (store.fenceRun(run.runId()) != 1) {
                    conflict("ARCHIVE_EXECUTION_CHANGED", "Archive execution changed during manager revocation");
                }
            }
            if (store.revokeManagerGrant(actor, collectionId, expectedRevision) != 1) {
                conflict("ARCHIVE_MANAGER_AUTHORIZATION_CHANGED", "Archive manager authorization changed");
            }
            store.commitOperation(actor, key, collectionId);
            return new ArchiveOperationDTO(key, "COMMITTED", "MANAGER_AUTHORIZATION", collectionId);
        });
    }

    @Override
    public ArchiveJobDTO createJob(ArchiveActorScope actor, String collectionId,
            String key, ArchiveJobCreateRequest request) {
        return createJobAuthorized(actor, collectionId, key, request, null);
    }

    private ArchiveJobDTO createJobAuthorized(ArchiveActorScope actor, String collectionId,
            String key, ArchiveJobCreateRequest request, String expectedTargetAgentId) {
        requireScope(actor);
        exactId(collectionId);
        requireKey(key);
        validateJobRequest(request);
        String requestSha = sha(request);
        return transactions.required(() -> {
            ArchiveManagerGrantRecord manager = requireManager(actor, collectionId, "job.create", true);
            // Every entry takes manager -> slot/appointment -> intent-job in the same order.
            ArchiveAppointmentRecord appointment = currentAppointment(collectionId, true);
            if (appointment != null && expectedTargetAgentId != null) {
                requireSameOwner(actor, appointment);
                requireCurrentBinding(appointment);
                requireTargetAppointment(expectedTargetAgentId, appointment);
            }
            ArchiveMaintenanceJobRecord prior = store.findJobByIntent(actor, request.requestIntentId(), true);
            if (prior != null) {
                if (!prior.requestSha256().equals(requestSha)) {
                    conflict("IDEMPOTENCY_CONFLICT", "Request intent was already used with different content");
                }
                return jobDto(bindOrVerifyDirectTarget(actor, prior, expectedTargetAgentId));
            }
            String jobId = newId("aj");
            ArchiveMaintenanceStore.Operation operation = operation(actor, key, "POST",
                    "/archive/admin/v1/collections/" + collectionId + "/jobs",
                    requestSha, "JOB", jobId);
            if (!operation.created()) return jobDto(requireJobForActor(actor, operation.targetId(), false));

            ResolvedWork work = resolveRequestedWork(collectionId, request.operation(),
                    request.newWork(), request.workId());
            ArchiveSourceSnapshotRecord source = request.sourceId() == null ? null
                    : requireSourceSnapshot(request.sourceId(), actor, collectionId);
            boolean inputsComplete = work != null && source != null;
            if (inputsComplete && appointment != null) {
                if (expectedTargetAgentId == null) {
                    requireSameOwner(actor, appointment);
                    requireCurrentBinding(appointment);
                }
                authorizeWorkScope(appointment, work.workId(), "ADD_WORK".equals(request.operation()));
            }

            String state = inputsComplete
                    ? (appointment == null ? "WAITING_ASSIGNEE" : "WAITING_SKILL")
                    : "WAITING_INPUT";
            String waitReason = waitReason(work == null, source == null,
                    appointment == null);
            boolean ready = "WAITING_SKILL".equals(state);
            String runId = ready ? newId("ar") : null;
            String draftId = ready ? newId("ad") : null;
            ArchiveMaintenanceJobRecord job = new ArchiveMaintenanceJobRecord(jobId, runId,
                    collectionId, actor.tenantId(), actor.clientId(), actor.ownerJiacn(),
                    ready ? appointment.appointmentId() : null,
                    ready ? appointment.revision() : null,
                    ready ? appointment.agentId() : null,
                    ready ? appointment.bindingVersion() : null,
                    ready ? appointment.permissionProfile() : null,
                    manager.revision(), request.publicationMode(), request.operation(),
                    work == null ? null : work.workId(),
                    work == null ? null : work.canonicalKey(),
                    work == null ? null : work.title(),
                    source == null ? null : source.sourceId(),
                    source == null ? null : source.rawSha256(),
                    source == null ? null : source.sourceName() + " / " + source.sourceVersion(),
                    source == null ? null : source.rightsBasis(), state, waitReason, 1,
                    draftId, null, request.requestIntentId(), requestSha, expectedTargetAgentId);
            store.insertJob(job);
            if (ready) createInitialCandidate(job);
            store.appendJobEvent(jobId, 1, "JOB_CREATED", jobStateEvent(job));
            store.commitOperation(actor, key, jobId);
            return jobDto(job);
        });
    }

    @Override
    public ArchiveMaintenanceRequestResultDTO confirmRequest(ArchiveActorScope actor,
            String collectionId, String key, ArchiveMaintenanceRequest request) {
        requireScope(actor);
        exactId(collectionId);
        requireKey(key);
        validateConfirmedRequest(collectionId, request);
        String requestJson = json(request);
        String requestSha = digest(requestJson);
        String path = "/archive/admin/v1/collections/" + collectionId + "/requests";
        ArchiveConfirmedRequestRecord confirmation = transactions.required(() -> {
            requireManager(actor, collectionId, "job.create", true);
            ArchiveMaintenanceStore.Operation operation = operation(actor, key, "POST", path,
                    requestSha, "REQUEST_CONFIRMATION", newId("acf"));
            if (operation.created()) {
                String requestIntentId = "ari_" + digest(actor.tenantId() + "\0"
                        + actor.clientId() + "\0" + actor.ownerJiacn() + "\0"
                        + operation.targetId());
                ArchiveConfirmedRequestRecord created = new ArchiveConfirmedRequestRecord(
                        operation.targetId(), requestIntentId, actor.tenantId(), actor.clientId(),
                        actor.ownerJiacn(), collectionId, requestJson, requestSha,
                        request.requestedPublicationMode(), null, null, null, null, null, null, 1);
                store.insertConfirmedRequest(created);
                store.commitOperation(actor, key, operation.targetId());
                return created;
            }
            return requireConfirmedRequest(actor, operation.targetId(), true);
        });
        ArchiveRequestContext context = confirmedContext(confirmation, "MANAGER_UI",
                null, null);
        ArchiveMaintenanceRequestResultDTO result = request(context,
                confirmedBusinessRequest(confirmation));
        return new ArchiveMaintenanceRequestResultDTO(result.job(), result.execution(),
                result.readiness(), result.nextAction(), confirmation.confirmationRef());
    }

    @Override
    public ArchiveRequestContext bindChatConfirmation(ArchiveActorScope actor,
            String confirmationRef, String conversationId, long conversationGeneration,
            String turnSha256, String entryPoint, String targetAgentId,
            java.util.function.Supplier<String> canonicalMessageIdWriter) {
        requireScope(actor);
        if (!ID.matcher(String.valueOf(confirmationRef)).matches()
                || !exact(confirmationRef, 64) || !exact(conversationId, 100)
                || conversationGeneration < 1
                || !SHA.matcher(String.valueOf(turnSha256)).matches()
                || !Set.of("SONGJIANG", "DIRECT_PRIVATE").contains(entryPoint)
                || canonicalMessageIdWriter == null) {
            invalid("Archive chat confirmation binding is invalid");
        }
        if (("DIRECT_PRIVATE".equals(entryPoint)
                && !ID.matcher(String.valueOf(targetAgentId)).matches())
                || ("SONGJIANG".equals(entryPoint) && targetAgentId != null)) {
            invalid("Archive chat target binding is invalid");
        }
        return transactions.required(() -> {
            ArchiveConfirmedRequestRecord confirmation = requireConfirmedRequest(
                    actor, confirmationRef, true);
            confirmedBusinessRequest(confirmation);
            if (confirmation.conversationId() == null) {
                String canonicalMessageId = canonicalMessageIdWriter.get();
                if (!ID.matcher(String.valueOf(canonicalMessageId)).matches()
                        || !exact(canonicalMessageId, 32)) {
                    throw error(409, "ARCHIVE_CONFIRMATION_BINDING_FAILED",
                            "Canonical archive chat message was not persisted");
                }
                if (store.bindConfirmedRequest(actor, confirmationRef, confirmation.revision(),
                        conversationId, canonicalMessageId, conversationGeneration, turnSha256,
                        entryPoint, targetAgentId) != 1) {
                    conflict("ARCHIVE_CONFIRMATION_CHANGED",
                            "Archive confirmation binding changed");
                }
                confirmation = requireConfirmedRequest(actor, confirmationRef, true);
            }
            requireExactConfirmationBinding(confirmation, conversationId,
                    conversationGeneration, turnSha256, entryPoint, targetAgentId);
            return confirmedContext(confirmation, entryPoint,
                    confirmation.conversationId() + ":" + confirmation.canonicalMessageId(),
                    targetAgentId);
        });
    }
    @Override
    public ArchiveMaintenanceRequestResultDTO request(ArchiveRequestContext context,
            ArchiveMaintenanceRequest request) {
        requireArchiveRequestContext(context, request);
        ArchiveActorScope actor = context.actorScope();
        ArchiveJobCreateRequest create = new ArchiveJobCreateRequest(
                request.operation(), request.newWork(), request.workId(), request.sourceId(),
                request.requestedPublicationMode(), context.requestIntentId());
        String requestKey = "m7-request-" + digest(actor.tenantId() + "\0" + actor.clientId()
                + "\0" + actor.ownerJiacn() + "\0" + context.requestIntentId());
        ArchiveJobDTO job = createJobAuthorized(actor, request.collectionId(), requestKey,
                create, context.targetAgentId());
        if (!"WAITING_SKILL".equals(job.state())) {
            String nextAction = switch (job.state()) {
                case "WAITING_INPUT" -> "RESOLVE_INPUT";
                case "WAITING_ASSIGNEE" -> "APPOINT_EDITOR";
                default -> "GET_JOB";
            };
            return new ArchiveMaintenanceRequestResultDTO(job, null,
                    job.waitReason(), nextAction);
        }
        try {
            ArchiveExecutionDTO execution = ensureExecution(actor, job.jobId(),
                    "m7-execute-" + digest(requestKey), Long.parseLong(job.revision()));
            return new ArchiveMaintenanceRequestResultDTO(
                    getJob(actor, job.jobId()), execution, "DISPATCHED", "GET_JOB");
        } catch (ArchiveMaintenanceException unavailable) {
            if (Set.of("ARCHIVE_EXECUTION_DISABLED", "ARCHIVE_EXECUTION_TARGET_FENCED",
                    "ARCHIVE_EXECUTION_TRANSPORT_UNAVAILABLE", "ARCHIVE_EXECUTION_SKILL_NOT_VERIFIED",
                    "ARCHIVE_EXECUTION_EXPIRED").contains(unavailable.code())) {
                ArchiveJobDTO current = getJob(actor, job.jobId());
                return new ArchiveMaintenanceRequestResultDTO(
                        current, null, unavailable.code(), "RESUME_WHEN_READY");
            }
            throw unavailable;
        }
    }

    private void requireArchiveRequestContext(ArchiveRequestContext context,
            ArchiveMaintenanceRequest request) {
        if (context == null || context.actorScope() == null || context.confirmedPolicyRef() == null) {
            throw error(401, "AUTH_CONTEXT_INCOMPLETE", "Archive request context is incomplete");
        }
        requireScope(context.actorScope());
        if (!exact(context.requestIntentId(), 100)
                || !Set.of("MANAGER_UI", "SONGJIANG", "DIRECT_PRIVATE").contains(context.entryPoint())
                || !exact(context.confirmedPolicyRef().policyRef(), 200)) {
            invalid("Archive request context is invalid");
        }
        boolean chatEntry = !"MANAGER_UI".equals(context.entryPoint());
        if (chatEntry != exact(context.conversationRef(), 200)) {
            invalid("Archive conversation context is invalid");
        }
        if ("DIRECT_PRIVATE".equals(context.entryPoint())) {
            if (!ID.matcher(String.valueOf(context.targetAgentId())).matches()) {
                invalid("Direct archive target is required");
            }
        } else if (context.targetAgentId() != null) {
            invalid("Only direct private entry may bind an explicit target");
        }
        ArchiveConfirmedPolicyRef policy = context.confirmedPolicyRef();
        if (request == null
                || !same(policy.collectionId(), request.collectionId())
                || !same(policy.operation(), request.operation())
                || !same(policy.newWork(), request.newWork())
                || !same(policy.workId(), request.workId())
                || !same(policy.sourceId(), request.sourceId())) {
            forbidden("ARCHIVE_REQUEST_POLICY_VIOLATION",
                    "Archive request exceeds its confirmed collection, operation, work or source ceiling");
        }
        String requestedMode = request.requestedPublicationMode();
        String ceiling = policy.publicationModeCeiling();
        boolean modeAllowed = "MANUAL".equals(requestedMode)
                || ("AUTO".equals(requestedMode) && "AUTO".equals(ceiling));
        if (!modeAllowed || !("MANUAL".equals(ceiling) || "AUTO".equals(ceiling))) {
            forbidden("ARCHIVE_PUBLICATION_MODE_WIDENING",
                    "Archive request exceeds its confirmed publication mode ceiling");
        }
    }

    @Override
    public ArchiveExecutionDTO ensureExecution(ArchiveActorScope actor, String jobId,
            String key, long expectedJobRevision) {
        requireScope(actor);
        exactId(jobId);
        requireKey(key);
        if (!executionEnabled || executionPort == null) {
            throw error(503, "ARCHIVE_EXECUTION_DISABLED",
                    "Archive Agent execution is not enabled");
        }
        String path = "/archive/admin/v1/jobs/" + jobId + "/execute";
        ArchiveMaintenanceJobRecord observed = requireJobForActor(actor, jobId, false);
        requireExecutionCandidate(observed);
        ExecutionAdmission admission = transactions.required(() -> {
            ArchiveAgentExecutionPort.TargetRequest target = target(observed);
            ArchiveAgentExecutionPort.LockedIdentityRoot root = executionPort.lockIdentityRoot(target);
            ArchiveManagerGrantRecord manager = requireManager(actor, observed.collectionId(), "job.manage", true);
            requireManagerRevision(manager, observed);
            ArchiveAppointmentRecord appointment = requireCurrentAppointmentForJob(observed, true);
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor, jobId, true);
            requireManagerRevision(manager, job);
            ArchiveJobRunRecord run = store.findRun(job.runId(), true);
            if (run == null || !job.jobId().equals(run.jobId())) notFound();
            String requestSha = digest(expectedJobRevision + ":" + job.runId() + ":"
                    + job.appointmentId() + ":" + job.appointmentRevision() + ":"
                    + job.managerAuthorizationRevision() + ":" + job.bindingVersion() + ":" + job.requestSha256());
            String grantRef = newId("aeg");
            String dispatchKey = executionDispatchKey(job);
            ArchiveMaintenanceStore.Operation operation = operation(actor, key, "POST", path,
                    requestSha, "EXECUTION", grantRef);
            if (!operation.created()) {
                ArchiveExecutionGrantRecord existing = store.findExecutionGrant(job.runId(), true);
                if (existing == null || !operation.targetId().equals(existing.grantRef())) {
                    conflict("ARCHIVE_OPERATION_IN_PROGRESS", "Execution grant is not committed");
                }
                if ("ACTIVE".equals(existing.state()) && clock.millis() >= existing.expiresAt()) {
                    if (store.fenceRun(job.runId()) != 1
                            || store.updateJobState(job.jobId(), job.revision(), "WAITING_SKILL",
                                    "EXECUTION_EXPIRED", null) != 1) {
                        conflict("ARCHIVE_EXECUTION_CHANGED", "Expired archive execution changed during fencing");
                    }
                    store.appendJobEvent(job.jobId(), job.revision() + 1, "EXECUTION_FENCED",
                            json(Map.of("grantRef", existing.grantRef(), "reason", "EXECUTION_EXPIRED")));
                    return new ExecutionAdmission(null, true);
                }
                return new ExecutionAdmission(executionDto(existing), false);
            }
            if (job.revision() != expectedJobRevision) revisionConflict(job.revision());
            requireRuntimeMutable(job);
            requireCurrentBinding(appointment);
            if (!"WAITING".equals(run.state()) || store.findExecutionGrant(job.runId(), false) != null) {
                conflict("ARCHIVE_EXECUTION_ALREADY_GRANTED", "Archive run already has an execution grant");
            }
            ArchiveAgentExecutionPort.LockedTarget lockedTarget =
                    executionPort.requireControlledTarget(target, root);
            String executionRef = newId("aex");
            ArchiveAgentExecutionPort.Grant admitted;
            try {
                admitted = executionPort.ensureExecution(new ArchiveAgentExecutionPort.Request(
                        job.tenantId(), job.clientId(), job.ownerJiacn(), job.agentId(),
                        parsePositive(job.bindingVersion(), "bindingVersion"), job.jobId(), job.runId(),
                        job.appointmentId(), job.appointmentRevision(), job.managerAuthorizationRevision(),
                        grantRef, executionRef, run.executionEpoch(), dispatchKey, InstalledSkillResolver.Origin.PLATFORM_PROVISIONED,
                        appointment.requiredSkillKey(), appointment.requiredSkillVersion(),
                        appointment.requiredSkillSha256(), contextRef(job)), lockedTarget);
            } catch (ArchiveAgentExecutionPort.Denied denied) {
                throw executionDenied(denied);
            }
            ArchiveExecutionGrantRecord grant = new ArchiveExecutionGrantRecord(admitted.grantRef(),
                    job.runId(), job.tenantId(), job.clientId(), job.ownerJiacn(), job.appointmentId(),
                    job.appointmentRevision(), job.managerAuthorizationRevision(), job.agentId(),
                    parsePositive(job.bindingVersion(), "bindingVersion"),
                    admitted.executionRef(), admitted.commandId(), admitted.activeAttempt(),
                    admitted.executionEpoch(), admitted.runtimeInstanceId(), admitted.registrationHash(),
                    InstalledSkillResolver.Origin.PLATFORM_PROVISIONED.name(),
                    admitted.skillProof().installationRef(), admitted.skillProof().revision(),
                    admitted.skillProof().key(), admitted.skillProof().version(),
                    admitted.skillProof().packageDigest(), dispatchKey, requestSha, contextRef(job),
                    admitted.expiresAt(), "ACTIVE", 1);
            store.insertExecutionGrant(grant);
            if (store.activateRun(run.runId(), run.revision(), admitted.runtimeInstanceId()) != 1
                    || store.updateJobState(job.jobId(), job.revision(), "EXECUTION_REQUESTED",
                            "AGENT_DISPATCH_PENDING", null) != 1) {
                conflict("ARCHIVE_EXECUTION_CHANGED", "Archive execution changed during admission");
            }
            store.appendJobEvent(job.jobId(), job.revision() + 1, "EXECUTION_GRANTED",
                    json(Map.of("grantRef", grant.grantRef(), "executionRef", grant.executionRef(),
                            "commandId", grant.commandId(), "executionEpoch",
                            Long.toString(grant.executionEpoch()))));
            store.commitOperation(actor, key, grant.grantRef());
            return new ExecutionAdmission(executionDto(grant), false);
        });
        if (admission.expired()) {
            throw error(409, "ARCHIVE_EXECUTION_EXPIRED", "Archive execution grant expired and was fenced");
        }
        return admission.value();
    }

    @Override
    public ArchiveExecutionRecoveryDTO resume(ArchiveActorScope actor, String jobId, String key,
            long expectedJobRevision, ArchiveResumeRequest request) {
        validateResumeRequest(request);
        return recoverExecution(actor, jobId, key, expectedJobRevision, request.reason(),
                request.expectedAppointmentId(), request.expectedAppointmentRevision(),
                request.expectedSkill(), null);
    }

    @Override
    public ArchiveExecutionRecoveryDTO reassign(ArchiveActorScope actor, String jobId, String key,
            long expectedJobRevision, ArchiveReassignRequest request) {
        validateReassignRequest(request);
        return recoverExecution(actor, jobId, key, expectedJobRevision, request.reason(),
                request.expectedAppointmentId(), request.expectedAppointmentRevision(),
                request.expectedSkill(), request);
    }

    private ArchiveExecutionRecoveryDTO recoverExecution(ArchiveActorScope actor, String jobId, String key,
            long expectedJobRevision, String reason, String expectedAppointmentId,
            String expectedAppointmentRevision, ArchiveSkillRef expectedSkill,
            ArchiveReassignRequest reassign) {
        requireScope(actor);
        exactId(jobId);
        requireKey(key);
        ArchiveMaintenanceJobRecord observed = requireJobForActor(actor, jobId, false);
        requireManager(actor, observed.collectionId(), "job.manage", false);
        requireExecutionCandidate(observed);
        ArchiveAppointmentRecord desiredObserved = reassign == null
                ? requireAppointmentForActor(actor, observed.appointmentId(), false)
                : requireAppointmentForActor(actor, reassign.newAppointmentId(), false);
        if (!observed.collectionId().equals(desiredObserved.collectionId())) notFound();
        ArchiveAgentExecutionPort port = requireExecutionPort();
        List<ArchiveAgentExecutionPort.TargetRequest> roots = new ArrayList<>();
        roots.add(target(observed));
        roots.add(target(desiredObserved));
        List<ArchiveAgentExecutionPort.TargetRequest> orderedRoots = roots.stream().distinct()
                .sorted(Comparator.comparing(ArchiveAgentExecutionPort.TargetRequest::canonicalAgent)
                        .thenComparingLong(ArchiveAgentExecutionPort.TargetRequest::binding))
                .toList();
        String path = "/archive/admin/v1/jobs/" + jobId
                + (reassign == null ? "/resume" : "/reassign");
        String requestSha = digest(expectedJobRevision + ":" + (reassign == null
                ? sha(new ArchiveResumeRequest(reason, expectedAppointmentId,
                        expectedAppointmentRevision, expectedSkill))
                : sha(reassign)));
        return transactions.required(() -> {
            try {
                for (ArchiveAgentExecutionPort.TargetRequest root : orderedRoots) {
                    port.lockIdentityRoot(root);
                }
            } catch (ArchiveAgentExecutionPort.Denied denied) {
                throw error(403, "EXECUTION_FENCED", "Archive execution identity is no longer current");
            }
            ArchiveManagerGrantRecord manager = requireManager(actor, observed.collectionId(),
                    "job.manage", true);
            ArchiveMaintenanceStore.Slot slot = store.lockSlot(observed.collectionId(), ROLE);
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor, jobId, true);
            ArchiveMaintenanceStore.Operation op = operation(actor, key, "POST", path, requestSha,
                    "EXECUTION_RECOVERY", newId("ar"));
            if (!op.created()) {
                ArchiveJobRunRecord replay = store.findRun(op.targetId(), false);
                if (replay == null || !jobId.equals(replay.jobId())) {
                    conflict("ARCHIVE_OPERATION_IN_PROGRESS", "Recovery run is not committed");
                }
                return recoveryDto(replay);
            }
            if (job.revision() != expectedJobRevision) revisionConflict(job.revision());
            if (job.publicationId() != null || Set.of("PUBLISHED", "CANCELLED").contains(job.state())) {
                throw error(422, "ARCHIVE_JOB_NOT_MUTABLE",
                        "Published or cancelled jobs cannot create a new execution");
            }
            ArchiveAppointmentRecord previous = requireAppointmentForActor(actor, job.appointmentId(), true);
            requireExpectedAppointment(job, previous, expectedAppointmentId,
                    expectedAppointmentRevision, expectedSkill);
            ArchiveAppointmentRecord selected;
            if (reassign == null) {
                selected = previous;
                if (slot == null || !selected.appointmentId().equals(slot.currentAppointmentId())
                        || selected.revision() != job.appointmentRevision()
                        || !"ACTIVE".equals(selected.status())) {
                    forbidden("ARCHIVE_ASSIGNMENT_CHANGED",
                            "Archive appointment was revoked or changed");
                }
            } else {
                selected = requireAppointmentForActor(actor, reassign.newAppointmentId(), true);
                requireRequestedAppointment(selected, reassign.newAppointmentId(),
                        reassign.newAppointmentRevision(), reassign.newSkill());
                if (slot == null || !selected.appointmentId().equals(slot.currentAppointmentId())
                        || !"ACTIVE".equals(selected.status())
                        || !job.collectionId().equals(selected.collectionId())) {
                    forbidden("ARCHIVE_ASSIGNMENT_CHANGED",
                            "Requested archive appointment is not current");
                }
            }
            requireSameOwner(actor, selected);
            requireCurrentBinding(selected);
            authorizeWorkScope(selected, job.workId(), "ADD_WORK".equals(job.operation()));
            ArchiveJobRunRecord oldRun = store.findRun(job.runId(), true);
            if (oldRun == null || !job.jobId().equals(oldRun.jobId())) notFound();
            ArchiveExecutionGrantRecord oldGrant = store.findExecutionGrant(oldRun.runId(), true);
            if ("FENCED".equals(oldRun.state())) {
                if (oldGrant != null && (!"FENCED".equals(oldGrant.state())
                        || oldGrant.executionEpoch() != oldRun.executionEpoch())) {
                    conflict("ARCHIVE_EXECUTION_CHANGED",
                            "Fenced archive run has inconsistent execution authority");
                }
            } else if (store.fenceRun(oldRun.runId()) != 1) {
                conflict("ARCHIVE_EXECUTION_CHANGED", "Archive execution changed during fencing");
            }
            long nextEpoch;
            try { nextEpoch = Math.addExact(oldRun.executionEpoch(), 1); }
            catch (ArithmeticException overflow) {
                conflict("ARCHIVE_EXECUTION_CHANGED", "Archive execution epoch cannot advance");
                return null;
            }
            String newRunId = op.targetId();
            store.insertRun(newRunId, job.jobId(), nextEpoch, selected.revision());
            if (store.replaceCurrentRun(job.jobId(), job.revision(), oldRun.runId(), newRunId,
                    selected, manager.revision(), "WAITING_SKILL", "CLIENT_UPDATE_REQUIRED") != 1) {
                conflict("ARCHIVE_JOB_CHANGED", "Archive job changed during recovery");
            }
            store.appendJobEvent(job.jobId(), job.revision() + 1,
                    reassign == null ? "EXECUTION_RESUMED" : "EXECUTION_REASSIGNED",
                    json(Map.of("oldRunId", oldRun.runId(), "newRunId", newRunId,
                            "executionEpoch", Long.toString(nextEpoch), "reason", reason,
                            "appointmentId", selected.appointmentId())));
            store.commitOperation(actor, key, newRunId);
            return new ArchiveExecutionRecoveryDTO(job.jobId(), newRunId,
                    Long.toString(nextEpoch), "WAITING");
        });
    }

    @Override
    public ArchiveJobDTO getJob(ArchiveActorScope actor, String jobId) {
        ArchiveMaintenanceJobRecord observed = requireJobForActor(actor, jobId, false);
        return transactions.required(() -> {
            // The canonical identity/binding aggregate root precedes every Archive lock. This
            // linearizes a hosted binding suspension against the current-assignment projection.
            LockedJobIdentity lockedIdentity = lockJobIdentityRoot(observed);
            requireJobReadManager(actor, observed.collectionId(), true);
            LockedJobAssignment lockedAssignment = lockJobAssignment(observed);
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor, jobId, true);
            if (!same(job.collectionId(), observed.collectionId())) notFound();
            if (!same(job.appointmentId(), observed.appointmentId())
                    || !same(job.appointmentRevision(), observed.appointmentRevision())) {
                conflict("ARCHIVE_JOB_CHANGED", "Archive job assignment changed during read");
            }
            CurrentJobAssignment assignment = currentJobAssignment(
                    actor, job, lockedAssignment, lockedIdentity);
            return jobDto(job, handlingFacts(actor, job, assignment));
        });
    }

    @Override
    public ArchiveJobDTO resolveInput(ArchiveActorScope actor, String jobId, String key,
            long expectedJobRevision, ArchiveResolveInputRequest request) {
        requireScope(actor);
        exactId(jobId);
        requireKey(key);
        validateResolveInputRequest(request);
        ArchiveMaintenanceJobRecord observed = requireJobForActor(actor, jobId, false);
        requireManager(actor, observed.collectionId(), "job.manage", false);
        String path = "/archive/admin/v1/jobs/" + jobId + "/resolve-input";
        String requestSha = digest(expectedJobRevision + ":" + sha(request));
        return transactions.required(() -> {
            ArchiveManagerGrantRecord manager = requireManager(actor, observed.collectionId(),
                    "job.manage", true);
            ArchiveAppointmentRecord appointment = currentAppointment(observed.collectionId(), true);
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor, jobId, true);
            ArchiveMaintenanceStore.Operation operation = operation(actor, key, "POST", path,
                    requestSha, "JOB_INPUT", jobId);
            if (!operation.created()) return jobDto(job);
            if (job.revision() != expectedJobRevision) revisionConflict(job.revision());
            if (!Set.of("WAITING_INPUT", "WAITING_ASSIGNEE").contains(job.state())
                    || job.runId() != null || job.draftId() != null || job.publicationId() != null) {
                conflict("ARCHIVE_JOB_NOT_WAITING_INPUT",
                        "Archive job inputs cannot be replaced after a candidate exists");
            }

            ResolvedWork work = resolvePersistedWork(job, request);
            ArchiveSourceSnapshotRecord source = resolvePersistedSource(actor, job, request);
            boolean inputsComplete = work != null && source != null;
            if (inputsComplete && appointment != null) {
                requireSameOwner(actor, appointment);
                requireCurrentBinding(appointment);
                requireTargetAppointment(job.targetAgentId(), appointment);
                requireResolveAppointmentSnapshot(request, appointment);
                authorizeWorkScope(appointment, work.workId(), "ADD_WORK".equals(job.operation()));
            } else if (inputsComplete && hasAppointmentSnapshot(request)) {
                conflict("ARCHIVE_ASSIGNMENT_CHANGED",
                        "The requested archive appointment is not currently assigned");
            }

            String state = inputsComplete
                    ? (appointment == null ? "WAITING_ASSIGNEE" : "WAITING_SKILL")
                    : "WAITING_INPUT";
            String waitReason = waitReason(work == null, source == null,
                    appointment == null);
            boolean ready = "WAITING_SKILL".equals(state);
            String runId = ready ? newId("ar") : null;
            String draftId = ready ? newId("ad") : null;
            ArchiveMaintenanceJobRecord resolved = new ArchiveMaintenanceJobRecord(
                    job.jobId(), runId, job.collectionId(), job.tenantId(), job.clientId(),
                    job.ownerJiacn(), ready ? appointment.appointmentId() : null,
                    ready ? appointment.revision() : null,
                    ready ? appointment.agentId() : null,
                    ready ? appointment.bindingVersion() : null,
                    ready ? appointment.permissionProfile() : null,
                    manager.revision(), job.publicationMode(), job.operation(),
                    work == null ? null : work.workId(),
                    work == null ? null : work.canonicalKey(),
                    work == null ? null : work.title(),
                    source == null ? null : source.sourceId(),
                    source == null ? null : source.rawSha256(),
                    source == null ? null : source.sourceName() + " / " + source.sourceVersion(),
                    source == null ? null : source.rightsBasis(), state, waitReason,
                    job.revision() + 1, draftId, null, job.requestIntentId(),
                    job.requestSha256(), job.targetAgentId());
            if (!ready && sameWaitingFacts(job, resolved)) {
                conflict("ARCHIVE_INPUT_INCOMPLETE",
                        "Archive input or an exact current appointment is still required");
            }
            if (store.resolveWaitingJob(resolved, job.revision()) != 1) {
                conflict("ARCHIVE_JOB_CHANGED", "Archive job changed during input resolution");
            }
            if (ready) createInitialCandidate(resolved);
            store.appendJobEvent(job.jobId(), resolved.revision(), "JOB_INPUT_RESOLVED",
                    jobStateEvent(resolved));
            store.commitOperation(actor, key, jobId);
            return jobDto(resolved);
        });
    }

    @Override
    public ArchiveRecoveryContextDTO recoveryContext(ArchiveActorScope actor, String jobId) {
        requireScope(actor);
        exactId(jobId);
        ArchiveMaintenanceJobRecord job = requireJobForActor(actor, jobId, false);
        requireManager(actor, job.collectionId(), "job.manage", false);
        requireExecutionCandidate(job);
        ArchiveAppointmentRecord previous = requireAppointmentForActor(
                actor, job.appointmentId(), false);
        if (!same(job.collectionId(), previous.collectionId())) notFound();

        ArchiveAppointmentRecord current = store.findCurrentAppointment(job.collectionId(), false);
        List<ArchiveRecoveryContextDTO.CandidateAppointment> candidates = List.of();
        if (current != null && "ACTIVE".equals(current.status())
                && same(job.collectionId(), current.collectionId())
                && same(actor.tenantId(), current.tenantId())
                && same(actor.clientId(), current.clientId())
                && same(actor.ownerJiacn(), current.ownerJiacn())) {
            candidates = List.of(new ArchiveRecoveryContextDTO.CandidateAppointment(
                    current.appointmentId(), Long.toString(current.revision()),
                    skillRef(current), current.status(), current.agentId()));
        }
        return new ArchiveRecoveryContextDTO(job.jobId(), Long.toString(job.revision()),
                new ArchiveRecoveryContextDTO.PreviousAppointment(previous.appointmentId(),
                        Long.toString(job.appointmentRevision()), skillRef(previous),
                        previous.status()), candidates);
    }

    @Override
    public List<ArchiveJobEventDTO> jobEvents(ArchiveActorScope actor, String jobId,
            long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1 || limit > 100) invalid("Invalid archive event cursor or limit");
        ArchiveMaintenanceJobRecord job = requireJobForActor(actor, jobId, false);
        requireJobReadManager(actor, job.collectionId(), false);
        return store.listJobEvents(jobId, afterSequence, limit).stream().map(event -> {
            try {
                Map<String, String> data = mapper.readValue(event.dataJson(), mapper.getTypeFactory()
                        .constructMapType(Map.class, String.class, String.class));
                return new ArchiveJobEventDTO(event.jobId(), Long.toString(event.sequence()),
                        Long.toString(event.schemaVersion()), event.type(), Long.toString(event.jobRevision()),
                        Map.copyOf(data), event.occurredAt());
            } catch (JsonProcessingException invalid) {
                throw new IllegalStateException("Persisted archive event is invalid", invalid);
            }
        }).toList();
    }
    @Override
    public List<ArchiveJobDTO> listJobs(ArchiveActorScope actor, String collectionId, int limit) {
        requireJobReadManager(actor, collectionId, false);
        return store.listJobs(actor, collectionId, Math.max(1, Math.min(limit, 100)))
                .stream().map(this::jobDto).toList();
    }

    @Override
    public ArchiveWorksDTO listWorks(ArchiveActorScope actor, String collectionId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return transactions.required(() -> {
            requireJobReadManager(actor, collectionId, true);
            List<ArchiveWorkSummaryDTO> items = store.listManagedWorks(actor, collectionId, bounded).stream()
                    .map(value -> new ArchiveWorkSummaryDTO(value.workId(), value.title(),
                            value.activeEditionId(), value.workRevision() == null ? null
                                    : Long.toString(value.workRevision()),
                            value.hasEditionHistory(), value.pendingJobId()))
                    .toList();
            return new ArchiveWorksDTO(items, null);
        });
    }

    @Override
    public ArchiveDraftBlockDTO getDraftBlock(ArchiveActorScope actor, String draftId, String blockId) {
        exactId(draftId);
        exactId(blockId);
        ArchiveDraftRecord observedDraft = store.findDraft(draftId, false);
        if (observedDraft == null) notFound();
        ArchiveMaintenanceJobRecord observed = requireJobForActor(actor, observedDraft.jobId(), false);
        return transactions.required(() -> {
            requireManager(actor, observed.collectionId(), "draft.write", true);
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor, observed.jobId(), true);
            ArchiveDraftRecord draft = requireDraft(job.jobId(), true);
            if (!same(job.draftId(), draftId) || !same(draft.draftId(), draftId)) notFound();
            ArchiveDraftBlockInput block = parseDraft(draft.contentJson()).blocks().stream()
                    .filter(value -> blockId.equals(value.blockKey())).findFirst().orElse(null);
            if (block == null) notFound();
            return new ArchiveDraftBlockDTO(draftId, job.jobId(), Long.toString(draft.revision()),
                    draft.state(), block);
        });
    }

    @Override
    public ArchiveDraftBlockDTO putDraftBlock(ArchiveActorScope actor, String draftId, String blockId,
            String key, long expectedRevision, ArchiveDraftBlockInput request) {
        exactId(draftId);
        exactId(blockId);
        requireKey(key);
        if (request == null || !same(blockId, request.blockKey())) {
            invalid("Draft block path and body must identify the same block");
        }
        ArchiveDraftRecord observedDraft = store.findDraft(draftId, false);
        if (observedDraft == null) notFound();
        ArchiveMaintenanceJobRecord observed = requireJobForActor(actor, observedDraft.jobId(), false);
        requireManager(actor, observed.collectionId(), "draft.write", false);
        String path = "/archive/admin/v1/drafts/" + draftId + "/blocks/" + blockId;
        String requestSha = digest(expectedRevision + "\0" + sha(request));
        return transactions.required(() -> {
            ArchiveManagerGrantRecord manager = requireManager(actor, observed.collectionId(), "draft.write", true);
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor, observed.jobId(), true);
            ArchiveDraftRecord draft = requireDraft(job.jobId(), true);
            if (!same(draft.draftId(), draftId)) notFound();
            ArchiveMaintenanceStore.Operation op = operation(actor, key, "PUT", path, requestSha,
                    "DRAFT", draftId);
            ArchiveAdminOperationRecord receipt = ensureAdminReceipt(actor, op, key,
                    job, draft, "BLOCK_PUT", manager.revision());
            if (!op.created()) return blockSnapshot(receipt, ArchiveDraftBlockDTO.class);
            requireDraftMutable(job, draft);
            if (draft.revision() != expectedRevision) revisionConflict(draft.revision());
            ArchiveDraftUpdateRequest current = parseDraft(draft.contentJson());
            ArrayList<ArchiveDraftBlockInput> blocks = new ArrayList<>(current.blocks());
            blocks.removeIf(block -> blockId.equals(block.blockKey()));
            blocks.add(request);
            blocks.sort(Comparator.comparingInt(block -> block.ordinal() == null
                    ? Integer.MAX_VALUE : block.ordinal()));
            ArchiveDraftUpdateRequest updated = new ArchiveDraftUpdateRequest(
                    blocks, current.excludedSourceRanges());
            ArchiveDraftDTO changed = persistHumanDraftUpdate(actor, manager, job, draft, updated,
                    "BLOCK_PUT");
            ArchiveDraftBlockDTO result = new ArchiveDraftBlockDTO(draftId, job.jobId(),
                    changed.revision(), changed.state(), request);
            commitAdminReceipt(actor, key, op.targetId(), receipt, result);
            return result;
        });
    }

    @Override
    public ArchiveDraftDTO patchDraft(ArchiveActorScope actor, String draftId, String key,
            long expectedRevision, ArchiveDraftPatchRequest request) {
        exactId(draftId);
        requireKey(key);
        if (request == null || request.blocks() == null && request.excludedSourceRanges() == null) {
            invalid("Draft patch must contain catalog metadata or excluded source ranges");
        }
        ArchiveDraftRecord observedDraft = store.findDraft(draftId, false);
        if (observedDraft == null) notFound();
        ArchiveMaintenanceJobRecord observed = requireJobForActor(actor, observedDraft.jobId(), false);
        requireManager(actor, observed.collectionId(), "draft.write", false);
        String path = "/archive/admin/v1/drafts/" + draftId;
        String requestSha = digest(expectedRevision + "\0" + sha(request));
        return transactions.required(() -> {
            ArchiveManagerGrantRecord manager = requireManager(actor, observed.collectionId(), "draft.write", true);
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor, observed.jobId(), true);
            ArchiveDraftRecord draft = requireDraft(job.jobId(), true);
            if (!same(draft.draftId(), draftId)) notFound();
            ArchiveMaintenanceStore.Operation op = operation(actor, key, "PATCH", path,
                    requestSha, "DRAFT", draftId);
            ArchiveAdminOperationRecord receipt = ensureAdminReceipt(actor, op, key,
                    job, draft, "DRAFT_PATCH", manager.revision());
            if (!op.created()) return blockSnapshot(receipt, ArchiveDraftDTO.class);
            requireDraftMutable(job, draft);
            if (draft.revision() != expectedRevision) revisionConflict(draft.revision());
            ArchiveDraftUpdateRequest current = parseDraft(draft.contentJson());
            Map<String, ArchiveDraftBlockMetadataPatch> patches = new LinkedHashMap<>();
            if (request.blocks() != null) {
                for (ArchiveDraftBlockMetadataPatch patch : request.blocks()) {
                    if (patch == null || !exact(patch.blockKey(), 100) || patch.ordinal() == null
                            || patch.ordinal() < 0 || !exact(patch.title(), 255)
                            || patch.titleSourceRanges() == null
                            || patches.putIfAbsent(patch.blockKey(), patch) != null) {
                        invalid("Draft catalog patch is invalid");
                    }
                }
            }
            ArrayList<ArchiveDraftBlockInput> blocks = new ArrayList<>();
            for (ArchiveDraftBlockInput block : current.blocks()) {
                ArchiveDraftBlockMetadataPatch patch = patches.remove(block.blockKey());
                blocks.add(patch == null ? block : new ArchiveDraftBlockInput(block.blockType(),
                        block.blockKey(), patch.ordinal(), patch.title(), patch.titleSourceRanges(),
                        block.paragraphs()));
            }
            if (!patches.isEmpty()) notFound();
            blocks.sort(Comparator.comparingInt(block -> block.ordinal() == null
                    ? Integer.MAX_VALUE : block.ordinal()));
            ArchiveDraftUpdateRequest updated = new ArchiveDraftUpdateRequest(blocks,
                    request.excludedSourceRanges() == null ? current.excludedSourceRanges()
                            : request.excludedSourceRanges());
            ArchiveDraftDTO result = persistHumanDraftUpdate(actor, manager, job, draft, updated,
                    "DRAFT_PATCH");
            commitAdminReceipt(actor, key, op.targetId(), receipt, result);
            return result;
        });
    }

    @Override
    public ArchiveOperationAcceptedDTO validateDraft(ArchiveActorScope actor, String draftId,
            String key, long expectedRevision) {
        exactId(draftId);
        requireKey(key);
        ArchiveDraftRecord observedDraft = store.findDraft(draftId, false);
        if (observedDraft == null) notFound();
        ArchiveMaintenanceJobRecord observed = requireJobForActor(actor, observedDraft.jobId(), false);
        requireManager(actor, observed.collectionId(), "validate", false);
        String path = "/archive/admin/v1/drafts/" + draftId + "/validate";
        String requestSha = digest("POST\0" + path + "\0" + draftId + "\0" + expectedRevision);
        ArchiveAdminOperationRecord replay = store.findAdminOperationByKey(actor, key, false);
        if (replay != null) {
            assertAdminReceipt(replay, observed, draftId, "DRAFT_VALIDATE");
            ArchiveMaintenanceStore.Operation op = store.findOperation(actor, key);
            if (op == null) throw new IllegalStateException("Persisted archive operation receipt is orphaned");
            assertOperationMatches(op, "POST", path, requestSha, "VALIDATION");
            if (!"COMMITTED".equals(replay.state()) || replay.resultJson() == null) {
                conflict("ARCHIVE_OPERATION_IN_PROGRESS", "Archive operation is not committed");
            }
            return accepted(replay);
        }
        byte[] sourceBytes = requireSource(observed);
        return transactions.required(() -> {
            ArchiveManagerGrantRecord manager = requireManager(actor, observed.collectionId(), "validate", true);
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor, observed.jobId(), true);
            ArchiveDraftRecord draft = requireDraft(job.jobId(), true);
            if (!same(draft.draftId(), draftId)) notFound();
            ArchiveMaintenanceStore.Operation op = operation(actor, key, "POST", path, requestSha,
                    "VALIDATION", newId("val"));
            ArchiveAdminOperationRecord receipt = ensureAdminReceipt(actor, op, key,
                    job, draft, "DRAFT_VALIDATE", manager.revision());
            if (!op.created()) return accepted(receipt);
            requireDraftMutable(job, draft);
            if (draft.revision() != expectedRevision) revisionConflict(draft.revision());
            ArchiveDraftUpdateRequest body = parseDraft(draft.contentJson());
            List<String> findings = new ArrayList<>(validateContent(body));
            findings.addAll(ArchiveSourceMappingValidator.validate(sourceBytes, body));
            String outcome = findings.isEmpty() ? "PASSED" : "FAILED";
            String findingsJson = json(findings);
            ArchiveValidationRecord validation = new ArchiveValidationRecord(op.targetId(),
                    draftId, draft.revision(), outcome,
                    digest(draft.contentSha256() + ":" + findingsJson), findingsJson);
            store.insertValidation(validation);
            String state = findings.isEmpty() ? "VALIDATED" : "CHANGES_REQUIRED";
            if (store.updateDraft(draftId, draft.revision(), draft.revision(), state,
                    draft.contentJson(), draft.contentSha256(),
                    findings.isEmpty() ? draft.revision() : null,
                    findings.isEmpty() ? validation.validationId() : null) != 1) {
                revisionConflict(draft.revision());
            }
            if (store.updateJobState(job.jobId(), job.revision(),
                    findings.isEmpty() ? "AWAITING_PUBLISH" : "NEEDS_CHANGES", null, null) != 1) {
                conflict("ARCHIVE_JOB_CHANGED", "Archive job changed during validation");
            }
            store.appendJobEvent(job.jobId(), job.revision() + 1, "VALIDATION_FINISHED",
                    humanAudit(actor, manager, Map.of("validationId", validation.validationId(),
                            "draftRevision", Long.toString(draft.revision()), "outcome", outcome)));
            ArchiveValidationDTO result = validationDto(validation);
            commitAdminReceipt(actor, key, validation.validationId(), receipt, result);
            return accepted(receipt);
        });
    }

    @Override
    public ArchiveOperationAcceptedDTO publishDraft(ArchiveActorScope actor, String draftId,
            String key, long expectedDraftRevision, ArchivePublishRequest request) {
        exactId(draftId);
        requireKey(key);
        Objects.requireNonNull(request, "request");
        ArchiveDraftRecord observedDraft = store.findDraft(draftId, false);
        if (observedDraft == null) notFound();
        ArchiveMaintenanceJobRecord observed = requireJobForActor(actor, observedDraft.jobId(), false);
        requireManager(actor, observed.collectionId(), "publish", false);
        String path = "/archive/admin/v1/drafts/" + draftId + "/publish";
        String requestSha = publicationRequestSha(expectedDraftRevision, request);
        ArchiveAdminOperationRecord replay = store.findAdminOperationByKey(actor, key, false);
        if (replay != null) {
            assertAdminReceipt(replay, observed, draftId, "DRAFT_PUBLISH");
            ArchiveMaintenanceStore.Operation op = store.findOperation(actor, key);
            if (op == null) throw new IllegalStateException("Persisted archive operation receipt is orphaned");
            assertOperationMatches(op, "POST", path, requestSha, "PUBLICATION");
            if (!"COMMITTED".equals(replay.state())) conflict("ARCHIVE_OPERATION_IN_PROGRESS",
                    "Archive operation is not committed");
            verifyPublicationForOperation(actor, key);
            return accepted(replay);
        }
        PublicationCandidate candidate = preparePublicationCandidate(observed,
                expectedDraftRevision, request);
        ArchiveOperationAcceptedDTO accepted = transactions.required(() -> {
            ArchiveManagerGrantRecord manager = requireManager(actor, observed.collectionId(), "publish", true);
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor, observed.jobId(), true);
            ArchiveDraftRecord draft = requireDraft(job.jobId(), true);
            if (!same(draft.draftId(), draftId)) notFound();
            ArchivePublicationDTO result = publishLocked(actor, job, manager, null, key,
                    expectedDraftRevision, request, path, null, candidate,
                    new AdminOperationSpec(draft, "DRAFT_PUBLISH"));
            ArchiveAdminOperationRecord receipt = store.findAdminOperationByKey(actor, key, false);
            if (receipt == null || !"COMMITTED".equals(receipt.state())) {
                throw new IllegalStateException("Archive publication receipt was not committed");
            }
            return accepted(receipt);
        });
        verifyPublicationForOperation(actor, key);
        return accepted;
    }

    @Override
    public ArchiveAdminOperationDTO operation(ArchiveActorScope actor, String operationId) {
        requireScope(actor);
        exactId(operationId);
        ArchiveAdminOperationRecord receipt = store.findAdminOperationById(operationId, false);
        if (receipt == null) return sourceOperation(actor, operationId);
        if (!same(actor.tenantId(), receipt.tenantId())
                || !same(actor.clientId(), receipt.clientId())
                || !same(actor.ownerJiacn(), receipt.ownerJiacn())) notFound();
        ArchiveMaintenanceJobRecord job = requireJobForActor(actor, receipt.jobId(), false);
        String permission = switch (receipt.action()) {
            case "BLOCK_PUT", "DRAFT_PATCH" -> "draft.write";
            case "DRAFT_VALIDATE" -> "validate";
            case "DRAFT_PUBLISH" -> "publish";
            default -> throw new IllegalStateException("Persisted archive admin operation action is invalid");
        };
        requireManager(actor, job.collectionId(), permission, false);
        ArchiveMaintenanceStore.Operation op = store.findOperation(actor, receipt.operationKey());
        if (op == null) throw new IllegalStateException("Persisted archive operation receipt is orphaned");
        if (!same(op.state(), receipt.state())) {
            throw new IllegalStateException("Persisted archive operation receipt state is inconsistent");
        }
        Map<String, Object> result = receipt.resultJson() == null ? null : parseResult(receipt.resultJson());
        if ("DRAFT_PUBLISH".equals(receipt.action()) && result != null
                && result.get("publicationId") instanceof String publicationId) {
            try { verifyPublication(publicationId); }
            catch (RuntimeException ignored) { /* committed result remains authoritative */ }
        }
        return transactions.required(() -> adminOperationSnapshot(actor, receipt, permission));
    }

    private ArchiveAdminOperationDTO adminOperationSnapshot(ArchiveActorScope actor,
            ArchiveAdminOperationRecord observed, String permission) {
        requireManager(actor, observed.collectionId(), permission, true);
        requireJobForActor(actor, observed.jobId(), true);
        ArchiveAdminOperationRecord receipt = store.findAdminOperationById(
                observed.operationId(), true);
        if (receipt == null || !same(actor.tenantId(), receipt.tenantId())
                || !same(actor.clientId(), receipt.clientId())
                || !same(actor.ownerJiacn(), receipt.ownerJiacn())) {
            notFound();
        }
        if (!same(observed.operationKey(), receipt.operationKey())
                || !same(observed.collectionId(), receipt.collectionId())
                || !same(observed.jobId(), receipt.jobId())
                || !same(observed.draftId(), receipt.draftId())
                || !same(observed.action(), receipt.action())
                || observed.authorizationRevision() != receipt.authorizationRevision()) {
            throw new IllegalStateException("Persisted archive admin operation identity changed");
        }
        ArchiveMaintenanceStore.Operation op = store.findOperation(actor, receipt.operationKey());
        if (op == null) throw new IllegalStateException("Persisted archive operation receipt is orphaned");
        if (!same(op.state(), receipt.state())) {
            throw new IllegalStateException("Persisted archive operation receipt state is inconsistent");
        }
        Map<String, Object> result = receipt.resultJson() == null ? null : parseResult(receipt.resultJson());
        ArchivePublicationVerificationDTO verification = null;
        if ("DRAFT_PUBLISH".equals(receipt.action()) && result != null
                && result.get("publicationId") instanceof String publicationId) {
            verification = verificationDto(requirePublicationReadback(publicationId));
        }
        return new ArchiveAdminOperationDTO(receipt.operationId(), receipt.operationKey(),
                receipt.state(), op.httpMethod(), op.canonicalPath(), receipt.collectionId(),
                receipt.jobId(), receipt.draftId(), receipt.action(),
                Long.toString(receipt.authorizationRevision()), result, verification);
    }

    private ArchiveAdminOperationDTO sourceOperation(ArchiveActorScope actor, String operationId) {
        ArchiveMaintenanceStore.TargetOperation op = store.findOperationByTarget(
                actor, "SOURCE", operationId);
        if (op == null || !"POST".equals(op.httpMethod()) || !same(op.targetId(), operationId)) {
            notFound();
        }
        java.util.regex.Matcher path = Pattern.compile(
                "^/archive/admin/v1/collections/([A-Za-z0-9._:-]{1,100})/source-snapshots$")
                .matcher(op.canonicalPath());
        if (!path.matches()) notFound();
        String collectionId = path.group(1);
        ArchiveManagerGrantRecord manager = requireManager(actor, collectionId,
                "source.prepare", false);
        Map<String, Object> result = null;
        if ("COMMITTED".equals(op.state())) {
            result = resultMap(sourceDto(requireSourceSnapshot(operationId, actor, collectionId)));
        } else if (!"PENDING".equals(op.state())) {
            throw new IllegalStateException("Persisted source operation state is invalid");
        }
        return new ArchiveAdminOperationDTO(operationId, op.operationKey(), op.state(),
                op.httpMethod(), op.canonicalPath(), collectionId, null, null,
                "SOURCE_PREPARE", Long.toString(manager.revision()), result, null);
    }

    @Override
    public ArchiveDraftDTO getDraft(ArchiveActorScope actor, String jobId) {
        ArchiveMaintenanceJobRecord job = requireJobForActor(actor, jobId, false);
        requireManager(actor, job.collectionId(), "draft.write", false);
        return draftDto(requireDraft(jobId, false));
    }

    @Override
    public ArchiveDraftDTO updateDraft(ArchiveActorScope actor, String jobId, String key,
            long expectedRevision, ArchiveDraftUpdateRequest request) {
        ArchiveMaintenanceJobRecord job = requireJobForActor(actor, jobId, false);
        return updateDraftAuthorized(actor, job, key, expectedRevision, request);
    }

    @Override
    public ArchiveValidationDTO validate(ArchiveActorScope actor, String jobId, String key,
            long expectedDraftRevision) {
        ArchiveMaintenanceJobRecord job = requireJobForActor(actor, jobId, false);
        return validateAuthorized(actor, job, key, expectedDraftRevision, false, null);
    }

    @Override
    public ArchiveValidationDTO validation(ArchiveActorScope actor, String draftId) {
        requireScope(actor);
        exactId(draftId);
        ArchiveDraftRecord draft = store.findDraft(draftId, false);
        if (draft == null) notFound();
        ArchiveMaintenanceJobRecord job = requireJobForActor(actor, draft.jobId(), false);
        requireValidationReader(actor, job.collectionId());
        if (!same(job.draftId(), draft.draftId())) notFound();
        ArchiveValidationRecord validation = store.findLatestValidation(draft.draftId(), draft.revision());
        if (validation == null) notFound();
        return validationDto(validation);
    }

    @Override
    public ArchiveOperationDTO operationByKey(ArchiveActorScope actor, String key) {
        requireScope(actor);
        requireKey(key);
        ArchiveMaintenanceStore.Operation op = store.findOperation(actor, key);
        if (op == null) notFound();
        ArchiveAdminOperationRecord admin = store.findAdminOperationByKey(actor, key, false);
        if (admin != null) {
            operation(actor, admin.operationId());
            return new ArchiveOperationDTO(key, op.state(), op.targetType(), op.targetId());
        }
        if (!"POST".equals(op.httpMethod()) && !"PUT".equals(op.httpMethod())) notFound();
        String path = op.canonicalPath();
        java.util.regex.Matcher collection = Pattern.compile(
                "^/archive/admin/v1/collections/([A-Za-z0-9._:-]{1,100})/(source-snapshots|appointments|jobs)$")
                .matcher(path);
        java.util.regex.Matcher managerRevoke = Pattern.compile(
                "^/archive/admin/v1/collections/([A-Za-z0-9._:-]{1,100})/manager-authorization/revoke$")
                .matcher(path);
        java.util.regex.Matcher appointment = Pattern.compile(
                "^/archive/admin/v1/appointments/([A-Za-z0-9._:-]{1,100})/revoke$")
                .matcher(path);
        java.util.regex.Matcher jobInput = Pattern.compile(
                "^/archive/admin/v1/jobs/([A-Za-z0-9._:-]{1,100})/resolve-input$")
                .matcher(path);
        java.util.regex.Matcher job = Pattern.compile(
                "^/archive/admin/v1/jobs/([A-Za-z0-9._:-]{1,100})/(draft|validate|publish|cancel|execute|resume|reassign)$")
                .matcher(path);
        if (managerRevoke.matches()) {
            if (!"MANAGER_AUTHORIZATION".equals(op.targetType())
                    || !managerRevoke.group(1).equals(op.targetId())) notFound();
            // The actor-scoped committed receipt remains readable after that exact grant was revoked.
        } else if (collection.matches()) {
            String permission = switch (collection.group(2)) {
                case "source-snapshots" -> {
                    if (!"SOURCE".equals(op.targetType()) || !ID.matcher(op.targetId()).matches()) {
                        notFound();
                    }
                    yield "source.prepare";
                }
                case "appointments" -> "appoint";
                default -> "job.create";
            };
            requireManager(actor, collection.group(1), permission, false);
        } else if (appointment.matches()) {
            ArchiveAppointmentRecord target = requireAppointmentForActor(actor, appointment.group(1), false);
            requireManager(actor, target.collectionId(), "appoint", false);
        } else if (jobInput.matches()) {
            if (!"JOB_INPUT".equals(op.targetType()) || !jobInput.group(1).equals(op.targetId())) {
                notFound();
            }
            ArchiveMaintenanceJobRecord target = requireJobForActor(actor, jobInput.group(1), false);
            requireManager(actor, target.collectionId(), "job.manage", false);
        } else if (job.matches()) {
            ArchiveMaintenanceJobRecord target = requireJobForActor(actor, job.group(1), false);
            String permission = switch (job.group(2)) {
                case "draft" -> "draft.write";
                case "validate" -> "validate";
                case "publish" -> "publish";
                default -> "job.manage";
            };
            requireManager(actor, target.collectionId(), permission, false);
        } else {
            java.util.regex.Matcher withdrawal = Pattern.compile(
                    "^/archive/admin/v1/works/([A-Za-z0-9._:-]{1,100})/editions/([A-Za-z0-9._:-]{1,100})/withdraw$")
                    .matcher(path);
            if (withdrawal.matches() && "EDITION_WITHDRAWAL".equals(op.targetType())) {
                ArchiveEditionVersionRecord target = store.findPublication(
                        withdrawal.group(1), withdrawal.group(2), false);
                if (target == null) notFound();
                requireWithdrawManager(actor, target.collectionId(), false);
                return new ArchiveOperationDTO(key, op.state(), op.targetType(), op.targetId());
            }
            // Native operations and unknown paths cannot be inspected through a user JWT.
            notFound();
        }
        return new ArchiveOperationDTO(key, op.state(), op.targetType(), op.targetId());
    }

    @Override
    public ArchiveJobDTO cancel(ArchiveActorScope actor, String jobId, String key,
            long expectedRevision, ArchiveCancelRequest request) {
        requireScope(actor);
        requireKey(key);
        if (request == null || !exact(request.reason(), 500)) {
            invalid("Cancellation reason is required");
        }
        ArchiveMaintenanceJobRecord observed = requireJobForActor(actor, jobId, false);
        return transactions.required(() -> {
            if (executionEnabled && observed.runId() != null) {
                requireExecutionPort().lockIdentityRoot(target(observed));
            }
            requireManager(actor, observed.collectionId(), "job.manage", true);
            // Agent root was acquired first when an active execution exists; archive order is manager -> slot -> job -> run -> grant.
            store.lockSlot(observed.collectionId(), ROLE);
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor, jobId, true);
            ArchiveMaintenanceStore.Operation op = operation(actor, key, "POST",
                    "/archive/admin/v1/jobs/" + jobId + "/cancel",
                    digest(expectedRevision + ":" + sha(request)), "JOB", jobId);
            if (!op.created()) return jobDto(job);
            if (job.revision() != expectedRevision) revisionConflict(job.revision());
            if ("PUBLISHED".equals(job.state()) || job.publicationId() != null
                    || "CANCELLED".equals(job.state())) {
                conflict("ARCHIVE_JOB_NOT_MUTABLE", "Published or cancelled jobs cannot be cancelled");
            }
            if ((job.runId() != null && store.fenceRun(job.runId()) != 1)
                    || store.updateJobState(jobId, job.revision(), "CANCELLED", "USER_CANCELLED", null) != 1) {
                conflict("ARCHIVE_JOB_CHANGED", "Archive execution changed during cancellation");
            }
            store.appendJobEvent(jobId, job.revision() + 1, "JOB_CANCELLED",
                    json(Map.of("reason", request.reason())));
            store.commitOperation(actor, key, jobId);
            return jobDto(requireJobForActor(actor, jobId, false));
        });
    }

    @Override
    public ArchivePublicationDTO publish(ArchiveActorScope actor, String jobId, String key,
            long expectedDraftRevision, ArchivePublishRequest request) {
        requireScope(actor);
        requireKey(key);
        Objects.requireNonNull(request, "request");
        String path = "/archive/admin/v1/jobs/" + jobId + "/publish";
        String requestSha = publicationRequestSha(expectedDraftRevision, request);
        ArchivePublicationDTO replay = transactions.required(() -> {
            ArchiveMaintenanceJobRecord observed = requireJobForActor(actor, jobId, false);
            requireManager(actor, observed.collectionId(), "publish", true);
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor, jobId, true);
            return committedPublicationReplay(actor, key, path, requestSha, job.jobId());
        });
        if (replay != null) return verifyPublicationSafely(replay);

        ArchiveMaintenanceJobRecord candidateJob = requireJobForActor(actor, jobId, false);
        requireManager(actor, candidateJob.collectionId(), "publish", false);
        PublicationCandidate candidate = preparePublicationCandidate(
                candidateJob, expectedDraftRevision, request);
        ArchivePublicationDTO committed = transactions.required(() -> {
            ArchiveManagerGrantRecord manager = requireManager(actor, candidateJob.collectionId(),
                    "publish", true);
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor, jobId, true);
            return publishLocked(actor, job, manager, null, key, expectedDraftRevision,
                    request, path, null, candidate, null);
        });
        return verifyPublicationSafely(committed);
    }

    private ArchivePublicationDTO publishLocked(ArchiveActorScope actor,
            ArchiveMaintenanceJobRecord job, ArchiveManagerGrantRecord manager,
            ArchiveAppointmentRecord appointment, String key, long expectedDraftRevision,
            ArchivePublishRequest request, String path, RuntimeAuthorization runtimeAuthorization,
            PublicationCandidate candidate, AdminOperationSpec adminOperation) {
        ArchiveMaintenanceStore.Operation op = operation(actor, key, "POST", path,
                publicationRequestSha(expectedDraftRevision, request),
                "PUBLICATION", newId("pub"));
        ArchiveAdminOperationRecord adminReceipt = adminOperation == null ? null
                : ensureAdminReceipt(actor, op, key, job, adminOperation.draft(),
                        adminOperation.action(), manager.revision());
        if (!op.created()) {
            if (adminReceipt != null) return blockSnapshot(adminReceipt, ArchivePublicationDTO.class);
            return publicationForOperation(op, job.jobId());
        }
        if ("PUBLISHED".equals(job.state()) || "CANCELLED".equals(job.state())
                || job.publicationId() != null) {
            throw error(422, "ARCHIVE_JOB_NOT_MUTABLE",
                    "Published or cancelled jobs cannot be published");
        }
        if (runtimeAuthorization != null) {
            if (!"AUTO".equals(job.publicationMode())
                    || !"PUBLISH_VALIDATED".equals(job.permissionProfile())) {
                forbidden("ARCHIVE_ACTION_FORBIDDEN",
                        "Native publication requires persisted AUTO and PUBLISH_VALIDATED authority");
            }
            requireProducerRunning(runtimeAuthorization.run());
            if (!"ACTIVE".equals(runtimeAuthorization.grant().state())
                    || !"AWAITING_PUBLISH".equals(job.state())) {
                conflict("ARCHIVE_EXECUTION_NOT_RUNNING",
                        "Archive execution is not eligible for native publication");
            }
        }
        if (runtimeAuthorization != null) requireCurrentBinding(appointment);
        requirePublicationCandidate(job, expectedDraftRevision, request, candidate);
        ArchiveDraftRecord draft = candidate.draft();
        ArchiveMaintenanceStore.CollectionWork cw = store.lockCollectionWork(
                job.collectionId(), job.workId());
        long expectedWork = parseNonNegative(request.expectedWorkRevision(), "expectedWorkRevision");
        ArchiveWorkRecord work = content.lockWork(job.workId());
        boolean createdWork = work == null;
        if (createdWork) {
            if (expectedWork != 0 || request.expectedActiveEditionId() != null) {
                conflict("ACTIVE_EDITION_CHANGED", "Work state changed");
            }
            content.insertWork(new ArchiveWorkRecord(job.workId(), job.title(), null));
            store.insertCollectionWork(job.collectionId(), job.workId(), job.canonicalKey());
            cw = store.lockCollectionWork(job.collectionId(), job.workId());
            work = content.lockWork(job.workId());
        }
        if (cw == null || (!createdWork && cw.revision() != expectedWork)
                || work == null
                || !Objects.equals(work.activeEditionId(), request.expectedActiveEditionId())) {
            conflict("ACTIVE_EDITION_CHANGED", "Active archive edition changed");
        }
        PublicationMaterial material = candidate.material();
        String editionId = material.edition().editionId();
        content.insertEdition(material.edition());
        material.blocks().forEach(content::insertBlock);
        material.paragraphs().forEach(content::insertParagraph);
        if (content.markReady(editionId) != 1) {
            conflict("ARCHIVE_PUBLICATION_CONFLICT", "Edition could not become ready");
        }
        String publicationId = op.targetId();
        ArchivePublicationRecord publication = new ArchivePublicationRecord(publicationId,
                job.jobId(), job.collectionId(), job.workId(), editionId, draft.revision(),
                material.edition().manifestSha256(), job.sourceSha256(), "PUBLISHED",
                runtimeAuthorization == null ? "HUMAN" : "AGENT",
                runtimeAuthorization == null ? actor.ownerJiacn() : job.agentId(), manager.revision());
        store.insertPublication(publication);
        store.insertPublicationReadback(new ArchivePublicationReadbackRecord(publicationId,
                "PENDING", 1, null, "[]", null));
        if (content.switchActiveEdition(job.workId(), editionId) != 1
                || content.markActivated(editionId) != 1
                || store.bumpCollectionWork(job.collectionId(), job.workId(), cw.revision()) != 1) {
            conflict("ACTIVE_EDITION_CHANGED", "Active archive edition changed");
        }
        if (store.updateDraft(draft.draftId(), draft.revision(), draft.revision(),
                "SEALED", draft.contentJson(), draft.contentSha256(),
                draft.validatedRevision(), draft.validationId()) != 1) {
            revisionConflict(draft.revision());
        }
        if (store.updateJobState(job.jobId(), job.revision(), "PUBLISHED", null, publicationId) != 1) {
            conflict("ARCHIVE_JOB_CHANGED", "Archive job changed");
        }
        if (runtimeAuthorization != null
                && (store.completeRun(runtimeAuthorization.run().runId(),
                        runtimeAuthorization.run().revision()) != 1
                || store.releaseExecutionGrant(runtimeAuthorization.run().runId(),
                        runtimeAuthorization.grant().revision()) != 1)) {
            conflict("ARCHIVE_EXECUTION_CHANGED",
                    "Archive execution changed during publication completion");
        }
        store.appendJobEvent(job.jobId(), job.revision() + 1, "PUBLICATION_COMMITTED",
                json(Map.of("publicationId", publicationId, "editionId", editionId)));
        if (runtimeAuthorization != null) {
            store.appendJobEvent(job.jobId(), job.revision() + 1, "EXECUTION_COMPLETED",
                    json(Map.of("runId", job.runId(), "publicationId", publicationId,
                            "stage", "PUBLISHED")));
        }
        ArchivePublicationDTO result = publicationDto(publication);
        store.commitOperation(actor, key, publicationId);
        if (adminReceipt != null) {
            if (store.commitAdminOperation(adminReceipt.operationId(), json(result)) != 1) {
                throw new IllegalStateException("Archive admin publication receipt commit failed");
            }
        }
        return result;
    }

    private PublicationCandidate preparePublicationCandidate(ArchiveMaintenanceJobRecord job,
            long expectedDraftRevision, ArchivePublishRequest request) {
        ArchiveDraftRecord draft = requireDraft(job.jobId(), false);
        if (draft.revision() != expectedDraftRevision) revisionConflict(draft.revision());
        ArchiveValidationRecord validation = requirePublicationValidation(draft, request);
        ArchiveSourceSnapshotRecord source = requireSourceRecord(job);
        byte[] sourceBytes = readSource(source);
        ArchiveDraftUpdateRequest body = parseDraft(draft.contentJson());
        List<String> findings = validateContent(body);
        findings.addAll(ArchiveSourceMappingValidator.validate(sourceBytes, body));
        if (!findings.isEmpty()) {
            throw error(422, "CONTENT_VALIDATION_FAILED", String.join("; ", findings));
        }
        return new PublicationCandidate(job, source, draft, validation,
                material(job, body, newId("aed")));
    }

    private void requirePublicationCandidate(ArchiveMaintenanceJobRecord job,
            long expectedDraftRevision, ArchivePublishRequest request,
            PublicationCandidate candidate) {
        if (candidate == null || !candidate.job().equals(job)) {
            conflict("ARCHIVE_JOB_CHANGED", "Archive job changed while publication was prepared");
        }
        ArchiveSourceSnapshotRecord source = requireSourceRecord(job);
        if (!candidate.source().equals(source)) {
            throw error(422, "CONTENT_VALIDATION_FAILED",
                    "Source snapshot changed while publication was prepared");
        }
        ArchiveDraftRecord draft = requireDraft(job.jobId(), true);
        if (draft.revision() != expectedDraftRevision || !candidate.draft().equals(draft)) {
            revisionConflict(draft.revision());
        }
        ArchiveValidationRecord validation = requirePublicationValidation(draft, request);
        if (!candidate.validation().equals(validation)) {
            conflict("ARCHIVE_VALIDATION_REQUIRED",
                    "Archive validation changed while publication was prepared");
        }
    }

    private ArchiveValidationRecord requirePublicationValidation(ArchiveDraftRecord draft,
            ArchivePublishRequest request) {
        ArchiveValidationRecord validation = store.findValidation(request.validationId());
        if (validation == null || !"PASSED".equals(validation.outcome())
                || !validation.draftId().equals(draft.draftId())
                || validation.draftRevision() != draft.revision()
                || draft.validatedRevision() == null
                || draft.validatedRevision() != draft.revision()
                || !request.validationId().equals(draft.validationId())) {
            throw error(409, "ARCHIVE_VALIDATION_REQUIRED",
                    "The exact draft revision must be validated before publication");
        }
        return validation;
    }

    private ArchivePublicationDTO committedPublicationReplay(ArchiveActorScope actor, String key,
            String path, String requestSha, String jobId) {
        ArchiveMaintenanceStore.Operation existing = store.findOperation(actor, key);
        if (existing == null) return null;
        assertOperationMatches(existing, "POST", path, requestSha, "PUBLICATION");
        if (!"COMMITTED".equals(existing.state())) {
            conflict("ARCHIVE_OPERATION_IN_PROGRESS", "Archive operation is not committed");
        }
        return publicationForOperation(existing, jobId);
    }

    private ArchivePublicationDTO publicationForOperation(ArchiveMaintenanceStore.Operation operation,
            String jobId) {
        ArchivePublicationRecord existing = store.findPublicationByJob(jobId);
        if (existing == null || !existing.publicationId().equals(operation.targetId())) {
            conflict("ARCHIVE_OPERATION_IN_PROGRESS", "Publication state is not yet committed");
        }
        return publicationDto(existing);
    }

    private void verifyPublicationForOperation(ArchiveActorScope actor, String key) {
        ArchiveMaintenanceStore.Operation operation = store.findOperation(actor, key);
        if (operation != null && "COMMITTED".equals(operation.state())
                && "PUBLICATION".equals(operation.targetType())) {
            try { verifyPublication(operation.targetId()); }
            catch (RuntimeException ignored) { /* publication commit must not be reclassified */ }
        }
    }

    private ArchivePublicationDTO verifyPublicationSafely(ArchivePublicationDTO committed) {
        try { return verifyPublication(committed.publicationId()); }
        catch (RuntimeException ignored) { return committed; }
    }

    private ArchivePublicationDTO verifyPublication(String publicationId) {
        ArchivePublicationRecord publication = store.findPublicationById(publicationId);
        if (publication == null) {
            throw new IllegalStateException("Committed archive publication is unavailable");
        }
        ArchivePublicationReadbackRecord observed = store.findPublicationReadback(publicationId);
        if (observed == null) {
            throw new IllegalStateException("Committed archive publication readback is unavailable");
        }
        if ("PASSED".equals(observed.state())) return publicationDto(publication);
        ReadbackOutcome outcome = inspectPublishedRepresentation(publication);
        String findingsJson = json(outcome.findings());
        transactions.required(() -> {
            // Reader I/O remains outside the transaction. Job-backed publications re-enter
            // through the job root before publication/readback so event/outbox creation cannot
            // reverse aggregate locks. Bootstrap publications have no job and emit no chat fact.
            ArchiveMaintenanceJobRecord job = publication.jobId() == null ? null
                    : store.findJob(publication.jobId(), true);
            ArchivePublicationRecord currentPublication = store.findPublicationById(publicationId, true);
            ArchivePublicationReadbackRecord current = store.findPublicationReadback(publicationId, true);
            if ((publication.jobId() != null && job == null)
                    || currentPublication == null || current == null
                    || (job != null && (!same(job.publicationId(), publicationId)
                            || !same(job.collectionId(), currentPublication.collectionId())
                            || !same(job.workId(), currentPublication.workId())))
                    || !samePublicationReadbackIdentity(currentPublication, publication)) {
                throw new IllegalStateException("Archive publication identity changed during readback verification");
            }
            if (!sameReadbackOutcome(current, outcome, findingsJson)) {
                if (store.completePublicationReadback(publicationId, current.revision(), outcome.state(),
                        outcome.digest(), findingsJson) != 1) {
                    throw new IllegalStateException("Archive publication readback changed during verification");
                }
                if (job != null) {
                    store.appendJobEvent(job.jobId(), job.revision(), "PUBLICATION_READBACK_COMPLETED",
                            json(Map.of("publicationId", publicationId,
                                    "state", outcome.state(),
                                    "readbackRevision", Long.toString(current.revision() + 1),
                                    "verificationDigest", outcome.digest())));
                }
            }
            return null;
        });
        return publicationDto(publication);
    }

    private boolean sameReadbackOutcome(ArchivePublicationReadbackRecord current,
            ReadbackOutcome outcome, String findingsJson) {
        return same(current.state(), outcome.state())
                && same(current.verificationDigest(), outcome.digest())
                && same(current.findingsJson(), findingsJson);
    }

    private boolean samePublicationReadbackIdentity(ArchivePublicationRecord current,
            ArchivePublicationRecord observed) {
        return same(current.publicationId(), observed.publicationId())
                && same(current.jobId(), observed.jobId())
                && same(current.collectionId(), observed.collectionId())
                && same(current.workId(), observed.workId())
                && same(current.editionId(), observed.editionId())
                && current.draftRevision() == observed.draftRevision()
                && same(current.manifestSha256(), observed.manifestSha256())
                && same(current.sourceSha256(), observed.sourceSha256())
                && same(current.actorType(), observed.actorType())
                && same(current.actorId(), observed.actorId())
                && current.authorizationRevision() == observed.authorizationRevision();
    }

    private ReadbackOutcome inspectPublishedRepresentation(ArchivePublicationRecord publication) {
        List<String> findings = new ArrayList<>();
        LinkedHashMap<String, Object> material = new LinkedHashMap<>();
        material.put("publicationId", publication.publicationId());
        material.put("workId", publication.workId());
        material.put("editionId", publication.editionId());
        try {
            ArchiveRepresentation<ArchiveCatalogDTO> catalogRepresentation =
                    reader.editionCatalog(publication.editionId());
            ArchiveCatalogDTO catalog = catalogRepresentation.data();
            ArchiveActiveEditionDTO active = catalog.activeEdition();
            if (!same(catalog.workId(), publication.workId())) findings.add("WORK_ID_MISMATCH");
            if (!same(catalogRepresentation.etag(),
                    ArchiveEtags.catalog(publication.manifestSha256()))) {
                findings.add("CATALOG_DIGEST_MISMATCH");
            }
            if (active == null || !same(active.editionId(), publication.editionId())) {
                findings.add("EDITION_ID_MISMATCH");
            } else {
                if (!same(active.manifestSha256(), publication.manifestSha256())) {
                    findings.add("MANIFEST_SHA256_MISMATCH");
                }
                if (!same(active.sourceSha256(), publication.sourceSha256())) {
                    findings.add("SOURCE_SHA256_MISMATCH");
                }
                material.put("catalog", catalog);
                material.put("catalogEtag", catalogRepresentation.etag());
                ArrayList<Object> blocks = new ArrayList<>();
                long prefaceParagraphs = 0;
                long chapterParagraphs = 0;
                long bytes = 0;
                if (active.preface() != null) {
                    ArchiveRepresentation<ArchiveBlockDTO> block = reader.preface(publication.editionId());
                    prefaceParagraphs += verifyReadbackBlock(publication.editionId(), active.manifestSha256(), active.preface(), block, findings, blocks);
                    bytes += block.data().utf8ByteLength();
                }
                for (ArchiveBlockSummaryDTO summary : active.chapters()) {
                    ArchiveRepresentation<ArchiveBlockDTO> block = reader.chapter(
                            publication.editionId(), summary.blockId());
                    chapterParagraphs += verifyReadbackBlock(publication.editionId(), active.manifestSha256(), summary, block, findings, blocks);
                    bytes += block.data().utf8ByteLength();
                }
                if (prefaceParagraphs != active.prefaceParagraphCount()) {
                    findings.add("PREFACE_PARAGRAPH_COUNT_MISMATCH");
                }
                if (chapterParagraphs != active.chapterParagraphCount()) {
                    findings.add("CHAPTER_PARAGRAPH_COUNT_MISMATCH");
                }
                if (prefaceParagraphs + chapterParagraphs != active.readerParagraphCount()) {
                    findings.add("READER_PARAGRAPH_COUNT_MISMATCH");
                }
                if (bytes != active.readerUtf8ByteLength()) {
                    findings.add("READER_UTF8_LENGTH_MISMATCH");
                }
                material.put("blocks", blocks);
            }
        } catch (RuntimeException failure) {
            findings.add("READER_READ_FAILED");
            material.put("readerFailure", failure.getClass().getSimpleName());
        }
        String verificationDigest = digest(json(material));
        return new ReadbackOutcome(findings.isEmpty() ? "PASSED" : "FAILED",
                verificationDigest, List.copyOf(findings));
    }

    private long verifyReadbackBlock(String editionId, String manifestSha256,
            ArchiveBlockSummaryDTO summary, ArchiveRepresentation<ArchiveBlockDTO> representation,
            List<String> findings, List<Object> material) {
        ArchiveBlockDTO block = representation.data();
        if (!same(block.editionId(), editionId)) {
            findings.add("BLOCK_EDITION_MISMATCH");
        }
        if (!same(block.manifestSha256(), manifestSha256)) {
            findings.add("BLOCK_MANIFEST_MISMATCH");
        }
        if (!same(summary.blockId(), block.blockId())
                || !same(summary.blockType(), block.blockType())
                || !Objects.equals(summary.number(), block.number())
                || !Objects.equals(summary.title(), block.title())) {
            findings.add("BLOCK_IDENTITY_MISMATCH");
        }
        if (summary.paragraphCount() != block.paragraphCount()
                || summary.utf8ByteLength() != block.utf8ByteLength()) {
            findings.add("BLOCK_COUNT_MISMATCH");
        }
        if (!same(summary.etag(), representation.etag())) findings.add("BLOCK_DIGEST_MISMATCH");
        long utf8Bytes = 0;
        List<String> paragraphDigests = new ArrayList<>();
        for (int i = 0; i < block.paragraphs().size(); i++) {
            ArchiveParagraphDTO paragraph = block.paragraphs().get(i);
            byte[] bytes = paragraph.text().getBytes(StandardCharsets.UTF_8);
            utf8Bytes += bytes.length;
            String actualDigest = digestBytes(bytes);
            paragraphDigests.add(actualDigest);
            if (paragraph.ordinal() != i + 1 || paragraph.utf8ByteLength() != bytes.length
                    || !same(paragraph.sha256(), actualDigest)) {
                findings.add("PARAGRAPH_DIGEST_MISMATCH");
            }
        }
        String actualBlockEtag = ArchiveEtags.blockFromDigest(digest(
                manifestSha256 + ":" + block.blockId() + ":" + paragraphDigests));
        if (!same(summary.etag(), actualBlockEtag)) findings.add("BLOCK_DIGEST_MISMATCH");
        if (block.paragraphs().size() != block.paragraphCount()
                || utf8Bytes != block.utf8ByteLength()) {
            findings.add("BLOCK_CONTENT_LENGTH_MISMATCH");
        }
        material.add(Map.of("summary", summary, "etag", representation.etag(), "block", block));
        return block.paragraphs().size();
    }

    private String publicationRequestSha(long expectedDraftRevision, ArchivePublishRequest request) {
        return digest(expectedDraftRevision + ":" + sha(request));
    }

    @Override
    public ArchiveRuntimeResultDTO runtimeStart(ArchiveRuntimeScope runtime, String jobId, String runId,
            ArchiveRuntimeStartRequest request) {
        requireRuntime(runtime);
        exactId(jobId);
        exactId(runId);
        validateStart(runtime, request);
        ArchiveMaintenanceJobRecord observed = requireJob(jobId, false);
        if (observed.runId() == null || !same(observed.runId(), runId)) notFound();
        requireRuntimeJobScope(runtime, observed);
        return transactions.required(() -> {
            ArchiveAgentExecutionPort.LockedTarget lockedTarget = lockControlledTarget(target(observed));
            ArchiveManagerGrantRecord manager = requireManager(actor(runtime), observed.collectionId(), "draft.write", true);
            requireManagerRevision(manager, observed);
            ArchiveAppointmentRecord appointment = requireCurrentAppointmentForJob(observed, true);
            ArchiveMaintenanceJobRecord job = requireJob(jobId, true);
            requireManagerRevision(manager, job);
            RuntimeAuthorization authorization = authorizeRuntimeLocked(runtime, job, appointment, lockedTarget, true);
            if (!request.messageId().equals(authorization.inspection().activeMessageId())) {
                throw error(403, "EXECUTION_FENCED", "Archive command message is not current");
            }
            ArchiveJobRunRecord run = authorization.run();
            if (!"AUTHORIZED".equals(run.state())) {
                if (same(run.startedMessageId(), request.messageId())) {
                    return runtimeResultDto(job, run, authorization.grant());
                }
                conflict("ARCHIVE_EXECUTION_START_CONFLICT", "Archive execution start does not match the accepted claim");
            }
            if (!"EXECUTION_REQUESTED".equals(job.state())) {
                conflict("ARCHIVE_EXECUTION_CHANGED", "Archive job is not awaiting a start claim");
            }
            if (store.startRun(run.runId(), run.revision(), request.messageId()) != 1
                    || store.updateJobState(job.jobId(), job.revision(), "RUNNING", null, null) != 1) {
                conflict("ARCHIVE_EXECUTION_CHANGED", "Archive execution changed during start");
            }
            ArchiveJobRunRecord started = runState(run, "RUNNING", run.revision() + 1,
                    request.messageId(), null, null, null);
            ArchiveMaintenanceJobRecord running = jobState(job, "RUNNING", null, job.revision() + 1);
            store.appendJobEvent(job.jobId(), running.revision(), "EXECUTION_STARTED",
                    json(Map.of("commandId", runtime.commandId(), "messageId", request.messageId(),
                            "attempt", Long.toString(runtime.activeAttempt()),
                            "executionEpoch", Long.toString(runtime.executionEpoch()))));
            return runtimeResultDto(running, started, authorization.grant());
        });
    }

    @Override
    public ArchiveRuntimeResultDTO runtimeFailure(ArchiveRuntimeScope runtime, String jobId, String runId,
            ArchiveRuntimeFailureRequest request) {
        requireRuntime(runtime);
        exactId(jobId);
        exactId(runId);
        validateFailure(request);
        ArchiveMaintenanceJobRecord observed = requireJob(jobId, false);
        requireExecutionCandidate(observed);
        if (!same(observed.runId(), runId)) notFound();
        requireRuntimeJobScope(runtime, observed);
        return transactions.required(() -> {
            ArchiveAgentExecutionPort.LockedTarget lockedTarget = lockControlledTarget(target(observed));
            ArchiveManagerGrantRecord manager = requireManager(actor(runtime), observed.collectionId(), "draft.write", true);
            requireManagerRevision(manager, observed);
            ArchiveAppointmentRecord appointment = requireCurrentAppointmentForJob(observed, true);
            ArchiveMaintenanceJobRecord job = requireJob(jobId, true);
            requireManagerRevision(manager, job);
            RuntimeAuthorization authorization = authorizeRuntimeLocked(runtime, job, appointment, lockedTarget, true);
            ArchiveJobRunRecord run = authorization.run();
            if ("FAILED".equals(run.state())) {
                if (same(run.failurePhase(), request.phase()) && same(run.failureCode(), request.code())
                        && same(run.failureRetryable(), request.retryable())) {
                    return runtimeResultDto(job, run, authorization.grant());
                }
                conflict("ARCHIVE_EXECUTION_FAILURE_CONFLICT", "Archive failure does not match the accepted outcome");
            }
            if (!"RUNNING".equals(run.state()) || !"ACTIVE".equals(authorization.grant().state())) {
                conflict("ARCHIVE_EXECUTION_NOT_RUNNING", "Archive execution has not started or is already terminal");
            }
            if (store.failRun(run.runId(), run.revision(), request.phase(), request.code(), request.retryable()) != 1
                    || store.releaseExecutionGrant(run.runId(), authorization.grant().revision()) != 1
                    || store.updateJobState(job.jobId(), job.revision(), "FAILED", request.code(), null) != 1) {
                conflict("ARCHIVE_EXECUTION_CHANGED", "Archive execution changed during failure recording");
            }
            ArchiveJobRunRecord failed = runState(run, "FAILED", run.revision() + 1,
                    run.startedMessageId(), request.phase(), request.code(), request.retryable());
            ArchiveMaintenanceJobRecord failedJob = jobState(job, "FAILED", request.code(), job.revision() + 1);
            store.appendJobEvent(job.jobId(), failedJob.revision(), "JOB_FAILED",
                    json(Map.of("phase", request.phase(), "code", request.code(),
                            "retryable", request.retryable().toString())));
            return runtimeResultDto(failedJob, failed, authorization.grant());
        });
    }

    @Override
    public ArchiveRuntimeResultDTO runtimeResult(ArchiveRuntimeScope runtime, String jobId, String runId) {
        requireRuntime(runtime);
        exactId(jobId);
        exactId(runId);
        ArchiveMaintenanceJobRecord observed = requireJob(jobId, false);
        requireExecutionCandidate(observed);
        if (!same(observed.runId(), runId)) notFound();
        requireRuntimeJobScope(runtime, observed);
        return transactions.required(() -> {
            ArchiveAgentExecutionPort.LockedTarget lockedTarget = lockControlledTarget(target(observed));
            ArchiveManagerGrantRecord manager = requireManager(actor(runtime), observed.collectionId(), "draft.write", true);
            requireManagerRevision(manager, observed);
            ArchiveAppointmentRecord appointment = requireCurrentAppointmentForJob(observed, true);
            ArchiveMaintenanceJobRecord job = requireJob(jobId, true);
            requireManagerRevision(manager, job);
            RuntimeAuthorization authorization = authorizeRuntimeLocked(runtime, job, appointment, lockedTarget, true);
            return runtimeResultDto(job, authorization.run(), authorization.grant());
        });
    }

    @Override
    public ArchiveRuntimeContextDTO runtimeContext(ArchiveRuntimeScope runtime, String jobId, String runId) {
        requireRuntime(runtime);
        exactId(jobId);
        exactId(runId);
        ArchiveMaintenanceJobRecord observed = requireJob(jobId, false);
        requireExecutionCandidate(observed);
        if (!same(observed.runId(), runId)) notFound();
        requireRuntimeJobScope(runtime, observed);
        return transactions.required(() -> {
            ArchiveAgentExecutionPort.LockedTarget lockedTarget = lockControlledTarget(target(observed));
            ArchiveManagerGrantRecord manager = requireManager(actor(runtime), observed.collectionId(),
                    "draft.write", true);
            requireManagerRevision(manager, observed);
            ArchiveAppointmentRecord appointment = requireCurrentAppointmentForJob(observed, true);
            ArchiveMaintenanceJobRecord job = requireJob(jobId, true);
            requireManagerRevision(manager, job);
            authorizeRuntimeLocked(runtime, job, appointment, lockedTarget, false);
            ArchiveDraftRecord draft = requireDraft(jobId, false);
            ArchiveMaintenanceStore.CollectionWork collectionWork =
                    store.findCollectionWork(job.collectionId(), job.workId());
            ArchiveWorkRecord work = content.findWork(job.workId());
            String expectedWorkRevision;
            String expectedActiveEditionId;
            if (collectionWork == null && work == null && "ADD_WORK".equals(job.operation())) {
                expectedWorkRevision = "0";
                expectedActiveEditionId = null;
            } else {
                if (collectionWork == null || work == null
                        || !job.collectionId().equals(collectionWork.collectionId())
                        || !job.workId().equals(collectionWork.workId())
                        || !job.workId().equals(work.workId())) {
                    conflict("ACTIVE_EDITION_CHANGED",
                            "Archive work state is unavailable for the exact maintenance job");
                }
                expectedWorkRevision = Long.toString(collectionWork.revision());
                expectedActiveEditionId = work.activeEditionId();
            }
            return new ArchiveRuntimeContextDTO(jobId, runId, job.collectionId(),
                    job.workId(), job.operation(), expectedWorkRevision, expectedActiveEditionId,
                    appointment.appointmentId(), Long.toString(appointment.revision()),
                    appointment.agentId(), appointment.bindingVersion(), appointment.permissionProfile(),
                    job.publicationMode(), job.state(), job.waitReason(),
                    new ArchiveSkillRef(appointment.requiredSkillKey(), appointment.requiredSkillVersion(),
                            appointment.requiredSkillSha256()),
                    job.sourceId(), job.sourceSha256(), job.sourceSummary(), job.rightsBasis(), draft.draftId(),
                    Long.toString(draft.revision()));
        });
    }

    @Override
    public ArchiveDraftDTO runtimeDraft(ArchiveRuntimeScope runtime, String jobId, String runId) {
        authorizeRuntime(runtime, jobId, runId);
        return draftDto(requireDraft(jobId, false));
    }

    @Override
    public ArchiveDraftDTO runtimePutBlock(ArchiveRuntimeScope runtime, String jobId, String runId,
            String blockKey, String key, long expectedRevision, ArchiveDraftUpdateRequest request) {
        ArchiveMaintenanceJobRecord job = authorizeRuntime(runtime, jobId, runId);
        if (request == null || request.blocks().size() != 1
                || !Objects.equals(blockKey, request.blocks().getFirst().blockKey())) {
            invalid("Native block request must contain exactly the path-selected block");
        }
        return updateRuntimeBlockAuthorized(runtime, job, blockKey, key, expectedRevision, request);
    }

    @Override
    public ArchiveOperationAcceptedDTO runtimeValidate(ArchiveRuntimeScope runtime, String jobId,
            String runId, String key, long expectedDraftRevision) {
        ArchiveMaintenanceJobRecord job = authorizeRuntime(runtime, jobId, runId, true);
        ArchiveValidationDTO validation = validateAuthorized(actor(runtime), job, key,
                expectedDraftRevision, true, runtime);
        return new ArchiveOperationAcceptedDTO(validation.validationId(), jobId, "COMMITTED");
    }

    @Override
    public ArchiveValidationDTO runtimeValidation(ArchiveRuntimeScope runtime, String jobId,
            String runId, String operationId) {
        ArchiveMaintenanceJobRecord job = authorizeRuntime(runtime, jobId, runId, true);
        ArchiveDraftRecord draft = requireDraft(jobId, false);
        ArchiveValidationRecord validation;
        if (operationId == null) {
            validation = store.findLatestValidation(draft.draftId(), draft.revision());
        } else {
            exactId(operationId);
            ArchiveMaintenanceStore.TargetOperation receipt = store.findOperationByTarget(
                    actor(runtime), "VALIDATION", operationId);
            String expectedPath = "/internal/archive/v1/jobs/" + jobId + "/runs/" + runId + "/validate";
            if (receipt == null || !"POST".equals(receipt.httpMethod())
                    || !expectedPath.equals(receipt.canonicalPath())
                    || !"COMMITTED".equals(receipt.state())) {
                notFound();
            }
            validation = store.findValidation(operationId);
        }
        if (validation == null || !draft.draftId().equals(validation.draftId())) notFound();
        return validationDto(validation);
    }

    @Override
    public ArchivePublicationDTO runtimePublish(ArchiveRuntimeScope runtime, String jobId, String runId,
            String key, long expectedDraftRevision, ArchivePublishRequest request) {
        requireRuntime(runtime);
        exactId(jobId);
        exactId(runId);
        requireKey(key);
        Objects.requireNonNull(request, "request");
        String path = "/internal/archive/v1/jobs/" + jobId + "/runs/" + runId + "/publish";
        String requestSha = publicationRequestSha(expectedDraftRevision, request);
        ArchivePublicationDTO replay = transactions.required(() -> {
            ArchiveMaintenanceJobRecord observed = requireJob(jobId, false);
            requireExecutionCandidate(observed);
            if (!same(observed.runId(), runId)) notFound();
            requireRuntimeJobScope(runtime, observed);
            ArchiveAgentExecutionPort.LockedTarget lockedTarget = lockControlledTarget(target(observed));
            ArchiveManagerGrantRecord manager = requireManager(actor(runtime), observed.collectionId(),
                    "publish", true);
            requireManagerRevision(manager, observed);
            ArchiveAppointmentRecord appointment = requireCurrentAppointmentForJob(observed, true);
            ArchiveMaintenanceJobRecord job = requireJob(jobId, true);
            requireManagerRevision(manager, job);
            authorizeRuntimeLocked(runtime, job, appointment, lockedTarget, true);
            return committedPublicationReplay(actor(runtime), key, path, requestSha, job.jobId());
        });
        if (replay != null) return verifyPublicationSafely(replay);

        ArchiveMaintenanceJobRecord candidateJob = requireJob(jobId, false);
        requireExecutionCandidate(candidateJob);
        if (!same(candidateJob.runId(), runId)) notFound();
        requireRuntimeJobScope(runtime, candidateJob);
        PublicationCandidate candidate = preparePublicationCandidate(
                candidateJob, expectedDraftRevision, request);
        ArchivePublicationDTO committed = transactions.required(() -> {
            ArchiveAgentExecutionPort.LockedTarget lockedTarget = lockControlledTarget(target(candidateJob));
            ArchiveManagerGrantRecord manager = requireManager(actor(runtime), candidateJob.collectionId(),
                    "publish", true);
            requireManagerRevision(manager, candidateJob);
            ArchiveAppointmentRecord appointment = requireCurrentAppointmentForJob(candidateJob, true);
            ArchiveMaintenanceJobRecord job = requireJob(jobId, true);
            requireManagerRevision(manager, job);
            RuntimeAuthorization authorization = authorizeRuntimeLocked(
                    runtime, job, appointment, lockedTarget, true);
            return publishLocked(actor(runtime), job, manager, appointment, key,
                    expectedDraftRevision, request, path, authorization, candidate, null);
        });
        return verifyPublicationSafely(committed);
    }

    private ArchiveDraftDTO updateRuntimeBlockAuthorized(ArchiveRuntimeScope runtime,
            ArchiveMaintenanceJobRecord observed, String blockKey, String key,
            long expectedRevision, ArchiveDraftUpdateRequest request) {
        requireKey(key);
        String requestSha = digest(expectedRevision + ":" + sha(request));
        return transactions.required(() -> {
            ArchiveAgentExecutionPort.LockedTarget lockedTarget = lockControlledTarget(target(observed));
            ArchiveManagerGrantRecord manager = requireManager(actor(runtime), observed.collectionId(),
                    "draft.write", true);
            requireManagerRevision(manager, observed);
            ArchiveAppointmentRecord appointment = requireCurrentAppointmentForJob(observed, true);
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor(runtime), observed.jobId(), true);
            requireManagerRevision(manager, job);
            RuntimeAuthorization authorization = authorizeRuntimeLocked(
                    runtime, job, appointment, lockedTarget);
            requireProducerRunning(authorization.run());
            requireRuntimeWritable(job);
            ArchiveDraftRecord draft = requireDraft(job.jobId(), true);
            requireDraftMutable(job, draft);
            String path = "/internal/archive/v1/jobs/" + job.jobId() + "/runs/" + job.runId()
                    + "/blocks/" + blockKey;
            ArchiveMaintenanceStore.Operation op = operation(actor(runtime), key, "PUT", path,
                    requestSha, "DRAFT", draft.draftId());
            if (!same(op.targetId(), draft.draftId())) {
                conflict("IDEMPOTENCY_CONFLICT", "Native block receipt resource changed");
            }
            if (!op.created()) return draftDto(requireDraft(job.jobId(), false));
            if (draft.revision() != expectedRevision) revisionConflict(draft.revision());

            ArchiveDraftUpdateRequest current = parseDraft(draft.contentJson());
            ArrayList<ArchiveDraftBlockInput> blocks = new ArrayList<>(current.blocks());
            blocks.removeIf(block -> blockKey.equals(block.blockKey()));
            blocks.add(request.blocks().getFirst());
            blocks.sort(Comparator.comparingInt(block -> block.ordinal() == null
                    ? Integer.MAX_VALUE : block.ordinal()));
            ArchiveDraftUpdateRequest merged = new ArchiveDraftUpdateRequest(
                    blocks, request.excludedSourceRanges());
            String json = json(merged);
            long next = draft.revision() + 1;
            if (store.updateDraft(draft.draftId(), expectedRevision, next, "EDITABLE",
                    json, digest(json), null, null) != 1) {
                revisionConflict(draft.revision());
            }
            store.appendJobEvent(job.jobId(), job.revision(), "DRAFT_UPDATED",
                    json(Map.of("draftId", draft.draftId(), "draftRevision", Long.toString(next),
                            "blockKey", blockKey)));
            store.commitOperation(actor(runtime), key, draft.draftId());
            return draftDto(requireDraft(job.jobId(), false));
        });
    }

    private ArchiveDraftDTO updateDraftAuthorized(ArchiveActorScope actor,
            ArchiveMaintenanceJobRecord observed, String key, long expectedRevision,
            ArchiveDraftUpdateRequest request) {
        requireKey(key);
        Objects.requireNonNull(request, "request");
        String requestSha = sha(request);
        return transactions.required(() -> {
            ArchiveManagerGrantRecord manager = requireManager(actor, observed.collectionId(),
                    "draft.write", true);
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor, observed.jobId(), true);
            ArchiveDraftRecord draft = requireDraft(job.jobId(), true);
            requireDraftMutable(job, draft);
            String path = "/archive/admin/v1/jobs/" + job.jobId() + "/draft";
            ArchiveMaintenanceStore.Operation op = operation(actor, key, "PUT", path,
                    requestSha, "DRAFT", draft.draftId());
            if (!op.created()) return draftDto(requireDraft(job.jobId(), false));
            if (draft.revision() != expectedRevision) revisionConflict(draft.revision());
            String json = json(request);
            long next = draft.revision() + 1;
            if (store.updateDraft(draft.draftId(), expectedRevision, next, "EDITABLE",
                    json, digest(json), null, null) != 1) {
                revisionConflict(draft.revision());
            }
            Map<String, String> updateFacts = Map.of("draftId", draft.draftId(),
                    "draftRevision", Long.toString(next));
            store.appendJobEvent(job.jobId(), job.revision(), "DRAFT_UPDATED",
                    humanAudit(actor, manager, updateFacts));
            store.commitOperation(actor, key, draft.draftId());
            return draftDto(requireDraft(job.jobId(), false));
        });
    }

    private ArchiveValidationDTO validateAuthorized(ArchiveActorScope actor,
            ArchiveMaintenanceJobRecord observed, String key, long expectedRevision,
            boolean runtime, ArchiveRuntimeScope runtimeScope) {
        requireKey(key);
        byte[] observedSource = null;
        String observedSourceFailure = null;
        try {
            observedSource = requireSource(observed);
        } catch (ArchiveMaintenanceException failure) {
            observedSourceFailure = failure.getMessage();
        }
        byte[] sourceBytes = observedSource;
        String sourceFailure = observedSourceFailure;
        return transactions.required(() -> {
            ArchiveAgentExecutionPort.LockedTarget lockedTarget = runtime
                    ? lockControlledTarget(target(observed)) : null;
            ArchiveManagerGrantRecord manager = requireManager(actor, observed.collectionId(), "validate", true);
            if (runtime) requireManagerRevision(manager, observed);
            ArchiveAppointmentRecord appointment = runtime
                    ? requireCurrentAppointmentForJob(observed, true) : null;
            ArchiveMaintenanceJobRecord job = requireJobForActor(actor, observed.jobId(), true);
            RuntimeAuthorization authorization = null;
            if (runtime) {
                requireManagerRevision(manager, job);
                authorization = authorizeRuntimeLocked(runtimeScope, job, appointment,
                        lockedTarget, true);
            }
            ArchiveDraftRecord draft = requireDraft(job.jobId(), true);
            String path = runtime
                    ? "/internal/archive/v1/jobs/" + job.jobId() + "/runs/" + job.runId() + "/validate"
                    : "/archive/admin/v1/jobs/" + job.jobId() + "/validate";
            String requestSha = runtime
                    ? digest(path + ":" + draft.draftId() + ":" + expectedRevision)
                    : digest(draft.draftId() + ":" + expectedRevision + ":" + draft.contentSha256());
            ArchiveMaintenanceStore.Operation op = operation(actor, key, "POST", path,
                    requestSha, "VALIDATION", newId("val"));
            if (!op.created()) {
                ArchiveValidationRecord existing = store.findValidation(op.targetId());
                if (existing == null) {
                    conflict("ARCHIVE_OPERATION_IN_PROGRESS", "Validation state is not committed");
                }
                if (!draft.draftId().equals(existing.draftId())
                        || existing.draftRevision() != expectedRevision) {
                    conflict("IDEMPOTENCY_CONFLICT", "Validation receipt scope changed");
                }
                return validationDto(existing);
            }
            if (runtime) {
                if (!"RUNNING".equals(authorization.run().state())
                        || !"ACTIVE".equals(authorization.grant().state())) {
                    throw error(403, "EXECUTION_FENCED",
                            "Only the exact committed validation operation may be replayed after completion");
                }
                requireProducerRunning(authorization.run());
                requireRuntimeWritable(job);
            }
            requireDraftMutable(job, draft);
            if (draft.revision() != expectedRevision) revisionConflict(draft.revision());
            List<String> findings = new ArrayList<>();
            try {
                ArchiveDraftUpdateRequest body = parseDraft(draft.contentJson());
                findings.addAll(validateContent(body));
                if (sourceFailure == null) {
                    findings.addAll(ArchiveSourceMappingValidator.validate(sourceBytes, body));
                } else {
                    findings.add(sourceFailure);
                }
            } catch (ArchiveMaintenanceException failure) {
                findings.add(failure.getMessage());
            }
            String outcome = findings.isEmpty() ? "PASSED" : "FAILED";
            String findingsJson = json(findings);
            ArchiveValidationRecord validation = new ArchiveValidationRecord(op.targetId(),
                    draft.draftId(), draft.revision(), outcome,
                    digest(draft.contentSha256() + ":" + findingsJson), findingsJson);
            store.insertValidation(validation);
            String state = findings.isEmpty() ? "VALIDATED" : "CHANGES_REQUIRED";
            Long validated = findings.isEmpty() ? draft.revision() : null;
            String validationId = findings.isEmpty() ? validation.validationId() : null;
            if (store.updateDraft(draft.draftId(), draft.revision(), draft.revision(), state,
                    draft.contentJson(), draft.contentSha256(), validated, validationId) != 1) {
                revisionConflict(draft.revision());
            }
            String jobState = findings.isEmpty() ? "AWAITING_PUBLISH" : "NEEDS_CHANGES";
            if (store.updateJobState(job.jobId(), job.revision(), jobState, null, null) != 1) {
                conflict("ARCHIVE_JOB_CHANGED", "Archive job changed during validation");
            }
            boolean producerComplete = runtime && findings.isEmpty()
                    && !("AUTO".equals(job.publicationMode())
                            && "PUBLISH_VALIDATED".equals(job.permissionProfile()));
            if (producerComplete) {
                if (store.completeRun(authorization.run().runId(), authorization.run().revision()) != 1
                        || store.releaseExecutionGrant(authorization.run().runId(),
                                authorization.grant().revision()) != 1) {
                    conflict("ARCHIVE_EXECUTION_CHANGED",
                            "Archive execution changed during draft completion");
                }
            }
            Map<String, String> validationFacts = Map.of("validationId", validation.validationId(),
                    "draftRevision", Long.toString(draft.revision()), "outcome", outcome);
            store.appendJobEvent(job.jobId(), job.revision() + 1,
                    "VALIDATION_FINISHED",
                    runtime ? json(validationFacts) : humanAudit(actor, manager, validationFacts));
            if (producerComplete) {
                store.appendJobEvent(job.jobId(), job.revision() + 1, "EXECUTION_COMPLETED",
                        json(Map.of("runId", job.runId(), "validationId", validation.validationId(),
                                "stage", "AWAITING_HUMAN_RELEASE")));
            }
            store.commitOperation(actor, key, validation.validationId());
            return validationDto(validation);
        });
    }

    private ArchiveMaintenanceJobRecord authorizeRuntime(ArchiveRuntimeScope runtime,
            String jobId, String runId) {
        return authorizeRuntime(runtime, jobId, runId, false);
    }

    private ArchiveMaintenanceJobRecord authorizeRuntime(ArchiveRuntimeScope runtime,
            String jobId, String runId, boolean allowTerminalResult) {
        requireRuntime(runtime);
        ArchiveMaintenanceJobRecord observed = requireJob(jobId, false);
        requireExecutionCandidate(observed);
        if (!same(observed.runId(), runId)) notFound();
        requireRuntimeJobScope(runtime, observed);
        return transactions.required(() -> {
            ArchiveAgentExecutionPort.LockedTarget lockedTarget = lockControlledTarget(target(observed));
            ArchiveManagerGrantRecord manager = requireManager(actor(runtime), observed.collectionId(),
                    "draft.write", true);
            requireManagerRevision(manager, observed);
            ArchiveAppointmentRecord appointment = requireCurrentAppointmentForJob(observed, true);
            ArchiveMaintenanceJobRecord current = requireJob(jobId, true);
            requireManagerRevision(manager, current);
            authorizeRuntimeLocked(runtime, current, appointment, lockedTarget, allowTerminalResult);
            return current;
        });
    }

    private RuntimeAuthorization authorizeRuntimeLocked(ArchiveRuntimeScope runtime,
            ArchiveMaintenanceJobRecord job, ArchiveAppointmentRecord appointment,
            ArchiveAgentExecutionPort.LockedTarget lockedTarget) {
        return authorizeRuntimeLocked(runtime, job, appointment, lockedTarget, false);
    }

    private RuntimeAuthorization authorizeRuntimeLocked(ArchiveRuntimeScope runtime,
            ArchiveMaintenanceJobRecord job, ArchiveAppointmentRecord appointment,
            ArchiveAgentExecutionPort.LockedTarget lockedTarget, boolean allowTerminalResult) {
        if ("CANCELLED".equals(job.state())) {
            throw error(403, "EXECUTION_FENCED", "Archive execution was cancelled");
        }
        requireRuntimeJobScope(runtime, job);
        requireCurrentBinding(appointment);
        if (!executionEnabled || executionPort == null) {
            throw error(403, "EXECUTION_FENCED", "Archive execution is disabled");
        }
        ArchiveJobRunRecord run = store.findRun(job.runId(), true);
        ArchiveExecutionGrantRecord grant = store.findExecutionGrant(job.runId(), true);
        boolean active = run != null && grant != null
                && Set.of("AUTHORIZED", "RUNNING").contains(run.state())
                && "ACTIVE".equals(grant.state());
        boolean terminal = run != null && grant != null
                && Set.of("COMPLETED", "FAILED").contains(run.state())
                && "READ_ONLY".equals(grant.state());
        if ((!active && !(allowTerminalResult && terminal)) || !job.jobId().equals(run.jobId())
                || run.executionEpoch() != grant.executionEpoch()
                || !same(run.runtimeInstanceId(), grant.runtimeInstanceId())
                || !same(runtime.runtimeInstanceId(), grant.runtimeInstanceId())
                || !same(runtime.grantRef(), grant.grantRef())
                || !same(runtime.executionRef(), grant.executionRef())
                || !same(runtime.commandId(), grant.commandId())
                || runtime.activeAttempt() != grant.activeAttempt()
                || runtime.executionEpoch() != grant.executionEpoch()
                || !exactGrant(job, appointment, grant)) {
            throw error(403, "EXECUTION_FENCED", "Archive execution proof is not current");
        }
        try {
            ArchiveAgentExecutionPort.Inspection inspection = terminal
                    ? executionPort.inspectResult(expected(job, grant), lockedTarget)
                    : executionPort.inspectExecution(expected(job, grant), lockedTarget);
            if (!"AUTHORIZED".equals(run.state())
                    && !same(run.startedMessageId(), inspection.activeMessageId())) {
                throw error(403, "EXECUTION_FENCED",
                        "Archive command message is no longer current");
            }
            return new RuntimeAuthorization(run, grant, inspection);
        } catch (ArchiveAgentExecutionPort.Denied denied) {
            throw error(403, "EXECUTION_FENCED", "Archive execution proof is no longer valid");
        }
    }

    private ArchiveAppointmentRecord requireCurrentAppointment(String collectionId, boolean lock) {
        ArchiveAppointmentRecord current = currentAppointment(collectionId, lock);
        if (current == null) {
            forbidden("ARCHIVE_APPOINTMENT_REQUIRED", "An active archive editor appointment is required");
        }
        return current;
    }

    private ArchiveAppointmentRecord currentAppointment(String collectionId, boolean lock) {
        ArchiveAppointmentRecord current;
        if (lock) {
            ArchiveMaintenanceStore.Slot slot = store.lockSlot(collectionId, ROLE);
            current = slot == null || slot.currentAppointmentId() == null
                    ? null : requireAppointment(slot.currentAppointmentId(), true);
        } else {
            current = store.findCurrentAppointment(collectionId, false);
        }
        return current != null && "ACTIVE".equals(current.status()) ? current : null;
    }

    private ArchiveAppointmentRecord requireCurrentAppointmentForJob(
            ArchiveMaintenanceJobRecord job, boolean lock) {
        ArchiveAppointmentRecord current;
        if (lock) {
            ArchiveMaintenanceStore.Slot slot = store.lockSlot(job.collectionId(), ROLE);
            current = slot == null || slot.currentAppointmentId() == null
                    ? null : requireAppointment(slot.currentAppointmentId(), true);
        } else {
            current = store.findCurrentAppointment(job.collectionId(), false);
        }
        if (current == null || !current.appointmentId().equals(job.appointmentId())
                || current.revision() != job.appointmentRevision()
                || !"ACTIVE".equals(current.status())
                || !same(current.agentId(), job.agentId())
                || !same(current.bindingVersion(), job.bindingVersion())) {
            forbidden("ARCHIVE_ASSIGNMENT_CHANGED", "Archive appointment was revoked or changed");
        }
        return current;
    }

    private void requireRuntimeWritable(ArchiveMaintenanceJobRecord job) {
        if ("CANCELLED".equals(job.state())) {
            throw error(403, "EXECUTION_FENCED", "Archive execution was cancelled");
        }
        if ("WAITING_SKILL".equals(job.state()) || "CLIENT_UPDATE_REQUIRED".equals(job.waitReason())) {
            throw error(422, "ARCHIVE_EXECUTION_UNAVAILABLE",
                    "Archive runtime execution is unavailable until the required client skill is installed");
        }
    }

    private void requireDraftMutable(ArchiveMaintenanceJobRecord job, ArchiveDraftRecord draft) {
        if ("PUBLISHED".equals(job.state()) || "CANCELLED".equals(job.state())
                || "SEALED".equals(draft.state())) {
            throw error(422, "ARCHIVE_JOB_NOT_MUTABLE", "Published or cancelled jobs cannot be modified");
        }
    }

    private void requireCurrentBinding(ArchiveAppointmentRecord appointment) {
        identities.requireActiveIdentityForBinding(appointment.tenantId(), appointment.clientId(),
                appointment.ownerJiacn(), parsePositive(appointment.bindingVersion(), "bindingVersion"),
                appointment.agentId());
    }

    private ArchiveManagerGrantRecord requireWithdrawManager(ArchiveActorScope actor,
            String collectionId, boolean lock) {
        requireScope(actor);
        ArchiveManagerGrantRecord grant = store.findManagerGrant(actor, collectionId, lock);
        if (grant == null || !"ACTIVE".equals(grant.state())) notFound();
        if (!grant.allows("edition.withdraw")) {
            forbidden("ARCHIVE_FORBIDDEN", "Archive edition withdrawal permission is required");
        }
        return grant;
    }

    private ArchiveManagerGrantRecord requireWorkVersionReader(ArchiveActorScope actor,
            String collectionId) {
        requireScope(actor);
        ArchiveManagerGrantRecord grant = store.findManagerGrant(actor, collectionId, false);
        if (grant == null || !"ACTIVE".equals(grant.state())) notFound();
        if (!(grant.allows("publish") || grant.allows("edition.withdraw"))) {
            forbidden("ARCHIVE_FORBIDDEN", "Archive work version permission is required");
        }
        return grant;
    }

    private ArchiveManagerGrantRecord requireVersionReader(ArchiveActorScope actor,
            String collectionId) {
        return requireVersionReader(actor, collectionId, false);
    }

    private ArchiveManagerGrantRecord requireVersionReader(ArchiveActorScope actor,
            String collectionId, boolean lock) {
        requireScope(actor);
        ArchiveManagerGrantRecord grant = store.findManagerGrant(actor, collectionId, lock);
        if (grant == null || !"ACTIVE".equals(grant.state())) notFound();
        if (!(grant.allows("publish") || grant.allows("edition.withdraw"))) {
            forbidden("ARCHIVE_FORBIDDEN", "Archive edition history permission is required");
        }
        return grant;
    }

    private ArchiveManagerGrantRecord requireValidationReader(ArchiveActorScope actor,
            String collectionId) {
        requireScope(actor);
        ArchiveManagerGrantRecord grant = store.findManagerGrant(actor, collectionId, false);
        if (grant == null || !"ACTIVE".equals(grant.state())) notFound();
        if (!(grant.allows("draft.write") || grant.allows("validate")
                || grant.allows("publish") || grant.allows("job.manage"))) {
            forbidden("ARCHIVE_FORBIDDEN", "Archive validation read permission is required");
        }
        return grant;
    }

    private ArchiveManagerGrantRecord requireManager(ArchiveActorScope actor, String collectionId,
            String permission, boolean lock) {
        requireScope(actor);
        ArchiveManagerGrantRecord grant = store.findManagerGrant(actor, collectionId, lock);
        if (grant == null || !grant.allows(permission)) {
            forbidden("ARCHIVE_FORBIDDEN", "Archive management permission is required");
        }
        return grant;
    }

    private ArchiveManagerGrantRecord requireJobReadManager(ArchiveActorScope actor,
            String collectionId, boolean lock) {
        requireScope(actor);
        ArchiveManagerGrantRecord grant = store.findManagerGrant(actor, collectionId, lock);
        if (grant == null || !(grant.allows("job.create") || grant.allows("job.manage"))) {
            forbidden("ARCHIVE_FORBIDDEN", "Archive job read permission is required");
        }
        return grant;
    }

    private ArchiveAppointmentRecord requireAppointment(String id, boolean lock) {
        ArchiveAppointmentRecord value = store.findAppointment(id, lock);
        if (value == null) notFound();
        return value;
    }

    private ArchiveMaintenanceJobRecord requireJob(String id, boolean lock) {
        exactId(id);
        ArchiveMaintenanceJobRecord value = store.findJob(id, lock);
        if (value == null) notFound();
        return value;
    }

    private ArchiveMaintenanceJobRecord requireJobForActor(ArchiveActorScope actor, String id, boolean lock) {
        ArchiveMaintenanceJobRecord job = requireJob(id, lock);
        if (!same(actor.tenantId(), job.tenantId()) || !same(actor.clientId(), job.clientId())
                || !same(actor.ownerJiacn(), job.ownerJiacn())) notFound();
        return job;
    }

    private ArchiveAppointmentRecord requireAppointmentForActor(ArchiveActorScope actor,
            String id, boolean lock) {
        ArchiveAppointmentRecord appointment = requireAppointment(id, lock);
        if (!same(actor.tenantId(), appointment.tenantId())
                || !same(actor.clientId(), appointment.clientId())
                || !same(actor.ownerJiacn(), appointment.ownerJiacn())) notFound();
        return appointment;
    }

    private ArchiveDraftRecord requireDraft(String jobId, boolean lock) {
        ArchiveDraftRecord value = store.findDraftByJob(jobId, lock);
        if (value == null) notFound();
        return value;
    }

    private PublicationMaterial material(ArchiveMaintenanceJobRecord job,
            ArchiveDraftUpdateRequest body, String editionId) {
        String manifestSha = digest(json(body));
        List<ArchiveBlockRecord> blocks = new ArrayList<>();
        List<ArchiveParagraphRecord> paragraphs = new ArrayList<>();
        int prefaceCount = 0;
        int chapterParagraphs = 0;
        long prefaceBytes = 0;
        long chapterBytes = 0;
        for (ArchiveDraftBlockInput input : body.blocks()) {
            boolean preface = "PREFACE".equals(input.blockType());
            String blockId = preface ? editionId + "-preface"
                    : editionId + "-c" + String.format("%03d", input.ordinal());
            long bytes = 0;
            int paragraphOrdinal = 0;
            List<String> paragraphDigests = new ArrayList<>();
            for (ArchiveDraftParagraphInput paragraph : input.paragraphs()) {
                paragraphOrdinal++;
                byte[] utf8 = paragraph.text().getBytes(StandardCharsets.UTF_8);
                bytes += utf8.length;
                String paragraphSha = ArchiveEtags.sha256(utf8);
                paragraphDigests.add(paragraphSha);
                paragraphs.add(new ArchiveParagraphRecord(editionId, blockId,
                        blockId + "-p" + String.format("%04d", paragraphOrdinal),
                        paragraphOrdinal, paragraph.text(), utf8.length, paragraphSha));
            }
            blocks.add(new ArchiveBlockRecord(editionId, blockId, input.blockType(),
                    preface ? 0 : input.ordinal(), preface ? null : input.ordinal(),
                    input.title(), input.paragraphs().size(), bytes,
                    digest(manifestSha + ":" + blockId + ":" + paragraphDigests)));
            if (preface) {
                prefaceCount += input.paragraphs().size();
                prefaceBytes += bytes;
            } else {
                chapterParagraphs += input.paragraphs().size();
                chapterBytes += bytes;
            }
        }
        blocks.sort(Comparator.comparingInt(ArchiveBlockRecord::readerOrdinal));
        int chapterCount = Math.toIntExact(blocks.stream()
                .filter(block -> "CHAPTER".equals(block.blockType())).count());
        ArchiveEditionRecord edition = new ArchiveEditionRecord(editionId, job.workId(),
                "STAGING", job.sourceSha256(), manifestSha, manifestSha,
                prefaceBytes + chapterBytes,
                chapterCount, prefaceCount, chapterParagraphs,
                prefaceCount + chapterParagraphs, prefaceBytes, chapterBytes,
                prefaceBytes + chapterBytes);
        return new PublicationMaterial(edition, List.copyOf(blocks), List.copyOf(paragraphs));
    }

    private List<String> validateContent(ArchiveDraftUpdateRequest body) {
        List<String> findings = new ArrayList<>();
        if (body == null || body.blocks() == null || body.blocks().isEmpty()) {
            findings.add("at least one chapter is required");
            return findings;
        }
        int expectedChapter = 1;
        int prefaces = 0;
        Set<String> keys = new HashSet<>();
        for (ArchiveDraftBlockInput block : body.blocks()) {
            if (block == null) {
                findings.add("null block");
                continue;
            }
            if (!keys.add(block.blockKey())) findings.add("duplicate blockKey " + block.blockKey());
            if (!exact(block.blockKey(), 100) || !exact(block.title(), 255)) {
                findings.add("invalid block metadata");
            }
            if ("PREFACE".equals(block.blockType())) {
                prefaces++;
                if (block.ordinal() == null || block.ordinal() != 0) {
                    findings.add("preface ordinal must be 0");
                }
            } else if ("CHAPTER".equals(block.blockType())) {
                if (block.ordinal() == null || block.ordinal() != expectedChapter) {
                    findings.add("chapter ordinals must be continuous from 1");
                }
                expectedChapter++;
            } else {
                findings.add("invalid blockType");
            }
            if (block.paragraphs() == null || block.paragraphs().isEmpty()) {
                findings.add("empty block " + block.blockKey());
            } else {
                int expectedParagraph = 1;
                for (ArchiveDraftParagraphInput paragraph : block.paragraphs()) {
                    if (paragraph == null || paragraph.ordinal() == null
                            || paragraph.ordinal() != expectedParagraph++
                            || paragraph.text() == null || paragraph.text().isEmpty()
                            || paragraph.text().codePoints().anyMatch(Character::isISOControl)) {
                        findings.add("invalid paragraph in " + block.blockKey());
                    }
                }
            }
        }
        if (prefaces > 1) findings.add("at most one preface is allowed");
        if (expectedChapter == 1) findings.add("at least one chapter is required");
        return findings;
    }

    private ArchiveSourceSnapshotRecord requireSourceSnapshot(String sourceId, ArchiveActorScope actor,
            String collectionId) {
        exactId(sourceId);
        ArchiveSourceSnapshotRecord source = store.findSource(sourceId);
        if (source == null || !same(source.collectionId(), collectionId)
                || !same(source.tenantId(), actor.tenantId())
                || !same(source.clientId(), actor.clientId())
                || !same(source.ownerJiacn(), actor.ownerJiacn()) || !"READY".equals(source.state())) {
            notFound();
        }
        return source;
    }

    private ArchiveSourceSnapshotDTO sourceDto(ArchiveSourceSnapshotRecord source) {
        return new ArchiveSourceSnapshotDTO(source.sourceId(), source.collectionId(), source.sourceName(),
                source.sourceVersion(), source.rightsBasis(), source.rawSha256(),
                Long.toString(source.rawByteLength()), source.normalizationRule(), source.state());
    }

    private byte[] readSource(ArchiveSourceSnapshotRecord source) {
        try {
            return sourceStorage.read(new AgentTaskArtifactStorage.Scope(source.tenantId(),
                    source.clientId(), source.ownerJiacn(), source.sourceId()), source.storageUri(),
                    source.rawSha256(), source.rawByteLength(), "text/plain").content();
        } catch (AgentTaskArtifactStorageException failure) {
            if (failure.getReason() == AgentTaskArtifactStorageException.Reason.CORRUPT_CONTENT)
                throw error(422, "CONTENT_VALIDATION_FAILED", "Persisted source integrity verification failed");
            throw error(503, "DEPENDENCY_UNAVAILABLE", "Private source storage is unavailable");
        }
    }

    private byte[] requireSource(ArchiveMaintenanceJobRecord job) {
        return readSource(requireSourceRecord(job));
    }

    private ArchiveSourceSnapshotRecord requireSourceRecord(ArchiveMaintenanceJobRecord job) {
        ArchiveSourceSnapshotRecord source = requireSourceSnapshot(job.sourceId(),
                new ArchiveActorScope(job.tenantId(), job.clientId(), job.ownerJiacn()), job.collectionId());
        if (!same(source.rawSha256(), job.sourceSha256())
                || !same(source.rightsBasis(), job.rightsBasis())
                || !same(source.sourceName() + " / " + source.sourceVersion(), job.sourceSummary())) {
            throw error(422, "CONTENT_VALIDATION_FAILED", "Source snapshot no longer matches the job");
        }
        return source;
    }

    private void validateResumeRequest(ArchiveResumeRequest request) {
        if (request == null || !exact(request.reason(), 500)
                || !ID.matcher(String.valueOf(request.expectedAppointmentId())).matches()
                || request.expectedSkill() == null) {
            invalid("Exact resume reason, appointment and skill snapshot are required");
        }
        parsePositive(request.expectedAppointmentRevision(), "expectedAppointmentRevision");
        validateSkillRef(request.expectedSkill());
    }

    private void validateReassignRequest(ArchiveReassignRequest request) {
        if (request == null || !exact(request.reason(), 500)
                || !ID.matcher(String.valueOf(request.expectedAppointmentId())).matches()
                || !ID.matcher(String.valueOf(request.newAppointmentId())).matches()
                || request.expectedSkill() == null || request.newSkill() == null) {
            invalid("Exact reassign reason and appointment snapshots are required");
        }
        parsePositive(request.expectedAppointmentRevision(), "expectedAppointmentRevision");
        parsePositive(request.newAppointmentRevision(), "newAppointmentRevision");
        validateSkillRef(request.expectedSkill());
        validateSkillRef(request.newSkill());
    }

    private void validateSkillRef(ArchiveSkillRef skill) {
        if (skill == null || !exact(skill.key(), 100) || !exact(skill.version(), 40)
                || !SHA.matcher(String.valueOf(skill.packageSha256())).matches()) {
            invalid("Exact archive skill snapshot is required");
        }
    }

    private void requireExpectedAppointment(ArchiveMaintenanceJobRecord job,
            ArchiveAppointmentRecord appointment, String appointmentId,
            String appointmentRevision, ArchiveSkillRef skill) {
        if (!same(job.appointmentId(), appointmentId)
                || job.appointmentRevision() != parsePositive(appointmentRevision,
                        "expectedAppointmentRevision")
                || !same(job.appointmentId(), appointment.appointmentId())
                || !same(job.agentId(), appointment.agentId())
                || !same(job.bindingVersion(), appointment.bindingVersion())
                || !same(job.permissionProfile(), appointment.permissionProfile())) {
            conflict("ARCHIVE_ASSIGNMENT_CHANGED",
                    "Expected archive appointment snapshot no longer matches the job");
        }
        requireRequestedSkill(appointment, skill);
    }

    private void requireRequestedAppointment(ArchiveAppointmentRecord appointment, String id,
            String revision, ArchiveSkillRef skill) {
        if (!same(appointment.appointmentId(), id)
                || appointment.revision() != parsePositive(revision, "newAppointmentRevision")) {
            conflict("ARCHIVE_ASSIGNMENT_CHANGED",
                    "Requested archive appointment snapshot is no longer current");
        }
        requireRequestedSkill(appointment, skill);
    }

    private void requireRequestedSkill(ArchiveAppointmentRecord appointment, ArchiveSkillRef skill) {
        if (!same(appointment.requiredSkillKey(), skill.key())
                || !same(appointment.requiredSkillVersion(), skill.version())
                || !same(appointment.requiredSkillSha256(), skill.packageSha256())) {
            conflict("ARCHIVE_SKILL_CHANGED",
                    "Expected archive skill snapshot is no longer current");
        }
    }

    private void validateAppointmentRequest(ArchiveAppointmentCreateRequest request) {
        if (request == null || !ID.matcher(String.valueOf(request.agentId())).matches()
                || !("COLLECTION".equals(request.workScopeMode())
                        || "EXPLICIT_WORKS".equals(request.workScopeMode()))
                || !("DRAFT_ONLY".equals(request.permissionProfile())
                        || "PUBLISH_VALIDATED".equals(request.permissionProfile()))
                || request.requiredSkill() == null
                || !exact(request.requiredSkill().key(), 100)
                || !exact(request.requiredSkill().version(), 40)
                || !SHA.matcher(String.valueOf(request.requiredSkill().packageSha256())).matches()) {
            invalid("Invalid archive appointment request");
        }
        if ("EXPLICIT_WORKS".equals(request.workScopeMode()) && request.workIds().isEmpty()) {
            invalid("Explicit work scope requires work IDs");
        }
        request.workIds().forEach(this::exactId);
    }

    private void validateConfirmedRequest(String collectionId,
            ArchiveMaintenanceRequest request) {
        if (request == null || !same(collectionId, request.collectionId())) {
            invalid("Confirmed archive request collection does not match the route");
        }
        validateJobRequest(new ArchiveJobCreateRequest(request.operation(), request.newWork(),
                request.workId(), request.sourceId(), request.requestedPublicationMode(),
                "confirmation-validation"));
    }

    private ArchiveConfirmedRequestRecord requireConfirmedRequest(ArchiveActorScope actor,
            String confirmationRef, boolean lock) {
        ArchiveConfirmedRequestRecord confirmation = store.findConfirmedRequest(
                actor, confirmationRef, lock);
        if (confirmation == null) {
            throw error(403, "ARCHIVE_CONFIRMATION_NOT_AVAILABLE",
                    "Archive confirmation is missing or does not belong to this actor");
        }
        if (!same(actor.tenantId(), confirmation.tenantId())
                || !same(actor.clientId(), confirmation.clientId())
                || !same(actor.ownerJiacn(), confirmation.ownerJiacn())
                || !ID.matcher(String.valueOf(confirmation.confirmationRef())).matches()
                || !exact(confirmation.requestIntentId(), 100)
                || !exact(confirmation.collectionId(), 64)
                || confirmation.requestJson() == null
                || !SHA.matcher(String.valueOf(confirmation.requestSha256())).matches()
                || !digest(confirmation.requestJson()).equals(confirmation.requestSha256())
                || confirmation.revision() < 1) {
            throw error(409, "ARCHIVE_CONFIRMATION_CORRUPT",
                    "Persisted archive confirmation is invalid");
        }
        return confirmation;
    }

    private ArchiveMaintenanceRequest confirmedBusinessRequest(
            ArchiveConfirmedRequestRecord confirmation) {
        try {
            ArchiveMaintenanceRequest request = mapper.readValue(
                    confirmation.requestJson(), ArchiveMaintenanceRequest.class);
            validateConfirmedRequest(confirmation.collectionId(), request);
            if (!same(confirmation.publicationModeCeiling(),
                    request.requestedPublicationMode())) {
                throw new IllegalArgumentException("mode ceiling drift");
            }
            return request;
        } catch (Exception failure) {
            throw error(409, "ARCHIVE_CONFIRMATION_CORRUPT",
                    "Persisted archive confirmation is invalid");
        }
    }

    private ArchiveRequestContext confirmedContext(ArchiveConfirmedRequestRecord confirmation,
            String entryPoint, String conversationRef, String targetAgentId) {
        ArchiveMaintenanceRequest request = confirmedBusinessRequest(confirmation);
        ArchiveConfirmedPolicyRef policy = new ArchiveConfirmedPolicyRef(
                confirmation.confirmationRef(), confirmation.collectionId(),
                request.operation(), request.newWork(), request.workId(), request.sourceId(),
                confirmation.publicationModeCeiling());
        return new ArchiveRequestContext(new ArchiveActorScope(confirmation.tenantId(),
                confirmation.clientId(), confirmation.ownerJiacn()),
                confirmation.requestIntentId(), entryPoint, conversationRef, targetAgentId, policy);
    }

    private void requireExactConfirmationBinding(ArchiveConfirmedRequestRecord confirmation,
            String conversationId, long conversationGeneration, String turnSha256,
            String entryPoint, String targetAgentId) {
        if (!same(confirmation.conversationId(), conversationId)
                || confirmation.conversationGeneration() == null
                || confirmation.conversationGeneration() != conversationGeneration
                || !same(confirmation.turnSha256(), turnSha256)
                || !same(confirmation.entryPoint(), entryPoint)
                || !same(confirmation.targetAgentId(), targetAgentId)
                || !ID.matcher(String.valueOf(confirmation.canonicalMessageId())).matches()) {
            throw error(403, "ARCHIVE_CONFIRMATION_BINDING_MISMATCH",
                    "Archive confirmation is bound to another canonical chat turn");
        }
    }
    private void validateJobRequest(ArchiveJobCreateRequest request) {
        if (request == null
                || !("ADD_WORK".equals(request.operation()) || "REVISE_WORK".equals(request.operation()))
                || !("MANUAL".equals(request.publicationMode()) || "AUTO".equals(request.publicationMode()))
                || !exact(request.requestIntentId(), 100)) {
            invalid("Invalid archive maintenance job request");
        }
        if (request.sourceId() != null) exactId(request.sourceId());
        if ("ADD_WORK".equals(request.operation())) {
            if (request.workId() != null) invalid("ADD_WORK cannot identify an existing work");
            if (request.newWork() != null) validateNewWork(request.newWork());
        } else {
            if (request.newWork() != null) invalid("REVISE_WORK cannot define a new work");
            if (request.workId() != null) exactId(request.workId());
        }
    }

    private void validateResolveInputRequest(ArchiveResolveInputRequest request) {
        if (request == null) invalid("Archive input resolution body is required");
        if (request.sourceId() != null) exactId(request.sourceId());
        if (request.workId() != null) exactId(request.workId());
        if (request.newWork() != null) validateNewWork(request.newWork());
        boolean anyAppointment = request.expectedAppointmentId() != null
                || request.expectedAppointmentRevision() != null || request.expectedSkill() != null;
        if (anyAppointment) {
            if (!ID.matcher(String.valueOf(request.expectedAppointmentId())).matches()
                    || request.expectedAppointmentRevision() == null
                    || request.expectedSkill() == null) {
                invalid("Exact current appointment and skill snapshot are required");
            }
            parsePositive(request.expectedAppointmentRevision(), "expectedAppointmentRevision");
            validateSkillRef(request.expectedSkill());
        }
    }

    private void validateNewWork(ArchiveNewWorkRequest work) {
        if (work == null || !exact(work.canonicalKey(), 128) || !exact(work.title(), 255)
                || (work.language() != null && !exact(work.language(), 40))) {
            invalid("New work metadata is required");
        }
    }

    private ResolvedWork resolveRequestedWork(String collectionId, String operation,
            ArchiveNewWorkRequest newWork, String workId) {
        if ("ADD_WORK".equals(operation)) {
            return newWork == null ? null
                    : new ResolvedWork(newId("wrk"), newWork.canonicalKey(), newWork.title());
        }
        if (workId == null) return null;
        ArchiveMaintenanceStore.CollectionWork collectionWork =
                store.lockCollectionWork(collectionId, workId);
        ArchiveWorkRecord work = content.lockWork(workId);
        if (collectionWork == null || work == null) notFound();
        return new ResolvedWork(workId, collectionWork.canonicalKey(), work.title());
    }

    private ResolvedWork resolvePersistedWork(ArchiveMaintenanceJobRecord job,
            ArchiveResolveInputRequest request) {
        if (job.workId() != null) {
            if ("ADD_WORK".equals(job.operation())) {
                if (request.workId() != null
                        || (request.newWork() != null
                        && (!same(job.canonicalKey(), request.newWork().canonicalKey())
                        || !same(job.title(), request.newWork().title())))) {
                    conflict("ARCHIVE_INPUT_FROZEN", "Archive work input is already frozen");
                }
                return new ResolvedWork(job.workId(), job.canonicalKey(), job.title());
            }
            if (request.newWork() != null
                    || (request.workId() != null && !same(job.workId(), request.workId()))) {
                conflict("ARCHIVE_INPUT_FROZEN", "Archive work input is already frozen");
            }
            ArchiveMaintenanceStore.CollectionWork collectionWork =
                    store.lockCollectionWork(job.collectionId(), job.workId());
            ArchiveWorkRecord work = content.lockWork(job.workId());
            if (collectionWork == null || work == null
                    || !same(job.canonicalKey(), collectionWork.canonicalKey())
                    || !same(job.title(), work.title())) {
                conflict("ARCHIVE_WORK_CHANGED", "Archive work input no longer matches the job");
            }
            return new ResolvedWork(job.workId(), job.canonicalKey(), job.title());
        }
        if ("ADD_WORK".equals(job.operation())) {
            if (request.workId() != null) invalid("ADD_WORK cannot identify an existing work");
            return request.newWork() == null ? null
                    : new ResolvedWork(newId("wrk"), request.newWork().canonicalKey(),
                            request.newWork().title());
        }
        if (request.newWork() != null) invalid("REVISE_WORK cannot define a new work");
        return request.workId() == null ? null : resolveRequestedWork(job.collectionId(),
                job.operation(), null, request.workId());
    }

    private ArchiveSourceSnapshotRecord resolvePersistedSource(ArchiveActorScope actor,
            ArchiveMaintenanceJobRecord job, ArchiveResolveInputRequest request) {
        if (job.sourceId() == null) {
            return request.sourceId() == null ? null
                    : requireSourceSnapshot(request.sourceId(), actor, job.collectionId());
        }
        if (request.sourceId() != null && !same(job.sourceId(), request.sourceId())) {
            conflict("ARCHIVE_INPUT_FROZEN", "Archive source snapshot is already frozen");
        }
        ArchiveSourceSnapshotRecord source = requireSourceSnapshot(job.sourceId(), actor,
                job.collectionId());
        if (!same(job.sourceSha256(), source.rawSha256())
                || !same(job.sourceSummary(), source.sourceName() + " / " + source.sourceVersion())
                || !same(job.rightsBasis(), source.rightsBasis())) {
            conflict("ARCHIVE_SOURCE_CHANGED", "Archive source snapshot no longer matches the job");
        }
        return source;
    }

    private void createInitialCandidate(ArchiveMaintenanceJobRecord job) {
        if (job.runId() == null || job.draftId() == null || job.appointmentRevision() == null) {
            throw new IllegalStateException("Ready archive job is missing its candidate identity");
        }
        store.insertRun(job.runId(), job.jobId(), 1, job.appointmentRevision());
        ArchiveDraftUpdateRequest empty = new ArchiveDraftUpdateRequest(List.of(), List.of());
        String json = json(empty);
        store.insertDraft(new ArchiveDraftRecord(job.draftId(), job.jobId(), 0, "EDITABLE",
                json, digest(json), null, null));
    }

    private ArchiveMaintenanceJobRecord bindOrVerifyDirectTarget(ArchiveActorScope actor,
            ArchiveMaintenanceJobRecord prior, String expectedTargetAgentId) {
        if (expectedTargetAgentId == null) return prior;
        if (prior.targetAgentId() != null) {
            if (!same(prior.targetAgentId(), expectedTargetAgentId)) {
                conflict("ARCHIVE_INTENT_TARGET_CONFLICT",
                        "Request intent is already bound to a different direct target");
            }
            return prior;
        }
        if (prior.runId() != null || prior.draftId() != null || prior.appointmentId() != null) {
            if (!same(prior.agentId(), expectedTargetAgentId)) {
                conflict("ARCHIVE_INTENT_TARGET_CONFLICT",
                        "Existing archive candidate does not match the direct target");
            }
            return prior;
        }
        if (!Set.of("WAITING_INPUT", "WAITING_ASSIGNEE").contains(prior.state())
                || store.bindWaitingJobTarget(prior.jobId(), prior.revision(), expectedTargetAgentId) != 1) {
            conflict("ARCHIVE_INTENT_TARGET_CONFLICT",
                    "Waiting archive intent could not be bound to the direct target");
        }
        long revision = prior.revision() + 1;
        store.appendJobEvent(prior.jobId(), revision, "DIRECT_TARGET_BOUND",
                json(Map.of("targetAgentId", expectedTargetAgentId)));
        ArchiveMaintenanceJobRecord bound = requireJobForActor(actor, prior.jobId(), true);
        if (bound.revision() != revision || !same(bound.targetAgentId(), expectedTargetAgentId)
                || bound.runId() != null || bound.draftId() != null || bound.appointmentId() != null) {
            conflict("ARCHIVE_INTENT_TARGET_CONFLICT",
                    "Waiting archive intent target changed during binding");
        }
        return bound;
    }
    private void requireTargetAppointment(String expectedTargetAgentId,
            ArchiveAppointmentRecord appointment) {
        if (expectedTargetAgentId != null
                && !expectedTargetAgentId.equals(appointment.agentId())) {
            forbidden("ARCHIVE_TARGET_NOT_APPOINTED",
                    "Direct archive target is not the current appointment");
        }
    }

    private boolean hasAppointmentSnapshot(ArchiveResolveInputRequest request) {
        return request.expectedAppointmentId() != null
                || request.expectedAppointmentRevision() != null || request.expectedSkill() != null;
    }

    private void requireResolveAppointmentSnapshot(ArchiveResolveInputRequest request,
            ArchiveAppointmentRecord appointment) {
        if (!hasAppointmentSnapshot(request)) {
            throw error(428, "PRECONDITION_REQUIRED",
                    "Exact current appointment and skill snapshot are required");
        }
        if (!same(appointment.appointmentId(), request.expectedAppointmentId())
                || appointment.revision() != parsePositive(request.expectedAppointmentRevision(),
                        "expectedAppointmentRevision")) {
            conflict("ARCHIVE_ASSIGNMENT_CHANGED",
                    "Expected archive appointment snapshot is no longer current");
        }
        requireRequestedSkill(appointment, request.expectedSkill());
    }

    private String waitReason(boolean missingWork, boolean missingSource,
            boolean missingAssignee) {
        List<String> missing = new ArrayList<>();
        if (missingSource) missing.add("SOURCE");
        if (missingWork) missing.add("WORK_INPUT");
        if (missingAssignee) missing.add("ASSIGNEE");
        return missing.isEmpty() ? "CLIENT_UPDATE_REQUIRED"
                : String.join("_AND_", missing) + "_REQUIRED";
    }

    private String jobStateEvent(ArchiveMaintenanceJobRecord job) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("state", job.state());
        if (job.waitReason() != null) data.put("waitReason", job.waitReason());
        if (job.sourceId() != null) data.put("sourceId", job.sourceId());
        if (job.workId() != null) data.put("workId", job.workId());
        if (job.appointmentId() != null) data.put("appointmentId", job.appointmentId());
        return json(data);
    }

    private boolean sameWaitingFacts(ArchiveMaintenanceJobRecord before,
            ArchiveMaintenanceJobRecord after) {
        return same(before.workId(), after.workId())
                && same(before.canonicalKey(), after.canonicalKey())
                && same(before.title(), after.title())
                && same(before.sourceId(), after.sourceId())
                && same(before.sourceSha256(), after.sourceSha256())
                && same(before.sourceSummary(), after.sourceSummary())
                && same(before.rightsBasis(), after.rightsBasis())
                && same(before.state(), after.state())
                && same(before.waitReason(), after.waitReason());
    }

    private void authorizeWorkScope(ArchiveAppointmentRecord appointment,
            String workId, boolean creating) {
        if ("EXPLICIT_WORKS".equals(appointment.workScopeMode())
                && (creating || !csv(appointment.workIds()).contains(workId))) {
            forbidden("ARCHIVE_ACTION_FORBIDDEN",
                    "Appointment work scope does not allow this work");
        }
    }

    private ArchiveDraftDTO persistHumanDraftUpdate(ArchiveActorScope actor,
            ArchiveManagerGrantRecord manager, ArchiveMaintenanceJobRecord job,
            ArchiveDraftRecord draft, ArchiveDraftUpdateRequest request, String action) {
        String body = json(request);
        long next = draft.revision() + 1;
        if (store.updateDraft(draft.draftId(), draft.revision(), next, "EDITABLE",
                body, digest(body), null, null) != 1) {
            revisionConflict(draft.revision());
        }
        store.appendJobEvent(job.jobId(), job.revision(), "HUMAN_DRAFT_UPDATED",
                humanAudit(actor, manager, Map.of("draftId", draft.draftId(),
                        "draftRevision", Long.toString(next), "action", action)));
        return draftDto(requireDraft(job.jobId(), false));
    }

    private ArchiveAdminOperationRecord ensureAdminReceipt(ArchiveActorScope actor,
            ArchiveMaintenanceStore.Operation operation, String key, ArchiveMaintenanceJobRecord job,
            ArchiveDraftRecord draft, String action, long authorizationRevision) {
        ArchiveAdminOperationRecord receipt = store.findAdminOperationByKey(actor, key, true);
        if (operation.created()) {
            if (receipt != null) conflict("IDEMPOTENCY_CONFLICT",
                    "Admin operation receipt already exists");
            receipt = new ArchiveAdminOperationRecord(newId("aop"), actor.tenantId(),
                    actor.clientId(), actor.ownerJiacn(), key, job.collectionId(), job.jobId(),
                    draft.draftId(), action, authorizationRevision, "PENDING", null);
            store.insertAdminOperation(receipt);
            return receipt;
        }
        if (receipt == null) conflict("ARCHIVE_OPERATION_IN_PROGRESS",
                "Admin operation receipt is unavailable");
        assertAdminReceipt(receipt, job, draft.draftId(), action);
        if (!"COMMITTED".equals(receipt.state()) || receipt.resultJson() == null) {
            conflict("ARCHIVE_OPERATION_IN_PROGRESS", "Archive operation is not committed");
        }
        return receipt;
    }

    private void assertAdminReceipt(ArchiveAdminOperationRecord receipt,
            ArchiveMaintenanceJobRecord job, String draftId, String action) {
        if (!same(receipt.collectionId(), job.collectionId())
                || !same(receipt.jobId(), job.jobId()) || !same(receipt.draftId(), draftId)
                || !same(receipt.action(), action)) {
            conflict("IDEMPOTENCY_CONFLICT", "Admin operation receipt scope changed");
        }
    }

    private void commitAdminReceipt(ArchiveActorScope actor, String key, String targetId,
            ArchiveAdminOperationRecord receipt, Object result) {
        store.commitOperation(actor, key, targetId);
        if (store.commitAdminOperation(receipt.operationId(), json(result)) != 1) {
            throw new IllegalStateException("Archive admin operation receipt commit failed");
        }
    }

    private ArchiveOperationAcceptedDTO accepted(ArchiveAdminOperationRecord receipt) {
        return new ArchiveOperationAcceptedDTO(receipt.operationId(), receipt.jobId(), "COMMITTED");
    }

    private <T> T blockSnapshot(ArchiveAdminOperationRecord receipt, Class<T> type) {
        try {
            return mapper.readValue(receipt.resultJson(), type);
        } catch (Exception failure) {
            throw new IllegalStateException("Persisted archive admin operation result is invalid", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resultMap(Object value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(
                mapper.convertValue(value, LinkedHashMap.class)));
    }

    private Map<String, Object> parseResult(String json) {
        try {
            Map<String, Object> parsed = mapper.readValue(json, mapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
            return Collections.unmodifiableMap(new LinkedHashMap<>(parsed));
        } catch (Exception failure) {
            throw new IllegalStateException("Persisted archive admin operation result is invalid", failure);
        }
    }

    private String humanAudit(ArchiveActorScope actor, ArchiveManagerGrantRecord manager,
            Map<String, String> facts) {
        LinkedHashMap<String, String> audit = new LinkedHashMap<>(facts);
        audit.put("actorType", "HUMAN");
        audit.put("actorId", actor.ownerJiacn());
        audit.put("authorizationRevision", Long.toString(manager.revision()));
        return json(audit);
    }

    private ArchiveMaintenanceStore.Operation operation(ArchiveActorScope actor, String key,
            String method, String path, String requestSha, String type, String target) {
        ArchiveMaintenanceStore.Operation operation = store.beginOperation(actor, key, method,
                path, requestSha, type, target);
        assertOperationMatches(operation, method, path, requestSha, type);
        if (!operation.created() && !"COMMITTED".equals(operation.state())) {
            conflict("ARCHIVE_OPERATION_IN_PROGRESS", "Archive operation is not committed");
        }
        return operation;
    }

    private void assertOperationMatches(ArchiveMaintenanceStore.Operation operation, String method,
            String path, String requestSha, String type) {
        if (!operation.httpMethod().equals(method) || !operation.canonicalPath().equals(path)
                || !operation.requestSha256().equals(requestSha)
                || !operation.targetType().equals(type)) {
            conflict("IDEMPOTENCY_CONFLICT", "Idempotency key was used with different content");
        }
    }

    private void requireExecutionCandidate(ArchiveMaintenanceJobRecord job) {
        if ("WAITING_INPUT".equals(job.state())) {
            conflict("ARCHIVE_JOB_WAITING_INPUT",
                    "Archive job requires explicit input resolution before execution");
        }
        if ("WAITING_ASSIGNEE".equals(job.state())) {
            conflict("ARCHIVE_JOB_WAITING_ASSIGNEE",
                    "Archive job requires an exact current appointment before execution");
        }
        if (job.runId() == null || job.draftId() == null || job.appointmentId() == null
                || job.appointmentRevision() == null || job.agentId() == null
                || job.bindingVersion() == null || job.permissionProfile() == null
                || job.sourceId() == null || job.workId() == null) {
            conflict("ARCHIVE_JOB_CANDIDATE_CORRUPT",
                    "Archive job execution candidate is incomplete");
        }
    }

    private void requireRuntimeMutable(ArchiveMaintenanceJobRecord job) {
        if ("CANCELLED".equals(job.state()) || "PUBLISHED".equals(job.state())
                || job.publicationId() != null) {
            throw error(422, "ARCHIVE_JOB_NOT_MUTABLE", "Archive job cannot start execution");
        }
    }

    private boolean exactGrant(ArchiveMaintenanceJobRecord job, ArchiveAppointmentRecord appointment,
            ArchiveExecutionGrantRecord grant) {
        return same(job.tenantId(), grant.tenantId()) && same(job.clientId(), grant.clientId())
                && same(job.ownerJiacn(), grant.ownerJiacn())
                && same(job.appointmentId(), grant.appointmentId())
                && job.appointmentRevision() == grant.appointmentRevision()
                && job.managerAuthorizationRevision() == grant.managerAuthorizationRevision()
                && same(job.agentId(), grant.agentId())
                && parsePositive(job.bindingVersion(), "bindingVersion") == grant.bindingVersion()
                && same(appointment.requiredSkillKey(), grant.skillKey())
                && same(appointment.requiredSkillVersion(), grant.skillVersion())
                && same(appointment.requiredSkillSha256(), grant.packageSha256())
                && same(contextRef(job), grant.contextRef());
    }

    ArchiveAgentExecutionPort.Expected expected(ArchiveMaintenanceJobRecord job,
            ArchiveExecutionGrantRecord grant) {
        return new ArchiveAgentExecutionPort.Expected(grant.tenantId(), grant.clientId(), grant.ownerJiacn(),
                grant.agentId(), grant.bindingVersion(), job.jobId(), grant.runId(),
                grant.appointmentId(), grant.appointmentRevision(), grant.managerAuthorizationRevision(),
                grant.grantRef(), grant.executionRef(), grant.executionEpoch(), grant.dispatchKey(), grant.commandId(), grant.activeAttempt(),
                grant.expiresAt(), grant.runtimeInstanceId(), grant.registrationHash(),
                InstalledSkillResolver.Origin.valueOf(grant.skillOrigin()),
                new InstalledSkillResolver.Proof(grant.installationRef(), grant.installationRevision(),
                        grant.skillKey(), grant.skillVersion(), grant.packageSha256()), grant.contextRef());
    }

    private ArchiveAgentExecutionPort.LockedTarget lockControlledTarget(
            ArchiveAgentExecutionPort.TargetRequest target) {
        ArchiveAgentExecutionPort port = requireExecutionPort();
        ArchiveAgentExecutionPort.LockedIdentityRoot root = port.lockIdentityRoot(target);
        return port.requireControlledTarget(target, root);
    }

    private ArchiveAgentExecutionPort requireExecutionPort() {
        if (!executionEnabled || executionPort == null) {
            throw error(403, "EXECUTION_FENCED", "Archive execution is disabled");
        }
        return executionPort;
    }

    private ArchiveAgentExecutionPort.TargetRequest target(ArchiveMaintenanceJobRecord job) {
        return new ArchiveAgentExecutionPort.TargetRequest(job.tenantId(), job.clientId(), job.ownerJiacn(),
                job.agentId(), parsePositive(job.bindingVersion(), "bindingVersion"));
    }

    private ArchiveAgentExecutionPort.TargetRequest target(ArchiveAppointmentRecord appointment) {
        return new ArchiveAgentExecutionPort.TargetRequest(appointment.tenantId(), appointment.clientId(),
                appointment.ownerJiacn(), appointment.agentId(),
                parsePositive(appointment.bindingVersion(), "bindingVersion"));
    }

    private List<ArchiveAgentExecutionPort.TargetRequest> managerTargets(ArchiveActorScope actor,
            List<ArchiveMaintenanceStore.ManagerRunTarget> runs) {
        return runs.stream().map(run -> managerTarget(actor, run)).distinct()
                .sorted(Comparator.comparing(ArchiveAgentExecutionPort.TargetRequest::canonicalAgent)
                        .thenComparingLong(ArchiveAgentExecutionPort.TargetRequest::binding))
                .toList();
    }

    private ArchiveAgentExecutionPort.TargetRequest managerTarget(ArchiveActorScope actor,
            ArchiveMaintenanceStore.ManagerRunTarget run) {
        return new ArchiveAgentExecutionPort.TargetRequest(actor.tenantId(), actor.clientId(),
                actor.ownerJiacn(), run.agentId(), parsePositive(run.bindingVersion(), "bindingVersion"));
    }

    private static boolean sameAppointmentSnapshot(ArchiveAppointmentRecord left,
            ArchiveAppointmentRecord right) {
        if (left == null || right == null) return left == right;
        return left.appointmentId().equals(right.appointmentId())
                && left.revision() == right.revision()
                && left.agentId().equals(right.agentId())
                && left.bindingVersion().equals(right.bindingVersion());
    }

    private void requireManagerRevision(ArchiveManagerGrantRecord manager, ArchiveMaintenanceJobRecord job) {
        if (manager == null || manager.revision() != job.managerAuthorizationRevision()) {
            throw error(403, "ARCHIVE_MANAGER_AUTHORIZATION_CHANGED",
                    "Archive manager authorization no longer matches this job");
        }
    }

    private ArchiveMaintenanceException executionDenied(ArchiveAgentExecutionPort.Denied denied) {
        String code = denied.code();
        int status = "ARCHIVE_EXECUTION_SKILL_NOT_VERIFIED".equals(code) ? 409 : 503;
        return error(status, code, "Archive execution admission was denied");
    }

    private String contextRef(ArchiveMaintenanceJobRecord job) {
        return "/internal/archive/v1/jobs/" + job.jobId() + "/runs/" + job.runId() + "/context";
    }

    private String executionDispatchKey(ArchiveMaintenanceJobRecord job) {
        return "adk_" + digest(job.jobId() + "\0" + job.runId());
    }

    private ArchiveExecutionRecoveryDTO recoveryDto(ArchiveJobRunRecord run) {
        return new ArchiveExecutionRecoveryDTO(run.jobId(), run.runId(),
                Long.toString(run.executionEpoch()), run.state());
    }

    private ArchiveExecutionDTO executionDto(ArchiveExecutionGrantRecord grant) {
        return new ArchiveExecutionDTO(grant.grantRef(), grant.runId(), grant.executionRef(),
                grant.commandId(), Integer.toString(grant.activeAttempt()),
                Long.toString(grant.executionEpoch()), grant.state(), grant.installationRef(),
                Long.toString(grant.installationRevision()), Long.toString(grant.expiresAt()));
    }

    private ArchiveSkillRef skillRef(ArchiveAppointmentRecord value) {
        return new ArchiveSkillRef(value.requiredSkillKey(), value.requiredSkillVersion(),
                value.requiredSkillSha256());
    }

    private ArchiveAppointmentRecord currentAppointmentFor(ArchiveAppointmentRecord value) {
        return "ACTIVE".equals(value.status())
                ? store.findCurrentAppointment(value.collectionId(), false) : null;
    }

    private ArchiveAppointmentDTO appointmentDto(ArchiveActorScope actor, ArchiveAppointmentRecord value,
            ArchiveAppointmentRecord current) {
        AppointmentReadiness readiness = appointmentReadiness(actor, value, current);
        return new ArchiveAppointmentDTO(value.appointmentId(), value.collectionId(),
                value.roleCode(), value.agentId(), value.bindingVersion(), value.workScopeMode(),
                csv(value.workIds()), value.permissionProfile(), skillRef(value),
                value.status(), Long.toString(value.revision()), readiness.readiness(),
                readiness.skillReadiness());
    }

    private AppointmentReadiness appointmentReadiness(ArchiveActorScope actor,
            ArchiveAppointmentRecord value, ArchiveAppointmentRecord current) {
        boolean currentScope = actor != null && same(actor.tenantId(), value.tenantId())
                && same(actor.clientId(), value.clientId()) && same(actor.ownerJiacn(), value.ownerJiacn());
        if (!"ACTIVE".equals(value.status())) {
            return blockedReadiness("REVOKED", InstalledSkillResolver.State.REVOKED,
                    null, "APPOINTMENT_REVOKED");
        }
        if (!currentScope) {
            return blockedReadiness("UNAVAILABLE", InstalledSkillResolver.State.UNAVAILABLE,
                    null, "SCOPE_UNAVAILABLE");
        }
        if (!sameCurrentAppointment(value, current)) {
            return blockedReadiness("REASSIGNMENT_REQUIRED", InstalledSkillResolver.State.UNAVAILABLE,
                    null, "REASSIGNMENT_REQUIRED");
        }

        long binding;
        try {
            binding = parsePositive(value.bindingVersion(), "bindingVersion");
        } catch (ArchiveMaintenanceException invalid) {
            return blockedReadiness("BINDING_CHANGED", InstalledSkillResolver.State.UNAVAILABLE,
                    null, "BINDING_CHANGED");
        }
        ArchiveAgentExecutionPort.Readiness execution = !executionEnabled
                ? ArchiveAgentExecutionPort.Readiness.blocked("EXECUTION_DISABLED")
                : executionPort == null
                ? ArchiveAgentExecutionPort.Readiness.blocked("EXECUTION_NOT_WIRED")
                : executionPort.observeReadiness(new ArchiveAgentExecutionPort.TargetRequest(
                        value.tenantId(), value.clientId(), value.ownerJiacn(), value.agentId(), binding));
        if (execution == null) {
            execution = ArchiveAgentExecutionPort.Readiness.blocked("EXECUTION_NOT_WIRED");
        }

        InstalledSkillResolver.Resolution resolution = InstalledSkillResolver.Resolution.unavailable();
        if (!"BINDING_CHANGED".equals(execution.blocker()) && installedSkillResolver != null) {
            resolution = exactSkillResolution(installedSkillResolver.resolve(new InstalledSkillResolver.Request(
                    value.tenantId(), value.clientId(), value.ownerJiacn(), value.agentId(), binding,
                    InstalledSkillResolver.Origin.PLATFORM_PROVISIONED,
                    value.requiredSkillKey(), value.requiredSkillVersion(), value.requiredSkillSha256())), value);
        }
        ArchiveInstalledSkillProofDTO proof = skillProofDto(resolution.proof());
        if (!execution.executable()) {
            return blockedReadiness(execution.blocker(), resolution.state(), proof, execution.blocker());
        }
        String skillBlocker = switch (resolution.state()) {
            case VERIFIED -> null;
            case PENDING -> "SKILL_INSTALL_PENDING";
            case REVOKED -> "SKILL_INSTALL_REVOKED";
            case UNAVAILABLE -> "SKILL_INSTALL_UNAVAILABLE";
        };
        if (skillBlocker != null) {
            return blockedReadiness(skillBlocker, resolution.state(), proof, skillBlocker);
        }
        return new AppointmentReadiness("READY",
                new ArchiveSkillReadinessDTO(InstalledSkillResolver.State.VERIFIED.name(), proof, true, null));
    }

    private InstalledSkillResolver.Resolution exactSkillResolution(
            InstalledSkillResolver.Resolution resolution, ArchiveAppointmentRecord appointment) {
        if (resolution == null || resolution.state() == InstalledSkillResolver.State.UNAVAILABLE) {
            return InstalledSkillResolver.Resolution.unavailable();
        }
        InstalledSkillResolver.Proof proof = resolution.proof();
        if (proof == null || !same(proof.key(), appointment.requiredSkillKey())
                || !same(proof.version(), appointment.requiredSkillVersion())
                || !same(proof.packageDigest(), appointment.requiredSkillSha256())) {
            return InstalledSkillResolver.Resolution.unavailable();
        }
        return resolution;
    }

    private ArchiveInstalledSkillProofDTO skillProofDto(InstalledSkillResolver.Proof proof) {
        return proof == null ? null : new ArchiveInstalledSkillProofDTO(proof.installationRef(),
                Long.toString(proof.revision()), proof.key(), proof.version(), proof.packageDigest());
    }

    private AppointmentReadiness blockedReadiness(String readiness,
            InstalledSkillResolver.State state, ArchiveInstalledSkillProofDTO proof, String blocker) {
        return new AppointmentReadiness(readiness,
                new ArchiveSkillReadinessDTO(state.name(), proof, false, blocker));
    }

    private boolean sameCurrentAppointment(ArchiveAppointmentRecord expected,
            ArchiveAppointmentRecord current) {
        return current != null && "ACTIVE".equals(current.status())
                && same(expected.appointmentId(), current.appointmentId())
                && same(expected.collectionId(), current.collectionId())
                && same(expected.roleCode(), current.roleCode())
                && same(expected.tenantId(), current.tenantId())
                && same(expected.clientId(), current.clientId())
                && same(expected.ownerJiacn(), current.ownerJiacn())
                && same(expected.agentId(), current.agentId())
                && same(expected.bindingVersion(), current.bindingVersion())
                && same(expected.permissionProfile(), current.permissionProfile())
                && same(expected.requiredSkillKey(), current.requiredSkillKey())
                && same(expected.requiredSkillVersion(), current.requiredSkillVersion())
                && same(expected.requiredSkillSha256(), current.requiredSkillSha256())
                && expected.revision() == current.revision();
    }

    private record AppointmentReadiness(String readiness,
            ArchiveSkillReadinessDTO skillReadiness) { }

    private ArchiveJobDTO jobDto(ArchiveMaintenanceJobRecord value) {
        return jobDto(value, null);
    }

    private ArchiveJobDTO jobDto(ArchiveMaintenanceJobRecord value, ArchiveJobHandlingFactsDTO handling) {
        return new ArchiveJobDTO(value.jobId(), value.runId(), value.collectionId(), value.state(),
                value.waitReason(), Long.toString(value.revision()), value.appointmentId(),
                value.agentId(), value.permissionProfile(), value.publicationMode(), value.operation(),
                value.workId(), value.canonicalKey(), value.title(), value.sourceId(), value.draftId(),
                value.publicationId(), handling);
    }

    private LockedJobIdentity lockJobIdentityRoot(ArchiveMaintenanceJobRecord observed) {
        if (observed.appointmentId() == null) return new LockedJobIdentity(false);
        if (observed.agentId() == null || observed.bindingVersion() == null) {
            return new LockedJobIdentity(false);
        }
        long binding;
        try {
            binding = Long.parseLong(observed.bindingVersion());
        } catch (NumberFormatException invalid) {
            return new LockedJobIdentity(false);
        }
        if (binding <= 0) return new LockedJobIdentity(false);
        AgentIdentityService.BindingAuthority authority = identities.lockBindingAuthority(
                observed.tenantId(), observed.clientId(), observed.ownerJiacn(), binding,
                observed.agentId());
        // Historical is a normal identity-domain projection returned from inside the proxied
        // transaction boundary; infrastructure/database failures still propagate.
        return new LockedJobIdentity(
                AgentIdentityService.BindingAuthority.CURRENT.equals(authority));
    }

    private LockedJobAssignment lockJobAssignment(ArchiveMaintenanceJobRecord observed) {
        if (observed.appointmentId() == null) return new LockedJobAssignment(null, null);
        ArchiveMaintenanceStore.Slot slot = store.lockSlot(observed.collectionId(), ROLE);
        ArchiveAppointmentRecord appointment = store.findAppointment(observed.appointmentId(), true);
        return new LockedJobAssignment(slot, appointment);
    }

    private CurrentJobAssignment currentJobAssignment(ArchiveActorScope actor,
            ArchiveMaintenanceJobRecord job, LockedJobAssignment locked,
            LockedJobIdentity identity) {
        if (job.appointmentId() == null) {
            if (job.appointmentRevision() != null || job.agentId() != null
                    || job.bindingVersion() != null || job.permissionProfile() != null) {
                conflict("ARCHIVE_ASSIGNMENT_CHANGED", "Archive job assignment snapshot is incomplete");
            }
            return new CurrentJobAssignment("UNASSIGNED", null, null, null, job.waitReason());
        }
        if (job.appointmentRevision() == null || job.agentId() == null
                || job.bindingVersion() == null || job.permissionProfile() == null) {
            conflict("ARCHIVE_ASSIGNMENT_CHANGED", "Archive job assignment snapshot is incomplete");
        }
        ArchiveJobHandlingFactsDTO.AssignmentSnapshot snapshot =
                new ArchiveJobHandlingFactsDTO.AssignmentSnapshot(job.appointmentId(),
                        Long.toString(job.appointmentRevision()), job.agentId(), job.permissionProfile());
        ArchiveAppointmentRecord appointment = locked.appointment();
        ArchiveMaintenanceStore.Slot slot = locked.slot();
        if (appointment == null || slot == null) {
            conflict("ARCHIVE_ASSIGNMENT_CHANGED", "Archive appointment snapshot is unavailable");
        }
        if (!same(actor.tenantId(), appointment.tenantId())
                || !same(actor.clientId(), appointment.clientId())
                || !same(actor.ownerJiacn(), appointment.ownerJiacn())) {
            notFound();
        }
        if (!same(job.collectionId(), appointment.collectionId())
                || !ROLE.equals(appointment.roleCode())
                || !same(job.collectionId(), slot.collectionId())
                || !ROLE.equals(slot.roleCode())
                || !same(job.appointmentId(), appointment.appointmentId())
                || !same(job.agentId(), appointment.agentId())
                || !same(job.bindingVersion(), appointment.bindingVersion())
                || !same(job.permissionProfile(), appointment.permissionProfile())) {
            conflict("ARCHIVE_ASSIGNMENT_CHANGED", "Archive appointment no longer matches the job snapshot");
        }
        if ("ACTIVE".equals(appointment.status())
                && appointment.revision() == job.appointmentRevision()
                && same(job.appointmentId(), slot.currentAppointmentId())) {
            if (!identity.current()) {
                return new CurrentJobAssignment("BINDING_CHANGED", null, null, snapshot,
                        "REASSIGNMENT_REQUIRED");
            }
            return new CurrentJobAssignment("ACTIVE", appointment.agentId(),
                    appointment.permissionProfile(), snapshot, job.waitReason());
        }
        if ("REVOKED".equals(appointment.status())
                && appointment.revision() > job.appointmentRevision()
                && appointment.revokedAt() != null
                && !same(job.appointmentId(), slot.currentAppointmentId())) {
            return new CurrentJobAssignment("REVOKED", null, null, snapshot,
                    "REASSIGNMENT_REQUIRED");
        }
        conflict("ARCHIVE_ASSIGNMENT_CHANGED", "Archive appointment is neither current nor validly revoked");
        return null;
    }

    private ArchiveJobHandlingFactsDTO handlingFacts(ArchiveActorScope actor,
            ArchiveMaintenanceJobRecord job, CurrentJobAssignment assignment) {
        ArchiveJobHandlingFactsDTO.SourceRef sourceRef = null;
        if (job.sourceId() != null) {
            ArchiveSourceSnapshotRecord source = store.findSource(job.sourceId());
            if (source == null || !same(source.sourceId(), job.sourceId())
                    || !same(source.collectionId(), job.collectionId())
                    || !same(source.tenantId(), job.tenantId())
                    || !same(source.clientId(), job.clientId())
                    || !same(source.ownerJiacn(), job.ownerJiacn())
                    || !same(source.rawSha256(), job.sourceSha256())
                    || !"READY".equals(source.state())) {
                conflict("ARCHIVE_SOURCE_CHANGED", "Archive source binding changed");
            }
            sourceRef = new ArchiveJobHandlingFactsDTO.SourceRef(source.sourceId(),
                    source.sourceName(), source.sourceVersion());
        }

        ArchiveDraftRecord draft = job.draftId() == null ? null : store.findDraftByJob(job.jobId(), true);
        if (job.draftId() != null && (draft == null || !same(draft.draftId(), job.draftId())
                || !same(draft.jobId(), job.jobId()))) {
            conflict("ARCHIVE_DRAFT_CHANGED", "Archive draft binding changed");
        }
        ArchiveDraftUpdateRequest body = draft == null ? null : parseDraft(draft.contentJson());
        long completedChapters = completedDraftChapters(body);
        boolean totalKnown = trustedCompleteDraft(draft);
        Long totalChapters = totalKnown ? completedChapters : null;

        ArchiveJobHandlingFactsDTO.CurrentPublication publicationFacts = null;
        if (job.publicationId() != null) {
            ArchivePublicationRecord publication = store.findPublicationById(job.publicationId(), true);
            if (!sameJobPublication(job, publication)) {
                conflict("ARCHIVE_PUBLICATION_CHANGED", "Archive publication binding changed");
            }
            ArchivePublicationReadbackRecord readback = store.findPublicationReadback(
                    publication.publicationId(), true);
            if (readback == null || !same(readback.publicationId(), publication.publicationId())) {
                throw new IllegalStateException("Committed archive publication readback is unavailable");
            }
            ArchiveEditionRecord edition = content.findEdition(publication.editionId());
            List<ArchiveBlockRecord> blocks = edition == null ? List.of()
                    : content.listBlocks(publication.editionId());
            long persistedChapters = blocks.stream()
                    .filter(block -> "CHAPTER".equals(block.blockType())).count();
            boolean trustedEdition = edition != null && "READY".equals(edition.importState())
                    && same(edition.editionId(), publication.editionId())
                    && same(edition.workId(), publication.workId())
                    && same(edition.sourceSha256(), publication.sourceSha256())
                    && same(edition.manifestSha256(), publication.manifestSha256())
                    && persistedChapters == edition.chapterCount();
            if (trustedEdition) {
                completedChapters = persistedChapters;
                if ("PASSED".equals(readback.state())) {
                    totalKnown = true;
                    totalChapters = (long) edition.chapterCount();
                }
            }
            ArchiveJobHandlingFactsDTO.ReaderTarget readerTarget =
                    trustedEdition && "PUBLISHED".equals(publication.state())
                            && "PASSED".equals(readback.state())
                            && readerAccessPolicy != null
                            && readerAccessPolicy.allows(actor.tenantId(), actor.clientId())
                    ? new ArchiveJobHandlingFactsDTO.ReaderTarget(
                            publication.workId(), publication.editionId()) : null;
            ArchiveJobHandlingFactsDTO.Receipt receipt =
                    new ArchiveJobHandlingFactsDTO.Receipt(publication.publicationId(),
                            publication.jobId(), publication.workId(), publication.editionId(),
                            Long.toString(publication.draftRevision()), publication.manifestSha256(),
                            publication.sourceSha256());
            publicationFacts = new ArchiveJobHandlingFactsDTO.CurrentPublication(
                    publication.state(), receipt, verificationDto(readback), readerTarget);
        }

        return new ArchiveJobHandlingFactsDTO(job.title(), job.collectionId(), sourceRef,
                assignment.assignedAgentId(), assignment.permissionProfile(), job.publicationMode(),
                job.state(), assignment.blocker(), new ArchiveJobHandlingFactsDTO.Progress(
                        Long.toString(completedChapters), totalKnown,
                        totalChapters == null ? null : Long.toString(totalChapters)),
                publicationFacts, assignment.status(), assignment.snapshot());
    }

    private boolean sameJobPublication(ArchiveMaintenanceJobRecord job,
            ArchivePublicationRecord publication) {
        return publication != null && same(publication.publicationId(), job.publicationId())
                && same(publication.jobId(), job.jobId())
                && same(publication.collectionId(), job.collectionId())
                && same(publication.workId(), job.workId());
    }

    private boolean trustedCompleteDraft(ArchiveDraftRecord draft) {
        if (draft == null || draft.validatedRevision() == null
                || draft.validatedRevision() != draft.revision() || draft.validationId() == null
                || !("VALIDATED".equals(draft.state()) || "SEALED".equals(draft.state()))) {
            return false;
        }
        ArchiveValidationRecord validation = store.findCurrentValidation(
                draft.draftId(), draft.revision());
        return validation != null && same(validation.validationId(), draft.validationId())
                && same(validation.draftId(), draft.draftId())
                && validation.draftRevision() == draft.revision()
                && "PASSED".equals(validation.outcome());
    }

    private long completedDraftChapters(ArchiveDraftUpdateRequest body) {
        if (body == null || body.blocks() == null) return 0;
        return body.blocks().stream().filter(this::completedDraftChapter).count();
    }

    private boolean completedDraftChapter(ArchiveDraftBlockInput block) {
        if (block == null || !"CHAPTER".equals(block.blockType())
                || block.ordinal() == null || block.ordinal() < 1
                || !exact(block.blockKey(), 100) || !exact(block.title(), 255)
                || block.paragraphs() == null || block.paragraphs().isEmpty()) {
            return false;
        }
        int expected = 1;
        for (ArchiveDraftParagraphInput paragraph : block.paragraphs()) {
            if (paragraph == null || paragraph.ordinal() == null
                    || paragraph.ordinal() != expected++ || paragraph.text() == null
                    || paragraph.text().isEmpty()
                    || paragraph.text().codePoints().anyMatch(Character::isISOControl)) {
                return false;
            }
        }
        return true;
    }

    private ArchiveDraftDTO draftDto(ArchiveDraftRecord value) {
        return new ArchiveDraftDTO(value.draftId(), value.jobId(), Long.toString(value.revision()),
                value.state(), parseDraft(value.contentJson()), value.contentSha256(),
                value.validatedRevision() == null ? null : Long.toString(value.validatedRevision()),
                value.validationId());
    }

    private String exactVersionCollection(List<ArchiveEditionVersionRecord> versions, String workId) {
        String collectionId = versions.getFirst().collectionId();
        if (versions.stream().anyMatch(value -> !same(workId, value.workId())
                || !same(collectionId, value.collectionId()))) {
            throw new IllegalStateException("Persisted archive edition history scope is invalid");
        }
        return collectionId;
    }

    private ArchiveWithdrawalDTO withdrawalDto(ArchiveWithdrawalRecord value) {
        return new ArchiveWithdrawalDTO(value.withdrawalId(), value.editionId(), value.reason(),
                value.actorType(), value.actorId(), Long.toString(value.authorizationRevision()),
                value.withdrawnAt().toString(), value.requestedReplacementActiveEditionId(),
                value.resultingActiveEditionId(), Long.toString(value.resultingWorkRevision()),
                value.operationKey(), value.outboxState());
    }

    private ArchiveEditionVersionDTO versionDto(ArchiveEditionVersionRecord value) {
        return new ArchiveEditionVersionDTO(value.publicationId(), value.collectionId(),
                value.workId(), value.editionId(), Long.toString(value.draftRevision()),
                value.manifestSha256(), value.sourceSha256(), value.state(), value.actorType(),
                value.actorId(), Long.toString(value.authorizationRevision()),
                value.publishedAt().toString(), value.withdrawal() == null ? null : withdrawalDto(value.withdrawal()),
                verificationDto(requirePublicationReadback(value.publicationId())));
    }

    private ArchiveValidationDTO validationDto(ArchiveValidationRecord value) {
        try {
            List<String> findings = mapper.readValue(value.findingsJson(), mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
            return new ArchiveValidationDTO(value.validationId(), value.draftId(),
                    Long.toString(value.draftRevision()), value.outcome(),
                    value.validationDigest(), findings);
        } catch (Exception failure) {
            throw new IllegalStateException("Persisted archive validation is invalid", failure);
        }
    }

    private ArchivePublicationDTO publicationDto(ArchivePublicationRecord value) {
        ArchivePublicationVerificationDTO verification = verificationDto(
                requirePublicationReadback(value.publicationId()));
        return new ArchivePublicationDTO(value.publicationId(), value.jobId(), value.workId(),
                value.editionId(), Long.toString(value.draftRevision()), value.manifestSha256(),
                value.sourceSha256(), value.state(),
                verification.state(), verification);
    }

    private ArchivePublicationReadbackRecord requirePublicationReadback(String publicationId) {
        ArchivePublicationReadbackRecord value = store.findPublicationReadback(publicationId);
        if (value == null) {
            throw new IllegalStateException("Committed archive publication readback is unavailable");
        }
        return value;
    }

    private ArchivePublicationVerificationDTO verificationDto(
            ArchivePublicationReadbackRecord value) {
        if (value == null) return null;
        try {
            List<String> findings = mapper.readValue(value.findingsJson(), mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
            return new ArchivePublicationVerificationDTO(value.state(),
                    Long.toString(value.revision()), value.verificationDigest(), findings,
                    value.checkedAt() == null ? null : value.checkedAt().toString());
        } catch (Exception failure) {
            throw new IllegalStateException("Persisted archive publication verification is invalid", failure);
        }
    }

    private ArchiveDraftUpdateRequest parseDraft(String json) {
        try {
            return mapper.readValue(json, ArchiveDraftUpdateRequest.class);
        } catch (Exception failure) {
            throw new IllegalStateException("Persisted archive draft is invalid", failure);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    private String sha(Object value) { return digest(json(value)); }
    private String digestBytes(byte[] value) { return ArchiveEtags.sha256(value); }
    private String digest(String value) {
        return ArchiveEtags.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
    private String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
    private List<String> csv(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }
    private List<String> permissions(String value) {
        return value == null ? List.of() : Arrays.stream(value.split(","))
                .map(String::strip).filter(item -> !item.isEmpty()).toList();
    }
    private ArchiveActorScope actor(ArchiveRuntimeScope runtime) {
        return new ArchiveActorScope(runtime.tenantId(), runtime.clientId(), runtime.ownerJiacn());
    }
    private void requireSameOwner(ArchiveActorScope actor, ArchiveAppointmentRecord appointment) {
        if (!same(actor.tenantId(), appointment.tenantId())
                || !same(actor.clientId(), appointment.clientId())
                || !same(actor.ownerJiacn(), appointment.ownerJiacn())) {
            forbidden("ARCHIVE_ACTION_FORBIDDEN", "Cross-owner archive appointment is forbidden");
        }
    }
    private void requireScope(ArchiveActorScope actor) {
        if (actor == null || !"0".equals(actor.tenantId()) || !exact(actor.clientId(), 50)
                || !exact(actor.ownerJiacn(), 50) || "0".equals(actor.ownerJiacn())) {
            throw error(401, "AUTH_CONTEXT_INCOMPLETE",
                    "Archive authentication context is incomplete");
        }
    }
    private void validateStart(ArchiveRuntimeScope runtime, ArchiveRuntimeStartRequest request) {
        if (request == null || !ID.matcher(String.valueOf(request.commandId())).matches()
                || !ID.matcher(String.valueOf(request.messageId())).matches()
                || !same(request.commandId(), runtime.commandId())
                || parsePositive(request.attempt(), "attempt") != runtime.activeAttempt()
                || parsePositive(request.executionEpoch(), "executionEpoch") != runtime.executionEpoch()) {
            invalid("Native start proof does not match the authenticated execution");
        }
    }

    private void validateFailure(ArchiveRuntimeFailureRequest request) {
        if (request == null || !FAILURE_PHASES.contains(request.phase())
                || !FAILURE_CODE.matcher(String.valueOf(request.code())).matches()
                || request.retryable() == null) {
            invalid("Native failure must use a safe phase, code and retryability");
        }
    }

    private void requireRuntimeJobScope(ArchiveRuntimeScope runtime, ArchiveMaintenanceJobRecord job) {
        if (!same(runtime.tenantId(), job.tenantId())
                || !same(runtime.clientId(), job.clientId())
                || !same(runtime.ownerJiacn(), job.ownerJiacn())
                || !same(runtime.agentId(), job.agentId())) {
            notFound();
        }
    }

    private void requireProducerRunning(ArchiveJobRunRecord run) {
        if (run == null || !"RUNNING".equals(run.state()) || run.startedMessageId() == null) {
            conflict("ARCHIVE_EXECUTION_NOT_RUNNING",
                    "Archive execution must claim start before producer writes");
        }
    }

    private ArchiveRuntimeResultDTO runtimeResultDto(ArchiveMaintenanceJobRecord job,
            ArchiveJobRunRecord run, ArchiveExecutionGrantRecord grant) {
        ArchiveDraftRecord draft = requireDraft(job.jobId(), false);
        ArchiveValidationRecord validation = draft.validationId() == null
                ? null : store.findValidation(draft.validationId());
        ArchivePublicationRecord publication = job.publicationId() == null
                ? null : store.findPublicationByJob(job.jobId());
        if (publication != null && !same(job.publicationId(), publication.publicationId())) {
            throw error(409, "ARCHIVE_RESULT_CONFLICT",
                    "Archive publication receipt does not match the job");
        }
        return new ArchiveRuntimeResultDTO(job.jobId(), run.runId(), grant.commandId(),
                Integer.toString(grant.activeAttempt()), Long.toString(run.executionEpoch()),
                run.state(), Long.toString(run.revision()), job.state(),
                Long.toString(job.revision()), resultStage(job, run),
                validation == null ? null : validation.validationId(),
                validation == null ? null : validation.outcome(),
                validation == null ? null : validation.validationDigest(),
                Long.toString(draft.revision()),
                publication == null ? null : publication.publicationId(),
                publication == null ? null : publication.workId(),
                publication == null ? null : publication.editionId(),
                publication == null ? null : publication.state(),
                run.failurePhase(), run.failureCode(), run.failureRetryable());
    }

    private String resultStage(ArchiveMaintenanceJobRecord job, ArchiveJobRunRecord run) {
        return switch (run.state()) {
            case "WAITING" -> "QUEUED";
            case "AUTHORIZED" -> "READY_TO_START";
            case "RUNNING" -> "RUNNING";
            case "COMPLETED" -> "AWAITING_PUBLISH".equals(job.state())
                    ? "AWAITING_HUMAN_RELEASE" : "COMPLETED";
            case "FAILED" -> "FAILED";
            case "FENCED" -> "FENCED";
            default -> throw error(409, "ARCHIVE_RESULT_CONFLICT",
                    "Archive run state is not recognized");
        };
    }

    private ArchiveMaintenanceJobRecord jobState(ArchiveMaintenanceJobRecord job, String state,
            String waitReason, long revision) {
        return new ArchiveMaintenanceJobRecord(job.jobId(), job.runId(), job.collectionId(),
                job.tenantId(), job.clientId(), job.ownerJiacn(), job.appointmentId(),
                job.appointmentRevision(), job.agentId(), job.bindingVersion(),
                job.permissionProfile(), job.managerAuthorizationRevision(), job.publicationMode(),
                job.operation(), job.workId(), job.canonicalKey(), job.title(), job.sourceId(),
                job.sourceSha256(), job.sourceSummary(), job.rightsBasis(), state, waitReason,
                revision, job.draftId(), job.publicationId(), job.requestIntentId(),
                job.requestSha256(), job.targetAgentId());
    }

    private ArchiveJobRunRecord runState(ArchiveJobRunRecord run, String state, long revision,
            String messageId, String failurePhase, String failureCode, Boolean failureRetryable) {
        return new ArchiveJobRunRecord(run.runId(), run.jobId(), run.executionEpoch(),
                run.runtimeInstanceId(), run.grantRevision(), messageId, failurePhase, failureCode,
                failureRetryable, state, revision);
    }

    private void requireRuntime(ArchiveRuntimeScope runtime) {
        if (runtime == null || !"0".equals(runtime.tenantId())
                || !exact(runtime.clientId(), 50) || !exact(runtime.ownerJiacn(), 50)
                || !ID.matcher(String.valueOf(runtime.agentId())).matches()
                || !exact(runtime.runtimeInstanceId(), 100)
                || !ID.matcher(String.valueOf(runtime.grantRef())).matches()
                || !ID.matcher(String.valueOf(runtime.executionRef())).matches()
                || !ID.matcher(String.valueOf(runtime.commandId())).matches()
                || runtime.activeAttempt() < 1 || runtime.executionEpoch() < 1) {
            throw error(401, "RUNTIME_UNAUTHENTICATED",
                    "Runtime authentication context is incomplete");
        }
    }
    private void requireKey(String key) {
        if (!exact(key, 100)) throw error(428, "PRECONDITION_REQUIRED", "Idempotency-Key is required");
    }
    private void exactId(String value) {
        if (!ID.matcher(String.valueOf(value)).matches()) notFound();
    }
    private boolean exact(String value, int maxBytes) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.getBytes(StandardCharsets.UTF_8).length <= maxBytes
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private boolean same(Object left, Object right) { return Objects.equals(left, right); }
    private long parsePositive(String value, String field) {
        long number = parseNonNegative(value, field);
        if (number < 1) invalid(field + " must be positive");
        return number;
    }
    private long parseNonNegative(String value, String field) {
        try {
            if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (Exception failure) {
            invalid(field + " is invalid");
            return -1;
        }
    }
    private void revisionConflict(long current) {
        throw new ArchiveMaintenanceException(409, "ARCHIVE_REVISION_CONFLICT",
                "Archive revision no longer matches",
                Map.of("currentRevision", Long.toString(current)));
    }
    private void invalid(String message) { throw error(400, "INVALID_REQUEST", message); }
    private void notFound() { throw error(404, "ARCHIVE_RESOURCE_NOT_FOUND", "Archive resource is not available"); }
    private void forbidden(String code, String message) { throw error(403, code, message); }
    private void conflict(String code, String message) { throw error(409, code, message); }
    private ArchiveMaintenanceException error(int status, String code, String message) {
        return new ArchiveMaintenanceException(status, code, message);
    }
    private record LockedJobIdentity(boolean current) { }
    private record LockedJobAssignment(ArchiveMaintenanceStore.Slot slot,
            ArchiveAppointmentRecord appointment) { }
    private record CurrentJobAssignment(String status, String assignedAgentId,
            String permissionProfile, ArchiveJobHandlingFactsDTO.AssignmentSnapshot snapshot,
            String blocker) { }
    private record RuntimeAuthorization(ArchiveJobRunRecord run,
            ArchiveExecutionGrantRecord grant, ArchiveAgentExecutionPort.Inspection inspection) { }
    private record ResolvedWork(String workId, String canonicalKey, String title) { }

    private record PublicationMaterial(ArchiveEditionRecord edition,
            List<ArchiveBlockRecord> blocks, List<ArchiveParagraphRecord> paragraphs) { }
    private record AdminOperationSpec(ArchiveDraftRecord draft, String action) { }

    private record EditionHistorySnapshot(long workRevision, String activeEditionId,
            List<ArchiveEditionVersionRecord> versions) { }

    private record ReadbackOutcome(String state, String digest, List<String> findings) { }

    private record PublicationCandidate(ArchiveMaintenanceJobRecord job,
            ArchiveSourceSnapshotRecord source, ArchiveDraftRecord draft,
            ArchiveValidationRecord validation, PublicationMaterial material) { }
    private record ExecutionAdmission(ArchiveExecutionDTO value, boolean expired) { }

}
