package cn.jia.chat.service.impl;

import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.service.SummaryGenerator;
import cn.jia.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 摘要生成器默认实现
 * </p>
 *
 * @author chc
 * @since 2026-05-01
 */
@Slf4j
@Service
public class DefaultSummaryGenerator implements SummaryGenerator {

    private static final String CONVERSATION_SUMMARY_PROMPT = """
你是一个对话摘要助手。请分析以下对话，生成一段简洁的摘要。

对话内容:
{messages}

请按以下JSON格式输出:
{
  "summary": "一段话概括对话主旨",
  "topic": "主要话题",
  "keyPoints": ["关键点1", "关键点2"],
  "userPreferences": ["用户偏好1"],
  "categories": ["分类1"]
}
""";

    private static final String DAILY_SUMMARY_PROMPT = """
你是一个用户画像助手。请分析用户近期的对话记录，生成用户画像摘要。

对话摘要列表:
{conversations}

请按以下JSON格式输出:
{
  "summary": "用户近期行为概述",
  "categories": ["主要关注领域"],
  "preferences": ["用户偏好"],
  "trends": ["行为趋势"]
}
""";

    private static final String WEEKLY_SUMMARY_PROMPT = """
你是一个周报生成助手。请分析用户近期的日汇总，生成周报。

日汇总列表:
{dailySummaries}

请按以下JSON格式输出:
{
  "summary": "本周整体概述",
  "categories": ["主要领域"],
  "highlights": ["本周亮点"],
  "insights": ["洞察"]
}
""";

    private static final String MONTHLY_SUMMARY_PROMPT = """
你是一个月报生成助手。请分析用户近期的周汇总，生成月报。

周汇总列表:
{weeklySummaries}

请按以下JSON格式输出:
{
  "summary": "本月整体概述",
  "categories": ["主要领域"],
  "trends": ["趋势"],
  "achievements": ["成就"]
}
""";

    @Autowired
    private ChatClient chatClient;

    @Override
    public String summarizeConversation(List<ChatMessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return "{}";
        }

        try {
            // 格式化消息内容
            String formattedMessages = messages.stream()
                    .map(msg -> String.format("[%s] %s", msg.getMessageType(), msg.getContent()))
                    .collect(Collectors.joining("\n"));

            String promptText = CONVERSATION_SUMMARY_PROMPT.replace("{messages}", formattedMessages);

            // 调用 LLM 生成摘要
            String content = chatClient.prompt(promptText).call().content();

            if (!StringUtils.hasText(content)) {
                return "{}";
            }

            // 尝试解析并返回原始 JSON
            JsonUtil.jsonToMap(content);
            return content;
        } catch (Exception e) {
            log.error("Failed to summarize conversation", e);
            // 如果解析失败，创建一个简单的摘要
            return createSimpleConversationSummary(messages);
        }
    }

    @Override
    public String generateDailySummary(List<String> conversationSummaries, String jiacn, String date) {
        if (conversationSummaries == null || conversationSummaries.isEmpty()) {
            return "{}";
        }

        try {
            String formattedSummaries = String.join("\n---\n", conversationSummaries);
            String promptText = DAILY_SUMMARY_PROMPT.replace("{conversations}", formattedSummaries);

            String content = chatClient.prompt(promptText).call().content();

            if (!StringUtils.hasText(content)) {
                return "{}";
            }

            JsonUtil.jsonToMap(content);
            return content;
        } catch (Exception e) {
            log.error("Failed to generate daily summary", e);
            return createSimpleDailySummary(conversationSummaries, jiacn, date);
        }
    }

    @Override
    public String generateWeeklySummary(List<String> dailySummaries, String jiacn, String weekStartDate) {
        if (dailySummaries == null || dailySummaries.isEmpty()) {
            return "{}";
        }

        try {
            String formattedSummaries = String.join("\n---\n", dailySummaries);
            String promptText = WEEKLY_SUMMARY_PROMPT.replace("{dailySummaries}", formattedSummaries);

            String content = chatClient.prompt(promptText).call().content();

            if (!StringUtils.hasText(content)) {
                return "{}";
            }

            JsonUtil.jsonToMap(content);
            return content;
        } catch (Exception e) {
            log.error("Failed to generate weekly summary", e);
            return createSimpleWeeklySummary(dailySummaries, jiacn, weekStartDate);
        }
    }

    @Override
    public String generateMonthlySummary(List<String> weeklySummaries, String jiacn, String month) {
        if (weeklySummaries == null || weeklySummaries.isEmpty()) {
            return "{}";
        }

        try {
            String formattedSummaries = String.join("\n---\n", weeklySummaries);
            String promptText = MONTHLY_SUMMARY_PROMPT.replace("{weeklySummaries}", formattedSummaries);

            String content = chatClient.prompt(promptText).call().content();

            if (!StringUtils.hasText(content)) {
                return "{}";
            }

            JsonUtil.jsonToMap(content);
            return content;
        } catch (Exception e) {
            log.error("Failed to generate monthly summary", e);
            return createSimpleMonthlySummary(weeklySummaries, jiacn, month);
        }
    }

    /**
     * 创建简单的会话摘要 (当 LLM 调用失败时使用)
     */
    private String createSimpleConversationSummary(List<ChatMessageEntity> messages) {
        try {
            int userCount = (int) messages.stream()
                    .filter(m -> "USER".equals(m.getMessageType()))
                    .count();
            int assistantCount = (int) messages.stream()
                    .filter(m -> "ASSISTANT".equals(m.getMessageType()))
                    .count();
            
            String firstContent = messages.get(0).getContent();
            String truncated = firstContent.length() > 100 
                    ? firstContent.substring(0, 100) + "..." 
                    : firstContent;

            return String.format(
                    "{\"summary\":\"对话包含%d条用户消息和%d条AI回复，首条消息：%s\",\"topic\":\"日常对话\",\"keyPoints\":[],\"userPreferences\":[],\"categories\":[\"general\"]}",
                    userCount, assistantCount, truncated);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 创建简单的日汇总 (当 LLM 调用失败时使用)
     */
    private String createSimpleDailySummary(List<String> conversationSummaries, String jiacn, String date) {
        return String.format(
                "{\"summary\":\"用户%d日共进行了%d次对话\",\"categories\":[\"general\"],\"preferences\":[],\"trends\":[]}",
                date, conversationSummaries.size());
    }

    /**
     * 创建简单的周汇总 (当 LLM 调用失败时使用)
     */
    private String createSimpleWeeklySummary(List<String> dailySummaries, String jiacn, String weekStartDate) {
        return String.format(
                "{\"summary\":\"本周共生成%d条日汇总\",\"categories\":[\"general\"],\"highlights\":[],\"insights\":[]}",
                dailySummaries.size());
    }

    /**
     * 创建简单的月汇总 (当 LLM 调用失败时使用)
     */
    private String createSimpleMonthlySummary(List<String> weeklySummaries, String jiacn, String month) {
        return String.format(
                "{\"summary\":\"本月共生成%d条周汇总\",\"categories\":[\"general\"],\"trends\":[],\"achievements\":[]}",
                weeklySummaries.size());
    }
}