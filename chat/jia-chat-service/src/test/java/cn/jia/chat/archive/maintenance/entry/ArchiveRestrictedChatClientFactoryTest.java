package cn.jia.chat.archive.maintenance.entry;

import cn.jia.chat.archive.maintenance.dto.*;
import cn.jia.chat.archive.maintenance.model.*;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceException;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArchiveRestrictedChatClientFactoryTest {
    private static final ArchiveActorScope ACTOR = new ArchiveActorScope("0", "client-a", "owner-a");

    @Test
    void freshClientContainsExactlyThreeClosedOverToolsAndNoIdentityArguments() {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ChatModel model = prompt -> null;
        ArchiveRequestContext context = context("MANUAL");
        ArchiveRestrictedChatClientFactory.Session session =
                new ArchiveRestrictedChatClientFactory(model, service).create(context);

        assertEquals(ArchiveRestrictedChatClientFactory.TOOL_NAMES.stream().sorted().toList(),
                session.toolNames());
        var callbacks = ToolCallbacks.from(session.tools());
        assertEquals(3, callbacks.length);
        String schemas = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().inputSchema()).toList().toString();
        assertFalse(schemas.contains("actorScope"));
        assertFalse(schemas.contains("requestIntentId"));
        assertFalse(schemas.contains("entryPoint"));
        assertFalse(schemas.contains("conversationRef"));
        assertFalse(schemas.contains("targetAgentId"));
        assertFalse(schemas.contains("confirmedPolicyRef"));
        assertFalse(schemas.contains("shell"));
        assertFalse(schemas.contains("url"));

        ArchiveMaintenanceRequestResultDTO expected = new ArchiveMaintenanceRequestResultDTO(
                null, null, "WAITING", "GET_JOB");
        when(service.request(eq(context), any())).thenReturn(expected);
        assertSame(expected, session.tools().requestArchiveMaintenance(
                "platform-classics", "REVISE_WORK", null, null, null,
                "work-1", "src-1", "MANUAL"));
        verify(service).request(eq(context), eq(new ArchiveMaintenanceRequest(
                "platform-classics", "REVISE_WORK", null, "work-1", "src-1", "MANUAL")));
    }

    @Test
    void finalPromptReplacesRawModelGlobalToolsWithOnlyRestrictedCallbacks() {
        AtomicReference<Prompt> captured = new AtomicReference<>();
        ToolCallback globalShell = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("globalShell").description("unsafe")
                        .inputSchema("{\"type\":\"object\"}").build();
            }
            @Override public String call(String input) { return "never"; }
        };
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new AssertionError(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                captured.set(prompt);
                return Flux.just(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("ok")))));
            }
            @Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().model("tool-model").temperature(0.4)
                        .toolCallbacks(globalShell)
                        .toolContext(Map.of("rawSecret", "must-not-leak")).build();
            }
        };
        var session = new ArchiveRestrictedChatClientFactory(model,
                mock(ArchiveMaintenanceService.class)).create(context("MANUAL"));
        assertEquals("ok", session.client().prompt("coordinate").stream().content()
                .collectList().block().getFirst());
        assertNotNull(captured.get());
        ToolCallingChatOptions options = assertInstanceOf(
                ToolCallingChatOptions.class, captured.get().getOptions());
        List<String> finalNames = options.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name()).sorted().toList();
        assertEquals(ArchiveRestrictedChatClientFactory.TOOL_NAMES.stream().sorted().toList(), finalNames);
        assertFalse(finalNames.contains("globalShell"));
        assertEquals("tool-model", options.getModel());
        assertEquals(0.4, options.getTemperature());
        assertTrue(options.getToolContext() == null || options.getToolContext().isEmpty());
    }

    @Test
    void finalPromptAddsRestrictedCallbacksWhenRawModelHasPlainOptions() {
        AtomicReference<Prompt> captured = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new AssertionError(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                captured.set(prompt);
                return Flux.just(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("ok")))));
            }
            @Override public ChatOptions getOptions() {
                return ChatOptions.builder().model("plain-model").temperature(0.25).build();
            }
        };
        var session = new ArchiveRestrictedChatClientFactory(model,
                mock(ArchiveMaintenanceService.class)).create(context("MANUAL"));
        assertEquals("ok", session.client().prompt("coordinate").stream().content()
                .collectList().block().getFirst());

        ToolCallingChatOptions options = assertInstanceOf(
                ToolCallingChatOptions.class, captured.get().getOptions());
        assertEquals("plain-model", options.getModel());
        assertEquals(0.25, options.getTemperature());
        assertTrue(options.getToolContext() == null || options.getToolContext().isEmpty());
        assertEquals(ArchiveRestrictedChatClientFactory.TOOL_NAMES.stream().sorted().toList(),
                options.getToolCallbacks().stream()
                        .map(callback -> callback.getToolDefinition().name()).sorted().toList());
    }

    @Test
    void sessionReceiptRequiresActualCallbackAndOnlyExplicitJobTerminalStateIsTerminal() {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        var tools = new ArchiveRestrictedChatClientFactory((ChatModel) prompt -> null, service)
                .create(context("MANUAL")).tools();
        assertEquals("UNCONFIRMED", tools.authoritativeReceipt().authority());
        assertFalse(tools.authoritativeReceipt().terminal());

        ArchiveJobDTO waiting = job("WAITING_SKILL", null);
        when(service.request(any(), any())).thenReturn(new ArchiveMaintenanceRequestResultDTO(
                waiting, null, "ARCHIVE_EXECUTION_DISABLED", "RESUME_WHEN_READY"));
        tools.requestArchiveMaintenance("platform-classics", "REVISE_WORK", null, null,
                null, "work-1", "src-1", "MANUAL");
        assertEquals("AUTHORITATIVE_NON_TERMINAL", tools.authoritativeReceipt().authority());
        assertFalse(tools.authoritativeReceipt().terminal());
        assertEquals("WAITING_SKILL", tools.authoritativeReceipt().state());

        ArchiveMaintenanceException unrelated = assertThrows(ArchiveMaintenanceException.class,
                () -> tools.getArchiveMaintenanceJob("job-other"));
        assertEquals("ARCHIVE_REQUEST_POLICY_VIOLATION", unrelated.code());
        verify(service, never()).getJob(any(), eq("job-other"));

        ArchiveJobDTO published = job("PUBLISHED", "publication-1");
        when(service.getJob(ACTOR, "job-1")).thenReturn(published);
        tools.getArchiveMaintenanceJob("job-1");
        assertEquals("AUTHORITATIVE_TERMINAL", tools.authoritativeReceipt().authority());
        assertTrue(tools.authoritativeReceipt().terminal());
        assertEquals("PUBLISHED", tools.authoritativeReceipt().state());
    }

    @Test
    void restrictedToolCanCreateOnlyTheServerConfirmedWaitingIntentWhenInputsAreMissing() {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ArchiveRequestContext waitingContext = new ArchiveRequestContext(ACTOR, "intent-wait",
                "SONGJIANG", "conversation-1:turn-2", null,
                new ArchiveConfirmedPolicyRef("policy-wait", "platform-classics", "ADD_WORK",
                        null, null, null, "MANUAL"));
        ArchiveJobDTO waiting = new ArchiveJobDTO("job-wait", null, "platform-classics",
                "WAITING_INPUT", "SOURCE_AND_WORK_INPUT_AND_ASSIGNEE_REQUIRED", "1",
                null, null, null, "MANUAL", "ADD_WORK", null, null, null,
                null, null, null);
        when(service.request(eq(waitingContext), any())).thenReturn(
                new ArchiveMaintenanceRequestResultDTO(waiting, null, waiting.waitReason(),
                        "RESOLVE_INPUT"));
        var tools = new ArchiveRestrictedChatClientFactory((ChatModel) prompt -> null, service)
                .create(waitingContext).tools();

        tools.requestArchiveMaintenance("platform-classics", "ADD_WORK", null, null,
                null, null, null, "MANUAL");

        var request = org.mockito.ArgumentCaptor.forClass(ArchiveMaintenanceRequest.class);
        verify(service).request(eq(waitingContext), request.capture());
        assertNull(request.getValue().newWork());
        assertNull(request.getValue().sourceId());
        assertEquals("WAITING_INPUT", tools.authoritativeReceipt().state());
        assertFalse(tools.authoritativeReceipt().terminal());
    }

    @Test
    void contextToolCannotInspectAnotherCollection() {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        var tools = new ArchiveRestrictedChatClientFactory((ChatModel) prompt -> null, service)
                .create(context("AUTO")).tools();
        ArchiveMaintenanceException denied = assertThrows(ArchiveMaintenanceException.class,
                () -> tools.getArchiveMaintenanceContext("other-collection"));
        assertEquals("ARCHIVE_REQUEST_POLICY_VIOLATION", denied.code());
        verifyNoInteractions(service);
    }


    private ArchiveJobDTO job(String state, String publicationId) {
        return new ArchiveJobDTO("job-1", "run-1", "platform-classics", state,
                "CLIENT_UPDATE_REQUIRED", "1", "appointment-1", "agent-wuyong",
                "DRAFT_ONLY", "MANUAL", "REVISE_WORK", "work-1", "tiny-book",
                "小书", "src-1", "draft-1", publicationId);
    }

    private ArchiveRequestContext context(String ceiling) {
        return new ArchiveRequestContext(ACTOR, "intent-1", "SONGJIANG", "conversation-1:turn-1",
                null, new ArchiveConfirmedPolicyRef("policy-1", "platform-classics", "REVISE_WORK",
                        null, "work-1", "src-1", ceiling));
    }
}
