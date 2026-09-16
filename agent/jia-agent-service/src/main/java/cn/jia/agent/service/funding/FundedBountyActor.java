package cn.jia.agent.service.funding;

/** Authenticated money actor: shared tenant, client, personal owner, and payer subject. */
public record FundedBountyActor(String tenantId, String clientId, String ownerJiacn, String userId) {
}
