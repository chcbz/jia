package cn.jia.chat.service;

import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic router. Chat text is deliberately never interpreted as execution authorization. */
@Component
public class InteractionRouter {
    private static final Set<String> EXECUTE = Set.of("EXECUTE", "EXECUTION", "COMMAND", "RUN", "TOOL",
            "EXECUTECOMMAND", "COMMANDEXECUTION", "EXECUTIONCOMMAND", "COMMANDMODE", "EXECUTIONMODE");
    private static final Set<String> STATUS = Set.of("CHATSTATUS", "STATUS");

    public InteractionRoute routeStream(ChatMessageDTO request) {
        if (request == null) throw invalid("Chat request is required");
        Set<String> declared = new LinkedHashSet<>();
        addRouteToken(declared, request.getInteractionHint(), true);
        Map<String, Object> metadata = request.getMetadata();
        if (metadata != null) {
            addRouteToken(declared, metadata.get("interactionHint"), true);
            addRouteToken(declared, metadata.get("interactionMode"), true);
            addRouteToken(declared, metadata.get("route"), true);
            // mode is historically conversation scope (public/private/bounty); inspect it only if it is a route token.
            addRouteToken(declared, metadata.get("mode"), false);
        }
        if (declared.stream().anyMatch(EXECUTE::contains)) {
            throw invalidRoute("EXECUTE is forbidden on /chat/stream; use the durable command API");
        }
        if (declared.stream().anyMatch(STATUS::contains)) {
            throw invalidRoute("/chat/stream does not accept CHAT_STATUS");
        }
        if (declared.isEmpty()) {
            return request.getInputRefs() == null || request.getInputRefs().isEmpty()
                    ? InteractionRoute.CHAT : InteractionRoute.INSPECT;
        }
        if (declared.size() != 1) throw invalidRoute("Conflicting interaction route declarations");
        String route = declared.iterator().next();
        if ("CHAT".equals(route)) return InteractionRoute.CHAT;
        if ("INSPECT".equals(route)) return InteractionRoute.INSPECT;
        throw invalidRoute("/chat/stream accepts only chat|inspect");
    }

    private void addRouteToken(Set<String> target, Object raw, boolean rejectUnknown) {
        if (raw == null || raw instanceof String text && text.isBlank()) return;
        if (!(raw instanceof String text)) throw invalidRoute("Interaction route must be a string");
        String normalized = text.strip().toUpperCase(Locale.ROOT).replaceAll("[-_\\s]", "");
        if ("CHAT".equals(normalized) || "INSPECT".equals(normalized)
                || EXECUTE.contains(normalized) || STATUS.contains(normalized)) {
            target.add(normalized);
        } else if (rejectUnknown) {
            throw invalidRoute("Unknown interaction route");
        }
    }

    private ChatDeliberationException invalid(String message) {
        return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST, message);
    }

    private ChatDeliberationException invalidRoute(String message) {
        return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_ROUTE, message);
    }
}
