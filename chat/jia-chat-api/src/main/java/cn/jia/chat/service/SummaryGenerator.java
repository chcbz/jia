package cn.jia.chat.service;

import cn.jia.chat.entity.ChatMessageEntity;

import java.util.List;

/**
 * <p>
 * 摘要生成器接口
 * </p>
 *
 * @author chc
 * @since 2026-05-01
 */
public interface SummaryGenerator {

    /**
     * 生成会话摘要
     *
     * @param messages 对话消息列表
     * @return 结构化摘要 (JSON格式字符串)
     */
    String summarizeConversation(List<ChatMessageEntity> messages);

    /**
     * 生成日汇总
     *
     * @param conversationSummaries 会话摘要列表
     * @param jiacn 用户标识
     * @param date 日期
     * @return 用户画像摘要 (JSON格式字符串)
     */
    String generateDailySummary(List<String> conversationSummaries, String jiacn, String date);

    /**
     * 生成周汇总
     *
     * @param dailySummaries 日汇总列表
     * @param jiacn 用户标识
     * @param weekStartDate 周开始日期
     * @return 周汇总 (JSON格式字符串)
     */
    String generateWeeklySummary(List<String> dailySummaries, String jiacn, String weekStartDate);

    /**
     * 生成月汇总
     *
     * @param weeklySummaries 周汇总列表
     * @param jiacn 用户标识
     * @param month 月份 (格式: yyyy-MM)
     * @return 月汇总 (JSON格式字符串)
     */
    String generateMonthlySummary(List<String> weeklySummaries, String jiacn, String month);
}