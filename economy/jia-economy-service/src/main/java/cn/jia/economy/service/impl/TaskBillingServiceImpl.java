package cn.jia.economy.service.impl;

import cn.jia.economy.common.EconomyAccountOwnerType;
import cn.jia.economy.common.EconomyAccountPurpose;
import cn.jia.economy.common.EconomyConstants;
import cn.jia.economy.common.EconomyEscrowType;
import cn.jia.economy.common.EconomyJournalType;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.entity.EconomyAccountEntity;
import cn.jia.economy.entity.EconomyTaskChargeEntity;
import cn.jia.economy.entity.EconomyTaskQuoteEntity;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.mapper.EconomyTaskBillingMapper;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.pricing.TaskPricingService;
import cn.jia.economy.pricing.TaskWorkload;
import cn.jia.economy.pricing.TaskQuoteCalculator;
import cn.jia.economy.service.EconomyAccountKey;
import cn.jia.economy.service.EconomyEscrowFunding;
import cn.jia.economy.service.EconomyEscrowSettlement;
import cn.jia.economy.service.EconomyPostingCommand;
import cn.jia.economy.service.EconomyPostingLine;
import cn.jia.economy.service.EconomyPostingResult;
import cn.jia.economy.service.EconomyPostingService;
import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.task.TaskBillingException;
import cn.jia.economy.task.TaskBillingService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static cn.jia.economy.task.TaskBillingException.Reason.*;

