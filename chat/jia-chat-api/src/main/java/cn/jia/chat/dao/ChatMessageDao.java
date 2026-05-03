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

    /**
     * 查询所有会话ID
     *
     * @return 会话ID列表
     */
    List<String> findConversationIds();

    /**
     * 查询待同步的会话ID列表
     *
     * @param syncStatus 同步状态
     * @param limit 查询数量限制
     * @return 会话ID列表
     */
    List<String> findPendingConversationIds(String syncStatus, int limit);

    /**
     * 根据会话ID更新同步状态
     *
     * @param conversationId 会话ID
     * @param syncStatus 同步状态
     */
    void updateSyncStatusByConversationId(String conversationId, String syncStatus);

    /**
     * 查询待清理的过期消息（用于数据清理）
     *
     * @param beforeTime 创建时间阈值
     * @param limit 查询数量限制
     * @return 消息ID列表
     */
    List<Long> findExpiredMessageIds(long beforeTime, int limit);

    /**
     * 查询活跃用户列表（在指定时间后有消息的用户）
     *
     * @param sinceTime 起始时间
     * @return 用户标识列表
     */
    List<String> findActiveJiacns(long sinceTime);

}