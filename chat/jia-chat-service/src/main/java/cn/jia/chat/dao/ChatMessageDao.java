package cn.jia.chat.dao;

import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

/**
 * <p>
 * 聊天消息 DAO 接口
 * </p>
 *
 * @author chc
 * @since 2026-04-19
 */
public interface ChatMessageDao extends IBaseDao<ChatMessageEntity> {

    /**
     * 根据会话ID查询消息列表
     *
     * @param conversationId 会话ID
     * @return 消息列表（按messageOrder排序）
     */
    List<ChatMessageEntity> findByConversationId(String conversationId);

    /**
     * 根据会话ID查询最近的消息列表
     *
     * @param conversationId 会话ID
     * @param limit 返回的最大消息数量
     * @return 消息列表（按messageOrder升序，仅返回最后limit条）
     */
    List<ChatMessageEntity> findByConversationIdWithLimit(String conversationId, int limit);

    /**
     * 根据会话ID删除消息
     *
     * @param conversationId 会话ID
     */
    void deleteByConversationId(String conversationId);
}
