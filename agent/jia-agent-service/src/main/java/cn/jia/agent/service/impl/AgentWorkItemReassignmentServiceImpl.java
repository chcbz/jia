package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.AgentWorkItemReassignmentDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandTransportWriteResult;
import cn.jia.agent.entity.AgentHallCommandContext;
import cn.jia.agent.entity.AgentHallCommandPayload;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentTaskEventWriteResult;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentEntity;
import cn.jia.agent.entity.AgentWorkItemReassignmentLeaseDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentLeaseRequestDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentRequestDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentResultDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.exception.AgentWorkItemReassignmentException;
import cn.jia.agent.exception.AgentWorkItemReassignmentException.Reason;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.agent.service.AgentWorkItemReassignmentService;
import cn.jia.agent.state.AgentTaskMemberStatus;
import cn.jia.agent.state.AgentTaskStatus;
import cn.jia.agent.state.AgentTaskWorkItemStatus;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** E05 one-CAS expired-lease reassignment with a permanent command-bound receipt. */
@Named
public class AgentWorkItemReassignmentServiceImpl implements AgentWorkItemReassignmentService {
    private static final Pattern CANONICAL_AGENT_ID = Pattern.compile("agt_[0-9a-f]{32}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<AgentTaskMemberStatus> ACTIVE_MEMBER_STATUSES = Set.of(
            AgentTaskMemberStatus.ACCEPTED, AgentTaskMemberStatus.WORKING);
    private static final Set<String> MEMBER_ROLES = Set.of(
            "coordinator", "worker", "reviewer", "observer");
    private static final Set<String> RUNTIME_STATUSES = Set.of(
            AgentConstants.STATUS_ONLINE, AgentConstants.STATUS_BUSY,
            AgentConstants.STATUS_OFFLINE, AgentConstants.STATUS_ERROR);
    private static final Set<String> SENSITIVE_REASON_MARKERS = Set.of(
            "authorization", "bearer ", "basic ", "api-key", "api_key",
            "cookie", "credential", "private key", "lease token", "password");
    private static final int MAX_LEASE_TOKEN_LENGTH = 100;
    private static final long MAX_LEASE_DURATION_MILLIS = 900_000;

    private final AgentWorkItemReassignmentDao reassignmentDao;
    private final AgentTaskMemberDao memberDao;
    private final AgentTaskWorkItemDao workItemDao;
    private final AgentTaskMutationTransaction mutationTransaction;
    private final AgentIdentityService identityService;
    private final AgentService agentService;
    private final AgentTaskEventWriter eventWriter;
    private final AgentCommandTransportWriter commandWriter;
    private final AgentWorkItemLeaseService leaseService;
    private final LongSupplier clock;
    private final Supplier<String> tokenGenerator;
    private final long leaseDurationMillis;

    @Inject
    public AgentWorkItemReassignmentServiceImpl(
            AgentWorkItemReassignmentDao reassignmentDao,
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao,
            AgentTaskMutationTransaction mutationTransaction,
            AgentIdentityService identityService,
            AgentService agentService,
            AgentTaskEventWriter eventWriter,
            AgentCommandTransportWriter commandWriter,
            AgentWorkItemLeaseService leaseService,
            @Value("${jia.agent.work-item-reassignment.lease-duration-ms:300000}")
            long leaseDurationMillis) {
        this(reassignmentDao, memberDao, workItemDao, mutationTransaction, identityService,
                agentService, eventWriter, commandWriter, leaseService,
                System::currentTimeMillis, AgentWorkItemReassignmentServiceImpl::secureToken,
                leaseDurationMillis);
    }

