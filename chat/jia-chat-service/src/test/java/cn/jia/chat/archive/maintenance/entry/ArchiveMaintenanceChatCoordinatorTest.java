package cn.jia.chat.archive.maintenance.entry;

import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.maintenance.dto.*;
import cn.jia.chat.archive.maintenance.http.ArchiveAdminController;
import cn.jia.chat.archive.maintenance.model.*;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceException;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceService;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.chat.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchiveMaintenanceChatCoordinatorTest {
    private static final ArchiveActorScope ACTOR = new ArchiveActorScope("0", "client-a", "owner-a");
    private static final ArchiveConfirmedPolicyRef POLICY = new ArchiveConfirmedPolicyRef(
            "acf_confirmed", "platform-classics", "REVISE_WORK", null,
            "work-1", "src-1", "MANUAL");
    private static final ArchiveMaintenanceRequest BUSINESS = new ArchiveMaintenanceRequest(
            "platform-classics", "REVISE_WORK", null, "work-1", "src-1", "MANUAL");

    @Test
    void directPrivateUsesOpaqueConfirmationCanonicalServerMessageAndBypassesSongjiang() {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ArchiveRestrictedChatClientFactory factory = mock(ArchiveRestrictedChatClientFactory.class);
        ChatConversationService conversations = mock(ChatConversationService.class);
        ChatMessageEntity saved = new ChatMessageEntity().setId(701L);
        when(conversations.appendOwnedMessage(eq("owner-a"), eq("client-a"), any(), eq(4L)))
                .thenReturn(saved);
        ArchiveRequestContext bound = new ArchiveRequestContext(ACTOR, "ari_intent",
                "DIRECT_PRIVATE", "conversation-7:701", "agent-wuyong", POLICY);
        when(service.bindChatConfirmation(eq(ACTOR), eq("acf_confirmed"), eq("conversation-7"),
                eq(4L), anyString(), eq("DIRECT_PRIVATE"), eq("agent-wuyong"), any()))
                .thenAnswer(call -> {
                    @SuppressWarnings("unchecked") Supplier<String> writer = call.getArgument(7);
                    assertEquals("701", writer.get());
                    return bound;
                });
        when(service.request(bound, BUSINESS)).thenReturn(result("WAITING_SKILL"));
        ArchiveMaintenanceChatCoordinator coordinator = new ArchiveMaintenanceChatCoordinator(
                service, factory, conversations, mock(JuyitingConversationScopeService.class),
                mock(BuiltinHallAgentSupport.class));

        ChatMessageDTO message = message("只处理已确认来源，不要转给宋江", 1, "acf_confirmed");
        ChatConversationEntity conversation = privateConversation(4L, "agent-wuyong");
        var frames = coordinator.relay(message, "conversation-7", conversation, sender())
                .stream().collectList().block();

        assertEquals(1, frames.size());
        assertTrue(frames.getFirst().contains("archive_maintenance_receipt"));
        assertTrue(frames.getFirst().contains("AUTHORITATIVE_NON_TERMINAL"));
        verify(service).request(bound, BUSINESS);
        verifyNoInteractions(factory);
        verify(service).bindChatConfirmation(eq(ACTOR), eq("acf_confirmed"), eq("conversation-7"),
                eq(4L), eq(ArchiveEtags.sha256(message.getContent().getBytes(StandardCharsets.UTF_8))),
                eq("DIRECT_PRIVATE"), eq("agent-wuyong"), any());
        verify(conversations, times(2)).appendOwnedMessage(
                eq("owner-a"), eq("client-a"), any(), eq(4L));
    }

    @Test
    void managerRouteAuthenticatesAndPersistsConfirmationThroughCommonFacade() throws Exception {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        when(service.confirmRequest(ACTOR, "platform-classics", "manager-key", BUSINESS))
                .thenReturn(new ArchiveMaintenanceRequestResultDTO(
                        job("WAITING_SKILL"), null, "WAITING", "GET_JOB", "acf_server"));
        ArchiveAdminController admin = new ArchiveAdminController(service, new ObjectMapper());

        var response = admin.request("platform-classics", "manager-key",
                new ObjectMapper().writeValueAsBytes(BUSINESS), authenticatedJwt());

        assertEquals(202, response.getStatusCode().value());
        assertEquals("acf_server", response.getBody().getData().confirmationRef());
        verify(service).confirmRequest(ACTOR, "platform-classics", "manager-key", BUSINESS);
        verify(service, never()).request(any(), any());
    }

    @Test
    void missingForeignOrLegacyConfirmationReturnsManagementEntryWithoutPersistenceOrModel() {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ArchiveRestrictedChatClientFactory factory = mock(ArchiveRestrictedChatClientFactory.class);
        ChatConversationService conversations = mock(ChatConversationService.class);
        JuyitingConversationScopeService scopes = mock(JuyitingConversationScopeService.class);
        BuiltinHallAgentSupport builtin = mock(BuiltinHallAgentSupport.class);
        ArchiveMaintenanceChatCoordinator coordinator = new ArchiveMaintenanceChatCoordinator(
                service, factory, conversations, scopes, builtin);

        String legacy = coordinator.relay(message("legacy", 0, "old"), "conversation-1",
                new ChatConversationEntity(), sender()).stream().blockFirst();
        assertTrue(legacy.contains("archive_management_entry_required"));
        verifyNoInteractions(service, factory, conversations, scopes, builtin);

        when(service.bindChatConfirmation(any(), eq("acf_foreign"), anyString(), anyLong(),
                anyString(), anyString(), any(), any())).thenThrow(new ArchiveMaintenanceException(
                        403, "ARCHIVE_CONFIRMATION_NOT_AVAILABLE", "not available"));
        String foreign = coordinator.relay(message("same turn", 1, "acf_foreign"),
                "conversation-2", privateConversation(1L, "agent-wuyong"), sender())
                .stream().blockFirst();
        assertTrue(foreign.contains("archive_management_entry_required"));
        verifyNoInteractions(factory);
        verifyNoInteractions(conversations);
        verify(service, never()).request(any(), any());
    }

    @Test
    void songjiangWithoutToolCannotPersistOrReturnModelSuccessClaim() {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ChatConversationService conversations = mock(ChatConversationService.class);
        bindSongjiang(service, conversations, "模型不应成为回执");
        ChatModel model = streamingModel(prompt -> Flux.just(new ChatResponse(List.of(
                new Generation(new AssistantMessage("已经成功上架并发布"))))));
        ArchiveMaintenanceChatCoordinator coordinator = songjiangCoordinator(service,
                conversations, model);

        List<String> frames = coordinator.relay(message("模型不应成为回执", 1, "acf_confirmed"),
                "conversation-song", songjiangConversation(2L), sender())
                .stream().collectList().block();

        assertEquals(1, frames.size());
        assertTrue(frames.getFirst().contains("UNCONFIRMED"));
        assertTrue(frames.getFirst().contains("AUTHORITATIVE_TOOL_RESULT_REQUIRED"));
        assertFalse(frames.getFirst().contains("已经成功上架"));
        verify(service, never()).request(any(), any());
        var persisted = org.mockito.ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(conversations, times(2)).appendOwnedMessage(eq("owner-a"), eq("client-a"),
                persisted.capture(), eq(2L));
        assertFalse(persisted.getAllValues().get(1).getContent().contains("已经成功上架"));
    }

    @Test
    void songjiangNonterminalToolResultOverridesArbitraryPublishedNarration() {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ChatConversationService conversations = mock(ChatConversationService.class);
        bindSongjiang(service, conversations, "只执行服务端确认请求");
        when(service.request(any(), eq(BUSINESS))).thenReturn(result("WAITING_SKILL"));
        AtomicReference<Prompt> captured = new AtomicReference<>();
        ChatModel model = streamingModel(prompt -> {
            captured.set(prompt);
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            options.getToolCallbacks().stream()
                    .filter(tool -> "requestArchiveMaintenance".equals(
                            tool.getToolDefinition().name())).findFirst().orElseThrow()
                    .call("""
                            {"collectionId":"platform-classics","operation":"REVISE_WORK",
                             "workId":"work-1","sourceId":"src-1",
                             "requestedPublicationMode":"MANUAL"}
                            """);
            return Flux.just(new ChatResponse(List.of(
                    new Generation(new AssistantMessage("已经成功上架")))));
        });
        ArchiveMaintenanceChatCoordinator coordinator = songjiangCoordinator(service,
                conversations, model);

        String frame = coordinator.relay(message("只执行服务端确认请求", 1, "acf_confirmed"),
                "conversation-song", songjiangConversation(2L), sender())
                .stream().blockFirst();

        assertTrue(frame.contains("AUTHORITATIVE_NON_TERMINAL"));
        assertTrue(frame.contains("WAITING_SKILL"));
        assertFalse(frame.contains("已经成功上架"));
        assertTrue(captured.get().getSystemMessage().getText().contains(
                "AUTHORIZED_ARCHIVE_REQUEST_JSON"));
        assertTrue(captured.get().getSystemMessage().getText().contains("\"sourceId\":\"src-1\""));
        List<String> tools = ((ToolCallingChatOptions) captured.get().getOptions())
                .getToolCallbacks().stream().map(tool -> tool.getToolDefinition().name())
                .sorted().toList();
        assertEquals(ArchiveRestrictedChatClientFactory.TOOL_NAMES.stream().sorted().toList(), tools);
        verify(service).request(any(ArchiveRequestContext.class), eq(BUSINESS));
    }

    private void bindSongjiang(ArchiveMaintenanceService service,
            ChatConversationService conversations, String content) {
        ChatMessageEntity saved = new ChatMessageEntity().setId(811L);
        when(conversations.appendOwnedMessage(eq("owner-a"), eq("client-a"), any(), eq(2L)))
                .thenReturn(saved);
        ArchiveRequestContext bound = new ArchiveRequestContext(ACTOR, "ari_intent", "SONGJIANG",
                "conversation-song:811", null, POLICY);
        when(service.bindChatConfirmation(eq(ACTOR), eq("acf_confirmed"),
                eq("conversation-song"), eq(2L),
                eq(ArchiveEtags.sha256(content.getBytes(StandardCharsets.UTF_8))),
                eq("SONGJIANG"), isNull(), any())).thenAnswer(call -> {
                    @SuppressWarnings("unchecked") Supplier<String> writer = call.getArgument(7);
                    assertEquals("811", writer.get());
                    return bound;
                });
    }

    private ArchiveMaintenanceChatCoordinator songjiangCoordinator(
            ArchiveMaintenanceService service, ChatConversationService conversations,
            ChatModel model) {
        JuyitingConversationScopeService scopes = mock(JuyitingConversationScopeService.class);
        BuiltinHallAgentSupport builtin = mock(BuiltinHallAgentSupport.class);
        when(builtin.defaultAgentId()).thenReturn("builtin-songjiang");
        when(scopes.parsePersistedTargetAgentIds("[\"builtin-songjiang\"]"))
                .thenReturn(List.of("builtin-songjiang"));
        return new ArchiveMaintenanceChatCoordinator(service,
                new ArchiveRestrictedChatClientFactory(model, service), conversations,
                scopes, builtin);
    }

    private ChatModel streamingModel(java.util.function.Function<Prompt, Flux<ChatResponse>> stream) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new AssertionError(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return stream.apply(prompt); }
        };
    }

    private JwtAuthenticationToken authenticatedJwt() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", "owner-a").claim("client_id", "client-a").build();
        return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_ARCHIVE_MANAGER")));
    }

    private ChatMessageDTO message(String content, int version, String ref) {
        ChatMessageDTO message = new ChatMessageDTO();
        message.setConversationType("juyiting");
        message.setContent(content);
        message.setArchiveMaintenanceIntent(new ArchiveMaintenanceChatIntent(version, ref));
        return message;
    }

    private ChatConversationEntity privateConversation(long generation, String target) {
        return new ChatConversationEntity().setConversationType("juyiting")
                .setConversationScopeType("private").setTargetAgentId(target)
                .setLifecycleGeneration(generation);
    }

    private ChatConversationEntity songjiangConversation(long generation) {
        return new ChatConversationEntity().setConversationType("juyiting")
                .setConversationScopeType("public")
                .setTargetAgentIds("[\"builtin-songjiang\"]")
                .setLifecycleGeneration(generation);
    }

    private ServerResolvedSender sender() {
        return new ServerResolvedSender("user", "Owner", "owner-a", "client-a",
                DisplayNameSource.JIACN);
    }

    private ArchiveMaintenanceRequestResultDTO result(String state) {
        return new ArchiveMaintenanceRequestResultDTO(job(state), null,
                "ARCHIVE_EXECUTION_DISABLED", "RESUME_WHEN_READY");
    }

    private ArchiveJobDTO job(String state) {
        return new ArchiveJobDTO("job-1", "run-1", "platform-classics", state,
                "CLIENT_UPDATE_REQUIRED", "1", "appointment-1", "agent-wuyong",
                "DRAFT_ONLY", "MANUAL", "REVISE_WORK", "work-1", "tiny-book",
                "小书", "src-1", "draft-1", null);
    }
}
