package cn.jia.agent.dao;

import cn.jia.agent.entity.HallCaseExecutionEntity;
import cn.jia.agent.entity.HallPrivateCaseEntity;

import java.util.List;

/** Exact owner-scoped persistence boundary for private Hall cases and immutable lineage. */
public interface HallPrivateCaseDao {
    HallPrivateCaseEntity find(String tenantId, String clientId, String ownerJiacn, String caseId);
    HallPrivateCaseEntity lock(String tenantId, String clientId, String ownerJiacn, String caseId);
    void insert(HallPrivateCaseEntity entity);
    int updateRevision(String tenantId, String clientId, String ownerJiacn, String caseId,
            long expectedRevision, long nextRevision, String title, long updatedAt);
    HallCaseExecutionEntity findByExecution(String tenantId, String clientId, String ownerJiacn,
            String executionId);
    List<HallCaseExecutionEntity> listExecutions(String tenantId, String clientId,
            String ownerJiacn, String caseId);
    void insertExecution(HallCaseExecutionEntity entity);
}
