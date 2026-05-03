package cn.jia.chat.service;

import cn.jia.chat.entity.ChatMessageEntity;

import java.util.List;

/**
 * <p>
 * 长效记忆服务接口
 * </p>
 *
 * @author chc
 * @since 2026-05-01
 */
public interface LongTermMemoryService {

    /**
     * 同步会话消息到向量库
     * <p>
     * 流程：
     * 1. 查询未同步的消息
     * 2. 调用 LLM 生成摘要
     * 3. 写入向量库
     * 4. 标记已同步
     *
     * @param conversationId 会话ID
     */
    void syncConversation(String conversationId);

    /**
     * 同步待处理会话
     * <p>
     * 定时任务调用，处理所有未同步的会话
     */
    void syncPendingConversations();

    /**
     * 生成日汇总
     * <p>
     * 每天凌晨执行，汇总用户近期对话
     */
    void generateDailySummary();

    /**
     * 生成周汇总
     * <p>
     * 每周日凌晨执行
     */
    void generateWeeklySummary();

    /**
     * 生成月汇总
     * <p>
     * 每月1日凌晨执行
     */
    void generateMonthlySummary();

    /**
     * 清理过期记忆
     */
    void cleanupExpiredMemories();

    /**
     * 获取会话的所有消息
     *
     * @param conversationId 会话ID
     * @return 消息列表
     */
    List<ChatMessageEntity> getConversationMessages(String conversationId);
}