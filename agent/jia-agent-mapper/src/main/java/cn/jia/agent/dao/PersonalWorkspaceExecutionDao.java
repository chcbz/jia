package cn.jia.agent.dao;

import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionInputEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;

import java.util.List;

/** Exact owner-scoped persistence boundary for private execution grants and output receipts. */
public interface PersonalWorkspaceExecutionDao {
    PersonalWorkspaceExecutionEntity find(String tenantId, String clientId, String ownerJiacn,
            String executionId);
    PersonalWorkspaceExecutionEntity findByIdempotency(String tenantId, String clientId,
            String ownerJiacn, String idempotencyKey);
    /** Browser-safe persisted create reconciliation; excludes instruction and runtime credentials. */
    PersonalWorkspaceExecutionEntity findRequestByIdempotency(String tenantId, String clientId,
            String ownerJiacn, String idempotencyKey);
    /** Bounded keyset history query. The limit is the caller's lookahead size (maximum 101). */
    List<PersonalWorkspaceExecutionEntity> listHistory(String tenantId, String clientId,
            String ownerJiacn, Long beforeCreatedAt, String beforeExecutionId, int limit);
    PersonalWorkspaceExecutionEntity findByRevokeIdempotency(String tenantId, String clientId,
            String ownerJiacn, String idempotencyKey);
    PersonalWorkspaceExecutionEntity lock(String tenantId, String clientId, String ownerJiacn,
            String executionId);
    PersonalWorkspaceExecutionEntity findByTaskRun(String tenantId, String clientId, String ownerJiacn,
            String taskId, String runId);
    PersonalWorkspaceExecutionEntity lockByTaskRun(String tenantId, String clientId, String ownerJiacn,
            String taskId, String runId);
    List<PersonalWorkspaceExecutionEntity> listQueuedByTarget(String tenantId, String clientId,
            String ownerJiacn, String targetAgentId, int limit);
    void insert(PersonalWorkspaceExecutionEntity execution);
    void update(PersonalWorkspaceExecutionEntity execution);
    void insertInput(PersonalWorkspaceExecutionInputEntity input);
    List<PersonalWorkspaceExecutionInputEntity> listInputs(String tenantId, String clientId,
            String ownerJiacn, String executionId);
    PersonalWorkspaceExecutionInputEntity findInput(String tenantId, String clientId,
            String ownerJiacn, String executionId, String inputRef);
    PersonalWorkspaceExecutionInputEntity lockInput(String tenantId, String clientId,
            String ownerJiacn, String executionId, String inputRef);
    List<String> listActiveExecutionIdsByFile(String tenantId, String clientId,
            String ownerJiacn, String fileId, int limit);
    void updateInput(PersonalWorkspaceExecutionInputEntity input);
    PersonalWorkspaceExecutionOutputEntity lockOutput(String tenantId, String clientId,
            String ownerJiacn, String executionId, String outputId);
    List<PersonalWorkspaceExecutionOutputEntity> lockOutputs(String tenantId, String clientId,
            String ownerJiacn, String executionId);
    /** Exact immutable, already-published TASK output eligible as a rework source. */
    PersonalWorkspaceExecutionOutputEntity findPublishedReworkSource(String tenantId,
            String clientId, String ownerJiacn, String taskId, String formalDeliveryId,
            String outputId, String workspaceFileId, int workspaceFileVersion);
    void insertOutput(PersonalWorkspaceExecutionOutputEntity output);
    void updateOutput(PersonalWorkspaceExecutionOutputEntity output);
}
