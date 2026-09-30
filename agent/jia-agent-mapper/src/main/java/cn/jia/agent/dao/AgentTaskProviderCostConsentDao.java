package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskProviderCostConsentEntity;

public interface AgentTaskProviderCostConsentDao {
    AgentTaskProviderCostConsentEntity findByIdempotencyKey(String tenantId,
            String clientId, String ownerJiacn, String taskId, String idempotencyKey);
    AgentTaskProviderCostConsentEntity findByIdempotencyKeyForUpdate(String tenantId,
            String clientId, String ownerJiacn, String taskId, String idempotencyKey);
    AgentTaskProviderCostConsentEntity findByConsent(String tenantId, String clientId,
            String ownerJiacn, String taskId, String consentId);
    AgentTaskProviderCostConsentEntity findByConsentForUpdate(String tenantId, String clientId,
            String ownerJiacn, String taskId, String consentId);
    void insert(AgentTaskProviderCostConsentEntity consent);
    boolean bind(AgentTaskProviderCostConsentEntity consent, long expectedVersion);
    boolean reserve(AgentTaskProviderCostConsentEntity consent, long expectedVersion);
    boolean consume(AgentTaskProviderCostConsentEntity consent, long expectedVersion);
    boolean revoke(AgentTaskProviderCostConsentEntity consent, long expectedVersion);
}
