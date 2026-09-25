package cn.jia.chat.deliberation;

import java.util.List;

public interface ChatDeliberationDao {
    int insertSnapshot(ChatContextSnapshotEntity entity);
    int insertRequest(ChatRequestEntity entity);
    int insertTurn(ChatTurnEntity entity);
    int insertOutbox(ChatDispatchOutboxEntity entity);
    ChatRequestEntity lockRequest(String tenantId, String ownerJiacn, String clientId, String requestId, long revision);
    ChatRequestEntity findRequest(String tenantId, String ownerJiacn, String clientId, String requestId);
    List<ChatTurnEntity> findTurnsByRequest(String tenantId, String ownerJiacn, String clientId, String requestId);
    ChatTurnEntity findTurn(String tenantId, String ownerJiacn, String clientId, String turnId);
    ChatTurnEntity lockTurn(String tenantId, String ownerJiacn, String clientId, String turnId);
    ChatContextSnapshotEntity findSnapshot(String tenantId, String ownerJiacn, String clientId, String snapshotId);
    ChatDispatchOutboxEntity lockOutbox(String tenantId, String ownerJiacn, String clientId, String turnId, String eventType);
    int acceptDelta(ChatTurnEntity turn, long deltaSeq, String deltaDigest, long now);
    int persistFinal(ChatTurnEntity turn, String finalDigest, long finalMessageId, long now);
    int updateTurnState(ChatTurnEntity turn, String state, String terminalReason, long now);
    int publishFinal(ChatTurnEntity turn, long now);
    int updateOutbox(ChatDispatchOutboxEntity outbox, String status, long now);
    int updateRequestState(ChatRequestEntity request, String state, long now);
}
