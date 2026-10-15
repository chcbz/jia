package cn.jia.agent.api;

import cn.jia.agent.preview.EconomyReadOnlyPreviewDtos.*;
import cn.jia.economy.common.Money;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/** Explicit HTTP projections. Internal price calculation, ledger facts and scopes are unchanged. */
final class EconomyPreviewWire {
    private EconomyPreviewWire() { }
    private static Money money(String value) { return new Money(value, "SILVER"); }
    private static Long epoch(String value) {
        if (value == null) return null;
        long result = Long.parseLong(value);
        if (result < 0 || result > 8_640_000_000_000_000L) throw new IllegalArgumentException("Invalid persisted epoch");
        return result;
    }
    record CapabilitiesView(String contractVersion,String mode,boolean enabled,String principalScopeSha256,
            Map<String,Boolean> features,Map<String,Boolean> actions) { }
    static CapabilitiesView from(Capabilities v) { return new CapabilitiesView("economy-readonly-v2",v.mode(),v.enabled(),v.principalScopeFingerprint(),v.features(),v.actions()); }
    record WalletView(String currency,Money available,Money held,String version) { }
    static WalletView from(Wallet v) { return new WalletView(v.currency(),money(v.availableMicro()),money(v.heldMicro()),v.version()); }
    record CursorPage<T>(List<T> items,String nextCursor,boolean hasMore) { }
    record LedgerItemView(String transactionId,String entryId,String businessType,String businessRef,String direction,Money amount,String status,Long postedAt) { }
    static CursorPage<LedgerItemView> from(LedgerPage v) { return new CursorPage<>(v.items().stream().map(i -> new LedgerItemView(i.transactionId(),i.entryId(),i.businessType(),i.businessRef(),i.direction(),money(i.amountMicro()),i.status(),epoch(i.postedAt()))).toList(),v.nextCursor(),v.nextCursor()!=null); }
    record ProductView(String productId,String name,String description,String productVersionId,String skillKey,String skillVersion,Money price,List<String> permissions,String deploymentRestriction,String priceSource,boolean purchaseAllowed) { }
    static ProductView from(Product v) { return new ProductView(v.productId(),v.name(),v.description(),v.productVersionId(),v.skillKey(),v.skillVersion(),money(v.priceMicro()),v.permissions(),v.deploymentRestriction(),v.priceSource(),v.purchaseAllowed()); }
    static CursorPage<ProductView> from(ProductPage v) {
        String cursor = v.nextOffset()==null ? null : productCursor(Integer.parseInt(v.nextOffset()));
        return new CursorPage<>(v.items().stream().map(EconomyPreviewWire::from).toList(),cursor,cursor!=null);
    }
    static String productCursor(int offset) {
        if (offset < 0) throw new IllegalArgumentException("Invalid cursor");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("offset:"+offset).getBytes(StandardCharsets.UTF_8));
    }
    static int productOffset(String cursor) {
        if (cursor==null) return 0;
        String decoded = new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
        if (!decoded.matches("offset:(0|[1-9][0-9]*)")) throw new IllegalArgumentException("Invalid cursor");
        int offset = Integer.parseInt(decoded.substring(7));
        if (!productCursor(offset).equals(cursor)) throw new IllegalArgumentException("Invalid cursor");
        return offset;
    }
    record EntitlementView(String entitlementId,String orderId,String skillInstallationId,String productVersionId,String skillKey,String skillVersion,String status,String permissionGrantVersion) { }
    record EvidenceView(String skillInstallationId,String skillKey,String skillVersion,String status,String evidenceKind,Long verifiedAt) { }
    record AgentSkillsView(String agentId,List<EntitlementView> entitlements,List<EvidenceView> installationEvidence,Long observedAt) { }
    static AgentSkillsView from(AgentSkills v) { return new AgentSkillsView(v.agentId(),v.entitlements().stream().map(i -> new EntitlementView(i.entitlementId(),i.orderId(),i.installationId(),i.productVersionId(),i.skillKey(),i.skillVersion(),i.status(),i.permissionGrantVersion())).toList(),v.installationEvidence().stream().map(i -> new EvidenceView(i.installationId(),i.skillKey(),i.skillVersion(),i.status(),i.evidenceKind(),epoch(i.verifiedAt()))).toList(),epoch(v.observedAt())); }
    record HostingPlanView(String source,String planVersion,Money amount,String periodSeconds,String currency,boolean activationAllowed) { }
    static HostingPlanView from(HostingPlan v) { return new HostingPlanView(v.source(),v.planVersion(),money(v.amountMicro()),v.periodSeconds(),v.currency(),v.activationAllowed()); }
    record LeaseView(String leaseId,String version,String status,String planVersion,Money amount,String periodSeconds,Long paidFrom,Long paidThrough) { }
    record HostingLeaseView(String agentId,String applicability,LeaseView lease) { }
    static HostingLeaseView from(HostingLease v) { var i=v.lease(); return new HostingLeaseView(v.agentId(),v.applicability(),i==null?null:new LeaseView(i.leaseId(),i.version(),i.status(),i.planVersion(),money(i.amountMicro()),i.periodSeconds(),epoch(i.paidFrom()),epoch(i.paidThrough()))); }
    record EstimateView(String mode,boolean charged,boolean persisted,String currency,String priceBookVersion,String rateProvenance,TokenClasses estimatedTokens,TokenClasses worstTokens,Money estimatedCompute,Money worstCompute,Money platformFee,Money estimatedAgentPayout,Money worstAgentPayout,Money budgetHeadroom,boolean budgetCovered) { }
    static EstimateView from(BountyEstimate v) { return new EstimateView(v.mode(),v.charged(),v.persisted(),v.currency(),v.priceBookVersion(),v.rateProvenance(),v.estimatedTokens(),v.worstTokens(),money(v.estimatedComputeMicro()),money(v.worstComputeMicro()),money(v.platformFeeMicro()),money(v.estimatedAgentPayoutMicro()),money(v.worstAgentPayoutMicro()),money(v.budgetHeadroomMicro()),v.budgetCovered()); }
}
