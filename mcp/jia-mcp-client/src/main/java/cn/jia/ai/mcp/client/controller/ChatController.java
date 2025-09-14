package cn.jia.ai.mcp.client.controller;

import cn.jia.core.entity.JsonResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.Disposable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cn.jia.ai.mcp.client.handler.dto.ChatMessageDTO;
import cn.jia.ai.mcp.client.advisor.RequestResponseAdvisor;

@Slf4j
@RestController
@RequestMapping("/mcp/chat")
@RequiredArgsConstructor
public class ChatController {
    private final ChatClient chatClient;
    private final Map<String, FluxSink<String>> activeSinks = new ConcurrentHashMap<>();
    private final Map<String, Disposable> activeSubscriptions = new ConcurrentHashMap<>();
    private final Object sinkLock = new Object();

    @RequestMapping(value = "/stream", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> handleChat(@RequestBody ChatMessageDTO chatMessage) {
        return Flux.create(sink -> {
            synchronized (sinkLock) {
                activeSinks.put(chatMessage.getConversationId(), sink);
            }
            
            Flux<String> responseFlux = chatClient.prompt(chatMessage.getContent())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, chatMessage.getConversationId()))
                .advisors(new RequestResponseAdvisor())
                .stream().content();

            Disposable subscription = responseFlux.subscribe(content -> {
                // 回车转义为换行符
                content = content.replace("\n", "\\n");
                sink.next("{\"v\": \"" + content + "\"}");
            }, null, () -> {
                synchronized (sinkLock) {
                    activeSinks.remove(chatMessage.getConversationId());
                    activeSubscriptions.remove(chatMessage.getConversationId());
                }
                sink.complete();
            });
            synchronized (sinkLock) {
                activeSubscriptions.put(chatMessage.getConversationId(), subscription);
            }
        });
    }

    @RequestMapping(value = "/stop_stream", method = RequestMethod.POST)
    public Object stopStream(@RequestBody ChatMessageDTO chatMessage) {
        synchronized (sinkLock) {
            FluxSink<String> sink = activeSinks.get(chatMessage.getConversationId());
            Disposable subscription = activeSubscriptions.get(chatMessage.getConversationId());
            
            if (sink != null) {
                sink.complete();
                activeSinks.remove(chatMessage.getConversationId());
            }
            if (subscription != null && !subscription.isDisposed()) {
                subscription.dispose();
                activeSubscriptions.remove(chatMessage.getConversationId());
            }
        }
        return JsonResult.success();
    }
}