    AgentWorkItemReassignmentServiceImpl(
            AgentWorkItemReassignmentDao reassignmentDao,
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao,
            AgentTaskMutationTransaction mutationTransaction,
            AgentIdentityService identityService,
            AgentService agentService,
            AgentTaskEventWriter eventWriter,
            AgentCommandTransportWriter commandWriter,
            AgentWorkItemLeaseService leaseService,
            LongSupplier clock, Supplier<String> tokenGenerator, long leaseDurationMillis) {
        this.reassignmentDao = Objects.requireNonNull(reassignmentDao);
        this.memberDao = Objects.requireNonNull(memberDao);
        this.workItemDao = Objects.requireNonNull(workItemDao);
        this.mutationTransaction = Objects.requireNonNull(mutationTransaction);
        this.identityService = Objects.requireNonNull(identityService);
        this.agentService = Objects.requireNonNull(agentService);
        this.eventWriter = Objects.requireNonNull(eventWriter);
        this.commandWriter = Objects.requireNonNull(commandWriter);
        this.leaseService = Objects.requireNonNull(leaseService);
        this.clock = Objects.requireNonNull(clock);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        if (leaseDurationMillis <= 0 || leaseDurationMillis > MAX_LEASE_DURATION_MILLIS) {
            throw new IllegalArgumentException("reassignment lease duration must be within 1..900000ms");
        }
        this.leaseDurationMillis = leaseDurationMillis;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentWorkItemReassignmentResultDTO reassign(
            String tenantId, String clientId, String operatorSubject,
            String coordinatorAgentId, String taskId, String workItemId,
            String idempotencyKey, AgentWorkItemReassignmentRequestDTO request) {
        RequiredRequest required = requireRequest(tenantId, clientId, operatorSubject,
                coordinatorAgentId, taskId, workItemId, idempotencyKey, request);
        return withRoot(tenantId, clientId, taskId, root -> reassignLocked(
                tenantId, clientId, taskId, workItemId, required, root));
    }

    private AgentWorkItemReassignmentResultDTO reassignLocked(
            String tenantId, String clientId, String taskId, String workItemId,
            RequiredRequest required, AgentTaskMetaEntity root) {
        String reassignmentId = reassignmentId(
                tenantId, clientId, taskId, workItemId, required.idempotencyKey());
        String requestDigest = requestDigest(
                tenantId, clientId, taskId, workItemId, required);
        AgentWorkItemReassignmentEntity prior = reassignmentDao.findByReassignmentIdForUpdate(
                tenantId, clientId, taskId, workItemId, reassignmentId);
        if (prior != null) {
            requireReceipt(prior, tenantId, clientId, taskId, workItemId, reassignmentId);
            if (!requestDigest.equals(prior.getRequestSha256())) {
                throw failure(Reason.IDEMPOTENCY_CONFLICT,
                        "Idempotency key is permanently bound to a different request");
            }
            return result(prior, true);
        }
        requireTask(root, required);
        AgentWorkItemReassignmentEntity latest = reassignmentDao.findLatestByWorkItemForUpdate(
                tenantId, clientId, taskId, workItemId);

        List<String> agents = sortedDistinct(required.coordinatorAgentId(),
                required.previousAgentId(), required.targetAgentId());
        for (String agentId : agents) {
            requireActiveMemberLocked(tenantId, clientId, taskId, agentId,
                    agentId.equals(required.coordinatorAgentId()));
        }
        lockIdentityAndRuntime(tenantId, clientId, agents);
        try {
            agentService.requireHostingNewWork(tenantId, clientId, required.targetAgentId());
        } catch (RuntimeException denied) {
            throw failure(Reason.NOT_FOUND_OR_FORBIDDEN,
                    "Target cannot accept new hosted work", denied);
        }

        AgentTaskWorkItemEntity current = requireCurrentWorkItem(
                tenantId, clientId, taskId, workItemId, required.expectedWorkItemVersion());
        long now = now();
        requireExpiredLease(current, required, now);
        String oldFence = sha256(current.getLeaseToken());
        if (latest != null) {
            requireReceipt(latest, tenantId, clientId, taskId, workItemId,
                    latest.getReassignmentId());
            if (!required.sourceCommandId().equals(latest.getCommandId())
                    || !required.previousAgentId().equals(latest.getTargetAgentId())
                    || !oldFence.equals(latest.getLeaseFenceSha256())) {
                throw failure(Reason.INVALID_SOURCE_COMMAND,
                        "Source command is not bound to the current reassigned lease");
            }
        }
        requireSourceCommand(tenantId, clientId, taskId, workItemId, required);

        if (current.getAttemptCount() >= current.getMaxAttempts() - 1) {
            throw failure(Reason.DOMAIN_ATTEMPTS_EXHAUSTED,
                    "No bounded domain attempt remains after the expired lease");
        }
        int nextAttempt = current.getAttemptCount() + 1;
        long leaseUntil = add(now, leaseDurationMillis);
        String leaseToken = token();
        String leaseFence = sha256(leaseToken);
        AgentTaskWorkItemDTO update = copy(current);
        update.setAssigneeAgentId(required.targetAgentId());
        update.setStatus(AgentTaskWorkItemStatus.CLAIMED.value());
        update.setLeaseToken(leaseToken);
        update.setLeaseUntil(leaseUntil);
        update.setAttemptCount(nextAttempt);
        int updated = workItemDao.reassignExpiredLeaseByVersion(
                tenantId, clientId, taskId, workItemId,
                required.previousAgentId(), current.getLeaseToken(), current.getStatus(),
                current.getLeaseUntil(), current.getVersion(), now, update);
        if (updated == 0) throw failure(Reason.VERSION_CONFLICT, "Expired lease CAS lost");
        if (updated != 1) throw failure(Reason.INVALID_PERSISTED_STATE,
                "Expired lease CAS changed an unexpected row count");

        long resultVersion = current.getVersion() + 1;
        String intentId = "rsi_" + sha256("intent\0" + reassignmentId);
        String commandId = AgentCommandCanonicalCodec.hallCommandId(
                tenantId, clientId, taskId, required.targetAgentId(), intentId,
                AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE);
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.WORK_ITEM_ID, workItemId)
                .put(TaskEventPayload.Key.COORDINATOR_AGENT_ID, required.coordinatorAgentId())
                .put(TaskEventPayload.Key.PREVIOUS_AGENT_ID, required.previousAgentId())
                .put(TaskEventPayload.Key.TARGET_AGENT_ID, required.targetAgentId())
                .put(TaskEventPayload.Key.FROM_STATUS, current.getStatus())
                .put(TaskEventPayload.Key.TO_STATUS, AgentTaskWorkItemStatus.CLAIMED.value())
                .put(TaskEventPayload.Key.EXPECTED_VERSION, current.getVersion())
                .put(TaskEventPayload.Key.RESULT_VERSION, resultVersion)
                .put(TaskEventPayload.Key.ATTEMPT_COUNT, nextAttempt)
                .put(TaskEventPayload.Key.MAX_ATTEMPTS, current.getMaxAttempts())
                .put(TaskEventPayload.Key.PREVIOUS_LEASE_EXPIRES_AT, current.getLeaseUntil())
                .put(TaskEventPayload.Key.LEASE_EXPIRES_AT, leaseUntil)
                .put(TaskEventPayload.Key.REASSIGNMENT_ID, reassignmentId)
                .put(TaskEventPayload.Key.SOURCE_COMMAND_ID, required.sourceCommandId())
                .put(TaskEventPayload.Key.COMMAND_ID, commandId)
                .put(TaskEventPayload.Key.REQUEST_DIGEST, requestDigest)
                .put(TaskEventPayload.Key.LEASE_FENCE_SHA256, leaseFence);
        AgentTaskEventWriteResult event = eventWriter.append(
                AgentTaskMutationEventSupport.command(
                        tenantId, clientId, taskId, TaskEventType.WORK_ITEM_REASSIGNED,
                        TaskEventType.ActorType.AGENT, required.coordinatorAgentId(),
                        TaskEventType.Aggregate.WORK_ITEM, workItemId,
                        payload, now, resultVersion));
        String eventId = event == null || event.getEvent() == null
                ? null : event.getEvent().getEventId();
        if (!exact(eventId, 100)) {
            throw failure(Reason.INVALID_PERSISTED_STATE,
                    "Reassignment event writer returned an invalid identity");
        }

        AgentCommandDraft draft = commandDraft(tenantId, clientId, taskId, workItemId,
                required, current, intentId, commandId, eventId, now);
        AgentCommandTransportWriteResult transport = commandWriter.writeAuthorizedHall(
                draft, required.coordinatorAgentId());
        if (transport == null || transport.duplicate()
                || !commandId.equals(transport.commandId())
                || !exact(transport.messageId(), 100)
                || !exact(transport.outboxEventId(), 100)) {
            throw failure(Reason.INVALID_PERSISTED_STATE,
                    "New reassignment command identity was not inserted exactly once");
        }

        AgentWorkItemReassignmentEntity receipt = new AgentWorkItemReassignmentEntity()
                .setReassignmentId(reassignmentId)
                .setRequestSha256(requestDigest)
                .setTaskId(taskId)
                .setWorkItemId(workItemId)
                .setOperatorSubject(required.operatorSubject())
                .setCoordinatorAgentId(required.coordinatorAgentId())
                .setPreviousAgentId(required.previousAgentId())
                .setTargetAgentId(required.targetAgentId())
                .setSourceCommandId(required.sourceCommandId())
                .setCommandId(commandId)
                .setMessageId(transport.messageId())
                .setOutboxEventId(transport.outboxEventId())
                .setExpectedWorkItemVersion(current.getVersion())
                .setResultWorkItemVersion(resultVersion)
                .setTaskVersion(root.getTaskVersion())
                .setLeaseFenceSha256(leaseFence)
                .setPreviousLeaseUntil(current.getLeaseUntil())
                .setLeaseUntil(leaseUntil)
                .setAttemptCount(nextAttempt)
                .setMaxAttempts(current.getMaxAttempts());
        receipt.setTenantId(tenantId);
        receipt.setClientId(clientId);
        receipt.setCreateTime(now);
        receipt.setUpdateTime(now);
        if (reassignmentDao.insert(receipt) != 1 || receipt.getId() == null || receipt.getId() <= 0) {
            throw failure(Reason.INVALID_PERSISTED_STATE,
                    "Reassignment receipt was not inserted exactly once");
        }
        return result(receipt, false);
    }

