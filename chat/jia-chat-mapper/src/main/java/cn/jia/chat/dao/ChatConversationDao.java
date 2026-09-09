package cn.jia.chat.dao;

import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

/**
 * <p>
 * 聊天会话 DAO 接口
 * </p>
 *
 * @author chc
 * @since 2026-04-19
 */
public interface ChatConversationDao extends IBaseDao<ChatConversationEntity> {

    ChatConversationEntity findScopedById(String tenantId, String clientId, String conversationId);

    ChatConversationEntity findExactOwnedById(
            String tenantId, String clientId, String jiacn, String conversationId,
            boolean forUpdate);

    List<ChatConversationEntity> selectNonTaskThreadByEntity(ChatConversationEntity example);
}
