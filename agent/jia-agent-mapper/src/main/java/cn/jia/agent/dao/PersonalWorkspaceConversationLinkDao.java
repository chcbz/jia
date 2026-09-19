package cn.jia.agent.dao;

import cn.jia.agent.entity.PersonalWorkspaceConversationFileLinkEntity;

import java.util.List;

/** Strict tenant/client/owner persistence boundary for conversation file selections. */
public interface PersonalWorkspaceConversationLinkDao {
    boolean lockFile(String tenantId, String clientId, String ownerJiacn, String fileId,
            boolean requireActive);
    boolean versionExists(String tenantId, String clientId, String ownerJiacn,
            String fileId, int version);
    PersonalWorkspaceConversationFileLinkEntity findByRelation(String tenantId, String clientId,
            String ownerJiacn, String conversationId, String relationId);
    PersonalWorkspaceConversationFileLinkEntity lockByRelation(String tenantId, String clientId,
            String ownerJiacn, String conversationId, String relationId);
    PersonalWorkspaceConversationFileLinkEntity lockBySelection(String tenantId, String clientId,
            String ownerJiacn, String conversationId, String fileId, int version, String role);
    List<PersonalWorkspaceConversationFileLinkEntity> list(String tenantId, String clientId,
            String ownerJiacn, String conversationId, Long beforeCreatedAt, String afterRelationId, int limit);
    /** Active conversation references are part of the file's destructive-action impact snapshot. */
    default List<PersonalWorkspaceConversationFileLinkEntity> listActiveByFile(String tenantId,
            String clientId, String ownerJiacn, String fileId, int limit) {
        return List.of();
    }
    /** Must run after the caller has locked the same file row. */
    default boolean bumpFileImpactRevision(String tenantId, String clientId,
            String ownerJiacn, String fileId) {
        return true;
    }
    void insert(PersonalWorkspaceConversationFileLinkEntity link);
    boolean changeState(String tenantId, String clientId, String ownerJiacn, String conversationId,
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