    @Override
    public AgentWorkItemReassignmentLeaseDTO readLease(
            String tenantId, String clientId, String targetAgentId,
            String taskId, String workItemId, String reassignmentId,
            AgentWorkItemReassignmentLeaseRequestDTO request) {
        return leaseAccess(tenantId, clientId, targetAgentId, taskId, workItemId,
                reassignmentId, request, LeaseOperation.READ);
    }

    @Override
    public AgentWorkItemReassignmentLeaseDTO startLease(
            String tenantId, String clientId, String targetAgentId,
            String taskId, String workItemId, String reassignmentId,
            AgentWorkItemReassignmentLeaseRequestDTO request) {
        return leaseAccess(tenantId, clientId, targetAgentId, taskId, workItemId,
                reassignmentId, request, LeaseOperation.START);
    }

    @Override
    public AgentWorkItemReassignmentLeaseDTO heartbeatLease(
            String tenantId, String clientId, String targetAgentId,
            String taskId, String workItemId, String reassignmentId,
            AgentWorkItemReassignmentLeaseRequestDTO request) {
        return leaseAccess(tenantId, clientId, targetAgentId, taskId, workItemId,
                reassignmentId, request, LeaseOperation.HEARTBEAT);
    }

    @Transactional(rollbackFor = Exception.class)
    protected AgentWorkItemReassignmentLeaseDTO leaseAccess(
            String tenantId, String clientId, String targetAgentId,
            String taskId, String workItemId, String reassignmentId,
            AgentWorkItemReassignmentLeaseRequestDTO request, LeaseOperation operation) {
        requireScope(tenantId, clientId, taskId, workItemId);
        requireAgent(targetAgentId, "targetAgentId");
        if (operation == null || !exact(reassignmentId, 100) || request == null
                || !exact(request.getCommandId(), 100)
                || request.getExpectedWorkItemVersion() == null
                || request.getExpectedWorkItemVersion() < 0
                || request.getExpectedWorkItemVersion() == Long.MAX_VALUE
                || (operation == LeaseOperation.HEARTBEAT
                && (request.getLeaseDurationMillis() == null
                || request.getLeaseDurationMillis() <= 0
                || request.getLeaseDurationMillis() > MAX_LEASE_DURATION_MILLIS))
                || (operation != LeaseOperation.HEARTBEAT
                && request.getLeaseDurationMillis() != null)) {
            throw failure(Reason.INVALID_REQUEST, "Invalid command-bound lease request");
        }
        return withRoot(tenantId, clientId, taskId, root -> {
            AgentWorkItemReassignmentEntity receipt = reassignmentDao
                    .findByReassignmentIdForUpdate(
                            tenantId, clientId, taskId, workItemId, reassignmentId);
            if (receipt == null) throw unavailable();
            requireReceipt(receipt, tenantId, clientId, taskId, workItemId, reassignmentId);
            if (!targetAgentId.equals(receipt.getTargetAgentId())
                    || !request.getCommandId().equals(receipt.getCommandId())) {
                throw unavailable();
            }
            requireActiveMemberLocked(tenantId, clientId, taskId, targetAgentId, false);
            lockIdentityAndRuntime(tenantId, clientId, List.of(targetAgentId));
            AgentTaskWorkItemEntity current = requireCurrentWorkItem(
                    tenantId, clientId, taskId, workItemId,
                    request.getExpectedWorkItemVersion());
            AgentTaskWorkItemStatus status = persistedStatus(current.getStatus());
            long accessTime = now();
            if ((status != AgentTaskWorkItemStatus.CLAIMED
                    && status != AgentTaskWorkItemStatus.RUNNING)
                    || !targetAgentId.equals(current.getAssigneeAgentId())
                    || !validToken(current.getLeaseToken())
                    || current.getLeaseUntil() == null || current.getLeaseUntil() <= accessTime
                    || current.getVersion() < receipt.getResultWorkItemVersion()
                    || !current.getAttemptCount().equals(receipt.getAttemptCount())
                    || !current.getMaxAttempts().equals(receipt.getMaxAttempts())
                    || !receipt.getLeaseFenceSha256().equals(sha256(current.getLeaseToken()))) {
                throw unavailable();
            }
            if (operation == LeaseOperation.READ) {
                return leaseResult(receipt, current, current.getVersion(), accessTime);
            }
            AgentWorkItemLeaseCommandDTO command = new AgentWorkItemLeaseCommandDTO();
            command.setAgentId(targetAgentId);
            command.setLeaseToken(current.getLeaseToken());
            command.setExpectedVersion(current.getVersion());
            command.setLeaseDurationMillis(request.getLeaseDurationMillis());
            try {
                AgentWorkItemLeaseDTO changed = operation == LeaseOperation.START
                        ? leaseService.start(tenantId, clientId, taskId, workItemId, command)
                        : leaseService.heartbeat(tenantId, clientId, taskId, workItemId, command);
                requireLeaseMutationResult(receipt, current, changed, operation, targetAgentId);
                return leaseResult(receipt, changed);
            } catch (AgentTaskStateException state) {
                throw switch (state.getReason()) {
                    case VERSION_CONFLICT -> failure(Reason.VERSION_CONFLICT,
                            "Lease version changed");
                    case INVALID_REQUEST -> failure(Reason.INVALID_REQUEST,
                            "Invalid lease operation request");
                    case INVALID_PERSISTED_STATE -> failure(Reason.INVALID_PERSISTED_STATE,
                            "Lease service returned invalid persisted state", state);
                    default -> unavailable();
                };
            }
        });
    }

