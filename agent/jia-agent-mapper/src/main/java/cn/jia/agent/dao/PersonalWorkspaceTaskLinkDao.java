package cn.jia.agent.dao;

import cn.jia.agent.entity.PersonalWorkspaceTaskFileLinkEntity;

import java.util.List;

/** Strict tenant/client/owner persistence boundary for task file selections. */
public interface PersonalWorkspaceTaskLinkDao {
    boolean taskExists(String tenantId, String clientId, String ownerJiacn, String taskId);
    boolean lockTask(String tenantId, String clientId, String ownerJiacn, String taskId);
    boolean lockFile(String tenantId, String clientId, String ownerJiacn, String fileId,
            boolean requireActive);
    boolean versionExists(String tenantId, String clientId, String ownerJiacn,
            String fileId, int version);
    PersonalWorkspaceTaskFileLinkEntity findByRelation(String tenantId, String clientId,
            String ownerJiacn, String taskId, String relationId);
    PersonalWorkspaceTaskFileLinkEntity lockByRelation(String tenantId, String clientId,
            String ownerJiacn, String taskId, String relationId);
    PersonalWorkspaceTaskFileLinkEntity lockBySelection(String tenantId, String clientId,
            String ownerJiacn, String taskId, String fileId, int version, String role);
    List<PersonalWorkspaceTaskFileLinkEntity> list(String tenantId, String clientId,
            String ownerJiacn, String taskId, Long beforeCreatedAt, String afterRelationId, int limit);
    void insert(PersonalWorkspaceTaskFileLinkEntity link);
    boolean changeState(String tenantId, String clientId, String ownerJiacn, String taskId,
            String relationId, String expectedState, long expectedRevision,
            String nextState, long nextRevision, Long detachedAt);
    OperationRow reserveOperation(String tenantId, String clientId, String ownerJiacn,
            String operationType, String idempotencyKey, String requestHash,
            String proposedOperationId, long now);
    boolean completeOperation(String tenantId, String clientId, String ownerJiacn,
            String operationId, String relationId, long completedAt);

    final class OperationRow {
        private Long id;
        private String operationId;
        private String operationType;
        private String idempotencyKey;
        private String requestHash;
        private String operationState;
        private String relationId;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getOperationId() { return operationId; }
        public void setOperationId(String operationId) { this.operationId = operationId; }
        public String getOperationType() { return operationType; }
        public void setOperationType(String operationType) { this.operationType = operationType; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
        public String getRequestHash() { return requestHash; }
        public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
        public String getOperationState() { return operationState; }
        public void setOperationState(String operationState) { this.operationState = operationState; }
        public String getRelationId() { return relationId; }
        public void setRelationId(String relationId) { this.relationId = relationId; }
    }
}
