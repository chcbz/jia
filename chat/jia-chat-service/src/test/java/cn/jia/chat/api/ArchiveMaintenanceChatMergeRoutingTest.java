package cn.jia.chat.api;

import cn.jia.chat.archive.maintenance.dto.ArchiveMaintenanceChatIntent;
import cn.jia.chat.archive.maintenance.entry.ArchiveMaintenanceChatCoordinator;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.chat.service.*;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.redis.RedisService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchiveMaintenanceChatMergeRoutingTest {
    @AfterEach void clearContext() { EsContextHolder.clearContext(); }

    @Test void mixedInspectOrExecuteCannotEnterArchiveToolsOrPersistConversation() {
        for (String hint : java.util.List.of("INSPECT", "EXECUTE")) {
            var conversations = mock(ChatConversationService.class);
            var sender = mock(HumanSenderIdentityResolver.class);
            var archive = mock(ArchiveMaintenanceChatCoordinator.class);
            var relay = mock(JuyitingAgentRelayService.class);
            var controller = new ChatController(null, conversations, null, null, null, null,
                    null, relay, null, null, sender);
            controller.setArchiveMaintenanceChatCoordinator(archive);
            var request = request(); request.setInteractionHint(hint);
            assertEquals(ChatDeliberationException.Reason.INVALID_ROUTE,
                    assertThrows(ChatDeliberationException.class,
                            () -> controller.handleChat(request, "mixed-key")).reason());
            verifyNoInteractions(conversations, sender, archive, relay);
        }
    }

    @Test void archiveChatAndOrdinaryChatInspectionRetainSeparateRelays() {
        EsContextHolder.setContext(new EsContext());
        for (String lane : java.util.List.of("ARCHIVE", "CHAT", "INSPECT")) {
            var conversations = mock(ChatConversationService.class);
            var identity = mock(HumanSenderIdentityResolver.class);
            var archive = mock(ArchiveMaintenanceChatCoordinator.class);
            var relay = mock(JuyitingAgentRelayService.class);
            var redis = mock(RedisService.class);
            var broker = mock(ChatConversationEventBroker.class);
            var sender = new ServerResolvedSender("user", "owner", "owner-a", "client-a", null);
            var conversation = new ChatConversationEntity().setId(1L).setTitle("Existing")
                    .setConversationType("juyiting").setLifecycleGeneration(1L);
            when(identity.resolve(any())).thenReturn(sender);
            when(conversations.get("1")).thenReturn(conversation);
            when(redis.subscribeToChannel("1")).thenReturn(Flux.never());
            when(broker.deletionSignal(eq("1"), eq(1L), any())).thenReturn(Flux.never());
            var accepted = new JuyitingAgentRelayResult(true, Mono.just(true), Flux.empty());
            when(archive.supports(any())).thenAnswer(call ->
                    ((ChatMessageDTO) call.getArgument(0)).getArchiveMaintenanceIntent() != null);
            when(archive.relay(any(), eq("1"), eq(conversation), eq(sender))).thenReturn(accepted);
            when(relay.relay(any(), eq("1"), eq(sender), any())).thenReturn(accepted);
            var controller = new ChatController(null, conversations, redis, null, broker, null,
                    null, relay, null, null, identity);
            controller.setArchiveMaintenanceChatCoordinator(archive);
            var request = request();
            if (!"ARCHIVE".equals(lane)) request.setArchiveMaintenanceIntent(null);
            if ("INSPECT".equals(lane)) request.setInteractionHint("INSPECT");
            assertNotNull(controller.handleChat(request, "merge-key")); // No provider/subscription is executed.
            if ("ARCHIVE".equals(lane)) {
                verify(archive).relay(request, "1", conversation, sender);
                verifyNoInteractions(relay);
            } else {
                verify(relay).relay(request, "1", sender, InteractionRoute.valueOf(lane));
                verify(archive, never()).relay(any(), anyString(), any(), any());
            }
        }
    }

    private static ChatMessageDTO request() {
        var request = new ChatMessageDTO();
        request.setConversationId("1"); request.setConversationType("juyiting");
        request.setContent("confirmed archive request");
        request.setArchiveMaintenanceIntent(new ArchiveMaintenanceChatIntent(1, "a".repeat(64)));
        return request;
    }
}
