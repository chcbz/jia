package cn.jia.agent.api;

import cn.jia.agent.hosting.HostingRentApplicationService;
import cn.jia.agent.entity.funding.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Public projections only: stored quotes, money, hashes and replay receipts remain unchanged. */
final class EconomyOperationWire {
    private EconomyOperationWire() { }
    private static Long epoch(String value) {
        if (value == null) return null;
        if (!value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Invalid stored timestamp");
        long parsed = Long.parseLong(value);
        if (parsed > 9007199254740991L) throw new IllegalArgumentException("Unsafe stored timestamp");
        return parsed;
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record QuoteView(String quoteId, String purpose, String personaCode, String agentId, String leaseId, String expectedLeaseVersion, String planId, String planVersion, String currency, String amountMicro, String periodSeconds, Long expiresAt) { }
    static QuoteView from(HostingRentApplicationService.QuoteView v) { return v == null ? null : new QuoteView(v.quoteId(), v.purpose(), v.personaCode(), v.agentId(), v.leaseId(), v.expectedLeaseVersion(), v.planId(), v.planVersion(), v.currency(), v.amountMicro(), v.periodSeconds(), epoch(v.expiresAt())); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ReprovisionView(String requestId, String agentId, String leaseId, String operation, String status, String currency, String amountMicro, String leaseVersion, Long paidThrough, Long occurredAt) { }
    static ReprovisionView from(HostingRentApplicationService.ReprovisionView v) { return v == null ? null : new ReprovisionView(v.requestId(), v.agentId(), v.leaseId(), v.operation(), v.status(), v.currency(), v.amountMicro(), v.leaseVersion(), epoch(v.paidThrough()), epoch(v.occurredAt())); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ReprovisionSnapshot(String requestId, String version, String status, Long requestedAt, Long serviceReadyAt) { }
    static ReprovisionSnapshot from(HostingRentApplicationService.ReprovisionSnapshot v) { return v == null ? null : new ReprovisionSnapshot(v.requestId(), v.version(), v.status(), epoch(v.requestedAt()), epoch(v.serviceReadyAt())); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record MutationView(String agentId, String intentId, String leaseId, String quoteId, String transactionId, String status, String currency, String amountMicro, String periodSeconds, Long occurredAt) { }
    static MutationView from(HostingRentApplicationService.MutationView v) { return v == null ? null : new MutationView(v.agentId(), v.intentId(), v.leaseId(), v.quoteId(), v.transactionId(), v.status(), v.currency(), v.amountMicro(), v.periodSeconds(), epoch(v.occurredAt())); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record LeaseView(boolean managed, LeaseSnapshot lease, IntentSnapshot intent, String admission, ReprovisionSnapshot reprovision) { }
    static LeaseView from(HostingRentApplicationService.LeaseView v) { return v == null ? null : new LeaseView(v.managed(), from(v.lease()), from(v.intent()), v.admission(), from(v.reprovision())); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record LeaseSnapshot(String leaseId, String agentId, String personaCode, String bindingId, String version, String status, String planVersion, String amountMicro, String periodSeconds, Long paidFrom, Long paidThrough) { }
    static LeaseSnapshot from(HostingRentApplicationService.LeaseSnapshot v) { return v == null ? null : new LeaseSnapshot(v.leaseId(), v.agentId(), v.personaCode(), v.bindingId(), v.version(), v.status(), v.planVersion(), v.amountMicro(), v.periodSeconds(), epoch(v.paidFrom()), epoch(v.paidThrough())); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record IntentSnapshot(String intentId, String version, String status, Long serviceReadyAt, String reserveTransactionId, String captureTransactionId, String refundTransactionId) { }
    static IntentSnapshot from(HostingRentApplicationService.IntentSnapshot v) { return v == null ? null : new IntentSnapshot(v.intentId(), v.version(), v.status(), epoch(v.serviceReadyAt()), v.reserveTransactionId(), v.captureTransactionId(), v.refundTransactionId()); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record AgentTaskQuoteView(String quoteId, String taskId, String agentId, String taskVersion, String priceBookVersion, String taskInputSha256, String skillSetSha256, String modelRouteVersion, AgentTokenEstimateDTO estimatedTokens, String estimatedComputeMicro, String worstComputeMicro, String platformFeeMicro, String grossAllocationMicro, String estimatedAgentPayoutMicro, String worstAgentPayoutMicro, String minimumAcceptedPayoutMicro, String budgetHeadroomMicro, boolean verifiedSkillMatch, boolean advisoryAbilityMatch, String recommendation, List<String> reasonCodes, Long expiresAt) { }
    static AgentTaskQuoteView from(AgentTaskQuoteDTO v) { return v == null ? null : new AgentTaskQuoteView(v.quoteId(), v.taskId(), v.agentId(), v.taskVersion(), v.priceBookVersion(), v.taskInputHash(), v.skillSetHash(), v.modelRouteVersion(), v.estimatedTokens(), v.estimatedComputeMicro(), v.worstComputeMicro(), v.platformFeeMicro(), v.grossAllocationMicro(), v.estimatedAgentPayoutMicro(), v.worstAgentPayoutMicro(), v.minimumAcceptedPayoutMicro(), v.budgetHeadroomMicro(), v.verifiedSkillMatch(), v.advisoryAbilityMatch(), v.recommendation(), v.reasonCodes(), epoch(v.expiresAt())); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record AgentTaskClaimReceiptView(String taskId, String agentId, String quoteId, String status, String taskVersion, Long claimedAt) { }
    static AgentTaskClaimReceiptView from(AgentTaskClaimReceiptDTO v) { return v == null ? null : new AgentTaskClaimReceiptView(v.taskId(), v.agentId(), v.quoteId(), v.status(), v.taskVersion(), epoch(v.claimedAt())); }
    static Object hosting(Object value) {
        if (value instanceof HostingRentApplicationService.QuoteView v) return from(v);
        if (value instanceof HostingRentApplicationService.LeaseView v) return from(v);
        if (value instanceof HostingRentApplicationService.MutationView v) return from(v);
        if (value instanceof HostingRentApplicationService.ReprovisionView v) return from(v);
        throw new IllegalArgumentException("Unexpected hosting response");
    }
}
