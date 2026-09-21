package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.HallPrivateCaseDao;
import cn.jia.agent.entity.HallCaseExecutionEntity;
import cn.jia.agent.entity.HallPrivateCaseEntity;
import cn.jia.agent.mapper.HallCaseExecutionMapper;
import cn.jia.agent.mapper.HallPrivateCaseMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;
import java.util.Objects;

@Named
public class HallPrivateCaseDaoImpl implements HallPrivateCaseDao {
    private final HallPrivateCaseMapper cases;
    private final HallCaseExecutionMapper executions;

    @Inject
    public HallPrivateCaseDaoImpl(HallPrivateCaseMapper cases, HallCaseExecutionMapper executions) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.executions = Objects.requireNonNull(executions, "executions");
    }

    @Override public HallPrivateCaseEntity find(String tenantId, String clientId,
            String ownerJiacn, String caseId) {
        scope(tenantId, clientId, ownerJiacn); id(caseId, "caseId");
        return cases.findExact(tenantId, clientId, ownerJiacn, caseId);
    }
    @Override public HallPrivateCaseEntity lock(String tenantId, String clientId,
            String ownerJiacn, String caseId) {
        scope(tenantId, clientId, ownerJiacn); id(caseId, "caseId");
        return cases.lockExact(tenantId, clientId, ownerJiacn, caseId);
    }
    @Override public void insert(HallPrivateCaseEntity entity) {
        Objects.requireNonNull(entity, "entity");
        scope(entity.getTenantId(), entity.getClientId(), entity.getOwnerJiacn());
        id(entity.getCaseId(), "caseId");
        text(entity.getTitle(), "title", 200); text(entity.getOriginRef(), "originRef", 120);
        if (entity.getRevision() == null || entity.getRevision() < 1
                || entity.getRevision() > 9_007_199_254_740_991L
                || entity.getCreatedAt() == null || entity.getCreatedAt() < 0
                || entity.getUpdatedAt() == null || entity.getUpdatedAt() < entity.getCreatedAt()) {
            throw new IllegalArgumentException("case");
        }
        if (cases.insert(entity) != 1) throw new IllegalStateException("Hall case insert failed");
    }
    @Override public int updateRevision(String tenantId, String clientId, String ownerJiacn,
            String caseId, long expectedRevision, long nextRevision, String title, long updatedAt) {
        scope(tenantId, clientId, ownerJiacn); id(caseId, "caseId");
        if (expectedRevision < 1 || nextRevision != expectedRevision + 1 || updatedAt < 0) {
            throw new IllegalArgumentException("revision");
        }
        return cases.updateRevision(tenantId, clientId, ownerJiacn, caseId,
                expectedRevision, nextRevision, title, updatedAt);
    }
    @Override public HallCaseExecutionEntity findByExecution(String tenantId, String clientId,
            String ownerJiacn, String executionId) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId");
        return executions.findByExecution(tenantId, clientId, ownerJiacn, executionId);
    }
    @Override public List<HallCaseExecutionEntity> listExecutions(String tenantId, String clientId,
            String ownerJiacn, String caseId) {
        scope(tenantId, clientId, ownerJiacn); id(caseId, "caseId");
        return executions.listByCase(tenantId, clientId, ownerJiacn, caseId);
    }
    @Override public void insertExecution(HallCaseExecutionEntity entity) {
        Objects.requireNonNull(entity, "entity");
        scope(entity.getTenantId(), entity.getClientId(), entity.getOwnerJiacn());
        id(entity.getCaseId(), "caseId"); id(entity.getExecutionId(), "executionId");
        if (entity.getRevisionNo() == null || entity.getRevisionNo() < 1
                || entity.getRevisionNo() > 9_007_199_254_740_991L
                || entity.getCreatedAt() == null || entity.getCreatedAt() < 0
                || (entity.getRevisionNo() == 1
                    && (entity.getParentExecutionId() != null
                        || entity.getSourceOutputRefJson() != null))
                || (entity.getRevisionNo() > 1
                    && (entity.getParentExecutionId() == null
                        || entity.getSourceOutputRefJson() == null))) {
            throw new IllegalArgumentException("caseExecution");
        }
        if (entity.getParentExecutionId() != null) id(entity.getParentExecutionId(), "parentExecutionId");
        if (executions.insert(entity) != 1) throw new IllegalStateException("Hall case execution insert failed");
    }

    private static void scope(String tenant, String client, String owner) {
        if (!"0".equals(tenant)) throw new IllegalArgumentException("tenantId");
        id(client, "clientId"); id(owner, "ownerJiacn");
        if ("0".equals(owner)) throw new IllegalArgumentException("ownerJiacn");
    }
    private static void text(String value, String field, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > max
                || value.chars().anyMatch(character -> Character.isISOControl(character)
                    && character != '\n' && character != '\r' && character != '\t')) {
            throw new IllegalArgumentException(field);
        }
    }
    private static void id(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > 100
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field);
        }
    }
}