/** Fixed task price lifecycle. The caller holds the task root lock in this same transaction.
 * Account locks and journal integrity remain owned by the existing posting service.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
public class TaskBillingServiceImpl implements TaskBillingService {
    private final EconomyTaskBillingMapper mapper;
    private final EconomyLedgerMapper ledger;
    private final EconomyPostingService posting;
    private final ObjectMapper json;
    private final TaskPricingService pricing;

    public TaskBillingServiceImpl(EconomyTaskBillingMapper mapper, EconomyLedgerMapper ledger,
            EconomyPostingService posting, ObjectMapper json, TaskPricingService pricing) {
        this.mapper = mapper;
        this.ledger = ledger;
        this.posting = posting;
        this.json = json;
        this.pricing = pricing;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class,
            noRollbackFor = TaskPricingService.Unavailable.class)
    public Quote quote(Task task, Binding binding, String payerUserId, TaskWorkload workload, TaskPricingService.ModelKey model) {
        Objects.requireNonNull(payerUserId, "payerUserId");
        String bindingHash = bindingHash(binding);
        EconomyTaskQuoteEntity stored = mapper.findQuoteByBinding(task.scope().tenantId(), task.scope().clientId(),
                task.ownerJiacn(), task.taskId(), bindingHash);
        if (stored != null) {
            if (!payerUserId.equals(stored.getPayerUserId())) throw new TaskBillingException(CHARGE_CONFLICT);
            return receipt(stored);
        }
        // Replay above precedes all current price lookup, including a now-removed configuration entry.
        var prepared = pricing.prepare(workload, model);
        var estimate = prepared.estimate();
        var modelPrice = prepared.modelPrice();
        TaskQuoteCalculator.Quote calculated;
        try { calculated = TaskQuoteCalculator.calculate(estimate, modelPrice, prepared.toolPrices()); }
        catch (ArithmeticException overflow) {
            throw new TaskPricingService.Unavailable(TaskPricingService.Unavailable.Reason.QUOTE_AMOUNT_OVERFLOW);
        }
        // Structured tool keys, not JSON object keys: the immutable snapshot can be read without guessing.
        List<ToolEstimate> tools = estimate.toolUnits().entrySet().stream()
                .map(entry -> new ToolEstimate(entry.getKey(), entry.getValue(), calculated.toolPrices().get(entry.getKey())))
                .toList();
        String snapshot = encode(new QuoteSnapshot(binding,
                new WorkloadSnapshot(workload.kind(), workload.inputTokens(), workload.outputTokens(), workload.deliverables()),
                estimate.ruleId(), estimate.ruleVersion(),
                estimate.tokens(), modelPrice, tools, calculated.estimatedYuan(), calculated.amountWen()));
        EconomyTaskQuoteEntity row = new EconomyTaskQuoteEntity().setQuoteId("tq_" + UUID.randomUUID())
                .setBindingSha256(bindingHash).setTenantId(task.scope().tenantId()).setClientId(task.scope().clientId())
                .setOwnerJiacn(task.ownerJiacn()).setTaskId(task.taskId()).setPayerUserId(payerUserId)
                .setAmountWen(calculated.amountWen()).setSnapshotJson(snapshot).setCreatedAt(System.currentTimeMillis());
        mapper.insertQuote(row);
        return receipt(row);
    }

    @Override
    public Charge reserve(Task task, String quoteId, Binding currentBinding, String payerUserId) {
        EconomyTaskQuoteEntity quote = mapper.findQuote(task.scope().tenantId(), task.scope().clientId(),
                task.ownerJiacn(), task.taskId(), quoteId);
        if (quote == null) throw new TaskBillingException(QUOTE_NOT_FOUND);
        // The task owner is a business identity, not the wallet principal. Check the immutable
        // payer before any write (and before returning an existing reservation).
        if (!quote.getPayerUserId().equals(payerUserId)) {
            throw new TaskBillingException(QUOTE_PAYER_MISMATCH);
        }
        if (!quote.getBindingSha256().equals(bindingHash(currentBinding))) {
            throw new TaskBillingException(QUOTE_BINDING_CHANGED);
        }
        long now = System.currentTimeMillis();
        EconomyTaskChargeEntity row = new EconomyTaskChargeEntity()
                .setTenantId(task.scope().tenantId()).setClientId(task.scope().clientId())
                .setOwnerJiacn(task.ownerJiacn()).setTaskId(task.taskId()).setQuoteId(quoteId)
                .setPayerUserId(quote.getPayerUserId()).setAmountWen(quote.getAmountWen())
                .setState("RESERVING").setCreatedAt(now).setUpdatedAt(now);
        // Insert first: locking an absent charge then inserting would take overlapping InnoDB
        // gap locks across unrelated tasks. The upsert never changes an existing charge.
        mapper.insertChargeIfAbsent(row);
        row = lock(task);
        if (!quoteId.equals(row.getQuoteId())) throw new TaskBillingException(CHARGE_CONFLICT);
        if (State.HELD.name().equals(row.getState())) return receipt(row);
        if (!"RESERVING".equals(row.getState())) throw new TaskBillingException(CHARGE_CONFLICT);
        if (row.getAmountWen() > 0) {
            EconomyAccountKey payer = payer(row);
            EconomyAccountKey escrow = escrow(task);
            List.of(payer, escrow).stream().sorted(EconomyAccountKey.LOCK_ORDER)
                    .forEach(account -> ensureAccount(task, account, now));
            EconomyPostingResult result = post(task, row, EconomyJournalType.RESERVE_BOUNTY, payer, escrow,
                    new EconomyEscrowFunding(EconomyEscrowType.BOUNTY, payer, escrow, row.getAmountWen(), null), null);
            row.setReserveTransactionId(result.transactionId()).setEscrowVersion(result.escrow().escrowVersion());
        }
        row.setState(State.HELD.name());
        requireTransition(mapper.markHeld(row));
        return receipt(row);
    }

    @Override
    public Charge reservation(Task task, String quoteId, String payerUserId, String recommendationId) {
        EconomyTaskChargeEntity row = lock(task);
        if (row == null) throw new TaskBillingException(RESERVATION_REQUIRED);
        if (!Objects.equals(row.getPayerUserId(), payerUserId) || payerUserId == null)
            throw new TaskBillingException(QUOTE_PAYER_MISMATCH);
        if (!Objects.equals(row.getQuoteId(), quoteId) || quoteId == null)
            throw new TaskBillingException(CHARGE_CONFLICT);
        EconomyTaskQuoteEntity quote = mapper.findQuote(task.scope().tenantId(), task.scope().clientId(),
                task.ownerJiacn(), task.taskId(), quoteId);
        if (quote == null) throw new TaskBillingException(QUOTE_NOT_FOUND);
        String originalRecommendation = json.readTree(quote.getSnapshotJson())
                .at("/binding/recommendationId").asText(null);
        if (recommendationId == null || !recommendationId.equals(originalRecommendation))
            throw new TaskBillingException(QUOTE_BINDING_CHANGED);
        return receipt(row);
    }

    @Override
    public Charge capture(Task task) {
        return settle(task, State.CAPTURED, true);
    }

    @Override
    public Charge release(Task task, boolean reservationRequired) {
        return settle(task, State.RELEASED, reservationRequired);
    }

    private Charge settle(Task task, State terminal, boolean required) {
        EconomyTaskChargeEntity row = lock(task);
        if (row == null) {
            if (required) throw new TaskBillingException(RESERVATION_REQUIRED);
            return null; // explicitly unassigned cancellation, never inferred free acceptance
        }
        State state = State.valueOf(row.getState());
        if (state == terminal || state == State.LEGACY_FREE) return receipt(row);
        if (state != State.HELD) throw new TaskBillingException(CHARGE_CONFLICT);
        if (row.getAmountWen() > 0) {
            EconomyAccountKey escrow = escrow(task);
            boolean capture = terminal == State.CAPTURED;
            EconomyAccountKey destination = capture
                    ? new EconomyAccountKey(EconomyConstants.CURRENCY_WEN, EconomyAccountOwnerType.SYSTEM,
                            "TASK_FEE", EconomyAccountPurpose.TASK_FEE)
                    : payer(row);
            if (capture) ensureAccount(task, destination, System.currentTimeMillis());
            EconomyPostingResult result = post(task, row,
                    capture ? EconomyJournalType.CAPTURE_TASK : EconomyJournalType.REFUND_BOUNTY,
                    escrow, destination, null, new EconomyEscrowSettlement(EconomyEscrowType.BOUNTY,
                            escrow, destination, row.getAmountWen(), row.getEscrowVersion(), row.getReserveTransactionId()));
            row.setTerminalTransactionId(result.transactionId()).setEscrowVersion(Math.addExact(row.getEscrowVersion(), 1L));
        }
        row.setState(terminal.name()).setUpdatedAt(System.currentTimeMillis());
        requireTransition(mapper.markTerminal(row));
        return receipt(row);
    }

    private EconomyPostingResult post(Task task, EconomyTaskChargeEntity row, EconomyJournalType type,
            EconomyAccountKey debit, EconomyAccountKey credit, EconomyEscrowFunding funding,
            EconomyEscrowSettlement settlement) {
        byte[] identity = encode(List.of("task-charge-v1", task.scope().tenantId(), task.scope().clientId(),
                task.ownerJiacn(), task.taskId(), row.getQuoteId(), type.name(), row.getPayerUserId(), row.getAmountWen()))
                .getBytes(StandardCharsets.UTF_8);
        return posting.post(new EconomyPostingCommand(task.scope(),
                new EconomyPrincipal(EconomyPrincipalType.USER, row.getPayerUserId()),
                UUID.nameUUIDFromBytes(identity).toString(), digest(identity), type, task.taskId(),
                List.of(new EconomyPostingLine(debit, -row.getAmountWen()),
                        new EconomyPostingLine(credit, row.getAmountWen())), funding, settlement));
    }

    private void ensureAccount(Task task, EconomyAccountKey key, long now) {
        String id = "task_billing_" + HexFormat.of().formatHex(digest(encode(List.of(task.scope().tenantId(),
                task.scope().clientId(), key.currency(), key.ownerType().name(), key.ownerId(), key.purpose().name()))
                .getBytes(StandardCharsets.UTF_8)));
        ledger.insertAccountIfAbsentAndLock(new EconomyAccountEntity().setAccountId(id)
                .setTenantId(task.scope().tenantId()).setClientId(task.scope().clientId())
                .setOwnerType(key.ownerType().name()).setOwnerId(key.ownerId()).setPurpose(key.purpose().name())
                .setCurrency(key.currency()).setBalanceWen(0L).setAllowNegative(0).setStatus("ACTIVE")
                .setVersion(0L).setCreateTime(now).setUpdateTime(now));
    }

    private EconomyTaskChargeEntity lock(Task task) {
        return mapper.lockCharge(task.scope().tenantId(), task.scope().clientId(), task.ownerJiacn(), task.taskId());
    }

    private static EconomyAccountKey payer(EconomyTaskChargeEntity row) {
        return new EconomyAccountKey(EconomyConstants.CURRENCY_WEN, EconomyAccountOwnerType.USER,
                row.getPayerUserId(), EconomyAccountPurpose.AVAILABLE);
    }

    private static EconomyAccountKey escrow(Task task) {
        return new EconomyAccountKey(EconomyConstants.CURRENCY_WEN, EconomyAccountOwnerType.TASK,
                task.taskId(), EconomyAccountPurpose.ESCROW);
    }

    private String bindingHash(Binding binding) {
        return HexFormat.of().formatHex(digest(encode(List.of(binding.requirementRevision(),
                binding.requirementSha256(), binding.inputSnapshotDigest(), binding.agentId(), binding.recommendationId()))
                .getBytes(StandardCharsets.UTF_8)));
    }

    private String encode(Object value) {
        return json.writeValueAsString(value);
    }

    private static byte[] digest(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static void requireTransition(int changed) {
        if (changed != 1) throw new TaskBillingException(CHARGE_CONFLICT);
    }

    private static Quote receipt(EconomyTaskQuoteEntity row) {
        return new Quote(row.getQuoteId(), row.getAmountWen(), row.getSnapshotJson());
    }

    private static Charge receipt(EconomyTaskChargeEntity row) {
        return new Charge(row.getQuoteId(), row.getAmountWen(), State.valueOf(row.getState()),
                row.getReserveTransactionId(), row.getTerminalTransactionId());
    }

    private record ToolEstimate(TaskQuoteCalculator.ToolKey key, BigDecimal units, TaskQuoteCalculator.ToolPrice price) { }
    private record WorkloadSnapshot(String kind, long inputTokens, long outputTokens, long deliverables) { }
    private record QuoteSnapshot(Binding binding, WorkloadSnapshot workload, String ruleId, String ruleVersion,
            Map<ModelTokenPrice.Category, Long> tokens, ModelTokenPrice modelPrice, List<ToolEstimate> tools,
            BigDecimal estimatedYuan, long amountWen) { }
}
