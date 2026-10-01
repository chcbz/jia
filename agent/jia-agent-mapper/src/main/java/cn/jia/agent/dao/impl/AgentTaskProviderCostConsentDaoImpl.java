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
        return hasPurposeColumn()
                ? mapper.selectPurposeAwareByIdempotencyKey(tenantId,clientId,ownerJiacn,
                        taskId,idempotencyKey)
                : mapper.selectByIdempotencyKey(tenantId,clientId,ownerJiacn,
                        taskId,idempotencyKey);
    }
    @Override public AgentTaskProviderCostConsentEntity findByIdempotencyKeyForUpdate(
            String tenantId, String clientId, String ownerJiacn, String taskId,
            String idempotencyKey) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        id(idempotencyKey, "idempotencyKey", 100);
        return hasPurposeColumn()
                ? mapper.selectPurposeAwareByIdempotencyKeyForUpdate(tenantId,clientId,ownerJiacn,
                        taskId,idempotencyKey)
                : mapper.selectByIdempotencyKeyForUpdate(tenantId,clientId,ownerJiacn,
                        taskId,idempotencyKey);
    }
    @Override public AgentTaskProviderCostConsentEntity findByConsent(String tenantId,
            String clientId, String ownerJiacn, String taskId, String consentId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        id(consentId, "consentId", 100);
        return hasPurposeColumn()
                ? mapper.selectPurposeAwareByConsent(tenantId,clientId,ownerJiacn,taskId,consentId)
                : mapper.selectByConsent(tenantId,clientId,ownerJiacn,taskId,consentId);
    }
    @Override public AgentTaskProviderCostConsentEntity findByConsentForUpdate(String tenantId,
            String clientId, String ownerJiacn, String taskId, String consentId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        id(consentId, "consentId", 100);
        return hasPurposeColumn()
                ? mapper.selectPurposeAwareByConsentForUpdate(tenantId,clientId,ownerJiacn,taskId,consentId)
                : mapper.selectByConsentForUpdate(tenantId,clientId,ownerJiacn,taskId,consentId);
    }
    @Override public void insert(AgentTaskProviderCostConsentEntity consent) {
        validate(consent); consent.init4Creation();
        if (mapper.insert(consent) != 1) throw new IllegalStateException("consent insert failed");
    }
    @Override public boolean bind(AgentTaskProviderCostConsentEntity consent,long expectedVersion) {
        validateInitial(consent); return mapper.bind(consent, expectedVersion) == 1;
    }
    @Override public boolean reserve(AgentTaskProviderCostConsentEntity consent,long expectedVersion) {
        validateInitial(consent); return mapper.reserve(consent, expectedVersion) == 1;
    }
    @Override public boolean consume(AgentTaskProviderCostConsentEntity consent,long expectedVersion) {
        validateInitial(consent); return mapper.consume(consent, expectedVersion) == 1;
    }
    @Override public boolean revoke(AgentTaskProviderCostConsentEntity consent,long expectedVersion) {
        validateInitial(consent); return mapper.revoke(consent, expectedVersion) == 1;
    }

    @Override public AgentTaskProviderCostConsentEntity findFollowupByConsent(String tenantId,
            String clientId,String ownerJiacn,String taskId,String consentId) {
        scope(tenantId,clientId,ownerJiacn);id(taskId,"taskId",100);id(consentId,"consentId",100);
        return mapper.selectFollowupByConsent(tenantId,clientId,ownerJiacn,taskId,consentId);
    }
    @Override public AgentTaskProviderCostConsentEntity findFollowupByConsentForUpdate(String tenantId,
            String clientId,String ownerJiacn,String taskId,String consentId) {
        scope(tenantId,clientId,ownerJiacn);id(taskId,"taskId",100);id(consentId,"consentId",100);
        return mapper.selectFollowupByConsentForUpdate(tenantId,clientId,ownerJiacn,taskId,consentId);
    }

    @Override public boolean bindFollowup(AgentTaskProviderCostConsentEntity consent,long expectedVersion) { validateFollowup(consent);return mapper.bindFollowup(consent,expectedVersion)==1; }
    @Override public boolean reserveFollowup(AgentTaskProviderCostConsentEntity consent,long expectedVersion) { validateFollowup(consent);return mapper.reserveFollowup(consent,expectedVersion)==1; }
    @Override public boolean consumeFollowup(AgentTaskProviderCostConsentEntity consent,long expectedVersion) { validateFollowup(consent);return mapper.consumeFollowup(consent,expectedVersion)==1; }
    @Override public boolean revokeFollowup(AgentTaskProviderCostConsentEntity consent,long expectedVersion) { validateFollowup(consent);return mapper.revokeFollowup(consent,expectedVersion)==1; }
    private static void validateFollowup(AgentTaskProviderCostConsentEntity row){validate(row);if(!"FOLLOWUP_EXECUTE".equals(row.getConsentPurpose())||row.getOperationGrantId()==null)throw new IllegalArgumentException("followup consent");}

    private boolean hasPurposeColumn() {
        int count=mapper.countConsentPurposeColumn();
        if(count<0||count>1)throw new IllegalStateException("consent purpose column ambiguity");
        return count==1;
    }
    private static void validateInitial(AgentTaskProviderCostConsentEntity row) {
        validate(row);
        if(row.getConsentPurpose()!=null
                && !"INITIAL_ASSIGN_AND_START".equals(row.getConsentPurpose()))
            throw new IllegalArgumentException("initial consent purpose");
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
