package cn.jia.chat.dao;

import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

/** Chat message DAO boundary. */
public interface ChatMessageDao extends IBaseDao<ChatMessageEntity> {
    /** Scoped task-thread write boundary; intentionally separate from generic conversations. */
    int insertScoped(String tenantId, String clientId, ChatMessageEntity message);

    /** Scoped stable latest-message query for task threads. */
    List<ChatMessageEntity> findByConversationIdScoped(
            String tenantId, String clientId, String conversationId, int limit);

    List<ChatMessageEntity> findOwnedByConversationId(
            String ownerJiacn, String clientId, String conversationId);

    List<ChatMessageEntity> findOwnedByConversationIdWithLimit(
            String ownerJiacn, String clientId, String conversationId, int limit);

    /** Deletes every byte-exact row for an already locked and authenticated conversation. */
    int deleteExactConversationMessages(String conversationId);

    /** Explicitly unscoped maintenance-only history query. Never expose through request paths. */
    List<ChatMessageEntity> findByConversationIdForMaintenance(String conversationId);

    List<String> findPendingConversationIds(String syncStatus, int limit);

    void updateSyncStatusByConversationId(String conversationId, String syncStatus);

    List<Long> findExpiredMessageIds(long beforeTime, int limit);

    List<String> findActiveJiacns(long sinceTime);
}
