package cn.jia.chat.api;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.ChatDeliberationService;
import cn.jia.chat.service.ChatBountyAssetProjector;
import cn.jia.chat.service.DisplayNameSource;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;
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
        return read(download, "GET", null, null);
    }
    private org.springframework.http.ResponseEntity<byte[]> read(boolean download, String method,
            String range, String ifRange) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        return controller.content("request-1", "step-1", "out-1", download,
                range, ifRange, request, authentication);
    }
    @Test void committedVerifiedPhotoCanBePreviewedAndDownloadedWithoutAgentPaths() {
        ready();
        var inline = read(false);
        assertArrayEquals(PHOTO, inline.getBody());
        assertEquals(MediaType.IMAGE_PNG, inline.getHeaders().getContentType());
        assertEquals("nosniff", inline.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(inline.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("inline"));
        var download = read(true);
        assertEquals(MediaType.IMAGE_PNG, download.getHeaders().getContentType());
        assertArrayEquals(PHOTO, download.getBody());
        assertEquals(inline.getHeaders().getETag(), download.getHeaders().getETag());
        assertTrue(download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
        assertFalse(download.getHeaders().toString().contains("bird.png"));
    }
    @Test void attachmentMimeMatchesVerifiedCatalogueForSupportedFormatsIncludingRangeAndHead() {
        ready();
        for (String mime : List.of("image/png", "image/jpeg", "image/webp", "image/gif",
                "audio/mpeg", "audio/wav", "audio/ogg", "audio/mp4", "audio/webm", "text/plain",
                "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation")) {
            when(executions.readConversationOutput(owner, "task-1", "run-1", "out-1"))
                    .thenReturn(new PersonalWorkspaceExecutionService.ConversationOutput("exec-1",
                            "out-1", "source-file", mime, sha(PHOTO), PHOTO.length, PHOTO));
            var full = read(true);
            assertEquals(MediaType.parseMediaType(mime), full.getHeaders().getContentType(), mime);
            assertArrayEquals(PHOTO, full.getBody());
            assertEquals("nosniff", full.getHeaders().getFirst("X-Content-Type-Options"));
            assertTrue(full.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
            var range = read(true, "GET", "bytes=2-5", null);
            assertEquals(HttpStatus.PARTIAL_CONTENT, range.getStatusCode(), mime);
            assertEquals(full.getHeaders().getContentType(), range.getHeaders().getContentType(), mime);
            assertArrayEquals(java.util.Arrays.copyOfRange(PHOTO, 2, 6), range.getBody());
            var head = read(true, "HEAD", null, null);
            assertEquals(full.getHeaders().getContentType(), head.getHeaders().getContentType(), mime);
            assertNull(head.getBody());
        }
    }
    @Test void modernAudioCatalogueProvidesAuthenticatedPreviewUrls() {
        ready();
        for (String mime : List.of("audio/mp4", "audio/webm")) {
            when(executions.listConversationOutputs(owner, "task-1", "run-1")).thenReturn(List.of(
                    new PersonalWorkspaceExecutionService.ConversationOutputInfo("exec-1", "out-1",
                            mime, sha(PHOTO), PHOTO.length)));
            var output = controller.outputs("request-1", "step-1", authentication).getBody().getData().getFirst();
            assertEquals("/chat/requests/request-1/steps/step-1/outputs/out-1", output.previewUrl());
        }
    }
    @Test void authenticatedAudioHeadAndRangeServeVerifiedBytesOnly() {
        ready();
        when(executions.readConversationOutput(owner, "task-1", "run-1", "out-1"))
                .thenReturn(new PersonalWorkspaceExecutionService.ConversationOutput("exec-1",
                        "out-1", "bird.mp3", "audio/mpeg", sha(PHOTO), PHOTO.length, PHOTO));
        String etag = "\"" + sha(PHOTO) + "\"";
        var head = read(false, "HEAD", null, null);
        assertEquals(HttpStatus.OK, head.getStatusCode());
        assertNull(head.getBody());
        assertEquals(PHOTO.length, head.getHeaders().getContentLength());
        assertEquals("bytes", head.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        var partial = read(false, "GET", "bytes=2-5", null);
        assertEquals(HttpStatus.PARTIAL_CONTENT, partial.getStatusCode());
        assertArrayEquals(java.util.Arrays.copyOfRange(PHOTO, 2, 6), partial.getBody());
        assertEquals("bytes 2-5/" + PHOTO.length, partial.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(4L, partial.getHeaders().getContentLength());
        assertEquals("no-store", partial.getHeaders().getCacheControl());
        assertEquals(etag, partial.getHeaders().getETag());
        var suffix = read(false, "HEAD", "bytes=-3", null);
        assertEquals(HttpStatus.PARTIAL_CONTENT, suffix.getStatusCode());
        assertEquals("bytes " + (PHOTO.length - 3) + "-" + (PHOTO.length - 1) + "/" + PHOTO.length,
                suffix.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(3L, suffix.getHeaders().getContentLength());
        assertNull(suffix.getBody());
        assertArrayEquals(java.util.Arrays.copyOfRange(PHOTO, 3, PHOTO.length),
                read(false, "GET", "bytes=3-", null).getBody());
        assertArrayEquals(PHOTO, read(false, "GET", "bytes=0-1", "\"stale\"").getBody());
        assertEquals(HttpStatus.OK, read(false, "GET", "bytes=0-1", "\"stale\"").getStatusCode());
    }
    @Test void malformedAndUnsatisfiableRangesFailClosedAfterOwnerValidation() {
        ready();
        for (String range : List.of("bytes=1000-", "bytes=5-2", "bytes=-0",
                "bytes=1-2,4-5", "bytes=9999999999999999999999-", "bits=0-3")) {
            var result = read(false, "GET", range, null);
            assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, result.getStatusCode(), range);
            assertEquals("bytes */" + PHOTO.length, result.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
            assertNull(result.getBody());
            assertEquals("no-store", result.getHeaders().getCacheControl());
        }
        when(requests.getRequest("0", "owner", "client", "request-1"))
                .thenThrow(new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                        "Unavailable"));
        clearInvocations(executions);
        assertThrows(ChatDeliberationException.class, () -> read(false, "GET", "bytes=1000-", null));
        assertThrows(ChatDeliberationException.class, () -> read(false, "HEAD", "bytes=0-2", null));
        verifyNoInteractions(executions);
    }
    @Test void catalogReturnsOnlyScopedVerifiedOutputsAndOwnerUrls() {
        ready();
        when(executions.listConversationOutputs(owner,"task-1","run-1")).thenReturn(List.of(
                new PersonalWorkspaceExecutionService.ConversationOutputInfo("exec-1",
                        "out-1","image/png",sha(PHOTO),PHOTO.length)));
        var response=controller.outputs("request-1","step-1",authentication);
        assertEquals("no-store",response.getHeaders().getCacheControl());
        var item=response.getBody().getData().getFirst();
        assertEquals("/chat/requests/request-1/steps/step-1/outputs/out-1",item.previewUrl());
        assertEquals(item.previewUrl()+"?download=true",item.downloadUrl());
        assertFalse(response.toString().contains("bird.png"));
        assertFalse(response.toString().contains("run-1"));
        verify(executions,never()).readConversationOutput(any(),any(),any(),any());
    }
    @Test void catalogRejectsMismatchedOrUnsupportedOutputAndNeverExposesForeignRequest() {
        ready();
        when(executions.listConversationOutputs(owner,"task-1","run-1")).thenReturn(List.of(
                new PersonalWorkspaceExecutionService.ConversationOutputInfo("different-execution",
                        "out-1","image/png",sha(PHOTO),PHOTO.length)));
        assertThrows(ChatDeliberationException.class,()->controller.outputs("request-1","step-1",authentication));
        when(executions.listConversationOutputs(owner,"task-1","run-1")).thenReturn(List.of(
                new PersonalWorkspaceExecutionService.ConversationOutputInfo("exec-1",
                        "out-1","image/svg+xml",sha(PHOTO),PHOTO.length)));
        assertNull(controller.outputs("request-1","step-1",authentication).getBody().getData().getFirst().previewUrl());
        when(requests.getRequest("0","owner","client","request-1"))
                .thenThrow(new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                        "Unavailable"));
        clearInvocations(executions);
        assertThrows(ChatDeliberationException.class,()->controller.outputs("request-1","step-1",authentication));
        verifyNoInteractions(executions);
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
    private ChatBountyAssetProjector.Asset asset(String ownerName, String requestId,
            long generation, String hash) {
        return new ChatBountyAssetProjector.Asset("ast_1", "0", ownerName, "client", "10",
                generation, requestId, "step-1", "exec-1", "run-1", "out-1", "100", "part-1",
                "image", "image/png", hash, PHOTO.length, 1);
    }
    @Test void catalogExposesOnlyAlreadyPersistedExactAssetAndNeverProjectsOnRead() {
        ready();
        var projector = mock(ChatBountyAssetProjector.class);
        controller.setBountyAssets(projector);
        when(executions.listConversationOutputs(owner, "task-1", "run-1")).thenReturn(List.of(
                new PersonalWorkspaceExecutionService.ConversationOutputInfo("exec-1", "out-1",
                        "image/png", sha(PHOTO), PHOTO.length)));
        assertNull(controller.outputs("request-1", "step-1", authentication)
                .getBody().getData().getFirst().assetRef());
        when(projector.findOutput(owner, "10", "request-1", "step-1", "exec-1", "run-1", "out-1"))
                .thenReturn(asset("owner", "request-1", 1, sha(PHOTO)));
        var output = controller.outputs("request-1", "step-1", authentication)
                .getBody().getData().getFirst();
        assertEquals(new ChatBountyMediaController.AssetRef("ast_1", "1"), output.assetRef());
        var wire = cn.jia.core.util.JsonUtil.toJson(output);
        assertTrue(wire.contains("\"assetRef\":{\"assetId\":\"ast_1\",\"revision\":\"1\"}"));
        verify(projector, never()).project(any());
        verifyNoInteractionsExceptLookup(projector);
    }
    private void verifyNoInteractionsExceptLookup(ChatBountyAssetProjector projector) {
        verify(projector, times(2)).findOutput(owner, "10", "request-1", "step-1", "exec-1", "run-1", "out-1");
        verifyNoMoreInteractions(projector);
    }
    @Test void catalogRejectsForeignStaleOrChangedPersistedAssets() {
        ready();
        var projector = mock(ChatBountyAssetProjector.class); controller.setBountyAssets(projector);
        when(executions.listConversationOutputs(owner, "task-1", "run-1")).thenReturn(List.of(
                new PersonalWorkspaceExecutionService.ConversationOutputInfo("exec-1", "out-1",
                        "image/png", sha(PHOTO), PHOTO.length)));
        for (var invalid : List.of(asset("foreign", "request-1", 1, sha(PHOTO)),
                asset("owner", "foreign-request", 1, sha(PHOTO)),
                asset("owner", "request-1", 2, sha(PHOTO)),
                asset("owner", "request-1", 1, "b".repeat(64)))) {
            when(projector.findOutput(owner, "10", "request-1", "step-1", "exec-1", "run-1", "out-1"))
                    .thenReturn(invalid);
            assertThrows(ChatDeliberationException.class,
                    () -> controller.outputs("request-1", "step-1", authentication));
        }
        verify(projector, never()).project(any());
    }

    @Test void catalogExposesServerReplacementOnlyWithinSameTaskAndConversationGeneration() {
        ready();
        var replacement=new PersonalWorkspaceExecutionService.OutputReplacement("prior-request","prior-step","prior-output","a".repeat(64));
        when(executions.listConversationOutputs(owner,"task-1","run-1")).thenReturn(List.of(
                new PersonalWorkspaceExecutionService.ConversationOutputInfo("exec-1","out-1","image/png",sha(PHOTO),PHOTO.length,replacement)));
        var parentStep=new ChatDeliberationService.StepView("prior-step","1","task-1","3","agent-1","EXECUTE","SUCCEEDED","4","prior-intent","prior-exec","OUTPUT_COMMITTED");
        when(requests.getRequest("0","owner","client","prior-request")).thenReturn(new ChatDeliberationService.RequestView(
                "prior-request","1","10","1","41","OUTPUT_COMMITTED","2",List.of(),List.of(parentStep)));
        var item=controller.outputs("request-1","step-1",authentication).getBody().getData().getFirst();
        assertEquals(new ChatBountyMediaController.OutputReplacement("prior-request","prior-step","prior-output","a".repeat(64)),item.replaces());
        for(String generation:List.of("2","3")) {
            when(requests.getRequest("0","owner","client","prior-request")).thenReturn(new ChatDeliberationService.RequestView(
                    "prior-request","1","10",generation,"41","OUTPUT_COMMITTED","2",List.of(),List.of(parentStep)));
            assertThrows(ChatDeliberationException.class,()->controller.outputs("request-1","step-1",authentication));
        }
        when(requests.getRequest("0","owner","client","prior-request")).thenThrow(new ChatDeliberationException(
                ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"unavailable"));
        assertThrows(ChatDeliberationException.class,()->controller.outputs("request-1","step-1",authentication));
        verify(executions,never()).createConversation(any(),any());
    }

}
