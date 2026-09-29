package cn.jia.chat.api;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.ChatDeliberationService;
import cn.jia.chat.service.DisplayNameSource;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatBountyMediaControllerTest {
    private final ChatDeliberationService requests = mock(ChatDeliberationService.class);
    private final PersonalWorkspaceExecutionService executions = mock(PersonalWorkspaceExecutionService.class);
    private final HumanSenderIdentityResolver identities = mock(HumanSenderIdentityResolver.class);
    private final TenantScopeResolver tenants = mock(TenantScopeResolver.class);
    private final ChatBountyMediaController controller = new ChatBountyMediaController(requests,
            executions, identities, tenants);
    private final Authentication authentication = mock(Authentication.class);
    private final PersonalWorkspaceExecutionService.OwnerScope owner =
            new PersonalWorkspaceExecutionService.OwnerScope("0", "client", "owner");
    private static final byte[] PHOTO = "fake photo bytes".getBytes(StandardCharsets.UTF_8);

    private ChatDeliberationService.StepView step(String executionId) {
        return new ChatDeliberationService.StepView("step-1", "1", "task-1", "3", "agent-1",
                "EXECUTE", "SUCCEEDED", "4", "intent-1", executionId, "OUTPUT_COMMITTED");
    }
    private PersonalWorkspaceExecutionService.ExecutionView execution(String mode, String conversationId,
            String state) {
        return new PersonalWorkspaceExecutionService.ExecutionView("exec-1", "task-1", "run-1",
                conversationId, "agent-1", state, null, null, 1L, "image/png", List.of(),
                null, mode, null, null, null);
    }
    private String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private void ready() {
        when(tenants.resolve(authentication)).thenReturn("0");
        when(identities.resolve(nullable(EsContext.class))).thenReturn(new ServerResolvedSender("user",
                "Human", "owner", "client", DisplayNameSource.JIACN));
        when(requests.getRequest("0", "owner", "client", "request-1"))
                .thenReturn(new ChatDeliberationService.RequestView("request-1", "1", "10", "1",
                        "42", "RUNNING", "1", List.of(), List.of(step("exec-1"))));
        when(executions.get(owner, "exec-1"))
                .thenReturn(execution("CONVERSATION", "10", "OUTPUT_COMMITTED"));
        when(executions.readConversationOutput(owner, "task-1", "run-1", "out-1"))
                .thenReturn(new PersonalWorkspaceExecutionService.ConversationOutput("exec-1",
                        "out-1", "bird.png", "image/png", sha(PHOTO), PHOTO.length, PHOTO));
    }
    private org.springframework.http.ResponseEntity<byte[]> read(boolean download) {
        return controller.content("request-1", "step-1", "out-1", download, authentication);
    }
    @Test void committedVerifiedPhotoCanBePreviewedAndDownloadedWithoutAgentPaths() {
        ready();
        var inline = read(false);
        assertArrayEquals(PHOTO, inline.getBody());
        assertEquals(MediaType.IMAGE_PNG, inline.getHeaders().getContentType());
        assertEquals("nosniff", inline.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(inline.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("inline"));
        var download = read(true);
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, download.getHeaders().getContentType());
        assertTrue(download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
        assertFalse(download.getHeaders().toString().contains("bird.png"));
    }
    @Test void foreignRequestOrUnlinkedStepIsNeverReadFromAgentStorage() {
        ready();
        when(requests.getRequest("0", "owner", "client", "request-1"))
                .thenThrow(new ChatDeliberationException(
                        ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN, "Unavailable"));
        assertThrows(ChatDeliberationException.class, () -> read(false));
        verifyNoInteractions(executions);
        reset(requests);
        when(requests.getRequest("0", "owner", "client", "request-1"))
                .thenReturn(new ChatDeliberationService.RequestView("request-1", "1", "10", "1",
                        "42", "RUNNING", "1", List.of(), List.of(step(null))));
        assertThrows(ChatDeliberationException.class, () -> read(false));
        verifyNoInteractions(executions);
    }
    @Test void wrongConversationModeOrNotCommittedNeverReadsBytes() {
        ready();
        when(executions.get(owner, "exec-1")).thenReturn(execution("PRIVATE", "10", "OUTPUT_COMMITTED"));
        assertThrows(ChatDeliberationException.class, () -> read(false));
        when(executions.get(owner, "exec-1")).thenReturn(execution("CONVERSATION", "foreign", "OUTPUT_COMMITTED"));
        assertThrows(ChatDeliberationException.class, () -> read(false));
        when(executions.get(owner, "exec-1")).thenReturn(execution("CONVERSATION", "10", "QUEUED"));
        assertThrows(ChatDeliberationException.class, () -> read(false));
        verify(executions, never()).readConversationOutput(any(), any(), any(), any());
    }
    @Test void alteredOrUncommittedBytesCannotBeReturned() {
        ready();
        when(executions.readConversationOutput(owner, "task-1", "run-1", "out-1"))
                .thenReturn(new PersonalWorkspaceExecutionService.ConversationOutput("exec-1",
                        "out-1", "bird.png", "image/png", "0".repeat(64), PHOTO.length, PHOTO));
        assertThrows(ChatDeliberationException.class, () -> read(false));
        when(executions.readConversationOutput(owner, "task-1", "run-1", "out-1"))
                .thenThrow(new PersonalWorkspaceExecutionService.Failure(
                        PersonalWorkspaceExecutionService.Reason.NOT_FOUND));
        assertThrows(PersonalWorkspaceExecutionService.Failure.class, () -> read(false));
    }
    @Test void unsupportedMimeCanOnlyDownloadAsOpaqueBytes() {
        ready();
        when(executions.readConversationOutput(owner, "task-1", "run-1", "out-1"))
                .thenReturn(new PersonalWorkspaceExecutionService.ConversationOutput("exec-1",
                        "out-1", "unknown.svg", "image/svg+xml", sha(PHOTO), PHOTO.length, PHOTO));
        var result = read(false);
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, result.getHeaders().getContentType());
        assertTrue(result.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
    }
}
