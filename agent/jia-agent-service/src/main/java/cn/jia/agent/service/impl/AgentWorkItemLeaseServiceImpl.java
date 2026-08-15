package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseScanDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.exception.AgentTaskStateException.Reason;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.agent.state.AgentTaskMemberStatus;
import cn.jia.agent.state.AgentTaskWorkItemStatus;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Named
public class AgentWorkItemLeaseServiceImpl implements AgentWorkItemLeaseService {
    private static final Pattern CANONICAL_AGENT_ID = Pattern.compile("^agt_[0-9a-f]{32}$");
    private static final int MAX_LEASE_TOKEN_LENGTH = 100;

    private final AgentTaskMemberDao memberDao;
    private final AgentTaskWorkItemDao workItemDao;
    private final AgentTaskMutationTransaction mutationTransaction;
    private final AgentTaskEventWriter eventWriter;
    private final LongSupplier clock;
    private final Supplier<String> tokenGenerator;
    private final long maxLeaseDurationMillis;

    @Inject
    public AgentWorkItemLeaseServiceImpl(
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter,
            @Value("${jia.agent.work-item-lease.max-duration-ms:900000}")
            long maxLeaseDurationMillis) {
        this(memberDao, workItemDao, mutationTransaction, eventWriter,
                System::currentTimeMillis, AgentWorkItemLeaseServiceImpl::secureLeaseToken,
                maxLeaseDurationMillis);
    }

