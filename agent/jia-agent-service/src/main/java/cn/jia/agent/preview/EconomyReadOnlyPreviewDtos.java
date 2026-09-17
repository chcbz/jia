package cn.jia.agent.preview;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** Frozen JSON shapes for contract economy-readonly-v1. */
public final class EconomyReadOnlyPreviewDtos {
    private EconomyReadOnlyPreviewDtos() { }

    public record Principal(String tenantId, String clientId, String ownerJiacn, String actorId) { }

    public record Capabilities(String contractVersion, String mode, boolean enabled,
            String principalScopeFingerprint, Map<String, Boolean> features, Map<String, Boolean> actions) { }

    public record Wallet(String currency, String availableMicro, String heldMicro, String version) { }

    public record LedgerPage(List<LedgerItem> items, String nextCursor) { }

    public record LedgerItem(String transactionId, String entryId, String businessType, String businessRef,
            String direction, String amountMicro, String status, String postedAt) { }

    public record ProductPage(List<Product> items, String nextOffset) { }

    public record Product(String productId, String name, String description, String productVersionId,
            String skillKey, String skillVersion, String priceMicro, List<String> permissions,
            String deploymentRestriction, String priceSource, boolean purchaseAllowed) { }

    public record AgentSkills(String agentId, List<Entitlement> entitlements,
            List<InstallationEvidence> installationEvidence, String observedAt) { }

    public record Entitlement(String entitlementId, String orderId, String installationId,
            String productVersionId, String skillKey, String skillVersion, String status,
            String permissionGrantVersion) { }

    public record InstallationEvidence(String installationId, String skillKey, String skillVersion,
            String status, String evidenceKind, String verifiedAt) { }

    public record HostingPlan(String source, String planVersion, String amountMicro,
            String periodSeconds, String currency, boolean activationAllowed) { }

    public record HostingLease(String agentId, String applicability, Lease lease) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lease(String leaseId, String version, String status, String planVersion,
            String amountMicro, String periodSeconds, String paidFrom, String paidThrough) { }

    public record TokenClasses(String input, String cachedInput, String output, String reasoning) { }

    public record BountyEstimate(String mode, boolean charged, boolean persisted, String currency,
            String priceBookVersion, String rateProvenance, TokenClasses estimatedTokens,
            TokenClasses worstTokens, String estimatedComputeMicro, String worstComputeMicro,
            String platformFeeMicro, String estimatedAgentPayoutMicro, String worstAgentPayoutMicro,
            String budgetHeadroomMicro, boolean budgetCovered) { }

    public record EstimateInput(long grossBountyAmountMicro, long minimumAcceptedPayoutMicro,
            ParsedTokens estimatedTokens, ParsedTokens worstTokens) { }

    public record ParsedTokens(long input, long cachedInput, long output, long reasoning) { }
}
