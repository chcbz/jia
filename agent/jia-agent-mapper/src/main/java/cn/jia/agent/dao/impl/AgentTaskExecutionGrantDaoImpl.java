package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskExecutionGrantDao;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import cn.jia.agent.mapper.AgentTaskExecutionGrantMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.Objects;

@Named
public final class AgentTaskExecutionGrantDaoImpl implements AgentTaskExecutionGrantDao {
    private final AgentTaskExecutionGrantMapper mapper;
    @Inject public AgentTaskExecutionGrantDaoImpl(AgentTaskExecutionGrantMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }
    @Override public AgentTaskExecutionGrantEntity findByActionForUpdate(String tenantId,
            String clientId, String ownerJiacn, String sourceBusinessActionId) {
        scope(tenantId, clientId, ownerJiacn); id(sourceBusinessActionId, "sourceBusinessActionId", 160);
        return mapper.selectByActionForUpdate(tenantId, clientId, ownerJiacn, sourceBusinessActionId);
    }
    @Override public AgentTaskExecutionGrantEntity findByGrantForUpdate(String tenantId,
            String clientId, String ownerJiacn, String taskId, String grantId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId,"taskId",100); id(grantId,"grantId",100);
        return mapper.selectByGrantForUpdate(tenantId,clientId,ownerJiacn,taskId,grantId);
    }
    @Override public AgentTaskExecutionGrantEntity findActiveByTask(String tenantId,
            String clientId, String ownerJiacn, String taskId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId,"taskId",100);
        return mapper.selectActiveByTask(tenantId,clientId,ownerJiacn,taskId);
    }
    @Override public AgentTaskExecutionGrantEntity findByGrant(String tenantId,
            String clientId, String ownerJiacn, String taskId, String grantId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId,"taskId",100); id(grantId,"grantId",100);
        return mapper.selectByGrant(tenantId,clientId,ownerJiacn,taskId,grantId);
    }
    @Override public String latestAssignmentEventJson(String tenantId, String clientId,
            String ownerJiacn, String taskId) {
        scope(tenantId,clientId,ownerJiacn); id(taskId,"taskId",100);
        return mapper.selectLatestAssignmentEventJson(tenantId,clientId,ownerJiacn,taskId);
    }
    @Override public void insert(AgentTaskExecutionGrantEntity grant) {
        Objects.requireNonNull(grant,"grant").init4Creation();
        if (mapper.insert(grant) != 1) throw new IllegalStateException("grant insert did not affect one row");
    }
    @Override public int supersedeActiveForTask(String tenantId, String clientId,
            String ownerJiacn, String taskId, long supersededAt) {
        scope(tenantId,clientId,ownerJiacn); id(taskId,"taskId",100);
        if (supersededAt < 0) throw new IllegalArgumentException("supersededAt");
        return mapper.supersedeActiveForTask(tenantId,clientId,ownerJiacn,taskId,supersededAt);
    }
    @Override public boolean revoke(String tenantId, String clientId, String ownerJiacn,
            String taskId, String grantId, long expectedVersion, String idempotencyKey,
            String requestHash, long revokedAt) {
        scope(tenantId,clientId,ownerJiacn); id(taskId,"taskId",100); id(grantId,"grantId",100);
        id(idempotencyKey,"idempotencyKey",100); hash(requestHash);
        if (expectedVersion < 1 || revokedAt < 0) throw new IllegalArgumentException("version/time");
        return mapper.revoke(tenantId,clientId,ownerJiacn,taskId,grantId,expectedVersion,
                idempotencyKey,requestHash,revokedAt) == 1;
    }
    private static void scope(String tenant, String client, String owner) {
        if (!"0".equals(tenant)) throw new IllegalArgumentException("tenantId");
        id(client,"clientId",50); id(owner,"ownerJiacn",50);
        if ("0".equals(owner)) throw new IllegalArgumentException("ownerJiacn");
    }
    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("requestHash");
    }
    private static void id(String value,String name,int max) {
        if (value==null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0,value.length())>max
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(name);
    }
}
