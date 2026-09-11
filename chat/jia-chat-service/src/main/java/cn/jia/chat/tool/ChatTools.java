package cn.jia.chat.tool;

import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.LongTermMemoryService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ChatTools {

    private final ChatConversationService chatConversationService;
    private final LongTermMemoryService longTermMemoryService;

    @Tool(name = "listConversations", description = "查询聊天会话列表")
    public Map<String, Object> listConversations(
            @ToolParam(description = "搜索关键词，匹配会话标题") String keyword,
            @ToolParam(description = "会话类型：normal/juyiting") String conversationType,
            @ToolParam(description = "页码，默认1") Integer pageNum,
            @ToolParam(description = "每页条数，默认10") Integer pageSize) {
        EsContext ctx = EsContextHolder.getContext();
        ChatConversationEntity query = new ChatConversationEntity();
        query.setJiacn(ctx.getJiacn());
        if (keyword != null && !keyword.isEmpty()) {
            query.setTitle(keyword);
        }
        if (conversationType != null && !conversationType.isEmpty()) {
            query.setConversationType(conversationType);
        }
        int page = pageNum != null ? pageNum : 1;
        int size = pageSize != null ? pageSize : 10;
        PageInfo<ChatConversationEntity> pageInfo = chatConversationService.findPage(query, page, size, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageInfo.getTotal());
        result.put("pageNum", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        result.put("list", pageInfo.getList());
        return result;
    }

    @Tool(name = "getConversationDetail", description = "查看聊天会话详情")
    public ChatConversationEntity getConversationDetail(
            @ToolParam(description = "会话ID") String conversationId) {
        return chatConversationService.get(conversationId);
    }

    @Tool(name = "getConversationMessages", description = "获取会话中的消息记录")
    public List<ChatMessageEntity> getConversationMessages(
            @ToolParam(description = "会话ID") String conversationId) {
        return chatConversationService.findByConversationId(conversationId);
    }

    @Tool(name = "deleteConversation", description = "删除会话及其所有消息")
    public Map<String, Object> deleteConversation(
            @ToolParam(description = "会话ID") String conversationId) {
        chatConversationService.deleteConversation(conversationId);
        return Map.of("conversationId", conversationId, "deleted", true);
    }

    @Tool(name = "syncConversationMemory", description = "将会话同步到长期记忆向量库")
    public Map<String, Object> syncConversationMemory(
            @ToolParam(description = "会话ID") String conversationId) {
        EsContext context = EsContextHolder.getContext();
        longTermMemoryService.syncOwnedConversation(
                context.getJiacn(), context.getClientId(), conversationId);
        return Map.of("conversationId", conversationId, "synced", true);
    }

    @Tool(name = "generateDailySummary", description = "生成今日对话日报摘要")
    public Map<String, Object> generateDailySummary() {
        longTermMemoryService.generateDailySummary();
        return Map.of("generated", true, "type", "daily");
    }

    @Tool(name = "generateWeeklySummary", description = "生成本周对话周报摘要")
    public Map<String, Object> generateWeeklySummary() {
        longTermMemoryService.generateWeeklySummary();
        return Map.of("generated", true, "type", "weekly");
    }

    @Tool(name = "generateMonthlySummary", description = "生成本月对话月报摘要")
    public Map<String, Object> generateMonthlySummary() {
        longTermMemoryService.generateMonthlySummary();
        return Map.of("generated", true, "type", "monthly");
    }
}
