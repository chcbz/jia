package cn.jia.agent.service.funding;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.funding.*;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.mapper.AgentTaskBountyQuoteMapper;
import cn.jia.agent.mapper.AgentTaskFundingMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskSettlementMapper;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.core.util.JsonUtil;
import cn.jia.economy.bounty.*;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.common.MicroSilver;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Preview orchestration, NOT actual provider billing. No external work is sent before physical commit. */
@Service
@ConditionalOnProperty(prefix = "economy.preview", name = "enabled", havingValue = "true")
public final class FundedBountySettlementServiceImpl implements FundedBountySettlementService {
    private final AgentTaskFundingMapper funding;
    private final AgentTaskBountyQuoteMapper quotes;
    private final AgentTaskSettlementMapper settlements;
    private final AgentTaskMetaMapper tasks;
    private final AgentTaskMutationTransaction roots;
    private final AgentTaskEventWriter events;
    private final FundedBountyCaptureService capture;
    private final TransactionTemplate transactions;

    public FundedBountySettlementServiceImpl(AgentTaskFundingMapper funding, AgentTaskBountyQuoteMapper quotes,
            AgentTaskSettlementMapper settlements, AgentTaskMetaMapper tasks, AgentTaskMutationTransaction roots,
            AgentTaskEventWriter events, FundedBountyCaptureService capture,
            PlatformTransactionManager tm) {
        this.funding = funding; this.quotes = quotes; this.settlements = settlements; this.tasks = tasks;
        this.roots = roots; this.events = events; this.capture = capture;
        this.transactions = new TransactionTemplate(tm);
    }

    @Override
    public AgentTaskSettlementReceiptDTO complete(FundedBountyActor actor, String key, String taskId,
            AgentTaskFundingCompleteDTO request) {
        validateActor(actor); validateId(taskId, 100);
        byte[] keyBytes = canonicalKey(key);
        if (request == null) throw bad("Completion body is required");
        long expected = number(request.expectedTaskVersion());
        long actual = number(request.actualComputeMicro());
        if (expected == Long.MAX_VALUE) throw bad("Task version has no successor");
        byte[] hash = FundedBountyRequestDigest.complete(taskId, request.expectedTaskVersion(), request.actualComputeMicro());
        return locked(actor, taskId, root -> completeLocked(actor, key, keyBytes, hash, expected, actual, root));
    }

