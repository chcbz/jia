package cn.jia.chat.service;

import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import com.github.pagehelper.PageInfo;

import java.util.List;

/**
 * 聊天会话服务接口
 *
 * @author chc
 * @since 2026-04-19
 */
public interface ChatConversationService {

    /**
     * 分页查询会话列表
     *
     * @param example 查询条件
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param orderBy 排序字段
     * @return 分页结果
     */
    PageInfo<ChatConversationEntity> findPage(ChatConversationEntity example, int pageNum, int pageSize, String orderBy);

    /**
     * 删除指定会话（包含会话记录和消息）
     *
     * @param conversationId 会话ID
     */
    void deleteConversation(String conversationId);

    /**
     * 根据会话ID查询消息列表
     *
     * @param conversationId 会话ID
     * @return 消息列表（按messageOrder排序）
     */
    List<ChatMessageEntity> findByConversationId(String conversationId);

    /**
     * 创建会话
     *
     * @param entity 会话实体
     * @return 创建后的会话实体
     */
    ChatConversationEntity create(ChatConversationEntity entity);

    /**
     * 根据会话ID获取会话
     *
     * @param conversationId 会话ID
     * @return 会话实体
     */
    ChatConversationEntity get(String conversationId);

    /**
     * 更新会话
     *
     * @param entity 会话实体
     * @return 更新后的会话实体
     */
    ChatConversationEntity update(ChatConversationEntity entity);
}