    private AgentCommandDraft commandDraft(
            String tenantId, String clientId, String taskId, String workItemId,
            RequiredRequest required, AgentTaskWorkItemEntity current,
            String intentId, String commandId, String eventId, long now) {
        String instruction = boundedInstruction(current.getTitle(), current.getDescription());
        AgentHallCommandPayload payload = new AgentHallCommandPayload(
                "work_item_execute", instruction, "juyiting", "lease_expired_reassignment",
                null, eventId, "autonomous", Boolean.FALSE,
                new AgentHallCommandContext(null, current.getTitle(), null, null,
                        Long.toString(current.getVersion() + 1),
                        List.of(required.sourceCommandId()), List.of("lease-expired", "reassignment")));
        return new AgentCommandDraft(
                AgentCommandCanonicalCodec.SCHEMA_VERSION, commandId, taskId, eventId,
                tenantId, clientId, taskId, workItemId, required.targetAgentId(),
                AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE, now,
                add(now, AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS), intentId, payload);
    }

    private void requireTask(AgentTaskMetaEntity root, RequiredRequest request) {
        if (root == null || !request.taskId().equals(root.getTaskId())
                || root.getTaskVersion() == null || root.getTaskVersion() < 0
                || root.getTaskVersion() == Long.MAX_VALUE) {
            throw failure(Reason.INVALID_PERSISTED_STATE, "Task root is invalid");
        }
        if (root.getTaskVersion() != request.expectedTaskVersion()) {
            throw failure(Reason.VERSION_CONFLICT, "Task version changed");
        }
        AgentTaskStatus status;
        try {
            status = AgentTaskStatus.fromPersistedValue(root.getRewardStatus());
        } catch (IllegalArgumentException invalid) {
            throw failure(Reason.INVALID_PERSISTED_STATE, "Task status is invalid");
        }
        if (!Set.of(AgentTaskStatus.ASSIGNED, AgentTaskStatus.RUNNING,
                AgentTaskStatus.REVIEWING, AgentTaskStatus.BLOCKED).contains(status)) {
            throw failure(Reason.NOT_FOUND_OR_FORBIDDEN, "Task does not accept reassignment");
        }
        if (!request.coordinatorAgentId().equals(root.getCoordinatorAgentId())) {
            throw unavailable();
        }
    }

