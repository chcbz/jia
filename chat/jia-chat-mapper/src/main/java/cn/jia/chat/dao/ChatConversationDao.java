package cn.jia.chat.dao;

import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

/** Chat conversation DAO boundary. */
public interface ChatConversationDao extends IBaseDao<ChatConversationEntity> {
    default ChatConversationEntity findScopedById(String ownerJiacn, String clientId, String conversationId) {
        return findScopedById("0", ownerJiacn, clientId, conversationId);
    }
    ChatConversationEntity findScopedById(
            String tenantId, String ownerJiacn, String clientId, String conversationId);

    default ChatConversationEntity lockScopedById(String ownerJiacn, String clientId, String conversationId) {
        return lockScopedById("0", ownerJiacn, clientId, conversationId);
    }
    ChatConversationEntity lockScopedById(
            String tenantId, String ownerJiacn, String clientId, String conversationId);

    default ChatConversationEntity lockScopedByIdIncludingDeleted(String ownerJiacn, String clientId, String conversationId) {
        return lockScopedByIdIncludingDeleted("0", ownerJiacn, clientId, conversationId);
    }
    ChatConversationEntity lockScopedByIdIncludingDeleted(
            String tenantId, String ownerJiacn, String clientId, String conversationId);

    default int softDeleteScopedById(String ownerJiacn, String clientId, String conversationId, long deletedAt) {
        return softDeleteScopedById("0", ownerJiacn, clientId, conversationId, deletedAt);
    }
    int softDeleteScopedById(
            String tenantId, String ownerJiacn, String clientId, String conversationId, long deletedAt);

    default boolean isLiveGeneration(String ownerJiacn, String clientId, String conversationId, long expectedGeneration) {
        return isLiveGeneration("0", ownerJiacn, clientId, conversationId, expectedGeneration);
    }
    boolean isLiveGeneration(
            String tenantId, String ownerJiacn, String clientId, String conversationId, long expectedGeneration);

    default int updateScopedFields(String ownerJiacn, String clientId, ChatConversationEntity entity) {
        return updateScopedFields("0", ownerJiacn, clientId, entity);
    }
    int updateScopedFields(
            String tenantId, String ownerJiacn, String clientId, ChatConversationEntity entity);

    List<ChatConversationEntity> selectNonTaskThreadByEntity(ChatConversationEntity example);
}
