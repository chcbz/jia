package cn.jia.agent.service;

import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Named;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-local live event fan-out. Durable replay remains the DAO's responsibility.
 * Channels exist only while a scope has subscribers; publication without subscribers
 * is intentionally dropped because reconnecting clients recover from persisted events.
 */
@Named
public class AgentSceneEventBroker {
    private final ConcurrentHashMap<SceneScope, ScopedChannel> channels = new ConcurrentHashMap<>();

    public Flux<AgentSceneEventDTO> stream(SceneScope scope, long afterVersion) {
        SceneScope requiredScope = requireScope(scope);
        if (afterVersion < 0) {
            return Flux.error(new IllegalArgumentException("afterVersion must be nonnegative"));
        }
        return Flux.defer(() -> {
            ScopedChannel channel = channels.compute(requiredScope, (key, current) -> {
                ScopedChannel selected = current == null ? new ScopedChannel() : current;
                selected.subscribers.incrementAndGet();
                return selected;
            });
            return channel.sink.asFlux()
                    .filter(event -> event.getSceneVersion() != null
                            && event.getSceneVersion() > afterVersion)
                    .map(AgentSceneEventBroker::copyEvent)
                    .doFinally(signal -> release(requiredScope, channel));
        });
    }

    public void publish(SceneScope scope, AgentSceneEventDTO event) {
        SceneScope requiredScope = requireScope(scope);
        AgentSceneEventDTO safeEvent = requireEvent(event);
        ScopedChannel channel = channels.get(requiredScope);
        if (channel == null) {
            return;
        }
        synchronized (channel) {
            Sinks.EmitResult result = channel.sink.tryEmitNext(safeEvent);
            if (result == Sinks.EmitResult.OK
                    || result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER
                    || result == Sinks.EmitResult.FAIL_OVERFLOW
                    || result == Sinks.EmitResult.FAIL_CANCELLED
                    || result == Sinks.EmitResult.FAIL_TERMINATED) {
                return;
            }
            throw new IllegalStateException("Unable to publish scene event: " + result);
        }
    }

    int activeScopeCount() {
        return channels.size();
    }

    private void release(SceneScope scope, ScopedChannel channel) {
        channels.compute(scope, (key, current) -> {
            int remaining = channel.subscribers.decrementAndGet();
            if (remaining < 0) {
                throw new IllegalStateException("Scene event broker subscriber count underflow");
            }
            if (current == channel && remaining == 0) {
                channel.sink.tryEmitComplete();
                return null;
            }
            return current;
        });
    }

    private static SceneScope requireScope(SceneScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scene scope is required");
        }
        return scope;
    }

    private static AgentSceneEventDTO requireEvent(AgentSceneEventDTO source) {
        if (source == null || source.getSceneVersion() == null || source.getSceneVersion() < 0
                || StringUtil.isBlank(source.getEventType())) {
            throw new IllegalArgumentException("scene event version and type are required");
        }
        return copyEvent(source);
    }

    public static AgentSceneEventDTO copyEvent(AgentSceneEventDTO source) {
        if (source == null) {
            return null;
        }
        AgentSceneEventDTO copy = new AgentSceneEventDTO();
        copy.setSceneVersion(source.getSceneVersion());
        copy.setEventType(source.getEventType());
        copy.setState(AgentSceneStateDTO.copyOf(source.getState()));
        copy.setOccurredAt(source.getOccurredAt());
        return copy;
    }

    public record SceneScope(String tenantId, String clientId, String sceneId) {
        public SceneScope {
            tenantId = requireText(tenantId, "tenantId");
            clientId = requireText(clientId, "clientId");
            sceneId = requireText(sceneId, "sceneId");
        }

        private static String requireText(String value, String field) {
            String normalized = value == null ? null : value.trim();
            if (StringUtil.isBlank(normalized)) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
    }

    private static final class ScopedChannel {
        private final Sinks.Many<AgentSceneEventDTO> sink =
                Sinks.many().multicast().directBestEffort();
        private final AtomicInteger subscribers = new AtomicInteger();
    }
}
