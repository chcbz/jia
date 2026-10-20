package cn.jia.chat.service.impl;

import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.service.SummaryGenerator;
import cn.jia.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Lazy
@Service
public class DefaultSummaryGenerator implements SummaryGenerator {

    private static final String CONVERSATION_SUMMARY_PROMPT = """
你是摘要助手，请分析以下对话生成简洁摘要。

对话内容:
{messages}

请按以下JSON格式输出:
{
  "summary": "对话主旨概括",
  "topic": "话题",
  "keyPoints": ["关键点1", "关键点2"],
  "userPreferences": ["用户偏好"],
  "categories": ["分类"]
}
""";

    private static final String DAILY_SUMMARY_PROMPT = """
你是用户画像助手，请分析用户对话记录生成画像摘要。

对话摘要列表:
{conversations}

请按以下JSON格式输出:
{
  "summary": "用户行为概述",
  "categories": ["关注领域"],
  "preferences": ["偏好"],
  "trends": ["趋势"]
}
""";

    private static final String WEEKLY_SUMMARY_PROMPT = """
你是周报生成助手，请分析日汇总生成周报。

日汇总列表:
{dailySummaries}

请按以下JSON格式输出:
{
  "summary": "本周概述",
  "categories": ["主要领域"],
  "highlights": ["亮点"],
  "insights": ["洞察"]
}
""";

    private static final String MONTHLY_SUMMARY_PROMPT = """
你是月报生成助手，请分析周汇总生成月报。

周汇总列表:
{weeklySummaries}

请按以下JSON格式输出:
{
  "summary": "本月概述",
  "categories": ["主要领域"],
  "trends": ["趋势"],
  "achievements": ["成就"]
}
""";

    @Autowired
    private ChatModel chatModel;

    @Override
    public String summarizeConversation(List<ChatMessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return "{}";
        }

        try {
            String formattedMessages = messages.stream()
                    .map(msg -> String.format("[%s] %s", msg.getMessageType(), msg.getContent()))
                    .collect(Collectors.joining("\n"));

            String promptText = CONVERSATION_SUMMARY_PROMPT.replace("{messages}", formattedMessages);

            String content = chatModel.call(promptText);

            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("Summary provider returned empty content");
            }

            Object summary = JsonUtil.jsonToMap(content).get("summary");
            if (!(summary instanceof String text) || !StringUtils.hasText(text)) {
                throw new IllegalStateException("Summary provider returned no usable summary");
            }
            return content;
        } catch (Exception e) {
            log.error("Failed to summarize conversation", e);
            throw new IllegalStateException("Memory summary generation failed", e);
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

            String content = chatModel.call(promptText);

            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("Summary provider returned empty content");
            }

            Object summary = JsonUtil.jsonToMap(content).get("summary");
            if (!(summary instanceof String text) || !StringUtils.hasText(text)) {
                throw new IllegalStateException("Summary provider returned no usable summary");
            }
            return content;
        } catch (Exception e) {
            log.error("Failed to generate daily summary", e);
            throw new IllegalStateException("Memory summary generation failed", e);
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

            String content = chatModel.call(promptText);

            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("Summary provider returned empty content");
            }

            Object summary = JsonUtil.jsonToMap(content).get("summary");
            if (!(summary instanceof String text) || !StringUtils.hasText(text)) {
                throw new IllegalStateException("Summary provider returned no usable summary");
            }
            return content;
        } catch (Exception e) {
            log.error("Failed to generate weekly summary", e);
            throw new IllegalStateException("Memory summary generation failed", e);
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

            String content = chatModel.call(promptText);

            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("Summary provider returned empty content");
            }

            Object summary = JsonUtil.jsonToMap(content).get("summary");
            if (!(summary instanceof String text) || !StringUtils.hasText(text)) {
                throw new IllegalStateException("Summary provider returned no usable summary");
            }
            return content;
        } catch (Exception e) {
            log.error("Failed to generate monthly summary", e);
            throw new IllegalStateException("Memory summary generation failed", e);
        }
    }

}