    private void requireActiveMemberLocked(
            String tenantId, String clientId, String taskId, String agentId,
            boolean coordinator) {
        AgentTaskMemberEntity member = memberDao.findByTaskAndAgentForUpdate(
                tenantId, clientId, taskId, agentId);
        if (member == null || !tenantId.equals(member.getTenantId())
                || !clientId.equals(member.getClientId())
                || !taskId.equals(member.getTaskId()) || !agentId.equals(member.getAgentId())
                || !MEMBER_ROLES.contains(member.getMemberRole())
                || coordinator && !"coordinator".equals(member.getMemberRole())) {
            throw unavailable();
        }
        try {
            if (!ACTIVE_MEMBER_STATUSES.contains(
                    AgentTaskMemberStatus.fromPersistedValue(member.getMemberStatus()))) {
                throw unavailable();
            }
        } catch (IllegalArgumentException invalid) {
            throw failure(Reason.INVALID_PERSISTED_STATE, "Member status is invalid");
        }
    }

    private void lockIdentityAndRuntime(
            String tenantId, String clientId, List<String> sortedAgents) {
        List<String> locked;
        try {
            locked = identityService.lockActiveCanonicalAgentIdsInScope(
                    tenantId, clientId, tenantId, sortedAgents);
        } catch (RuntimeException denied) {
            throw unavailable();
        }
        if (!sortedAgents.equals(locked)) throw unavailable();
        for (String agentId : sortedAgents) {
            try {
                AgentRuntimeDTO runtime = agentService.requireApiKeyOwnedAgentForUpdate(
                        clientId, tenantId, agentId);
                if (runtime == null || !agentId.equals(runtime.getAgentId())
                        || !RUNTIME_STATUSES.contains(runtime.getStatus())) {
                    throw unavailable();
                }
            } catch (AgentWorkItemReassignmentException denied) {
                throw denied;
            } catch (RuntimeException denied) {
                throw unavailable();
            }
        }
    }

    private AgentTaskWorkItemEntity requireCurrentWorkItem(
            String tenantId, String clientId, String taskId, String workItemId,
            long expectedVersion) {
        AgentTaskWorkItemEntity current = workItemDao.findByTaskAndWorkItemId(
                tenantId, clientId, taskId, workItemId);
        if (current == null) throw unavailable();
        if (!tenantId.equals(current.getTenantId()) || !clientId.equals(current.getClientId())
                || !taskId.equals(current.getTaskId()) || !workItemId.equals(current.getWorkItemId())
                || current.getVersion() == null || current.getVersion() < 0
                || current.getVersion() == Long.MAX_VALUE
                || current.getAttemptCount() == null || current.getMaxAttempts() == null
                || current.getAttemptCount() < 0 || current.getMaxAttempts() <= 0
                || current.getAttemptCount() > current.getMaxAttempts()
                || !exact(current.getTitle(), 500) || !exact(current.getWorkType(), 100)
                || current.getPriority() == null || current.getRequiredItem() == null) {
            throw failure(Reason.INVALID_PERSISTED_STATE, "Work item snapshot is invalid");
        }
        if (current.getVersion() != expectedVersion) {
            throw failure(Reason.VERSION_CONFLICT, "Work item version changed");
        }
        persistedStatus(current.getStatus());
        return current;
    }

    private void requireExpiredLease(
            AgentTaskWorkItemEntity current, RequiredRequest required, long now) {
        AgentTaskWorkItemStatus status = persistedStatus(current.getStatus());
        if (status != AgentTaskWorkItemStatus.CLAIMED
                && status != AgentTaskWorkItemStatus.RUNNING) {
            throw failure(Reason.NOT_FOUND_OR_FORBIDDEN,
                    "Only an active lease can be authoritatively expired");
        }
        if (!required.previousAgentId().equals(current.getAssigneeAgentId())
                || !validToken(current.getLeaseToken())
                || current.getLeaseUntil() == null || current.getLeaseUntil() <= 0) {
            throw unavailable();
        }
        if (current.getLeaseUntil() > now) {
            throw failure(Reason.LEASE_NOT_EXPIRED,
                    "A live lease cannot be reassigned");
        }
    }

    private void requireSourceCommand(
            String tenantId, String clientId, String taskId, String workItemId,
            RequiredRequest required) {
        AgentCommandDeliveryEntity delivery = reassignmentDao.findSourceCommand(
                tenantId, clientId, required.sourceCommandId());
        if (delivery == null) throw failure(Reason.INVALID_SOURCE_COMMAND,
                "Source command is unavailable");
        try {
            AgentCommandDraft draft = AgentCommandCanonicalCodec.decodeBusinessBytes(
                    delivery.getCommandPayload());
            byte[] digest = AgentCommandCanonicalCodec.sha256(delivery.getCommandPayload());
            if (!MessageDigest.isEqual(digest, delivery.getCommandPayloadHash())
                    || !required.sourceCommandId().equals(delivery.getCommandId())
                    || !taskId.equals(delivery.getTaskId())
                    || !workItemId.equals(delivery.getWorkItemId())
                    || !required.previousAgentId().equals(delivery.getTargetAgentId())
                    || !AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE.equals(
                    delivery.getCommandType())
                    || !tenantId.equals(delivery.getTenantId())
                    || !clientId.equals(delivery.getClientId())
                    || !required.sourceCommandId().equals(draft.commandId())
                    || !tenantId.equals(draft.tenantId())
                    || !clientId.equals(draft.clientId())
                    || !taskId.equals(draft.taskId())
                    || !workItemId.equals(draft.workItemId())
                    || !required.previousAgentId().equals(draft.targetAgentId())
                    || !AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE.equals(
                    draft.commandType())) {
                throw failure(Reason.INVALID_SOURCE_COMMAND,
                        "Source command linkage is invalid");
            }
            // Deliberately do not inspect delivery.status/attempt_count/no-ACK state here.
        } catch (AgentWorkItemReassignmentException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw failure(Reason.INVALID_SOURCE_COMMAND,
                    "Source command bytes are not canonical", invalid);
        }
    }

