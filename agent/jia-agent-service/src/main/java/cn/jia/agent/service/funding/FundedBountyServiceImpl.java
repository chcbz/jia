package cn.jia.agent.service.funding;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingCancelReceiptDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingEntity;
import cn.jia.agent.entity.funding.AgentTaskFundingOperationEntity;
import cn.jia.agent.mapper.AgentTaskFundingMapper;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.economy.bounty.FundedBountyLedgerService;
import cn.jia.economy.bounty.FundedBountyRefundCommand;
import cn.jia.economy.bounty.FundedBountyRefundReceipt;
import cn.jia.economy.bounty.FundedBountyReserveCommand;
import cn.jia.economy.bounty.FundedBountyReserveReceipt;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.common.MicroSilver;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;
import cn.jia.task.common.TaskConstants;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.task.service.TaskService;
import tools.jackson.core.type.TypeReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "economy.preview", name = "enabled", havingValue = "true")
public final class FundedBountyServiceImpl implements FundedBountyService {
    static final String MODE = "FUNDED_SINGLE_AGENT";
    static final String FUNDS_HELD = "FUNDS_HELD";
    static final String REFUNDED = "REFUNDED";
    static final String POLICY = "GROSS_INCLUSIVE";

    private final AgentTaskFundingMapper fundingMapper;
    private final AgentTaskMetaDao taskMetaDao;
    private final AgentTaskMutationTransaction mutationTransaction;
    private final AgentTaskEventWriter eventWriter;
    private final ObjectProvider<TaskService> taskServiceProvider;
    private final FundedBountyLedgerService ledgerService;
    private final TransactionTemplate transactions;

