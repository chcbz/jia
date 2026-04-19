package cn.jia.agent.controller;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.redis.RedisService;
import cn.jia.core.util.StringUtil;
import cn.jia.kefu.entity.KefuMessageEntity;
import cn.jia.kefu.service.KefuMessageService;
import io.micrometer.core.instrument.util.StringEscapeUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cn.jia.agent.handler.dto.ChatMessageDTO;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * Agent聊天控制器
 * 提供基于Spring AI的流式聊天功能，支持会话管理和流传输控制
 *
 * @author jia
 */
@Slf4j
@RestController
@RequestMapping("/agent/chat")
@RequiredArgsConstructor
public class ChatController {
    private final ChatClient chatClient;
    private final KefuMessageService kefuMessageService;
    private final RedisService redisService;
    private final ChatClient.Builder chatClientBuilder;
    private static final PromptTemplate SUMMARY_PROMPT_TEMPLATE = new PromptTemplate("""
            帮我根据下面对话内容，输出15字以内的问题意图概述，需要名词开头。
            
            问：
            -------------------------------------------------
            {question}
            -------------------------------------------------
            答：
            -------------------------------------------------
            {answer}
            -------------------------------------------------
            """);

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

        Flux<String> cancelSignal = redisService.subscribeToChannel(conversationId);
        Flux<String> aiStream = createAIStream(chatMessage, conversationId, needSummary, summary);

        // 使用 Flux.create 将后台流与客户端响应连接
        return Flux.create(emitter -> {
            // 构建完整的后台处理流
            // 将数据推送给客户端，如果客户端已断开，该操作会被忽略
            // 正常完成
            Flux<String> backendStream = aiStream
                    .takeUntilOther(cancelSignal)                     // Redis 取消信号
                    .concatWith(Flux.defer(() -> Flux.just("{\"conversationId\": \"" + conversationId + "\"}")))
                    .concatWith(Flux.defer(() -> handleSummary(needSummary, chatMessage.getContent(), summary.toString(), conversationId)))
                    .doOnNext(emitter::next)
                    .doOnError(error -> {
                        log.error("Error processing chat response", error);
                        emitter.error(error);                         // 错误仍可传递（若客户端还在）
                    })
                    .doOnComplete(emitter::complete)
                    .subscribeOn(Schedulers.boundedElastic());        // 在独立线程池执行，不阻塞 Netty

            // 立即订阅后台流，启动处理（不受客户端生命周期影响）
            backendStream.subscribe();

            // 客户端断开连接时，仅停止发射数据，后台流继续运行
            emitter.onDispose(() ->
                    log.debug("Client disconnected, backend processing continues for conversation: {}", conversationId)
            );
        }, FluxSink.OverflowStrategy.BUFFER);  // 使用 BUFFER 避免背压导致数据丢失
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
     * 创建AI流
     *
     * @param chatMessage    聊天消息DTO
     * @param conversationId 会话ID
     * @param needSummary    是否需要总结
     * @param summary        总结内容构建器
     * @return AI流
     */
    private Flux<String> createAIStream(ChatMessageDTO chatMessage, String conversationId, boolean needSummary, StringBuilder summary) {
        // 构建过滤表达式: 同时过滤 jiacn 和 role
        String jiacn = EsContextHolder.getContext().getJiacn();
        String filterExpression = "metadata.jiacn == '" + jiacn + "' AND role == 'ASSISTANT'";
        
        return chatClient.prompt(
                        Prompt.builder().messages(UserMessage.builder().text(chatMessage.getContent()).build()).build())
                .advisors(advisor -> advisor
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .param("jiacn", jiacn)
                        .param(QuestionAnswerAdvisor.FILTER_EXPRESSION, filterExpression))
                .messages()
                .stream().content()
                .map(content -> processContent(content, needSummary, summary));
    }

    /**
     * 处理内容
     *
     * @param content     内容
     * @param needSummary 是否需要总结
     * @param summary     总结内容构建器
     * @return 处理后的内容
     */
    private String processContent(String content, boolean needSummary, StringBuilder summary) {
        if (needSummary) {
            summary.append(content);
        }
        return "{\"v\": \"" + StringEscapeUtils.escapeJson(content) + "\"}";
    }

    /**
     * 处理总结
     *
     * @param needSummary    是否需要总结
     * @param answer         总结内容
     * @param conversationId 会话ID
     * @return Flux流
     */
    private Flux<String> handleSummary(boolean needSummary, String question, String answer, String conversationId) {
        if (!needSummary) {
            return Flux.empty();
        }
        String title = chatClientBuilder.build().prompt(
                        SUMMARY_PROMPT_TEMPLATE.create(Map.of("question", question, "answer", answer)))
                .call().content();
        KefuMessageEntity upMessage = new KefuMessageEntity();
        upMessage.setId(Long.valueOf(conversationId));
        upMessage.setTitle(title);
        kefuMessageService.update(upMessage);
        return Flux.just("{\"t\": \"" + title + "\"}");
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
