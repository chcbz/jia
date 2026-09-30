package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskCreationOperationDao;
import cn.jia.agent.entity.AgentTaskCreationOperationEntity;
import cn.jia.agent.mapper.AgentTaskCreationOperationMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.Objects;

@Named
public final class AgentTaskCreationOperationDaoImpl implements AgentTaskCreationOperationDao {
    private final AgentTaskCreationOperationMapper mapper;

    @Inject
    public AgentTaskCreationOperationDaoImpl(AgentTaskCreationOperationMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public AgentTaskCreationOperationEntity reserveAndLock(String tenantId, String clientId,
            String ownerJiacn, String idempotencyKey, String requestHash,
            String proposedOperationId, String inputRefsJson, long createdAt) {
        scope(tenantId, clientId, ownerJiacn);
        id(idempotencyKey, "idempotencyKey", 100);
        id(proposedOperationId, "operationId", 100);
        hash(requestHash);
        json(inputRefsJson);
        if (createdAt <= 0) throw new IllegalArgumentException("createdAt");
        mapper.reserve(tenantId, clientId, ownerJiacn, idempotencyKey, requestHash,
                proposedOperationId, inputRefsJson, createdAt);
        AgentTaskCreationOperationEntity row = mapper.selectForUpdate(
                tenantId, clientId, ownerJiacn, idempotencyKey);
        if (row == null) throw new IllegalStateException("creation operation reservation missing");
        return row;
    }

    @Override
    public AgentTaskCreationOperationEntity find(String tenantId, String clientId,
            String ownerJiacn, String idempotencyKey) {
        scope(tenantId, clientId, ownerJiacn);
        id(idempotencyKey, "idempotencyKey", 100);
        return mapper.select(tenantId, clientId, ownerJiacn, idempotencyKey);
    }

    @Override
    public boolean complete(String tenantId, String clientId, String ownerJiacn,
            String operationId, String requestHash, String inputRefsJson,
            String taskId, long requirementRevision, long completedAt) {
        scope(tenantId, clientId, ownerJiacn);
        id(operationId, "operationId", 100);
        id(taskId, "taskId", 100);
        hash(requestHash);
        json(inputRefsJson);
        if (requirementRevision != 1 || completedAt <= 0) {
            throw new IllegalArgumentException("receipt");
        }
        return mapper.commit(tenantId, clientId, ownerJiacn, operationId, requestHash,
                inputRefsJson, taskId, requirementRevision, completedAt) == 1;
    }

    private static void scope(String tenantId, String clientId, String ownerJiacn) {
        if (!"0".equals(tenantId)) throw new IllegalArgumentException("tenantId");
        id(clientId, "clientId", 50);
        id(ownerJiacn, "ownerJiacn", 50);
        if ("0".equals(ownerJiacn)) throw new IllegalArgumentException("ownerJiacn");
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash");
        }
    }

    private static void json(String value) {
        if (value == null || value.isEmpty() || value.length() > 16384) {
            throw new IllegalArgumentException("inputRefsJson");
        }
    }

    private static void id(String value, String name, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name);
        }
    }
}
