package cn.jia.chat.dao;

import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

/** Chat conversation DAO boundary. */
public interface ChatConversationDao extends IBaseDao<ChatConversationEntity> {
    ChatConversationEntity findScopedById(
            String ownerJiacn, String clientId, String conversationId);

    ChatConversationEntity lockScopedById(
            String ownerJiacn, String clientId, String conversationId);

    ChatConversationEntity lockScopedByIdIncludingDeleted(
            String ownerJiacn, String clientId, String conversationId);

    int softDeleteScopedById(
            String ownerJiacn, String clientId, String conversationId, long deletedAt);

    List<ChatConversationEntity> selectNonTaskThreadByEntity(ChatConversationEntity example);
}