    public FundedBountyServiceImpl(AgentTaskFundingMapper fundingMapper, AgentTaskMetaDao taskMetaDao,
            AgentTaskMutationTransaction mutationTransaction, AgentTaskEventWriter eventWriter,
            ObjectProvider<TaskService> taskServiceProvider, FundedBountyLedgerService ledgerService,
            PlatformTransactionManager transactionManager) {
        this.fundingMapper = Objects.requireNonNull(fundingMapper, "fundingMapper");
        this.taskMetaDao = Objects.requireNonNull(taskMetaDao, "taskMetaDao");
        this.mutationTransaction = Objects.requireNonNull(mutationTransaction, "mutationTransaction");
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
        this.taskServiceProvider = Objects.requireNonNull(taskServiceProvider, "taskServiceProvider");
        this.ledgerService = Objects.requireNonNull(ledgerService, "ledgerService");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public AgentTaskDTO create(FundedBountyActor actor, String idempotencyKey, byte[] requestHash,
            AgentTaskCreateDTO request) {
        validateActor(actor);
        validateHash(requestHash);
        asciiUuid(idempotencyKey);
        long amount = parseFundedRequest(request);
        AgentTaskDTO result = transactions.execute(status -> createInTransaction(
                actor, idempotencyKey, requestHash, request, amount));
        if (result == null) throw unavailable("Funded task creation returned no result");
        return result;
    }

    private AgentTaskDTO createInTransaction(FundedBountyActor actor, String idempotencyKey,
            byte[] requestHash, AgentTaskCreateDTO request, long amount) {
        long now = positiveNow();
        String reservedTaskId = UUID.randomUUID().toString();
        AgentTaskFundingOperationEntity operation = new AgentTaskFundingOperationEntity();
        operation.setPrincipalType(EconomyPrincipalType.USER.name());
        operation.setPrincipalId(actor.userId());
        operation.setIdempotencyKey(asciiUuid(idempotencyKey));
        operation.setRequestHash(requestHash);
        operation.setTaskId(reservedTaskId);
        operation.setStatus("POSTING");
        operation.setTenantId(actor.tenantId());
        operation.setClientId(actor.clientId());
        operation.setCreateTime(now);
        operation.setUpdateTime(now);
        try {
            requireOne(fundingMapper.insertOperation(operation), "funded create idempotency reservation");
        } catch (DataIntegrityViolationException duplicate) {
            AgentTaskFundingOperationEntity existing = fundingMapper.selectOperationForUpdate(
                    actor.tenantId(), actor.clientId(), EconomyPrincipalType.USER.name(),
                    actor.userId(), asciiUuid(idempotencyKey));
            if (existing == null) throw conflict("Unable to resolve funded create idempotency collision");
            if (!MessageDigest.isEqual(requestHash, existing.getRequestHash())
                    || !"COMPLETED".equals(existing.getStatus())) {
                throw idempotencyConflict();
            }
            return replayCreate(actor, request, existing);
        }

        AgentTaskMetaEntity reserved = new AgentTaskMetaEntity();
        reserved.setTaskId(reservedTaskId);
        reserved.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        reserved.setRequiredAbilities(JsonUtil.toJson(
                request.getRequiredAbilities() == null ? List.of() : request.getRequiredAbilities()));
        reserved.setReward(request.getReward());
        reserved.setCollaborationMode("single");
        reserved.setRiskLevel("low");
        reserved.setMaxAgents(1);
        reserved.setReviewRequired(false);
        reserved.setTaskVersion(0L);
        reserved.setCurrentEventVersion(0L);
        reserved.setTenantId(actor.tenantId());
        reserved.setClientId(actor.clientId());
        reserved.setCreateTime(now);
        reserved.setUpdateTime(now);

        return mutationTransaction.executeAfterTaskRootReservation(
                actor.tenantId(), actor.clientId(), reservedTaskId,
                () -> taskMetaDao.insert(reserved),
                (root, created) -> {
                    if (!created) throw conflict("Funded task root identifier collision");
                    String finalTaskId = createTaskPlan(request, reservedTaskId, actor.tenantId());
                    if (!reservedTaskId.equals(finalTaskId)) {
                        if (taskMetaDao.findByTaskIdForUpdate(actor.tenantId(), actor.clientId(), finalTaskId) != null) {
                            throw conflict("Task plan ID collides with an existing task root");
                        }
                        long rekeyedAt = positiveNow();
                        requireOne(taskMetaDao.rekeyReservedTaskRoot(actor.tenantId(), actor.clientId(),
                                reservedTaskId, finalTaskId, rekeyedAt), "funded task root plan ID backfill");
                        requireOne(fundingMapper.rekeyOperation(actor.tenantId(), actor.clientId(),
                                reservedTaskId, finalTaskId, rekeyedAt), "funded create receipt rekey");
                        root.setTaskId(finalTaskId);
                        root.setUpdateTime(rekeyedAt);
                    }
                    AgentTaskFundingEntity funding = pendingFunding(actor, root.getTaskId(), amount,
                            request.getRequiredSkillRequirements(), root.getCreateTime());
                    requireOne(fundingMapper.insertFunding(funding), "funded task projection insert");
                    FundedBountyReserveReceipt reserve;
                    try {
                        reserve = ledgerService.reserve(new FundedBountyReserveCommand(
                                scope(actor), principal(actor), idempotencyKey, requestHash,
                                root.getTaskId(), amount));
                    } catch (EconomyPostingException exception) {
                        throw mapEconomy(exception);
                    }
                    long heldAt = reserve.postedAt();
                    requireOne(fundingMapper.markFundsHeld(actor.tenantId(), actor.clientId(), root.getTaskId(),
                            reserve.escrowId(), reserve.escrowVersion(), reserve.transactionId(), heldAt),
                            "funded task FUNDS_HELD CAS");
                    appendCreated(actor, root);
                    requireOne(fundingMapper.completeOperation(actor.tenantId(), actor.clientId(), root.getTaskId(),
                            reserve.transactionId(), root.getCreateTime(), root.getUpdateTime()),
                            "funded create receipt completion");
                    AgentTaskFundingEntity persisted = fundingMapper.selectFunding(
                            actor.tenantId(), actor.clientId(), root.getTaskId());
                    return taskReceipt(root, request, persisted, 0L, AgentConstants.TASK_STATUS_OPEN,
                            root.getCreateTime(), root.getUpdateTime());
                });
    }

    @Override
    public AgentTaskFundingCancelReceiptDTO cancel(FundedBountyActor actor, String idempotencyKey,
            byte[] requestHash, String taskId, long expectedTaskVersion) {
        validateActor(actor);
        validateHash(requestHash);
        asciiUuid(idempotencyKey);
        validateId(taskId, "taskId", 100);
        if (expectedTaskVersion < 0 || expectedTaskVersion == Long.MAX_VALUE) {
            throw badRequest("expectedTaskVersion is invalid");
        }
        AgentTaskFundingCancelReceiptDTO result;
        try {
            result = transactions.execute(status ->
                    mutationTransaction.executeWithLockedTaskRoot(actor.tenantId(), actor.clientId(), taskId,
                            root -> cancelLocked(actor, idempotencyKey, requestHash, expectedTaskVersion, root)));
        } catch (AgentTaskCollaborationException exception) {
            if (exception.getReason() == AgentTaskCollaborationException.Reason.NOT_FOUND
                    || exception.getReason() == AgentTaskCollaborationException.Reason.FORBIDDEN) {
                throw new FundedBountyException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found");
            }
            throw unavailable("Funded task root is unavailable");
        }
        if (result == null) throw unavailable("Funded task cancellation returned no result");
        return result;
    }

    private AgentTaskFundingCancelReceiptDTO cancelLocked(FundedBountyActor actor, String idempotencyKey,
            byte[] requestHash, long expectedTaskVersion, AgentTaskMetaEntity root) {
        AgentTaskFundingEntity funding = fundingMapper.selectFundingForUpdate(
                actor.tenantId(), actor.clientId(), root.getTaskId());
        requireOwnedFunding(actor, funding);
        byte[] keyBytes = asciiUuid(idempotencyKey);
        if (REFUNDED.equals(funding.getFundingStatus())) {
            if (!MessageDigest.isEqual(keyBytes, funding.getCancelIdempotencyKey())
                    || !MessageDigest.isEqual(requestHash, funding.getCancelRequestHash())) {
                throw idempotencyConflict();
            }
            return cancelReceipt(root.getTaskId(), funding);
        }
        if (!FUNDS_HELD.equals(funding.getFundingStatus()) || funding.getVersion() == null
                || funding.getEscrowVersion() == null || funding.getRemainingMicro() == null
                || funding.getReserveTransactionId() == null) {
            throw conflict("Task funding is not cancellable");
        }
        if (!Long.valueOf(expectedTaskVersion).equals(root.getTaskVersion())) {
            throw versionConflict(root.getTaskVersion());
        }
        if (!AgentConstants.TASK_STATUS_OPEN.equals(root.getRewardStatus())
                || root.getAssignedAt() != null || root.getStartedAt() != null
                || !StringUtil.isBlank(root.getAssignedAgentId())
                || fundingMapper.countMembers(actor.tenantId(), actor.clientId(), root.getTaskId()) != 0
                || fundingMapper.countWorkItems(actor.tenantId(), actor.clientId(), root.getTaskId()) != 0) {
            throw new FundedBountyException(HttpStatus.CONFLICT, "FUNDED_TASK_ALREADY_STARTED",
                    "Funded task can only be cancelled before assignment or start");
        }
        FundedBountyRefundReceipt refund;
        try {
            refund = ledgerService.refund(new FundedBountyRefundCommand(scope(actor), principal(actor),
                    idempotencyKey, requestHash, root.getTaskId(), funding.getRemainingMicro(),
                    funding.getEscrowVersion(), funding.getReserveTransactionId()));
        } catch (EconomyPostingException exception) {
            throw mapEconomy(exception);
        }
        requireOne(taskMetaDao.updateStatusByVersion(actor.tenantId(), actor.clientId(), root.getTaskId(),
                expectedTaskVersion, AgentConstants.TASK_STATUS_CANCELLED, null, null, null),
                "funded task cancellation version CAS");
        requireOne(fundingMapper.markRefunded(actor.tenantId(), actor.clientId(), root.getTaskId(),
                funding.getVersion(), refund.escrowVersion(), keyBytes, requestHash,
                refund.transactionId(), refund.amountMicro(), expectedTaskVersion + 1,
                refund.postedAt()), "funded task refund projection CAS");
        root.setRewardStatus(AgentConstants.TASK_STATUS_CANCELLED);
        root.setTaskVersion(expectedTaskVersion + 1);
        root.setUpdateTime(refund.postedAt());
        appendCancelled(actor, root, expectedTaskVersion, refund.postedAt());
        AgentTaskFundingEntity persisted = fundingMapper.selectFunding(
                actor.tenantId(), actor.clientId(), root.getTaskId());
        return cancelReceipt(root.getTaskId(), persisted);
    }

    @Override
    public AgentTaskFundingDTO findFunding(String tenantId, String clientId, String taskId) {
        validateId(tenantId, "tenantId", 50);
        validateId(clientId, "clientId", 50);
        validateId(taskId, "taskId", 100);
        AgentTaskFundingEntity funding = fundingMapper.selectFunding(tenantId, clientId, taskId);
        return funding == null ? null : fundingDto(funding);
    }

    @Override
    public void requireLegacyAssignmentAllowed(String tenantId, String clientId, String taskId,
            boolean automatic, int targetCount) {
        if (fundingMapper.selectFunding(tenantId, clientId, taskId) == null) return;
        if (automatic || targetCount != 1) {
            throw new FundedBountyException(HttpStatus.CONFLICT, "FUNDED_TEAM_NOT_SUPPORTED",
                    "Funded V0 tasks support only explicit single-Agent quote and claim");
        }
        throw new FundedBountyException(HttpStatus.CONFLICT, "QUOTE_REQUIRED",
                "Funded task assignment requires an accepted quote and explicit claim");
    }

    @Override
    public void requireLegacyLifecycleAllowed(String tenantId, String clientId, String taskId) {
        if (fundingMapper.selectFunding(tenantId, clientId, taskId) != null) {
            throw new FundedBountyException(HttpStatus.CONFLICT, "QUOTE_REQUIRED",
                    "Funded task lifecycle changes require the funded quote/settlement APIs");
        }
    }

    private AgentTaskDTO replayCreate(FundedBountyActor actor, AgentTaskCreateDTO request,
            AgentTaskFundingOperationEntity operation) {
        AgentTaskMetaEntity root = taskMetaDao.findByTaskId(actor.tenantId(), actor.clientId(), operation.getTaskId());
        AgentTaskFundingEntity funding = fundingMapper.selectFunding(
                actor.tenantId(), actor.clientId(), operation.getTaskId());
        if (root == null || funding == null || operation.getReceiptTaskVersion() == null
                || operation.getReceiptCreatedAt() == null || operation.getReceiptUpdatedAt() == null
                || !Objects.equals(operation.getReserveTransactionId(), funding.getReserveTransactionId())) {
            throw unavailable("Funded create receipt is incomplete");
        }
        requireOwnedFunding(actor, funding);
        return taskReceipt(root, request, funding, operation.getReceiptTaskVersion(),
                AgentConstants.TASK_STATUS_OPEN, operation.getReceiptCreatedAt(), operation.getReceiptUpdatedAt());
    }

    private AgentTaskFundingEntity pendingFunding(FundedBountyActor actor, String taskId, long amount,
            List<AgentSkillRequirementDTO> requirements, long now) {
        AgentTaskFundingEntity funding = new AgentTaskFundingEntity();
        funding.setTaskId(taskId);
        funding.setFundingMode(MODE);
        funding.setFundingStatus("RESERVING");
        funding.setPayerPrincipalType(EconomyPrincipalType.USER.name());
        funding.setPayerPrincipalId(actor.userId());
        funding.setSettlementPolicy(POLICY);
        funding.setGrossBountyAmountMicro(amount);
        funding.setRemainingMicro(amount);
        funding.setRequiredSkillRequirements(JsonUtil.toJson(requirements == null ? List.of() : requirements));
        funding.setVersion(0L);
        funding.setTenantId(actor.tenantId());
        funding.setClientId(actor.clientId());
        funding.setCreateTime(now);
        funding.setUpdateTime(now);
        return funding;
    }

    private AgentTaskDTO taskReceipt(AgentTaskMetaEntity root, AgentTaskCreateDTO request,
            AgentTaskFundingEntity funding, long taskVersion, String status, long createdAt, long updatedAt) {
        AgentTaskDTO dto = new AgentTaskDTO();
        dto.setId(root.getTaskId());
        dto.setTenantId(root.getTenantId());
        dto.setClientId(root.getClientId());
        dto.setTitle(request.getTitle());
        dto.setDescription(request.getDescription());
        dto.setStatus(status);
        dto.setRequiredAbilities(request.getRequiredAbilities() == null ? List.of() : List.copyOf(request.getRequiredAbilities()));
        dto.setReward(request.getReward());
        dto.setRequiredSkillRequirements(request.getRequiredSkillRequirements() == null
                ? List.of() : List.copyOf(request.getRequiredSkillRequirements()));
        dto.setTaskVersion(Long.toString(taskVersion));
        dto.setAssignedAgentIds(List.of());
        dto.setAssignees(List.of());
        dto.setActionDispatchResults(List.of());
        dto.setCreatedAt(createdAt);
        dto.setUpdatedAt(updatedAt);
        dto.setFunding(createReceiptFundingDto(funding));
        return dto;
    }

    private AgentTaskFundingDTO createReceiptFundingDto(AgentTaskFundingEntity funding) {
        validateCreateReceiptFunding(funding);
        AgentTaskFundingDTO dto = new AgentTaskFundingDTO();
        dto.setMode(MODE);
        dto.setStatus(FUNDS_HELD);
        dto.setEscrowId(funding.getEscrowId());
        dto.setGrossBountyAmountMicro(MicroSilver.format(funding.getGrossBountyAmountMicro()));
        dto.setRemainingMicro(MicroSilver.format(funding.getGrossBountyAmountMicro()));
        return dto;
    }

    private AgentTaskFundingDTO fundingDto(AgentTaskFundingEntity funding) {
        validateFunding(funding);
        AgentTaskFundingDTO dto = new AgentTaskFundingDTO();
        dto.setMode(funding.getFundingMode());
        dto.setStatus(funding.getFundingStatus());
        dto.setEscrowId(funding.getEscrowId());
        dto.setGrossBountyAmountMicro(MicroSilver.format(funding.getGrossBountyAmountMicro()));
        dto.setRemainingMicro(MicroSilver.format(funding.getRemainingMicro()));
        return dto;
    }

    @Override
    public List<AgentSkillRequirementDTO> requiredSkills(String tenantId, String clientId, String taskId) {
        AgentTaskFundingEntity funding = fundingMapper.selectFunding(tenantId, clientId, taskId);
        if (funding == null) return List.of();
        try {
            return List.copyOf(JsonUtil.getMapper().readValue(funding.getRequiredSkillRequirements(),
                    new TypeReference<List<AgentSkillRequirementDTO>>() { }));
        } catch (Exception exception) {
            throw unavailable("Funded task skill requirements are corrupt");
        }
    }

    private void validateCreateReceiptFunding(AgentTaskFundingEntity funding) {
        if (funding == null || !MODE.equals(funding.getFundingMode())
                || funding.getGrossBountyAmountMicro() == null || funding.getGrossBountyAmountMicro() <= 0
                || funding.getEscrowId() == null || funding.getEscrowVersion() == null
                || funding.getReserveTransactionId() == null) {
            throw unavailable("Funded create receipt projection is corrupt");
        }
    }

    private void validateFunding(AgentTaskFundingEntity funding) {
        if (funding == null || !MODE.equals(funding.getFundingMode())
                || !(FUNDS_HELD.equals(funding.getFundingStatus()) || REFUNDED.equals(funding.getFundingStatus())
                    || "SETTLED".equals(funding.getFundingStatus()))
                || funding.getGrossBountyAmountMicro() == null || funding.getGrossBountyAmountMicro() <= 0
                || funding.getRemainingMicro() == null || funding.getRemainingMicro() < 0
                || funding.getRemainingMicro() > funding.getGrossBountyAmountMicro()
                || funding.getEscrowId() == null || funding.getEscrowVersion() == null
                || funding.getReserveTransactionId() == null || funding.getVersion() == null) {
            throw unavailable("Funded task projection is corrupt");
        }
    }

    private void requireOwnedFunding(FundedBountyActor actor, AgentTaskFundingEntity funding) {
        if (funding == null || !actor.tenantId().equals(funding.getTenantId())
                || !actor.clientId().equals(funding.getClientId())
                || !EconomyPrincipalType.USER.name().equals(funding.getPayerPrincipalType())
                || !exact(actor.userId(), funding.getPayerPrincipalId())) {
            throw new FundedBountyException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found");
        }
    }

    private String createTaskPlan(AgentTaskCreateDTO request, String fallbackId, String tenantId) {
        TaskService taskService = taskServiceProvider.getIfAvailable();
        if (taskService == null) return fallbackId;
        TaskPlanEntity plan = new TaskPlanEntity();
        plan.setName(limit(request.getTitle(), 30));
        plan.setDescription(limit(request.getDescription(), 200));
        plan.setJiacn(tenantId);
        plan.setPeriod(TaskConstants.TASK_PERIOD_ALLTIME);
        plan.setType(TaskConstants.TASK_TYPE_NOTIFY);
        plan.setStatus(TaskConstants.TASK_STATUS_ENABLE);
        plan.setRemind(TaskConstants.TASK_REMIND_NO);
        if (request.getReward() != null) plan.setAmount(BigDecimal.valueOf(request.getReward()));
        TaskPlanEntity persisted = taskService.create(plan);
        Long id = plan.getId() != null ? plan.getId() : persisted == null ? null : persisted.getId();
        return id == null ? fallbackId : Long.toString(id);
    }

    private void appendCreated(FundedBountyActor actor, AgentTaskMetaEntity root) {
        long occurredAt = root.getCreateTime();
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.TASK_ID, root.getTaskId())
                .put(TaskEventPayload.Key.TASK_TYPE, "agent_task")
                .put(TaskEventPayload.Key.STATUS, AgentConstants.TASK_STATUS_OPEN)
                .put(TaskEventPayload.Key.RESULT_VERSION, 0L)
                .put(TaskEventPayload.Key.CREATED_AT, occurredAt);
        eventWriter.append(event(actor, root.getTaskId(), TaskEventType.TASK_CREATED, payload, occurredAt, 0L));
    }

