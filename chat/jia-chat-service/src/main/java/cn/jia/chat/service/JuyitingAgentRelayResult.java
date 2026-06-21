package cn.jia.chat.service;

import reactor.core.publisher.Flux;

public record JuyitingAgentRelayResult(
        boolean attempted,
        boolean delivered,
        Flux<String> stream
) {
}
