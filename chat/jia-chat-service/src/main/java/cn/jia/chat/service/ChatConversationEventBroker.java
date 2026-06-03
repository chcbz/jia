package cn.jia.chat.service;

import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ChatConversationEventBroker {
    private final Map<String, EventSink> sinks = new ConcurrentHashMap<>();

    public Flux<String> stream(String conversationId) {
        EventSink eventSink = sinks.computeIfAbsent(conversationId, ignored -> new EventSink());
        return eventSink.sink.asFlux()
                .doOnSubscribe(ignored -> eventSink.subscribers.incrementAndGet())
                .doFinally(ignored -> {
                    if (eventSink.subscribers.decrementAndGet() <= 0) {
                        sinks.remove(conversationId, eventSink);
                    }
                });
    }

    public void publish(String conversationId, Map<String, ?> event) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        EventSink eventSink = sinks.get(conversationId);
        if (eventSink == null) {
            return;
        }
        eventSink.sink.tryEmitNext(JsonUtil.toJson(event));
    }

    private static class EventSink {
        private final Sinks.Many<String> sink = Sinks.many().multicast().directBestEffort();
        private final AtomicInteger subscribers = new AtomicInteger();
    }
}
