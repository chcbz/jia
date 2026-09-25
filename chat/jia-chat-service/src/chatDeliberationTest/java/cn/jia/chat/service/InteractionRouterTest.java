package cn.jia.chat.service;

import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

class InteractionRouterTest {
    private final InteractionRouter router = new InteractionRouter();

    @Test
    void executeHintIsRejectedEvenWhenTextLooksHarmless() {
        ChatMessageDTO request = new ChatMessageDTO();
        request.setContent("just answer a question");
        request.setInteractionHint("execute");
        assertThrows(ChatDeliberationException.class, () -> router.routeStream(request));
    }

    @Test
    void executionWordsInTextNeverPromoteChat() {
        ChatMessageDTO request = new ChatMessageDTO();
        request.setContent("执行、修改、部署并继续");
        assertEquals(InteractionRoute.CHAT, router.routeStream(request));
        request.setInteractionHint("inspect");
        assertEquals(InteractionRoute.INSPECT, router.routeStream(request));
    }

    @Test
    void executeAliasesAreRejectedFromEveryMetadataField() {
        for (String key : new String[] {"interactionHint", "interactionMode", "route", "mode"}) {
            ChatMessageDTO request = new ChatMessageDTO();
            request.setContent("harmless");
            request.setMetadata(Map.of(key, "command_execution"));
            assertThrows(ChatDeliberationException.class, () -> router.routeStream(request), key);
        }
    }

    @Test
    void conversationModeIsIgnoredButConflictingRoutesFailClosed() {
        ChatMessageDTO request = new ChatMessageDTO();
        request.setContent("hello");
        request.setMetadata(Map.of("mode", "public"));
        assertEquals(InteractionRoute.CHAT, router.routeStream(request));
        request.setInteractionHint("chat");
        request.setMetadata(Map.of("route", "inspect"));
        assertThrows(ChatDeliberationException.class, () -> router.routeStream(request));
    }

    @Test
    void everyLegacyAliasAndEnumSpellingRejectsExecute() {
        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setContent("harmless text");
        dto.setInteractionMode("command_execution");
        assertThrows(ChatDeliberationException.class, () -> router.routeStream(dto));
        dto.setInteractionMode(null); dto.setRoute("EXECUTE-COMMAND");
        assertThrows(ChatDeliberationException.class, () -> router.routeStream(dto));
        dto.setRoute(null); dto.setIntent("Run");
        assertThrows(ChatDeliberationException.class, () -> router.routeStream(dto));
        dto.setIntent(null); dto.setMetadata(Map.of("interaction_type", "execution_mode"));
        assertThrows(ChatDeliberationException.class, () -> router.routeStream(dto));
    }
}