    private RequiredRequest requireRequest(
            String tenantId, String clientId, String operatorSubject,
            String coordinatorAgentId, String taskId, String workItemId,
            String idempotencyKey, AgentWorkItemReassignmentRequestDTO request) {
        requireScope(tenantId, clientId, taskId, workItemId);
        if (!exact(operatorSubject, 100) || !exact(coordinatorAgentId, 100)
                || idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~:/+\\-]{8,128}")
                || request == null || request.getExpectedTaskVersion() == null
                || request.getExpectedTaskVersion() < 0
                || request.getExpectedTaskVersion() == Long.MAX_VALUE
                || request.getExpectedWorkItemVersion() == null
                || request.getExpectedWorkItemVersion() < 0
                || request.getExpectedWorkItemVersion() == Long.MAX_VALUE
                || !exact(request.getReason(), 1000)
                || containsSensitiveReason(request.getReason())
                || !exact(request.getSourceCommandId(), 100)) {
            throw failure(Reason.INVALID_REQUEST, "Invalid reassignment request");
        }
        requireAgent(coordinatorAgentId, "coordinatorAgentId");
        requireAgent(request.getExpectedPreviousAgentId(), "expectedPreviousAgentId");
        requireAgent(request.getTargetAgentId(), "targetAgentId");
        if (request.getExpectedPreviousAgentId().equals(request.getTargetAgentId())) {
            throw failure(Reason.INVALID_REQUEST, "Target must differ from previous agent");
        }
        return new RequiredRequest(operatorSubject, coordinatorAgentId,
                request.getExpectedTaskVersion(), request.getExpectedWorkItemVersion(),
                request.getExpectedPreviousAgentId(), request.getTargetAgentId(),
                request.getSourceCommandId(), request.getReason(), idempotencyKey, taskId);
    }

    private void requireReceipt(
            AgentWorkItemReassignmentEntity value, String tenantId, String clientId,
            String taskId, String workItemId, String reassignmentId) {
        if (value == null || value.getId() == null || value.getId() <= 0
                || !tenantId.equals(value.getTenantId())
                || !clientId.equals(value.getClientId()) || !taskId.equals(value.getTaskId())
                || !workItemId.equals(value.getWorkItemId())
                || !reassignmentId.equals(value.getReassignmentId())
                || !exact(value.getOperatorSubject(), 100)
                || !canonicalAgent(value.getCoordinatorAgentId())
                || !canonicalAgent(value.getPreviousAgentId())
                || !canonicalAgent(value.getTargetAgentId())
                || value.getPreviousAgentId().equals(value.getTargetAgentId())
                || !exact(value.getSourceCommandId(), 100)
                || !exact(value.getCommandId(), 100)
                || value.getSourceCommandId().equals(value.getCommandId())
                || !exact(value.getMessageId(), 100) || !exact(value.getOutboxEventId(), 100)
                || !digest(value.getRequestSha256()) || !digest(value.getLeaseFenceSha256())
                || value.getExpectedWorkItemVersion() == null
                || value.getExpectedWorkItemVersion() < 0
                || value.getExpectedWorkItemVersion() == Long.MAX_VALUE
                || value.getResultWorkItemVersion() == null
                || value.getResultWorkItemVersion().longValue()
                != value.getExpectedWorkItemVersion() + 1
                || value.getTaskVersion() == null || value.getTaskVersion() < 0
                || value.getPreviousLeaseUntil() == null || value.getPreviousLeaseUntil() <= 0
                || value.getLeaseUntil() == null
                || value.getLeaseUntil() <= value.getPreviousLeaseUntil()
                || value.getAttemptCount() == null || value.getMaxAttempts() == null
                || value.getAttemptCount() <= 0
                || value.getAttemptCount() >= value.getMaxAttempts()
                || value.getCreateTime() == null || value.getCreateTime() <= 0
                || !value.getCreateTime().equals(value.getUpdateTime())) {
            throw failure(Reason.INVALID_PERSISTED_STATE, "Reassignment receipt is invalid");
        }
    }

    private AgentWorkItemReassignmentResultDTO result(
            AgentWorkItemReassignmentEntity receipt, boolean replay) {
        AgentWorkItemReassignmentResultDTO result = new AgentWorkItemReassignmentResultDTO();
        result.setReassignmentId(receipt.getReassignmentId());
        result.setTaskId(receipt.getTaskId());
        result.setWorkItemId(receipt.getWorkItemId());
        result.setPreviousAgentId(receipt.getPreviousAgentId());
        result.setTargetAgentId(receipt.getTargetAgentId());
        result.setSourceCommandId(receipt.getSourceCommandId());
        result.setCommandId(receipt.getCommandId());
        result.setMessageId(receipt.getMessageId());
        result.setStatus(AgentTaskWorkItemStatus.CLAIMED.value());
        result.setTaskVersion(receipt.getTaskVersion());
        result.setWorkItemVersion(receipt.getResultWorkItemVersion());
        result.setLeaseUntil(receipt.getLeaseUntil());
        result.setAttemptCount(receipt.getAttemptCount());
        result.setMaxAttempts(receipt.getMaxAttempts());
        result.setCreatedAt(receipt.getCreateTime());
        result.setIdempotentReplay(replay);
        return result;
    }


    private void requireLeaseMutationResult(
            AgentWorkItemReassignmentEntity receipt, AgentTaskWorkItemEntity before,
            AgentWorkItemLeaseDTO changed, LeaseOperation operation, String targetAgentId) {
        if (changed == null || changed.getLeaseUntil() == null
                || changed.getVersion() == null || changed.getChangedAt() == null) {
            throw invalidLeaseMutationResult();
        }
        long beforeVersion = before.getVersion();
        long changedVersion = changed.getVersion();
        long beforeLeaseUntil = before.getLeaseUntil();
        long changedLeaseUntil = changed.getLeaseUntil();
        boolean invalidTransition = switch (operation) {
            case START -> changedVersion != beforeVersion + 1
                    || changedLeaseUntil != beforeLeaseUntil
                    || !AgentTaskWorkItemStatus.RUNNING.value().equals(changed.getStatus());
            case HEARTBEAT -> !before.getStatus().equals(changed.getStatus())
                    || (changedVersion == beforeVersion
                    && changedLeaseUntil != beforeLeaseUntil)
                    || (changedVersion == beforeVersion + 1
                    && changedLeaseUntil <= beforeLeaseUntil);
            case READ -> true;
        };
        if (!before.getTaskId().equals(changed.getTaskId())
                || !before.getWorkItemId().equals(changed.getWorkItemId())
                || !targetAgentId.equals(changed.getAgentId())
                || !before.getLeaseToken().equals(changed.getLeaseToken())
                || !receipt.getLeaseFenceSha256().equals(sha256(changed.getLeaseToken()))
                || changedVersion < beforeVersion || changedVersion > beforeVersion + 1
                || invalidTransition
                || !before.getAttemptCount().equals(changed.getAttemptCount())
                || !before.getMaxAttempts().equals(changed.getMaxAttempts())
                || changed.getChangedAt() <= 0) {
            throw invalidLeaseMutationResult();
        }
    }

    private AgentWorkItemReassignmentException invalidLeaseMutationResult() {
        return failure(Reason.INVALID_PERSISTED_STATE,
                "Lease service returned an inconsistent command-bound result");
    }

    private AgentWorkItemReassignmentLeaseDTO leaseResult(
            AgentWorkItemReassignmentEntity receipt, AgentTaskWorkItemEntity item,
            long version, long changedAt) {
        AgentWorkItemReassignmentLeaseDTO result = new AgentWorkItemReassignmentLeaseDTO();
        result.setReassignmentId(receipt.getReassignmentId());
        result.setCommandId(receipt.getCommandId());
        result.setTaskId(item.getTaskId());
        result.setWorkItemId(item.getWorkItemId());
        result.setAgentId(item.getAssigneeAgentId());
        result.setStatus(item.getStatus());
        result.setLeaseToken(item.getLeaseToken());
        result.setLeaseUntil(item.getLeaseUntil());
        result.setWorkItemVersion(version);
        result.setAttemptCount(item.getAttemptCount());
        result.setMaxAttempts(item.getMaxAttempts());
        result.setChangedAt(changedAt);
        return result;
    }

    private AgentWorkItemReassignmentLeaseDTO leaseResult(
            AgentWorkItemReassignmentEntity receipt, AgentWorkItemLeaseDTO item) {
        AgentWorkItemReassignmentLeaseDTO result = new AgentWorkItemReassignmentLeaseDTO();
        result.setReassignmentId(receipt.getReassignmentId());
        result.setCommandId(receipt.getCommandId());
        result.setTaskId(item.getTaskId());
        result.setWorkItemId(item.getWorkItemId());
        result.setAgentId(item.getAgentId());
        result.setStatus(item.getStatus());
        result.setLeaseToken(item.getLeaseToken());
        result.setLeaseUntil(item.getLeaseUntil());
        result.setWorkItemVersion(item.getVersion());
        result.setAttemptCount(item.getAttemptCount());
        result.setMaxAttempts(item.getMaxAttempts());
        result.setChangedAt(item.getChangedAt());
        return result;
    }

    private AgentTaskWorkItemDTO copy(AgentTaskWorkItemEntity current) {
        AgentTaskWorkItemDTO update = new AgentTaskWorkItemDTO();
        update.setWorkItemId(current.getWorkItemId());
        update.setTaskId(current.getTaskId());
        update.setTitle(current.getTitle());
        update.setDescription(current.getDescription());
        update.setWorkType(current.getWorkType());
        update.setRequiredAbilities(current.getRequiredAbilities());
        update.setAssigneeAgentId(current.getAssigneeAgentId());
        update.setStatus(current.getStatus());
        update.setPriority(current.getPriority());
        update.setRequiredItem(current.getRequiredItem());
        update.setDependencyJson(current.getDependencyJson());
        update.setLeaseToken(current.getLeaseToken());
        update.setLeaseUntil(current.getLeaseUntil());
        update.setAttemptCount(current.getAttemptCount());
        update.setMaxAttempts(current.getMaxAttempts());
        update.setResultArtifactId(current.getResultArtifactId());
        update.setSubmittedAt(current.getSubmittedAt());
        update.setCompletedAt(current.getCompletedAt());
        update.setVersion(current.getVersion());
        return update;
    }

    private <T> T withRoot(String tenantId, String clientId, String taskId,
            AgentTaskMutationTransaction.LockedTaskMutation<T> action) {
        try {
            return mutationTransaction.executeWithLockedTaskRoot(
                    tenantId, clientId, taskId, action);
        } catch (AgentWorkItemReassignmentException known) {
            throw known;
        } catch (AgentTaskCollaborationException missing) {
            throw unavailable();
        }
    }

    private AgentTaskWorkItemStatus persistedStatus(String value) {
        try {
            return AgentTaskWorkItemStatus.fromPersistedValue(value);
        } catch (IllegalArgumentException invalid) {
            throw failure(Reason.INVALID_PERSISTED_STATE, "Work item status is invalid");
        }
    }

    private String requestDigest(String tenantId, String clientId, String taskId,
            String workItemId, RequiredRequest request) {
        return sha256(String.join("\0", tenantId, clientId, taskId, workItemId,
                request.operatorSubject(), request.coordinatorAgentId(),
                Long.toString(request.expectedTaskVersion()),
                Long.toString(request.expectedWorkItemVersion()),
                request.previousAgentId(), request.targetAgentId(),
                request.sourceCommandId(), request.reason()));
    }

    private String reassignmentId(String tenantId, String clientId, String taskId,
            String workItemId, String idempotencyKey) {
        return "rsn_" + sha256(String.join("\0", "e05-reassignment-v1",
                tenantId, clientId, taskId, workItemId, idempotencyKey));
    }

    private String boundedInstruction(String title, String description) {
        String value = "Execute reassigned work item: " + title
                + (description == null || description.isBlank() ? "" : "\n" + description);
        return codePointPrefix(value, 8_000);
    }

    private String codePointPrefix(String value, int max) {
        if (value.codePointCount(0, value.length()) <= max) return value;
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    private List<String> sortedDistinct(String... values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(List.of(values));
        ArrayList<String> sorted = new ArrayList<>(unique);
        sorted.sort(Comparator.comparing(
                value -> value.getBytes(StandardCharsets.UTF_8),
                AgentWorkItemReassignmentServiceImpl::compareUnsigned));
        return List.copyOf(sorted);
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int compared = Integer.compare(left[i] & 0xff, right[i] & 0xff);
            if (compared != 0) return compared;
        }
        return Integer.compare(left.length, right.length);
    }

    private void requireScope(String tenantId, String clientId, String taskId, String workItemId) {
        if (!exact(tenantId, 50) || !exact(clientId, 50)
                || !exact(taskId, 100) || !exact(workItemId, 100)) {
            throw failure(Reason.INVALID_REQUEST, "Scope or path identity is invalid");
        }
    }

    private void requireAgent(String value, String name) {
        if (!canonicalAgent(value)) {
            throw failure(Reason.INVALID_REQUEST, name + " is not canonical");
        }
    }

    private boolean canonicalAgent(String value) {
        return value != null && !hasUnpairedSurrogate(value)
                && CANONICAL_AGENT_ID.matcher(value).matches();
    }

    private boolean digest(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    private boolean exact(String value, int max) {
        return value != null && !value.isEmpty() && !hasUnpairedSurrogate(value)
                && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= max
                && value.chars().noneMatch(Character::isISOControl);
    }

    private boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSensitiveReason(String value) {
        if (value == null) return true;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return SENSITIVE_REASON_MARKERS.stream().anyMatch(lower::contains);
    }

    private boolean validToken(String value) {
        return exact(value, MAX_LEASE_TOKEN_LENGTH);
    }

    private String token() {
        String value = tokenGenerator.get();
        if (!validToken(value)) {
            throw failure(Reason.INVALID_PERSISTED_STATE, "Lease token generator failed closed");
        }
        return value;
    }

    private long now() {
        long value = clock.getAsLong();
        if (value <= 0) throw failure(Reason.INVALID_PERSISTED_STATE, "Clock failed closed");
        return value;
    }

    private long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw failure(Reason.INVALID_PERSISTED_STATE, "Time arithmetic overflow");
        }
    }

    private static String secureToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private AgentWorkItemReassignmentException unavailable() {
        return failure(Reason.NOT_FOUND_OR_FORBIDDEN, "Reassignment resource is unavailable");
    }

    private AgentWorkItemReassignmentException failure(Reason reason, String message) {
        return new AgentWorkItemReassignmentException(reason, message);
    }

    private AgentWorkItemReassignmentException failure(
            Reason reason, String message, Throwable cause) {
        return new AgentWorkItemReassignmentException(reason, message, cause);
    }

    private enum LeaseOperation { READ, START, HEARTBEAT }

    private record RequiredRequest(
            String operatorSubject, String coordinatorAgentId,
            long expectedTaskVersion, long expectedWorkItemVersion,
            String previousAgentId, String targetAgentId, String sourceCommandId,
            String reason, String idempotencyKey, String taskId) {
    }
}
