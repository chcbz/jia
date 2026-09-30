package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskProviderCostConsentDao;
import cn.jia.agent.entity.AgentTaskProviderCostConsentEntity;
import cn.jia.agent.mapper.AgentTaskProviderCostConsentMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.Objects;

@Named
public final class AgentTaskProviderCostConsentDaoImpl
        implements AgentTaskProviderCostConsentDao {
    private final AgentTaskProviderCostConsentMapper mapper;

    @Inject
    public AgentTaskProviderCostConsentDaoImpl(AgentTaskProviderCostConsentMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override public AgentTaskProviderCostConsentEntity findByIdempotencyKey(
            String tenantId, String clientId, String ownerJiacn, String taskId,
            String idempotencyKey) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        id(idempotencyKey, "idempotencyKey", 100);
        return mapper.selectByIdempotencyKey(tenantId, clientId, ownerJiacn,
                taskId, idempotencyKey);
    }
    @Override public AgentTaskProviderCostConsentEntity findByIdempotencyKeyForUpdate(
            String tenantId, String clientId, String ownerJiacn, String taskId,
            String idempotencyKey) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        id(idempotencyKey, "idempotencyKey", 100);
        return mapper.selectByIdempotencyKeyForUpdate(tenantId, clientId, ownerJiacn,
                taskId, idempotencyKey);
    }
    @Override public AgentTaskProviderCostConsentEntity findByConsent(String tenantId,
            String clientId, String ownerJiacn, String taskId, String consentId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        id(consentId, "consentId", 100);
        return mapper.selectByConsent(tenantId, clientId, ownerJiacn, taskId, consentId);
    }
    @Override public AgentTaskProviderCostConsentEntity findByConsentForUpdate(String tenantId,
            String clientId, String ownerJiacn, String taskId, String consentId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        id(consentId, "consentId", 100);
        return mapper.selectByConsentForUpdate(tenantId, clientId, ownerJiacn, taskId, consentId);
    }
    @Override public void insert(AgentTaskProviderCostConsentEntity consent) {
        validate(consent); consent.init4Creation();
        if (mapper.insert(consent) != 1) throw new IllegalStateException("consent insert failed");
    }
    @Override public boolean bind(AgentTaskProviderCostConsentEntity consent,long expectedVersion) {
        validate(consent); return mapper.bind(consent, expectedVersion) == 1;
    }
    @Override public boolean reserve(AgentTaskProviderCostConsentEntity consent,long expectedVersion) {
        validate(consent); return mapper.reserve(consent, expectedVersion) == 1;
    }
    @Override public boolean consume(AgentTaskProviderCostConsentEntity consent,long expectedVersion) {
        validate(consent); return mapper.consume(consent, expectedVersion) == 1;
    }
    @Override public boolean revoke(AgentTaskProviderCostConsentEntity consent,long expectedVersion) {
        validate(consent); return mapper.revoke(consent, expectedVersion) == 1;
    }

    private static void validate(AgentTaskProviderCostConsentEntity row) {
        Objects.requireNonNull(row, "consent");
        scope(row.getTenantId(), row.getClientId(), row.getOwnerJiacn());
        id(row.getTaskId(), "taskId", 100); id(row.getConsentId(), "consentId", 100);
    }
    private static void scope(String tenant,String client,String owner) {
        if (!"0".equals(tenant)) throw new IllegalArgumentException("tenantId");
        id(client,"clientId",50); id(owner,"ownerJiacn",50);
        if ("0".equals(owner)) throw new IllegalArgumentException("ownerJiacn");
    }
    private static void id(String value,String name,int max) {
        if (value==null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0,value.length())>max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name);
        }
    }
}
