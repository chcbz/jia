package cn.jia.chat.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public record JuyitingAgentRelayResult(
        boolean attempted,
        Mono<Boolean> delivered,
        Flux<String> stream
) {
}
