package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskBountyBootstrapOutboxDao;
import cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity;
import cn.jia.agent.mapper.AgentTaskBountyBootstrapOutboxMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.Objects;

@Named
public final class AgentTaskBountyBootstrapOutboxDaoImpl
        implements AgentTaskBountyBootstrapOutboxDao {
    private final AgentTaskBountyBootstrapOutboxMapper mapper;

    @Inject
    public AgentTaskBountyBootstrapOutboxDaoImpl(AgentTaskBountyBootstrapOutboxMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public AgentTaskBountyBootstrapOutboxEntity findByActionForUpdate(String tenantId,
            String clientId, String ownerJiacn, String sourceBusinessActionId) {
        scope(tenantId, clientId, ownerJiacn);
        exact(sourceBusinessActionId, "sourceBusinessActionId", 160);
        return mapper.selectByActionForUpdate(tenantId, clientId, ownerJiacn,
                sourceBusinessActionId);
    }

    @Override
    public AgentTaskBountyBootstrapOutboxEntity findClaimableForUpdate(String tenantId,
            String clientId, String ownerJiacn, long now) {
        scope(tenantId, clientId, ownerJiacn);
        if (now <= 0) throw new IllegalArgumentException("now");
        return mapper.selectClaimableForUpdate(tenantId, clientId, ownerJiacn, now);
    }

    @Override
    public AgentTaskBountyBootstrapOutboxEntity findClaimableAvailableForUpdate(long now) {
        if (now <= 0) throw new IllegalArgumentException("now");
        return mapper.selectClaimableAvailableForUpdate(now);
    }

    @Override
    public AgentTaskBountyBootstrapOutboxEntity findByBootstrapForUpdate(String tenantId,
            String clientId, String ownerJiacn, String bootstrapId) {
        scope(tenantId, clientId, ownerJiacn);
        exact(bootstrapId, "bootstrapId", 100);
        return mapper.selectByBootstrapForUpdate(tenantId, clientId, ownerJiacn, bootstrapId);
    }

    @Override
    public void insert(AgentTaskBountyBootstrapOutboxEntity intent) {
        Objects.requireNonNull(intent, "intent").init4Creation();
        if (mapper.insert(intent) != 1) {
            throw new IllegalStateException("bootstrap outbox insert did not affect one row");
        }
    }

    @Override
    public boolean claim(AgentTaskBountyBootstrapOutboxEntity intent, String leaseOwner,
            long leaseUntil, long now) {
        Objects.requireNonNull(intent, "intent");
        exact(leaseOwner, "leaseOwner", 100);
        if (now <= 0 || leaseUntil <= now) throw new IllegalArgumentException("claim time");
        return mapper.claim(intent, leaseOwner, leaseUntil, now) == 1;
    }

    @Override
    public boolean reconcile(AgentTaskBountyBootstrapOutboxEntity intent, String status,
            Long nextRetryAt, String conversationId, String requestId,
            String errorCode, Long reconciledAt, long now) {
        Objects.requireNonNull(intent, "intent");
        if (now <= 0) throw new IllegalArgumentException("now");
        return mapper.reconcile(intent, status, nextRetryAt, conversationId, requestId,
                errorCode, reconciledAt, now) == 1;
    }

    private static void scope(String tenant, String client, String owner) {
        if (!"0".equals(tenant)) throw new IllegalArgumentException("tenantId");
        exact(client, "clientId", 50);
        exact(owner, "ownerJiacn", 50);
        if ("0".equals(owner)) throw new IllegalArgumentException("ownerJiacn");
    }

    private static void exact(String value, String name, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name);
        }
    }
}
