package cn.jia.chat.deliberation;

import java.util.List;

public interface ChatDeliberationDao {
    int insertSnapshot(ChatContextSnapshotEntity entity);
    int insertRequest(ChatRequestEntity entity);
    int insertTurn(ChatTurnEntity entity);
    int insertOutbox(ChatDispatchOutboxEntity entity);
    int insertEvent(ChatConversationEventEntity entity);
    int assignEventVersion(long eventSequence);
    ChatRequestEntity lockRequest(String tenantId, String ownerJiacn, String clientId, String requestId, long revision);
    ChatRequestEntity findRequest(String tenantId, String ownerJiacn, String clientId, String requestId);
    List<ChatTurnEntity> findTurnsByRequest(String tenantId, String ownerJiacn, String clientId, String requestId);
    ChatTurnEntity findTurn(String tenantId, String ownerJiacn, String clientId, String turnId);
    ChatTurnEntity lockTurn(String tenantId, String ownerJiacn, String clientId, String turnId);
    ChatContextSnapshotEntity findSnapshot(String tenantId, String ownerJiacn, String clientId, String snapshotId);
    ChatDispatchOutboxEntity lockOutbox(String tenantId, String ownerJiacn, String clientId, String turnId, String eventType);
    ChatDispatchOutboxEntity findOutboxById(String tenantId, String ownerJiacn, String clientId, String eventId);
    ChatDispatchOutboxEntity lockOutboxById(String tenantId, String ownerJiacn, String clientId, String eventId);
    ChatDispatchOutboxEntity lockOutboxByDispatch(String tenantId, String ownerJiacn, String clientId, String dispatchId);
    List<ChatDispatchOutboxEntity> findDueOutbox(long now, int limit);
    List<ChatConversationEventEntity> replayEvents(String tenantId, String ownerJiacn, String clientId,
            String conversationId, long generation, long afterSequence, long throughSequence, int limit);
    long eventHighWatermark(String tenantId, String ownerJiacn, String clientId,
            String conversationId, long generation);
    int acceptDelta(ChatTurnEntity turn, long deltaSeq, String deltaDigest, long now);
    int persistFinal(ChatTurnEntity turn, String finalDigest, long finalMessageId, long now);
    int updateTurnState(ChatTurnEntity turn, String state, String terminalReason, long now);
    int publishFinal(ChatTurnEntity turn, long now);
    int updateOutbox(ChatDispatchOutboxEntity outbox, String status, long now);
    int claimOutbox(ChatDispatchOutboxEntity outbox, String leaseOwner, long leaseUntil, long fencingToken, long now);
    int renewOutbox(ChatDispatchOutboxEntity outbox, long leaseUntil, long now);
    int settleOutbox(ChatDispatchOutboxEntity outbox, String status, Long availableAt, String lastError, Long sentAt, long now);
    int acknowledgeOutbox(ChatDispatchOutboxEntity outbox, long now);
    int updateRequestState(ChatRequestEntity request, String state, long now);
}
