package cn.jia.economy.service.impl;

import cn.jia.economy.entity.EconomyTaskCostEntity;
import cn.jia.economy.entity.EconomyUsageAttemptEntity;
import cn.jia.economy.entity.EconomyUsageRevisionEntity;
import cn.jia.economy.mapper.EconomyUsageCostMapper;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.usage.TokenUsageCost;
import cn.jia.economy.usage.UsageCostConflict;
import cn.jia.economy.usage.UsageCostService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static cn.jia.economy.usage.UsageCostConflict.Reason.*;
import static cn.jia.economy.usage.TokenUsageCost.Status.*;

/** Independent cost journal. Serializes only a task's cost summary, never its business root or wallet. */
@Service
public class UsageCostServiceImpl implements UsageCostService {
    private static final BigDecimal ZERO = new BigDecimal("0.000000");
    private final EconomyUsageCostMapper mapper;
    private final ObjectMapper json;

    public UsageCostServiceImpl(EconomyUsageCostMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json.rebuild().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Receipt record(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        // Preserve source evidence verbatim, including a malformed payload awaiting reconciliation.
        // Parsing/normalization belongs to the source adapter; the journal does not parse it again.
        var binding = observation.binding(); var scope = binding.scope();
        String attemptId = digest(json.writeValueAsString(List.of(binding.source(), binding.sourceAttemptId())));
        String receiptKey = digest(observation.receiptId());
        String requestHash = digest(json.writeValueAsString(observation));
        long now = System.currentTimeMillis();
        EconomyTaskCostEntity summary = null;
        if (binding.taskId() != null) {
            var summaryKey = new EconomyTaskCostEntity().setTenantId(scope.tenantId()).setClientId(scope.clientId())
                    .setOwnerJiacn(binding.ownerJiacn()).setTaskId(binding.taskId()).setUpdatedAt(now);
            mapper.insertSummaryIfAbsent(summaryKey);
            summary = mapper.lockSummary(summaryKey);
        }
        var attemptKey = new EconomyUsageAttemptEntity().setTenantId(scope.tenantId()).setClientId(scope.clientId())
                .setAttemptId(attemptId).setOwnerJiacn(binding.ownerJiacn()).setTaskId(binding.taskId())
                .setSource(binding.source()).setSourceAttemptId(binding.sourceAttemptId()).setCreatedAt(now).setUpdatedAt(now);
        mapper.insertAttemptIfAbsent(attemptKey);
        var attempt = mapper.lockAttempt(attemptKey);
        if (!binding.ownerJiacn().equals(attempt.getOwnerJiacn()) || !Objects.equals(binding.taskId(), attempt.getTaskId())
                || !binding.source().equals(attempt.getSource()) || !binding.sourceAttemptId().equals(attempt.getSourceAttemptId()))
            throw new UsageCostConflict(ATTEMPT_BINDING);
        var revision = new EconomyUsageRevisionEntity().setTenantId(scope.tenantId()).setClientId(scope.clientId())
                .setAttemptId(attemptId).setReceiptKey(receiptKey).setReceiptId(observation.receiptId()).setRequestSha256(requestHash);
        var replay = mapper.findReceipt(revision);
        if (replay != null) {
            if (!requestHash.equals(replay.getRequestSha256()) || !observation.receiptId().equals(replay.getReceiptId()))
                throw new UsageCostConflict(RECEIPT_CONTENT);
            return receipt(replay, true);
        }
        if (attempt.getRevision() != observation.expectedRevision()) throw new UsageCostConflict(REVISION);
        var assessed = TokenUsageCost.assess(observation.normalizedUsage(), observation.price());
        long nextRevision = Math.incrementExact(attempt.getRevision());
        revision.setRevision(nextRevision).setRawUsageJson(observation.rawUsageJson())
                .setNormalizedUsageJson(encodeNullable(observation.normalizedUsage()))
                .setPriceSnapshotJson(encodeNullable(observation.price()))
                .setStatus(assessed.status().name()).setCostYuan(assessed.costYuan())
                .setObservedAt(observation.observedAt()).setRecordedAt(now);
        requireOne(mapper.insertRevision(revision));
        if (summary != null) summary.setAttempts(Math.addExact(summary.getAttempts(), attempt.getRevision() == 0 ? 1 : 0))
                .setPendingUsage(Math.addExact(summary.getPendingUsage(), pending(assessed.status().name(), PENDING_USAGE) - pending(attempt.getStatus(), PENDING_USAGE)))
                .setPendingPrice(Math.addExact(summary.getPendingPrice(), pending(assessed.status().name(), PENDING_PRICE) - pending(attempt.getStatus(), PENDING_PRICE)))
                .setCostYuan(summary.getCostYuan().subtract(amount(attempt.getCostYuan())).add(amount(assessed.costYuan())))
                .setUpdatedAt(now);
        attempt.setRevision(nextRevision).setStatus(assessed.status().name()).setCostYuan(assessed.costYuan()).setUpdatedAt(now);
        requireOne(mapper.updateAttempt(attempt));
        if (summary != null) requireOne(mapper.updateSummary(summary));
        return receipt(revision, false);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public Summary summary(EconomyScope scope, String ownerJiacn, String taskId) {
        var row = mapper.findSummary(scope.tenantId(), scope.clientId(), ownerJiacn, taskId);
        return row == null ? new Summary(0, 0, 0, ZERO)
                : new Summary(row.getAttempts(), row.getPendingUsage(), row.getPendingPrice(), row.getCostYuan());
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public java.util.Optional<Observation> observation(Binding binding, String receiptId) {
        var scope = binding.scope();
        String attemptId = digest(json.writeValueAsString(List.of(binding.source(), binding.sourceAttemptId())));
        var attempt = mapper.findAttempt(new EconomyUsageAttemptEntity().setTenantId(scope.tenantId())
                .setClientId(scope.clientId()).setAttemptId(attemptId));
        if (attempt == null) return java.util.Optional.empty();
        if (!binding.ownerJiacn().equals(attempt.getOwnerJiacn()) || !Objects.equals(binding.taskId(), attempt.getTaskId()))
            throw new UsageCostConflict(ATTEMPT_BINDING);
        var row = mapper.findReceipt(new EconomyUsageRevisionEntity().setTenantId(scope.tenantId())
                .setClientId(scope.clientId()).setAttemptId(attemptId).setReceiptKey(digest(receiptId)));
        if (row == null) return java.util.Optional.empty();
        return java.util.Optional.of(new Observation(binding, row.getReceiptId(), row.getRevision() - 1,
                row.getRawUsageJson(), row.getNormalizedUsageJson() == null ? null : json.readValue(row.getNormalizedUsageJson(), TokenUsageCost.Usage.class),
                row.getPriceSnapshotJson() == null ? null : json.readValue(row.getPriceSnapshotJson(), cn.jia.economy.pricing.ModelTokenPrice.class), row.getObservedAt()));
    }

    private String encodeNullable(Object value) { return value == null ? null : json.writeValueAsString(value); }
    private static long pending(String state, TokenUsageCost.Status pending) { return pending.name().equals(state) ? 1 : 0; }
    private static BigDecimal amount(BigDecimal value) { return value == null ? ZERO : value; }
    private static Receipt receipt(EconomyUsageRevisionEntity row, boolean replay) {
        return new Receipt(row.getAttemptId(), row.getRevision(), TokenUsageCost.Status.valueOf(row.getStatus()), row.getCostYuan(), replay);
    }
    private static void requireOne(int changed) {
        if (changed != 1) throw new IllegalStateException("Usage cost atomic write did not affect one row");
    }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
