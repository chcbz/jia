package cn.jia.ai.mcp.client.controller;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.redis.RedisService;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.kefu.entity.KefuMessageEntity;
import cn.jia.kefu.service.KefuMessageService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cn.jia.ai.mcp.client.handler.dto.ChatMessageDTO;
import cn.jia.ai.mcp.client.advisor.RequestResponseAdvisor;

/**
 * MCP聊天控制器
 * 提供基于Spring AI的流式聊天功能，支持会话管理和流传输控制
 *
 * @author jia
 */
@Slf4j
@RestController
@RequestMapping("/mcp/chat")
@RequiredArgsConstructor
public class ChatController {
    private final ChatClient chatClient;
    private final KefuMessageService kefuMessageService;
    private final RedisService redisService;

    /**
     * 处理聊天请求并返回流式响应
     *
     * @param chatMessage 包含用户消息和会话ID的DTO对象
     * @return 返回包含AI回复内容的流
     */
    @RequestMapping(value = "/stream", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> handleChat(@RequestBody ChatMessageDTO chatMessage) {
        KefuMessageEntity message = getOrCreateMessage(chatMessage);
        boolean needSummary = StringUtil.isBlank(message.getTitle());
        StringBuilder summary = new StringBuilder();
        String conversationId = String.valueOf(message.getId());
        
        if (needSummary) {
            prepareSummaryRequest(chatMessage);
        }

        Flux<String> cancelSignal = redisService.subscribeToChannel(conversationId);
        Flux<String> aiStream = createAIStream(chatMessage, conversationId, needSummary, summary);

        return aiStream.takeUntilOther(cancelSignal)
                .doOnError(error -> log.error("Error processing chat response", error))
                .concatWith(Flux.defer(() -> handleSummary(needSummary, summary, conversationId)));
    }

    /**
     * 获取或创建消息实体
     *
     * @param chatMessage 聊天消息DTO
     * @return 消息实体
     */
    private KefuMessageEntity getOrCreateMessage(ChatMessageDTO chatMessage) {
        KefuMessageEntity message;
        if (StringUtil.isBlank(chatMessage.getConversationId())) {
            message = new KefuMessageEntity();
            message.setStatus(0);
            message.setJiacn(EsContextHolder.getContext().getJiacn());
            message.setClientId(EsContextHolder.getContext().getClientId());
            message.setContent(chatMessage.getContent());
            message = kefuMessageService.create(message);
        } else {
            message = kefuMessageService.get(chatMessage.getConversationId());
        }
        return message;
    }

    /**
     * 为需要总结的请求准备内容
     *
     * @param chatMessage 聊天消息DTO
     */
    private void prepareSummaryRequest(ChatMessageDTO chatMessage) {
        chatMessage.setContent(chatMessage.getContent() + "\n在答复内容结束后，输出10字以内的标题，格式：$title$标题内容$title$");
    }

    /**
     * 创建AI流
     *
     * @param chatMessage 聊天消息DTO
     * @param conversationId 会话ID
     * @param needSummary 是否需要总结
     * @param summary 总结内容构建器
     * @return AI流
     */
    private Flux<String> createAIStream(ChatMessageDTO chatMessage, String conversationId, boolean needSummary, StringBuilder summary) {
        return chatClient.prompt(
                        Prompt.builder().messages(
                                UserMessage.builder().text(chatMessage.getContent()).metadata(Map.of(
                                        "createTime", DateUtil.nowTime(),
                                        "jiacn", EsContextHolder.getContext().getJiacn())).build()
                        ).build())
                .advisors(advisor -> advisor
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "role=='ASSISTANT'"))
                .advisors(new RequestResponseAdvisor())
                .stream().content()
                .map(content -> processContent(content, needSummary, summary));
    }

    /**
     * 处理内容
     *
     * @param content 内容
     * @param needSummary 是否需要总结
     * @param summary 总结内容构建器
     * @return 处理后的内容
     */
    private String processContent(String content, boolean needSummary, StringBuilder summary) {
        // 回车转义为换行符
        content = content.replace("\n", "\\n");
        if (needSummary) {
            summary.append(content);
        }
        return "{\"v\": \"" + content + "\"}";
    }

    /**
     * 处理总结
     *
     * @param needSummary 是否需要总结
     * @param summary 总结内容
     * @param conversationId 会话ID
     * @return Flux流
     */
    private Flux<String> handleSummary(boolean needSummary, StringBuilder summary, String conversationId) {
        if (needSummary) {
            try {
                String contentStr = summary.toString();
                if (contentStr.contains("$title$")) {
                    String content = contentStr.substring(contentStr.indexOf("$title$"));
                    String title = content.substring(7, content.length() - 7);
                    KefuMessageEntity upMessage = new KefuMessageEntity();
                    upMessage.setId(Long.valueOf(conversationId));
                    upMessage.setTitle(title);
                    kefuMessageService.update(upMessage);
                    return Flux.just("{\"t\": \"" + title + "\"}");
                }
            } catch (Exception e) {
                log.error("处理标题时出错", e);
            }
        }
        return Flux.empty();
    }

    /**
     * 停止指定会话的流传输
     *
     * @param chatMessage 包含会话ID的DTO对象
     * @return 操作结果
     */
    @RequestMapping(value = "/stop_stream", method = RequestMethod.POST)
    public Object stopStream(@RequestBody ChatMessageDTO chatMessage) {
        // 发布停止信号到Redis频道
        redisService.publishSignal(chatMessage.getConversationId()).subscribe();
        return JsonResult.success();
    }
}