    private void appendCancelled(FundedBountyActor actor, AgentTaskMetaEntity root,
            long expectedVersion, long occurredAt) {
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.TASK_ID, root.getTaskId())
                .put(TaskEventPayload.Key.FROM_STATUS, AgentConstants.TASK_STATUS_OPEN)
                .put(TaskEventPayload.Key.TO_STATUS, AgentConstants.TASK_STATUS_CANCELLED)
                .put(TaskEventPayload.Key.EXPECTED_VERSION, expectedVersion)
                .put(TaskEventPayload.Key.RESULT_VERSION, expectedVersion + 1)
                .put(TaskEventPayload.Key.CANCELLED_AT, occurredAt);
        eventWriter.append(event(actor, root.getTaskId(), TaskEventType.TASK_CANCELLED,
                payload, occurredAt, expectedVersion + 1));
    }

    private AgentTaskEventWriteCommand event(FundedBountyActor actor, String taskId, String eventType,
            TaskEventPayload.Builder payload, long occurredAt, long resultVersion) {
        String seed = actor.tenantId() + '\0' + actor.clientId() + '\0' + taskId + '\0'
                + eventType + '\0' + TaskEventType.Aggregate.TASK + '\0' + taskId + '\0' + resultVersion;
        String eventId = "evt_" + TaskEventPayload.ContentDigest.fromUtf8(seed).sha256();
        return new AgentTaskEventWriteCommand().setTenantId(actor.tenantId()).setClientId(actor.clientId())
                .setTaskId(taskId).setEventId(eventId).setEventType(eventType)
                .setActorType(TaskEventType.ActorType.SYSTEM).setActorId(null)
                .setAggregateType(TaskEventType.Aggregate.TASK).setAggregateId(taskId)
                .setEventJson(payload.toJson()).setOccurredAt(occurredAt);
    }

    private AgentTaskFundingCancelReceiptDTO cancelReceipt(String taskId, AgentTaskFundingEntity funding) {
        validateFunding(funding);
        if (!REFUNDED.equals(funding.getFundingStatus()) || funding.getRefundTransactionId() == null
                || funding.getCancelRefundedMicro() == null || funding.getCancelRefundedMicro() <= 0
                || funding.getCancelTaskVersion() == null || funding.getCancelTaskVersion() <= 0
                || funding.getRefundedAt() == null) {
            throw unavailable("Funded cancellation receipt is corrupt");
        }
        return new AgentTaskFundingCancelReceiptDTO(taskId, REFUNDED, funding.getRefundTransactionId(),
                MicroSilver.format(funding.getCancelRefundedMicro()),
                MicroSilver.format(funding.getRemainingMicro()), Long.toString(funding.getCancelTaskVersion()),
                Long.toString(funding.getVersion()), Long.toString(funding.getRefundedAt()));
    }

    private long parseFundedRequest(AgentTaskCreateDTO request) {
        if (request == null || StringUtil.isBlank(request.getTitle())) throw badRequest("title is required");
        if (!POLICY.equals(request.getSettlementPolicy())) throw badRequest("settlementPolicy must be GROSS_INCLUSIVE");
        long amount;
        try { amount = MicroSilver.parseUnsigned(request.getGrossBountyAmountMicro()); }
        catch (EconomyPostingException exception) { throw badRequest("grossBountyAmountMicro is invalid"); }
        if (amount <= 0) throw badRequest("grossBountyAmountMicro must be positive");
        List<AgentSkillRequirementDTO> requirements = request.getRequiredSkillRequirements();
        if (requirements == null) throw badRequest("requiredSkillRequirements is required");
        if (requirements.size() > 64) throw badRequest("requiredSkillRequirements is too large");
        for (AgentSkillRequirementDTO requirement : requirements) {
            if (requirement == null) throw badRequest("requiredSkillRequirements contains null");
            validateId(requirement.getSkillKey(), "skillKey", 100);
            validateId(requirement.getVersionRange(), "versionRange", 100);
        }
        return amount;
    }

    private static byte[] asciiUuid(String value) {
        if (value == null) throw badRequest("Idempotency-Key is required");
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw badRequest("Idempotency-Key must be a canonical lowercase UUID");
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void validateActor(FundedBountyActor actor) {
        if (actor == null) throw new FundedBountyException(HttpStatus.UNAUTHORIZED,
                "ECONOMY_UNAUTHENTICATED", "Authentication is required");
        validateId(actor.tenantId(), "jiacn", 50);
        validateId(actor.clientId(), "client_id", 50);
        validateId(actor.userId(), "sub", 100);
    }

    private static void validateHash(byte[] hash) {
        if (hash == null || hash.length != 32) throw badRequest("request hash is invalid");
    }

    private static void validateId(String value, String name, int maxBytes) {
        if (value == null || value.isEmpty() || hasUnpairedSurrogate(value)
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || !value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl)) {
            throw badRequest(name + " is invalid");
        }
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean exact(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static EconomyScope scope(FundedBountyActor actor) {
        return new EconomyScope(actor.tenantId(), actor.clientId());
    }

    private static EconomyPrincipal principal(FundedBountyActor actor) {
        return new EconomyPrincipal(EconomyPrincipalType.USER, actor.userId());
    }

    private static String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static long positiveNow() {
        long now = System.currentTimeMillis();
        if (now <= 0) throw unavailable("clock is invalid");
        return now;
    }

    private static void requireOne(int rows, String operation) {
        if (rows != 1) throw conflict(operation + " affected an unexpected row count");
    }

    private static FundedBountyException mapEconomy(EconomyPostingException exception) {
        return switch (exception.reason()) {
            case INSUFFICIENT_FUNDS -> new FundedBountyException(HttpStatus.CONFLICT,
                    "INSUFFICIENT_SILVER", "Insufficient SILVER balance");
            case IDEMPOTENCY_CONFLICT -> idempotencyConflict();
            case FEATURE_DISABLED -> new FundedBountyException(HttpStatus.FORBIDDEN,
                    "ECONOMY_PREVIEW_DISABLED", "Economy preview is disabled");
            case CONCURRENCY_CONFLICT, ESCROW_CONFLICT -> new FundedBountyException(HttpStatus.CONFLICT,
                    "ECONOMY_CONCURRENCY_CONFLICT", "Economy mutation conflicted", true);
            case INVALID_COMMAND, AMOUNT_RANGE_EXCEEDED, IMBALANCED_TRANSACTION,
                    ACCOUNT_NOT_FOUND, ACCOUNT_NOT_ACTIVE -> badRequest("Invalid funded bounty request");
            default -> unavailable("Economy service is unavailable");
        };
    }

    private static FundedBountyException badRequest(String message) {
        return new FundedBountyException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    private static FundedBountyException conflict(String message) {
        return new FundedBountyException(HttpStatus.CONFLICT, "FUNDED_TASK_CONFLICT", message);
    }

    private static FundedBountyException idempotencyConflict() {
        return new FundedBountyException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                "Idempotency-Key is already bound to a different request");
    }

    private static FundedBountyException versionConflict(Long current) {
        return new FundedBountyException(HttpStatus.CONFLICT, "TASK_VERSION_CONFLICT",
                "expectedTaskVersion does not match current task version" + (current == null ? "" : ": " + current));
    }

    private static FundedBountyException unavailable(String message) {
        return new FundedBountyException(HttpStatus.SERVICE_UNAVAILABLE, "FUNDED_BOUNTY_UNAVAILABLE", message, true);
    }
}