    private AgentTaskSettlementReceiptDTO completeLocked(FundedBountyActor actor, String key, byte[] keyBytes,
            byte[] hash, long expected, long actual, AgentTaskMetaEntity root) {
        AgentTaskFundingEntity held = ownedFunding(actor, root.getTaskId());
        AgentTaskSettlementEntity replay = settlements.findForUpdate(actor.tenantId(), actor.clientId(), root.getTaskId());
        if (replay != null) {
            if (!"SETTLED".equals(held.getFundingStatus()) || !actor.userId().equals(replay.getPrincipalId())
                    || !MessageDigest.isEqual(keyBytes, replay.getIdempotencyKey())
                    || !MessageDigest.isEqual(hash, replay.getRequestHash())) throw idempotency();
            return receipt(replay);
        }
        if (!Long.valueOf(expected).equals(root.getTaskVersion())) throw error(HttpStatus.CONFLICT,
                "TASK_VERSION_CONFLICT", "Task version does not match");
        if (!"FUNDS_HELD".equals(held.getFundingStatus()) || held.getVersion() == null || held.getVersion() < 1
                || held.getVersion() == Long.MAX_VALUE || held.getEscrowVersion() == null
                || held.getEscrowVersion() < 1 || held.getEscrowVersion() > Long.MAX_VALUE - 3
                || held.getGrossBountyAmountMicro() == null || held.getGrossBountyAmountMicro() <= 0
                || !Objects.equals(held.getRemainingMicro(), held.getGrossBountyAmountMicro())
                || !(AgentConstants.TASK_STATUS_ASSIGNED.equals(root.getRewardStatus())
                    || AgentConstants.TASK_STATUS_RUNNING.equals(root.getRewardStatus()))
                || root.getCompletedAt() != null || root.getAssignedAt() == null
                || root.getAssignedAgentId() == null || !root.getAssignedAgentId().matches("agt_[0-9a-f]{32}")) {
            throw conflict("Completion requires a held, explicitly claimed single-Agent task");
        }
        List<AgentTaskClaimOperationEntity> claims = settlements.acceptedClaims(
                actor.tenantId(), actor.clientId(), root.getTaskId());
        if (claims.size() != 1) throw conflict("Exactly one accepted claim is required");
        AgentTaskClaimOperationEntity claim = claims.getFirst();
        AgentTaskQuoteEntity quote = quotes.selectQuoteForUpdate(
                actor.tenantId(), actor.clientId(), root.getTaskId(), claim.getQuoteId());
        requireAccepted(actor, root, held, claim, quote);
        long gross = held.getGrossBountyAmountMicro();
        long fee = quote.getPlatformFeeMicro();
        // Subtraction only after ordering checks: never overflow and never round/floor a negative payout.
        if (actual > quote.getWorstComputeMicro() || actual > gross || fee > gross - actual) {
            throw error(HttpStatus.CONFLICT, "ACTUAL_COMPUTE_EXCEEDS_QUOTE", "Actual compute exceeds accepted budget");
        }
        long payout = gross - actual - fee;
        if (payout < quote.getMinimumAcceptedPayoutMicro()) throw conflict("Accepted minimum payout is not covered");
        FundedBountyCaptureReceipt captured = capture.capture(new FundedBountyCaptureCommand(
                new EconomyScope(actor.tenantId(), actor.clientId()), new EconomyPrincipal(EconomyPrincipalType.USER, actor.userId()),
                key, hash, root.getTaskId(), claim.getAgentId(), gross, actual, fee, payout,
                held.getEscrowVersion(), held.getReserveTransactionId()));
        long now = captured.postedAt();
        one(tasks.updateStatusByVersion(actor.tenantId(), actor.clientId(), root.getTaskId(), expected,
                AgentConstants.TASK_STATUS_COMPLETED, root.getStartedAt(), now, null, now));
        one(settlements.markSettled(actor.tenantId(), actor.clientId(), root.getTaskId(), held.getVersion(),
                captured.escrowVersion(), now));
        AgentTaskSettlementEntity result = new AgentTaskSettlementEntity();
        result.setTenantId(actor.tenantId()); result.setClientId(actor.clientId());
        result.setPrincipalType("USER"); result.setPrincipalId(actor.userId()); result.setIdempotencyKey(keyBytes);
        result.setRequestHash(hash); result.setTaskId(root.getTaskId()); result.setQuoteId(quote.getQuoteId());
        result.setAgentId(claim.getAgentId()); result.setEscrowId(held.getEscrowId()); result.setStatus("SETTLED");
        result.setGrossMicro(gross); result.setActualComputeMicro(actual); result.setPlatformFeeMicro(fee);
        result.setAgentPayoutMicro(payout); result.setRefundedMicro(0L); result.setTaskVersion(expected + 1);
        result.setFundingVersion(held.getVersion() + 1); result.setEscrowVersion(captured.escrowVersion());
        result.setSettledAt(now); result.setTransactionIds(JsonUtil.toJson(captured.transactionIds()));
        // Unique actor/key across all tasks plus unique task: collisions roll back EVERY ledger/task write.
        try { one(settlements.insert(result)); }
        catch (DataIntegrityViolationException duplicate) { throw idempotency(); }
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.TASK_ID, root.getTaskId())
                .put(TaskEventPayload.Key.AGENT_ID, claim.getAgentId())
                .put(TaskEventPayload.Key.FROM_STATUS, root.getRewardStatus())
                .put(TaskEventPayload.Key.TO_STATUS, AgentConstants.TASK_STATUS_COMPLETED)
                .put(TaskEventPayload.Key.EXPECTED_VERSION, expected)
                .put(TaskEventPayload.Key.RESULT_VERSION, expected + 1)
                .put(TaskEventPayload.Key.COMPLETED_AT, now)
                .put(TaskEventPayload.Key.REASON_CODE, "PREVIEW_BOUNTY_SETTLED");
        String seed = actor.tenantId() + '\0' + actor.clientId() + '\0' + root.getTaskId() + "\0W06\0" + (expected + 1);
        events.append(new AgentTaskEventWriteCommand().setTenantId(actor.tenantId()).setClientId(actor.clientId())
                .setTaskId(root.getTaskId()).setEventId("evt_" + TaskEventPayload.ContentDigest.fromUtf8(seed).sha256())
                .setEventType(TaskEventType.TASK_COMPLETED).setActorType(TaskEventType.ActorType.SYSTEM).setActorId(null)
                .setAggregateType(TaskEventType.Aggregate.TASK).setAggregateId(root.getTaskId())
                .setEventJson(payload.toJson()).setOccurredAt(now));
        return receipt(result);
    }

    @Override
    public AgentTaskSettlementDTO read(FundedBountyActor actor, String taskId) {
        validateActor(actor); validateId(taskId, 100);
        return locked(actor, taskId, root -> {
            AgentTaskFundingEntity row = ownedFunding(actor, taskId);
            AgentTaskSettlementEntity settled = settlements.findForUpdate(actor.tenantId(), actor.clientId(), taskId);
            AgentTaskSettlementReceiptDTO receipt = null;
            AgentTaskFundingCancelReceiptDTO cancel = null;
            switch (row.getFundingStatus()) {
                case "SETTLED" -> {
                    if (settled == null || !actor.userId().equals(settled.getPrincipalId())
                            || !Objects.equals(row.getVersion(), settled.getFundingVersion())
                            || !Objects.equals(row.getEscrowVersion(), settled.getEscrowVersion())
                            || !Objects.equals(row.getGrossBountyAmountMicro(), settled.getGrossMicro())
                            || !Objects.equals(row.getEscrowId(), settled.getEscrowId())
                            || !Long.valueOf(0).equals(row.getRemainingMicro())) throw unavailable("Settlement audit mismatch");
                    receipt = receipt(settled);
                }
                case "REFUNDED" -> {
                    if (settled != null || row.getRefundTransactionId() == null) throw unavailable("Refund audit mismatch");
                    cancel = new AgentTaskFundingCancelReceiptDTO(taskId, "REFUNDED", row.getRefundTransactionId(),
                            decimal(row.getCancelRefundedMicro()), decimal(row.getRemainingMicro()),
                            decimal(row.getCancelTaskVersion()), decimal(row.getVersion()), decimal(row.getRefundedAt()));
                }
                case "FUNDS_HELD" -> { if (settled != null) throw unavailable("Held audit mismatch"); }
                default -> throw conflict("Funding is not readable in its current state");
            }
            return new AgentTaskSettlementDTO(taskId, row.getFundingStatus(), row.getSettlementPolicy(), row.getEscrowId(),
                    decimal(row.getGrossBountyAmountMicro()), decimal(row.getRemainingMicro()), decimal(root.getTaskVersion()),
                    decimal(row.getVersion()), receipt, cancel);
        });
    }

    private static void requireAccepted(FundedBountyActor actor, AgentTaskMetaEntity root, AgentTaskFundingEntity funding,
            AgentTaskClaimOperationEntity claim, AgentTaskQuoteEntity quote) {
        if (quote == null || !"CLAIMED".equals(quote.getStatus()) || !"USER".equals(quote.getPrincipalType())
                || !"USER".equals(claim.getPrincipalType()) || !actor.userId().equals(claim.getPrincipalId())
                || !actor.userId().equals(quote.getPrincipalId()) || !root.getAssignedAgentId().equals(claim.getAgentId())
                || !claim.getAgentId().equals(quote.getAgentId()) || !root.getTaskId().equals(quote.getTaskId())
                || !actor.tenantId().equals(quote.getTenantId()) || !actor.clientId().equals(quote.getClientId())
                || !Objects.equals(quote.getGrossAllocationMicro(), funding.getGrossBountyAmountMicro())
                || quote.getTaskVersion() == null || quote.getTaskVersion() < 0 || quote.getTaskVersion() == Long.MAX_VALUE
                || !Long.valueOf(quote.getTaskVersion() + 1).equals(claim.getReceiptTaskVersion())
                || claim.getReceiptTaskVersion() > root.getTaskVersion()
                || claim.getClaimedAt() == null || claim.getClaimedAt() <= 0
                || !claim.getClaimedAt().equals(quote.getClaimedAt())
                || !claim.getClaimedAt().equals(root.getAssignedAt())
                || !Boolean.TRUE.equals(quote.getBudgetCovered()) || !Boolean.TRUE.equals(quote.getVerifiedSkillMatch())
                || quote.getWorstComputeMicro() == null || quote.getWorstComputeMicro() < 0
                || quote.getPlatformFeeMicro() == null || quote.getPlatformFeeMicro() < 0
                || quote.getMinimumAcceptedPayoutMicro() == null || quote.getMinimumAcceptedPayoutMicro() < 0) {
            throw conflict("Accepted quote/claim snapshot is incompatible");
        }
        long gross = funding.getGrossBountyAmountMicro();
        if (quote.getWorstComputeMicro() > gross || quote.getPlatformFeeMicro() > gross - quote.getWorstComputeMicro()
                || quote.getMinimumAcceptedPayoutMicro() > gross - quote.getWorstComputeMicro() - quote.getPlatformFeeMicro()) {
            throw conflict("Accepted worst budget is not covered");
        }
    }

    private AgentTaskFundingEntity ownedFunding(FundedBountyActor actor, String taskId) {
        AgentTaskFundingEntity row = funding.selectFundingForUpdate(actor.tenantId(), actor.clientId(), taskId);
        if (row == null || !"USER".equals(row.getPayerPrincipalType()) || !actor.userId().equals(row.getPayerPrincipalId())
                || !actor.tenantId().equals(row.getTenantId()) || !actor.clientId().equals(row.getClientId())
                || !taskId.equals(row.getTaskId())) throw error(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found");
        if (!"GROSS_INCLUSIVE".equals(row.getSettlementPolicy()) || !"FUNDED_SINGLE_AGENT".equals(row.getFundingMode())) {
            throw conflict("Unsupported settlement policy");
        }
        return row;
    }

    private <T> T locked(FundedBountyActor actor, String taskId, Function<AgentTaskMetaEntity, T> action) {
        try {
            capture.requirePreviewScope(new EconomyScope(actor.tenantId(), actor.clientId()));
            return Objects.requireNonNull(transactions.execute(status -> roots.executeWithLockedTaskRoot(
                    actor.tenantId(), actor.clientId(), taskId, action::apply)));
        } catch (AgentTaskCollaborationException scope) {
            if (scope.getReason() == AgentTaskCollaborationException.Reason.NOT_FOUND
                    || scope.getReason() == AgentTaskCollaborationException.Reason.FORBIDDEN) {
                throw error(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found");
            }
            throw unavailable("Task root unavailable");
        } catch (EconomyPostingException failure) {
            throw switch (failure.reason()) {
                case FEATURE_DISABLED -> error(HttpStatus.SERVICE_UNAVAILABLE, "ECONOMY_PREVIEW_DISABLED", "Preview disabled");
                case IDEMPOTENCY_CONFLICT -> idempotency();
                case INSUFFICIENT_FUNDS, ESCROW_CONFLICT, CONCURRENCY_CONFLICT -> conflict("Escrow capture conflict");
                default -> unavailable("Ledger capture rejected");
            };
        }
    }

    private static AgentTaskSettlementReceiptDTO receipt(AgentTaskSettlementEntity row) {
        List<String> ids = JsonUtil.jsonToList(row.getTransactionIds(), String.class);
        if (ids == null || ids.isEmpty() || ids.size() > 3 || ids.stream().anyMatch(Objects::isNull)
                || !"SETTLED".equals(row.getStatus()) || !Long.valueOf(0).equals(row.getRefundedMicro())) {
            throw unavailable("Settlement receipt is corrupt");
        }
        return new AgentTaskSettlementReceiptDTO(row.getTaskId(), "SETTLED", row.getQuoteId(), row.getAgentId(),
                row.getEscrowId(), decimal(row.getGrossMicro()), decimal(row.getActualComputeMicro()),
                decimal(row.getPlatformFeeMicro()), decimal(row.getAgentPayoutMicro()), "0", "0",
                decimal(row.getTaskVersion()), decimal(row.getFundingVersion()), decimal(row.getEscrowVersion()),
                decimal(row.getSettledAt()), ids);
    }

    static void validateActor(FundedBountyActor actor) {
        if (actor == null) throw error(HttpStatus.UNAUTHORIZED, "ECONOMY_UNAUTHENTICATED", "Authentication required");
        validateId(actor.tenantId(), 50); validateId(actor.clientId(), 50); validateId(actor.userId(), 100);
    }
    private static void validateId(String id, int limit) {
        if (id == null || id.isEmpty() || !id.equals(id.strip()) || id.getBytes(StandardCharsets.UTF_8).length > limit
                || id.codePoints().anyMatch(Character::isISOControl)) throw bad("Invalid exact identifier");
        for (int i = 0; i < id.length(); i++) {
            char ch = id.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= id.length() || !Character.isLowSurrogate(id.charAt(i))) throw bad("Invalid Unicode");
            } else if (Character.isLowSurrogate(ch)) throw bad("Invalid Unicode");
        }
    }
    private static long number(String value) {
        try { return MicroSilver.parseUnsigned(value); }
        catch (EconomyPostingException failure) { throw bad("Canonical unsigned BIGINT string required"); }
    }
    private static String decimal(Long value) {
        if (value == null || value < 0) throw unavailable("Invalid audit number");
        return Long.toString(value);
    }
    private static byte[] canonicalKey(String key) {
        try { if (key == null || !UUID.fromString(key).toString().equals(key)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException failure) { throw bad("Canonical UUID Idempotency-Key required"); }
        return key.getBytes(StandardCharsets.US_ASCII);
    }
    private static void one(int count) { if (count != 1) throw unavailable("Settlement CAS failed"); }
    private static FundedBountyException bad(String message) { return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message); }
    private static FundedBountyException conflict(String message) { return error(HttpStatus.CONFLICT, "FUNDED_BOUNTY_CONFLICT", message); }
    private static FundedBountyException idempotency() { return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Key is bound to another request"); }
    private static FundedBountyException unavailable(String message) { return new FundedBountyException(HttpStatus.SERVICE_UNAVAILABLE, "FUNDED_BOUNTY_UNAVAILABLE", message, true); }
    private static FundedBountyException error(HttpStatus status, String code, String message) { return new FundedBountyException(status, code, message); }
}
