package cn.jia.chat.dao;

import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

/** Chat conversation DAO boundary. */
public interface ChatConversationDao extends IBaseDao<ChatConversationEntity> {
    ChatConversationEntity findScopedById(ConversationDataScope scope, String conversationId);

    ChatConversationEntity lockScopedById(ConversationDataScope scope, String conversationId);

    ChatConversationEntity lockScopedByIdIncludingDeleted(
            ConversationDataScope scope, String conversationId);

    int softDeleteScopedById(
            ConversationDataScope scope, String conversationId, long deletedAt);

    boolean isLiveGeneration(
            ConversationDataScope scope, String conversationId, long expectedGeneration);

    int updateScopedFields(ConversationDataScope scope, ChatConversationEntity entity);

    List<ChatConversationEntity> selectNonTaskThreadByEntity(
            ConversationDataScope scope, ChatConversationEntity example);

    ChatConversationEntity findScopedById(
            String ownerJiacn, String clientId, String conversationId);

    ChatConversationEntity lockScopedById(
            String ownerJiacn, String clientId, String conversationId);

    ChatConversationEntity lockScopedByIdIncludingDeleted(
            String ownerJiacn, String clientId, String conversationId);

    int softDeleteScopedById(
            String ownerJiacn, String clientId, String conversationId, long deletedAt);

    boolean isLiveGeneration(
            String ownerJiacn, String clientId, String conversationId, long expectedGeneration);

    int updateScopedFields(
            String ownerJiacn, String clientId, ChatConversationEntity entity);

    List<ChatConversationEntity> selectNonTaskThreadByEntity(ChatConversationEntity example);
}