    AgentWorkItemLeaseServiceImpl(
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter,
            LongSupplier clock,
            Supplier<String> tokenGenerator,
            long maxLeaseDurationMillis) {
        this.memberDao = Objects.requireNonNull(memberDao, "memberDao");
        this.workItemDao = Objects.requireNonNull(workItemDao, "workItemDao");
        this.mutationTransaction = Objects.requireNonNull(mutationTransaction, "mutationTransaction");
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator, "tokenGenerator");
        if (maxLeaseDurationMillis <= 0) {
            throw new IllegalArgumentException("maxLeaseDurationMillis must be positive");
        }
        this.maxLeaseDurationMillis = maxLeaseDurationMillis;
    }

    private <T> T withLockedTaskRoot(String tenantId, String clientId, String taskId,
            AgentTaskMutationTransaction.LockedTaskMutation<T> mutation) {
        try {
            return mutationTransaction.executeWithLockedTaskRoot(
                    tenantId, clientId, taskId, mutation);
        } catch (AgentTaskCollaborationException e) {
            if (e.getReason() == AgentTaskCollaborationException.Reason.NOT_FOUND) {
                throw notFound();
            }
            throw invalidPersisted("Locked task root failed validation");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentWorkItemLeaseDTO claim(
            String tenantId, String clientId, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command) {
        requireScopeAndIds(tenantId, clientId, taskId, workItemId);
        RequiredCommand required = requireCommand(command, true, false);
        requireCanonicalAgentId(required.agentId());
        long duration = requireDuration(required.leaseDurationMillis());
        return withLockedTaskRoot(tenantId, clientId, taskId, root -> {
            long now = now();
            requireActiveMember(tenantId, clientId, taskId, required.agentId());
            AgentTaskWorkItemEntity current = requireWorkItem(tenantId, clientId, taskId, workItemId);
            requireCompleteSnapshot(current);
            requireVersion(current.getVersion(), required.expectedVersion());
            requireStatus(current, AgentTaskWorkItemStatus.READY);
            requireReadyLeaseState(current);
            requireClaimableAttempts(current);
            requireAssigneePermitsClaim(current.getAssigneeAgentId(), required.agentId());
            String leaseToken = generatedToken();
            long leaseUntil = addPositive(now, duration, "lease duration overflow");
            AgentTaskWorkItemDTO update = copyWorkItem(current);
            update.setAssigneeAgentId(required.agentId());
            update.setStatus(AgentTaskWorkItemStatus.CLAIMED.value());
            update.setLeaseToken(leaseToken);
            update.setLeaseUntil(leaseUntil);
            requireSingleCasUpdate(workItemDao.claimReadyByVersion(
                    tenantId, clientId, taskId, workItemId,
                    nullIfBlank(current.getAssigneeAgentId()), required.expectedVersion(), update));
            AgentWorkItemLeaseDTO result = result(
                    update, required.expectedVersion() + 1, now, required.agentId());
            appendLeaseEvent(tenantId, clientId, taskId, current, result,
                    TaskEventType.WORK_ITEM_CLAIMED, required.agentId(), null);
            return result;
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentWorkItemLeaseDTO start(
            String tenantId, String clientId, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command) {
        return mutateActiveLease(tenantId, clientId, taskId, workItemId, command,
                List.of(AgentTaskWorkItemStatus.CLAIMED), false,
                context -> {
                    AgentTaskWorkItemDTO update = copyWorkItem(context.current());
                    update.setStatus(AgentTaskWorkItemStatus.RUNNING.value());
                    return new LeaseMutation(update, TaskEventType.WORK_ITEM_STARTED, null);
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentWorkItemLeaseDTO heartbeat(
            String tenantId, String clientId, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command) {
        return mutateActiveLease(tenantId, clientId, taskId, workItemId, command,
                List.of(AgentTaskWorkItemStatus.CLAIMED, AgentTaskWorkItemStatus.RUNNING), true,
                context -> {
                    long duration = requireDuration(context.required().leaseDurationMillis());
                    long maxUntil = addPositive(context.now(), maxLeaseDurationMillis,
                            "lease duration limit overflow");
                    if (context.current().getLeaseUntil() > maxUntil) {
                        throw invalidPersisted("Persisted lease exceeds the configured lease horizon");
                    }
                    long requestedUntil = addPositive(context.now(), duration, "lease duration overflow");
                    long renewedUntil = Math.max(context.current().getLeaseUntil(), requestedUntil);
                    if (renewedUntil > maxUntil) {
                        throw invalidRequest("Heartbeat lease extension exceeds the configured limit");
                    }
                    if (renewedUntil == context.current().getLeaseUntil()) {
                        return new LeaseMutation(null, null, null);
                    }
                    AgentTaskWorkItemDTO update = copyWorkItem(context.current());
                    update.setLeaseUntil(renewedUntil);
                    return new LeaseMutation(update, TaskEventType.WORK_ITEM_LEASE_RENEWED, null);
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentWorkItemLeaseDTO release(
            String tenantId, String clientId, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command) {
        return mutateActiveLease(tenantId, clientId, taskId, workItemId, command,
                List.of(AgentTaskWorkItemStatus.CLAIMED, AgentTaskWorkItemStatus.RUNNING), false,
                context -> {
                    AgentTaskWorkItemDTO update = copyWorkItem(context.current());
                    update.setStatus(AgentTaskWorkItemStatus.READY.value());
                    clearLease(update);
                    return new LeaseMutation(update, TaskEventType.WORK_ITEM_LEASE_RELEASED, null);
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentWorkItemLeaseDTO cancel(
            String tenantId, String clientId, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command) {
        return mutateActiveLease(tenantId, clientId, taskId, workItemId, command,
                List.of(AgentTaskWorkItemStatus.CLAIMED, AgentTaskWorkItemStatus.RUNNING), false,
                context -> {
                    AgentTaskWorkItemDTO update = copyWorkItem(context.current());
                    update.setStatus(AgentTaskWorkItemStatus.CANCELLED.value());
                    clearLease(update);
                    return new LeaseMutation(update, TaskEventType.WORK_ITEM_CANCELLED, null);
                });
    }

    private AgentWorkItemLeaseDTO mutateActiveLease(
            String tenantId, String clientId, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command, List<AgentTaskWorkItemStatus> statuses,
            boolean requireDuration, java.util.function.Function<LeaseContext, LeaseMutation> mutation) {
        requireScopeAndIds(tenantId, clientId, taskId, workItemId);
        return withLockedTaskRoot(tenantId, clientId, taskId, root -> {
            LeaseContext context = requireActiveLease(
                    tenantId, clientId, taskId, workItemId, command, statuses, requireDuration);
            LeaseMutation requested = mutation.apply(context);
            if (requested.update() == null) {
                return result(copyWorkItem(context.current()), context.current().getVersion(),
                        context.now(), context.required().agentId());
            }
            AgentWorkItemLeaseDTO result = updateActiveLease(context, requested.update());
            appendLeaseEvent(tenantId, clientId, taskId, context.current(), result,
                    requested.eventType(), context.required().agentId(), requested.reasonCode());
            return result;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public AgentWorkItemLeaseDTO validateLeaseForResult(
            String tenantId, String clientId, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command) {
        LeaseContext context = requireActiveLease(
                tenantId, clientId, taskId, workItemId, command,
                List.of(AgentTaskWorkItemStatus.RUNNING), false);
        return result(copyWorkItem(context.current()), context.current().getVersion(),
                context.now(), context.required().agentId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentWorkItemLeaseScanDTO expireLeases(String tenantId, String clientId, int limit) {
        requireScope(tenantId, clientId);
        if (limit <= 0) throw invalidRequest("limit must be positive");
        long now = now();
        List<AgentTaskWorkItemEntity> candidates = workItemDao.listExpiredLeases(
                tenantId, clientId, now, limit);
        AgentWorkItemLeaseScanDTO scan = new AgentWorkItemLeaseScanDTO();
        scan.setScannedCount(candidates.size());
        for (AgentTaskWorkItemEntity candidate : candidates) {
            ExpiryOutcome outcome = withLockedTaskRoot(
                    tenantId, clientId, candidate.getTaskId(), root ->
                            expireCandidate(tenantId, clientId, candidate, now));
            if (outcome.conflict()) {
                scan.setConflictCount(scan.getConflictCount() + 1);
                continue;
            }
            scan.setExpiredCount(scan.getExpiredCount() + 1);
            if (outcome.failed()) scan.setFailedCount(scan.getFailedCount() + 1);
            else scan.setRequeuedCount(scan.getRequeuedCount() + 1);
            scan.getTransitions().add(outcome.result());
        }
        return scan;
    }

    private ExpiryOutcome expireCandidate(String tenantId, String clientId,
            AgentTaskWorkItemEntity candidate, long now) {
        AgentTaskWorkItemEntity current = workItemDao.findByTaskAndWorkItemId(
                tenantId, clientId, candidate.getTaskId(), candidate.getWorkItemId());
        if (current == null || !Objects.equals(current.getVersion(), candidate.getVersion())
                || !Objects.equals(current.getLeaseToken(), candidate.getLeaseToken())
                || !Objects.equals(current.getLeaseUntil(), candidate.getLeaseUntil())
                || !Objects.equals(current.getStatus(), candidate.getStatus())) {
            return ExpiryOutcome.casConflict();
        }
        requireCompleteSnapshot(current);
        AgentTaskWorkItemStatus status = persistedStatus(current.getStatus());
        if (status != AgentTaskWorkItemStatus.CLAIMED && status != AgentTaskWorkItemStatus.RUNNING) {
            throw invalidPersisted("Expiry scan returned a non-leased status");
        }
        requireLeaseIdentity(current);
        if (current.getLeaseUntil() > now) return ExpiryOutcome.casConflict();
        int nextAttempt = incrementAttempt(current);
        AgentTaskWorkItemDTO update = copyWorkItem(current);
        update.setAttemptCount(nextAttempt);
        boolean failed = nextAttempt >= current.getMaxAttempts();
        update.setStatus(failed ? AgentTaskWorkItemStatus.FAILED.value()
                : AgentTaskWorkItemStatus.READY.value());
        clearLease(update);
        int updated = workItemDao.expireLeaseByVersion(
                tenantId, clientId, current.getTaskId(), current.getWorkItemId(),
                current.getAssigneeAgentId(), current.getLeaseToken(), current.getStatus(),
                current.getLeaseUntil(), current.getVersion(), now, update);
        if (updated == 0) return ExpiryOutcome.casConflict();
        if (updated != 1) throw invalidPersisted("Scoped expiry CAS updated an unexpected row count");
        AgentWorkItemLeaseDTO result = result(
                update, current.getVersion() + 1, now, current.getAssigneeAgentId());
        appendLeaseEvent(tenantId, clientId, current.getTaskId(), current, result,
                failed ? TaskEventType.WORK_ITEM_FAILED : TaskEventType.WORK_ITEM_REQUEUED,
                null, "lease_expired");
        return new ExpiryOutcome(false, failed, result);
    }

    private void appendLeaseEvent(String tenantId, String clientId, String taskId,
            AgentTaskWorkItemEntity current, AgentWorkItemLeaseDTO result,
            String eventType, String actorId, String reasonCode) {
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.WORK_ITEM_ID, current.getWorkItemId())
                .put(TaskEventPayload.Key.FROM_STATUS, current.getStatus())
                .put(TaskEventPayload.Key.TO_STATUS, result.getStatus())
                .put(TaskEventPayload.Key.EXPECTED_VERSION, current.getVersion())
                .put(TaskEventPayload.Key.RESULT_VERSION, result.getVersion())
                .put(TaskEventPayload.Key.ATTEMPT_COUNT, result.getAttemptCount().longValue())
                .put(TaskEventPayload.Key.MAX_ATTEMPTS, result.getMaxAttempts().longValue());
        if (actorId != null) payload.put(TaskEventPayload.Key.ASSIGNEE_AGENT_ID, actorId);
        if (current.getLeaseUntil() != null) {
            payload.put(TaskEventPayload.Key.PREVIOUS_LEASE_EXPIRES_AT, current.getLeaseUntil());
        }
        if (result.getLeaseUntil() != null) {
            payload.put(TaskEventPayload.Key.LEASE_EXPIRES_AT, result.getLeaseUntil());
        }
        if (reasonCode != null) payload.put(TaskEventPayload.Key.REASON_CODE, reasonCode);
        eventWriter.append(AgentTaskMutationEventSupport.command(
                tenantId, clientId, taskId, eventType,
                actorId == null ? TaskEventType.ActorType.SYSTEM : TaskEventType.ActorType.AGENT,
                actorId, TaskEventType.Aggregate.WORK_ITEM, current.getWorkItemId(),
                payload, result.getChangedAt(), result.getVersion()));
    }

    private AgentWorkItemLeaseDTO updateActiveLease(
            LeaseContext context, AgentTaskWorkItemDTO update) {
        AgentTaskWorkItemEntity current = context.current();
        int updated = workItemDao.updateActiveLeaseByVersion(
                context.tenantId(), context.clientId(), context.taskId(), context.workItemId(),
                context.required().agentId(), context.required().leaseToken(), current.getStatus(),
                current.getLeaseUntil(), context.required().expectedVersion(), context.now(), update);
        requireSingleCasUpdate(updated);
        return result(update, context.required().expectedVersion() + 1,
                context.now(), context.required().agentId());
    }

    private LeaseContext requireActiveLease(
            String tenantId, String clientId, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command,
            List<AgentTaskWorkItemStatus> allowedStatuses,
            boolean requireDuration) {
        requireScopeAndIds(tenantId, clientId, taskId, workItemId);
        RequiredCommand required = requireCommand(command, requireDuration, true);
        requireCanonicalAgentId(required.agentId());
        long now = now();
        requireActiveMember(tenantId, clientId, taskId, required.agentId());
        AgentTaskWorkItemEntity current = requireWorkItem(
                tenantId, clientId, taskId, workItemId);
        requireCompleteSnapshot(current);
        requireVersion(current.getVersion(), required.expectedVersion());
        AgentTaskWorkItemStatus status = persistedStatus(current.getStatus());
        if (!allowedStatuses.contains(status)) {
            throw leaseInvalid("Lease operation is not allowed for the current work item state");
        }
        requireLeaseIdentity(current);
        if (!required.agentId().equals(current.getAssigneeAgentId())
                || !required.leaseToken().equals(current.getLeaseToken())) {
            throw leaseInvalid("Lease credentials are no longer current");
        }
        if (current.getLeaseUntil() <= now) {
            throw leaseInvalid("Lease is expired");
        }
        long maxUntil = addPositive(now, maxLeaseDurationMillis,
                "lease duration limit overflow");
        if (current.getLeaseUntil() > maxUntil) {
            throw invalidPersisted("Persisted lease exceeds the configured lease horizon");
        }
        return new LeaseContext(
                tenantId, clientId, taskId, workItemId, required, current, now);
    }

    private AgentTaskMemberEntity requireActiveMember(
            String tenantId, String clientId, String taskId, String agentId) {
        AgentTaskMemberEntity member = memberDao.findByTaskAndAgent(
                tenantId, clientId, taskId, agentId);
        if (member == null) {
            throw notFound();
        }
        if (!tenantId.equals(member.getTenantId()) || !clientId.equals(member.getClientId())
                || !taskId.equals(member.getTaskId()) || !agentId.equals(member.getAgentId())) {
            throw invalidPersisted("Persisted member identity does not match its scoped lookup");
        }
        AgentTaskMemberStatus status;
        try {
            status = AgentTaskMemberStatus.fromPersistedValue(member.getMemberStatus());
        } catch (IllegalArgumentException e) {
            throw invalidPersisted("Persisted member status is non-canonical or unknown");
        }
        if (status != AgentTaskMemberStatus.ACCEPTED
                && status != AgentTaskMemberStatus.WORKING) {
            throw leaseInvalid("Agent is not an active task member");
        }
        if (member.getVersion() == null || member.getVersion() < 0) {
            throw invalidPersisted("Persisted member version is invalid");
        }
        return member;
    }

    private AgentTaskWorkItemEntity requireWorkItem(
            String tenantId, String clientId, String taskId, String workItemId) {
        AgentTaskWorkItemEntity current = workItemDao.findByWorkItemId(
                tenantId, clientId, workItemId);
        if (current == null) {
            throw notFound();
        }
        if (!tenantId.equals(current.getTenantId()) || !clientId.equals(current.getClientId())) {
            throw invalidPersisted("Persisted work item scope does not match its scoped lookup");
        }
        if (!taskId.equals(current.getTaskId()) || !workItemId.equals(current.getWorkItemId())) {
            throw notFound();
        }
        return current;
    }

    private void requireCompleteSnapshot(AgentTaskWorkItemEntity current) {
        if (current == null || StringUtil.isBlank(current.getWorkItemId())
                || StringUtil.isBlank(current.getTaskId())
                || StringUtil.isBlank(current.getTitle())
                || StringUtil.isBlank(current.getWorkType())
                || current.getPriority() == null || current.getRequiredItem() == null
                || current.getVersion() == null || current.getVersion() < 0
                || current.getVersion() == Long.MAX_VALUE) {
            throw invalidPersisted("Persisted work item snapshot is incomplete");
        }
        persistedStatus(current.getStatus());
        requireAttempts(current);
        if (current.getAssigneeAgentId() != null
                && StringUtil.isBlank(current.getAssigneeAgentId())) {
            throw invalidPersisted("Persisted assignee is blank");
        }
        if (current.getAssigneeAgentId() != null) {
            requirePersistedCanonicalAgentId(current.getAssigneeAgentId());
        }
    }

    private void requireAttempts(AgentTaskWorkItemEntity current) {
        Integer attemptCount = current.getAttemptCount();
        Integer maxAttempts = current.getMaxAttempts();
        if (attemptCount == null || maxAttempts == null
                || attemptCount < 0 || maxAttempts <= 0 || attemptCount > maxAttempts) {
            throw invalidPersisted("Persisted attempt_count/max_attempts are invalid");
        }
    }

    private void requireClaimableAttempts(AgentTaskWorkItemEntity current) {
        if (current.getAttemptCount() >= current.getMaxAttempts()) {
            throw invalidPersisted("READY work item has exhausted maxAttempts");
        }
    }

    private int incrementAttempt(AgentTaskWorkItemEntity current) {
        requireAttempts(current);
        if (current.getAttemptCount() >= current.getMaxAttempts()) {
            throw invalidPersisted("Leased work item already exhausted maxAttempts");
        }
        return current.getAttemptCount() + 1;
    }

    private void requireReadyLeaseState(AgentTaskWorkItemEntity current) {
        if (current.getLeaseToken() != null || current.getLeaseUntil() != null) {
            throw invalidPersisted("READY work item must not carry lease state");
        }
    }

    private void requireLeaseIdentity(AgentTaskWorkItemEntity current) {
        if (StringUtil.isBlank(current.getAssigneeAgentId())
                || StringUtil.isBlank(current.getLeaseToken())
                || current.getLeaseToken().length() > MAX_LEASE_TOKEN_LENGTH
                || current.getLeaseUntil() == null || current.getLeaseUntil() <= 0) {
            throw invalidPersisted("Persisted active lease is incomplete");
        }
        requirePersistedCanonicalAgentId(current.getAssigneeAgentId());
    }

    private void requireAssigneePermitsClaim(String assigneeAgentId, String agentId) {
        if (assigneeAgentId != null && !agentId.equals(assigneeAgentId)) {
            throw leaseInvalid("Work item is reserved for a different agent");
        }
    }

    private AgentTaskWorkItemStatus persistedStatus(String status) {
        try {
            return AgentTaskWorkItemStatus.fromPersistedValue(status);
        } catch (IllegalArgumentException e) {
            throw invalidPersisted("Persisted work item status is non-canonical or unknown");
        }
    }

    private void requireStatus(
            AgentTaskWorkItemEntity current, AgentTaskWorkItemStatus expected) {
        AgentTaskWorkItemStatus actual = persistedStatus(current.getStatus());
        if (actual != expected) {
            throw new AgentTaskStateException(Reason.INVALID_TRANSITION,
                    "Only " + expected.value() + " work items support this operation");
        }
    }

    private RequiredCommand requireCommand(
            AgentWorkItemLeaseCommandDTO command,
            boolean requireDuration,
            boolean requireToken) {
        if (command == null || StringUtil.isBlank(command.getAgentId())
                || command.getExpectedVersion() == null
                || command.getExpectedVersion() < 0
                || command.getExpectedVersion() == Long.MAX_VALUE) {
            throw invalidRequest("agentId and a nonnegative incrementable expectedVersion are required");
        }
        if (requireToken && StringUtil.isBlank(command.getLeaseToken())) {
            throw invalidRequest("leaseToken is required");
        }
        if (command.getLeaseToken() != null
                && command.getLeaseToken().length() > MAX_LEASE_TOKEN_LENGTH) {
            throw invalidRequest("leaseToken exceeds the supported length");
        }
        if (requireDuration && command.getLeaseDurationMillis() == null) {
            throw invalidRequest("leaseDurationMillis is required");
        }
        return new RequiredCommand(
                command.getAgentId(), command.getLeaseToken(),
                command.getExpectedVersion(), command.getLeaseDurationMillis());
    }

    private long requireDuration(Long duration) {
        if (duration == null || duration <= 0 || duration > maxLeaseDurationMillis) {
            throw invalidRequest("leaseDurationMillis must be positive and within the configured limit");
        }
        return duration;
    }

    private void requireVersion(Long currentVersion, long expectedVersion) {
        if (currentVersion == null || currentVersion < 0 || currentVersion == Long.MAX_VALUE) {
            throw invalidPersisted("Persisted work item version is invalid");
        }
        if (currentVersion != expectedVersion) {
            throw conflict();
        }
    }

    private void requireSingleCasUpdate(int updated) {
        if (updated == 0) {
            throw conflict();
        }
        if (updated != 1) {
            throw invalidPersisted("Scoped lease CAS updated an unexpected row count");
        }
    }

    private AgentTaskWorkItemDTO copyWorkItem(AgentTaskWorkItemEntity current) {
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

    private AgentWorkItemLeaseDTO result(
            AgentTaskWorkItemDTO item, long version, long changedAt, String agentId) {
        AgentWorkItemLeaseDTO result = new AgentWorkItemLeaseDTO();
        result.setTaskId(item.getTaskId());
        result.setWorkItemId(item.getWorkItemId());
        result.setAgentId(agentId);
        result.setStatus(item.getStatus());
        result.setLeaseToken(item.getLeaseToken());
        result.setLeaseUntil(item.getLeaseUntil());
        result.setAttemptCount(item.getAttemptCount());
        result.setMaxAttempts(item.getMaxAttempts());
        result.setVersion(version);
        result.setChangedAt(changedAt);
        return result;
    }

    private void clearLease(AgentTaskWorkItemDTO update) {
        update.setAssigneeAgentId(null);
        update.setLeaseToken(null);
        update.setLeaseUntil(null);
    }

    private long now() {
        long value = clock.getAsLong();
        if (value < 0) {
            throw invalidPersisted("Lease clock returned a negative timestamp");
        }
        return value;
    }

    private long addPositive(long base, long delta, String message) {
        try {
            long value = Math.addExact(base, delta);
            if (value <= 0) {
                throw invalidRequest(message);
            }
            return value;
        } catch (ArithmeticException e) {
            throw invalidRequest(message);
        }
    }

    private String generatedToken() {
        String token = tokenGenerator.get();
        if (StringUtil.isBlank(token) || token.length() > MAX_LEASE_TOKEN_LENGTH) {
            throw invalidPersisted("Lease token generator returned an invalid token");
        }
        return token;
    }

    private static String secureLeaseToken() {
        byte[] bytes = new byte[32];
        SecureRandomHolder.INSTANCE.nextBytes(bytes);
        return "lease_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void requireScopeAndIds(
            String tenantId, String clientId, String taskId, String workItemId) {
        requireScope(tenantId, clientId);
        if (StringUtil.isBlank(taskId) || StringUtil.isBlank(workItemId)) {
            throw invalidRequest("taskId and workItemId are required");
        }
    }

    private void requireScope(String tenantId, String clientId) {
        if (StringUtil.isBlank(tenantId) || StringUtil.isBlank(clientId)) {
            throw invalidRequest("tenantId and clientId are required");
        }
    }

    private void requireCanonicalAgentId(String agentId) {
        if (!CANONICAL_AGENT_ID.matcher(agentId).matches()) {
            throw invalidRequest("agentId must be a canonical stable Agent ID");
        }
    }

    private void requirePersistedCanonicalAgentId(String agentId) {
        if (!CANONICAL_AGENT_ID.matcher(agentId).matches()) {
            throw invalidPersisted("Persisted assignee Agent ID is non-canonical");
        }
    }

    private String nullIfBlank(String value) {
        return StringUtil.isBlank(value) ? null : value;
    }

    private AgentTaskStateException invalidRequest(String message) {
        return new AgentTaskStateException(Reason.INVALID_REQUEST, message);
    }

    private AgentTaskStateException invalidPersisted(String message) {
        return new AgentTaskStateException(Reason.INVALID_PERSISTED_STATE, message);
    }

    private AgentTaskStateException leaseInvalid(String message) {
        return new AgentTaskStateException(Reason.LEASE_INVALID, message);
    }

    private AgentTaskStateException conflict() {
        return new AgentTaskStateException(
                Reason.VERSION_CONFLICT, "Lease state changed concurrently; refresh and retry");
    }

    private AgentTaskStateException notFound() {
        return new AgentTaskStateException(
                Reason.NOT_FOUND, "Task member or work item was not found in the requested scope");
    }

    private record RequiredCommand(
            String agentId, String leaseToken,
            long expectedVersion, Long leaseDurationMillis) {
    }

    private record LeaseMutation(AgentTaskWorkItemDTO update, String eventType, String reasonCode) {
    }

    private record ExpiryOutcome(boolean conflict, boolean failed, AgentWorkItemLeaseDTO result) {
        static ExpiryOutcome casConflict() { return new ExpiryOutcome(true, false, null); }
    }

    private record LeaseContext(
            String tenantId, String clientId, String taskId, String workItemId,
            RequiredCommand required, AgentTaskWorkItemEntity current, long now) {
    }

    private static final class SecureRandomHolder {
        private static final SecureRandom INSTANCE = new SecureRandom();

        private SecureRandomHolder() {
        }
    }
}
