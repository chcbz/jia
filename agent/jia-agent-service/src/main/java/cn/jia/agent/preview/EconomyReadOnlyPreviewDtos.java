package cn.jia.agent.preview;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** Frozen JSON shapes for contract economy-readonly-v1. */
public final class EconomyReadOnlyPreviewDtos {
    private EconomyReadOnlyPreviewDtos() { }

    /**
     * Trusted JWT identity projected into the exact historical scopes used by each write source.
     * Wallet writes use jiacn as tenant; marketplace, hosting and Agent registry writes use the
     * shared deployment tenant 0. Keeping the domains separate prevents either convention from
     * being silently generalized to the other.
     */
    public record Principal(String walletTenantId, String marketplaceTenantId,
            String hostingTenantId, String agentRegistryTenantId, String clientId,
            String ownerJiacn, String actorId) { }

    public record Capabilities(String contractVersion, String mode, boolean enabled,
            String principalScopeFingerprint, Map<String, Boolean> features, Map<String, Boolean> actions) { }

    public record Wallet(String currency, String availableWen, String heldWen, String version) { }

    public record LedgerPage(List<LedgerItem> items, String nextCursor) { }

    public record LedgerItem(String transactionId, String entryId, String businessType, String businessRef,
            String direction, String amountWen, String status, String postedAt) { }

    public record ProductPage(List<Product> items, String nextOffset) { }

    public record Product(String productId, String name, String description, String productVersionId,
            String skillKey, String skillVersion, String priceWen, List<String> permissions,
            String deploymentRestriction, String priceSource, boolean purchaseAllowed) { }

    public record AgentSkills(String agentId, List<Entitlement> entitlements,
            List<InstallationEvidence> installationEvidence, String observedAt) { }

    public record Entitlement(String entitlementId, String orderId, String installationId,
            String productVersionId, String skillKey, String skillVersion, String status,
            String permissionGrantVersion) { }

    public record InstallationEvidence(String installationId, String skillKey, String skillVersion,
            String status, String evidenceKind, String verifiedAt) { }

    public record HostingPlan(String source, String planVersion, String amountWen,
            String periodSeconds, String currency, boolean activationAllowed) { }

    public record HostingLease(String agentId, String applicability, Lease lease) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lease(String leaseId, String version, String status, String planVersion,
            String amountWen, String periodSeconds, String paidFrom, String paidThrough) { }

    public record TokenClasses(String input, String cachedInput, String output, String reasoning) { }

    public record BountyEstimate(String mode, boolean charged, boolean persisted, String currency,
            String priceBookVersion, String rateProvenance, TokenClasses estimatedTokens,
            TokenClasses worstTokens, String estimatedComputeWen, String worstComputeWen,
            String platformFeeWen, String estimatedAgentPayoutWen, String worstAgentPayoutWen,
            String budgetHeadroomWen, boolean budgetCovered) { }

    public record EstimateInput(long grossBountyAmountWen, long minimumAcceptedPayoutWen,
            ParsedTokens estimatedTokens, ParsedTokens worstTokens) { }

    public record ParsedTokens(long input, long cachedInput, long output, long reasoning) { }
}
