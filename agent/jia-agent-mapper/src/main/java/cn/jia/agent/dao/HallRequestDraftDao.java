package cn.jia.agent.dao;

import cn.jia.agent.entity.HallRequestDraftEntity;

import java.util.List;

/** Every lookup includes the exact authenticated tenant/client/owner scope. */
public interface HallRequestDraftDao {
    HallRequestDraftEntity find(String tenantId, String clientId, String ownerJiacn, String draftId);
    HallRequestDraftEntity findByCreateKey(String tenantId, String clientId, String ownerJiacn,
            String createKey);
    HallRequestDraftEntity findByDiscardKey(String tenantId, String clientId, String ownerJiacn,
            String discardKey);
    List<HallRequestDraftEntity> listEditing(String tenantId, String clientId, String ownerJiacn,
            Long beforeUpdatedAt, String beforeDraftId, int limit);
    void reserveCreate(HallRequestDraftEntity entity);
    int replaceEditing(String tenantId, String clientId, String ownerJiacn, String draftId,
            long expectedRevision, String title, String instruction, String targetAgentId,
            String outputMime, String inputsJson, long updatedAt);
    int discardEditing(String tenantId, String clientId, String ownerJiacn, String draftId,
            long expectedRevision, String discardKey, String discardHash, long updatedAt);
    boolean privateCommittedOutputExists(String tenantId, String clientId, String ownerJiacn,
            String executionId, String outputId, String fileId, int fileVersion);
}
