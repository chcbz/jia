package cn.jia.chat.archive.conversation;

import cn.jia.agent.entity.PersonalWorkspaceViews;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.PersonalWorkspaceService;
import cn.jia.chat.archive.conversation.ChatConversationArchiveException.Reason;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Operation;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Scope;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Source;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatConversationArchiveServiceTest {
    private static final Scope OWNER = new Scope("0", "owner", "client");
    private static final byte[] BYTES = "verified image bytes".getBytes(StandardCharsets.UTF_8);
    private final FakeStore store = new FakeStore();
    private final PersonalWorkspaceExecutionService executions = mock(PersonalWorkspaceExecutionService.class);
    private final PersonalWorkspaceService workspace = mock(PersonalWorkspaceService.class);
    private final ChatConversationArchiveService service = new ChatConversationArchiveService(store,
            new ChatConversationArchiveTransactions() {
                @Override public <T> T required(java.util.function.Supplier<T> action) {
                    return action.get();
                }
            }, executions, workspace);

    private ChatConversationArchiveService.Command command(String key, String asset) {
        return new ChatConversationArchiveService.Command("42", key, asset, 1);
    }

    private Source source(String asset, String mime, String hash, long length) {
        return new Source(asset, 1, "42", 3, "request-1", 1, "step-1", "task-1",
                "execution-1", "run-1", "output-1", mime, hash, length);
    }

    private void ready(String asset) {
        ready(asset, "image/png");
    }

    private void ready(String asset, String mime) {
        String hash = sha(BYTES);
        store.sources.put(asset, source(asset, mime, hash, BYTES.length));
        when(executions.readConversationOutput(any(), eq("task-1"), eq("run-1"), eq("output-1")))
                .thenReturn(new PersonalWorkspaceExecutionService.ConversationOutput("execution-1",
                        "output-1", "/untrusted/runtime/path", mime, hash,
                        BYTES.length, BYTES));
        when(workspace.archiveConversationAsset(any(), any())).thenReturn(upload(hash, mime));
    }

    @Test
    void savesOnlyVerifiedBytesAndReturnsTheWebReceiptAllowlist() {
        ready("asset_1");
        var receipt = service.archive(OWNER, command("archive-key-0001", "asset_1"));
        assertEquals("saved", receipt.state());
        assertEquals("asset_1", receipt.items().getFirst().assetId());
        assertEquals("saved", receipt.items().getFirst().state());
        assertEquals("pws_file_1", receipt.items().getFirst().fileId());
        assertEquals(1, receipt.items().getFirst().version());
        assertTrue(Long.parseLong(receipt.revision()) >= 1);
        verify(workspace).archiveConversationAsset(eq(new PersonalWorkspaceService.Scope("0", "client", "owner")),
                argThat(command -> command.idempotency().key().startsWith("chat-archive-")
                        && command.idempotency().key().length() == 77
                        && command.assetId().equals("asset_1") && command.revision() == 1
                        && command.filename().equals("conversation-asset-asset_1.png")
                        && command.displayName().equals(command.filename())
                        && java.util.Arrays.equals(command.content(), BYTES)));
    }

    @Test
    void sameKeyReplayAndDifferentKeyForSameSourceNeverCreateAnotherFile() {
        ready("asset_1");
        var first = service.archive(OWNER, command("archive-key-0001", "asset_1"));
        var replay = service.archive(OWNER, command("archive-key-0001", "asset_1"));
        var otherKey = service.archive(OWNER, command("archive-key-0002", "asset_1"));
        assertEquals(first.operationId(), replay.operationId());
        assertEquals(first.operationId(), otherKey.operationId());
        verify(workspace, times(1)).archiveConversationAsset(any(), any());
        verify(executions, times(1)).readConversationOutput(any(), any(), any(), any());
    }

    @Test
    void sameKeyForAnotherSourceIsAConflictBeforeAgentOrWorkspace() {
        ready("asset_1");
        service.archive(OWNER, command("archive-key-0001", "asset_1"));
        clearInvocations(executions, workspace);
        var failure = assertThrows(ChatConversationArchiveException.class,
                () -> service.archive(OWNER, command("archive-key-0001", "asset_2")));
        assertEquals(Reason.IDEMPOTENCY_CONFLICT, failure.reason());
        verifyNoInteractions(executions, workspace);
    }

    @Test
    void missingForeignOrRevisionMismatchedSourceNeverReadsAgentStorage() {
        var failure = assertThrows(ChatConversationArchiveException.class,
                () -> service.archive(OWNER, command("archive-key-0001", "asset_1")));
        assertEquals(Reason.NOT_FOUND_OR_FORBIDDEN, failure.reason());
        verifyNoInteractions(executions, workspace);
        Operation operation = store.only();
        assertEquals("PARTIAL_FAILED", operation.state());
        assertEquals("SOURCE_UNAVAILABLE", operation.errorCode());
    }

    @Test
    void executionOutputIdentityMimeLengthAndBytesHashMustAllMatch() {
        ready("asset_1");
        when(executions.readConversationOutput(any(), any(), any(), any()))
                .thenReturn(new PersonalWorkspaceExecutionService.ConversationOutput("execution-1",
                        "output-1", "bird.png", "image/png", sha(BYTES), BYTES.length,
                        "altered".getBytes(StandardCharsets.UTF_8)));
        var failure = assertThrows(ChatConversationArchiveException.class,
                () -> service.archive(OWNER, command("archive-key-0001", "asset_1")));
        assertEquals(Reason.NOT_FOUND_OR_FORBIDDEN, failure.reason());
        verifyNoInteractions(workspace);
        assertEquals("SOURCE_MISMATCH", store.only().errorCode());
    }

    @Test
    void unsupportedPersistedMimeFailsClosedBeforeWorkspaceWrite() {
        String hash = sha(BYTES);
        store.sources.put("asset_1", source("asset_1", "text/html", hash, BYTES.length));
        when(executions.readConversationOutput(any(), any(), any(), any()))
                .thenReturn(new PersonalWorkspaceExecutionService.ConversationOutput("execution-1",
                        "output-1", "untrusted.html", "text/html", hash, BYTES.length, BYTES));
        var failure = assertThrows(ChatConversationArchiveException.class,
                () -> service.archive(OWNER, command("archive-key-0001", "asset_1")));
        assertEquals(Reason.UNSUPPORTED, failure.reason());
        verifyNoInteractions(workspace);
        assertEquals("UNSUPPORTED_MEDIA", store.only().errorCode());
    }

    @Test
    void archivesEachSupportedAudioAndRasterFormatWithItsExactWorkspaceExtension() {
        Map<String, String> formats = Map.of("image/webp", "webp", "image/gif", "gif",
                "audio/mpeg", "mp3", "audio/ogg", "ogg", "audio/wav", "wav",
                "audio/mp4", "m4a", "audio/webm", "webm");
        int index = 0;
        for (var format : formats.entrySet()) {
            String assetId = "asset_media_" + (++index);
            ready(assetId, format.getKey());
            var receipt = service.archive(OWNER, command("archive-key-media-" + index, assetId));
            assertEquals("saved", receipt.state());
            assertEquals(assetId, receipt.items().getFirst().assetId());
            verify(workspace).archiveConversationAsset(any(), argThat(command ->
                    assetId.equals(command.assetId()) && format.getKey().equals(command.contentMimeType())
                            && ("conversation-asset-" + assetId + "." + format.getValue()).equals(command.filename())
                            && java.util.Arrays.equals(command.content(), BYTES)));
        }
        verify(workspace, times(formats.size())).archiveConversationAsset(any(), any());
    }

    @Test
    void lostAckReplaysTheSameWorkspaceOperationKeyAndReconcilesTheOriginalFile() {
        ready("asset_1");
        store.failNextSavedUpdate = true;
        var first = assertThrows(ChatConversationArchiveException.class,
                () -> service.archive(OWNER, command("archive-key-0001", "asset_1")));
        assertEquals(Reason.PERSISTENCE_ERROR, first.reason());
        assertEquals("SAVING", store.only().state());
        var recovered = service.archive(OWNER, command("archive-key-0001", "asset_1"));
        assertEquals("saved", recovered.state());
        ArgumentCaptor<PersonalWorkspaceService.ConversationArchiveCommand> commands =
                ArgumentCaptor.forClass(PersonalWorkspaceService.ConversationArchiveCommand.class);
        verify(workspace, times(2)).archiveConversationAsset(any(), commands.capture());
        assertEquals(commands.getAllValues().get(0).idempotency().key(),
                commands.getAllValues().get(1).idempotency().key());
        assertEquals("pws_file_1", recovered.items().getFirst().fileId());
    }

    @Test
    void savedRequiresAConcreteCommittedWorkspaceOperationFileAndVersion() {
        ready("asset_1");
        var hash = sha(BYTES);
        when(workspace.archiveConversationAsset(any(), any())).thenReturn(new PersonalWorkspaceViews.UploadView(
                new PersonalWorkspaceViews.OperationView("workspace-op-1", "COMMITTED", null, null, null),
                null, null));
        var failure = assertThrows(ChatConversationArchiveException.class,
                () -> service.archive(OWNER, command("archive-key-0001", "asset_1")));
        assertEquals(Reason.TEMPORARILY_UNAVAILABLE, failure.reason());
        assertEquals("SAVING", store.only().state());
        assertNull(store.only().fileId());
    }

    @Test
    void getIsReadOnlyAndOwnerConversationScoped() {
        ready("asset_1");
        var saved = service.archive(OWNER, command("archive-key-0001", "asset_1"));
        clearInvocations(executions, workspace);
        assertEquals(saved, service.get(OWNER, "42", saved.operationId()));
        assertThrows(ChatConversationArchiveException.class,
                () -> service.get(new Scope("0", "foreign", "client"), "42", saved.operationId()));
        assertThrows(ChatConversationArchiveException.class,
                () -> service.get(OWNER, "43", saved.operationId()));
        verifyNoInteractions(executions, workspace);
    }

    private PersonalWorkspaceViews.UploadView upload(String hash) {
        return upload(hash, "image/png");
    }

    private PersonalWorkspaceViews.UploadView upload(String hash, String mime) {
        return new PersonalWorkspaceViews.UploadView(
                new PersonalWorkspaceViews.OperationView("workspace-op-1", "COMMITTED", "pws_file_1", 1, null),
                new PersonalWorkspaceViews.FileView("pws_file_1", "UPLOAD", "AGENT_DELIVERY",
                        "conversation asset", mime.startsWith("audio/") ? "AUDIO" : "IMAGE", "ACTIVE", 1, 1, 1,
                        new PersonalWorkspaceViews.Capabilities("AVAILABLE", "AVAILABLE", "UNVERIFIED",
                                "AVAILABLE", "AVAILABLE")),
                new PersonalWorkspaceViews.VersionView("pws_file_1", 1, "asset", mime,
                        BYTES.length, hash, 1, "READY"));
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class FakeStore implements ChatConversationArchiveStore {
        private final Map<String, Operation> byOperation = new LinkedHashMap<>();
        private final Map<String, Source> sources = new LinkedHashMap<>();
        private boolean failNextSavedUpdate;

        @Override public boolean tryInsert(Operation operation) {
            boolean key = byOperation.values().stream().anyMatch(row -> scope(row, operation)
                    && row.idempotencyKey().equals(operation.idempotencyKey()));
            boolean source = byOperation.values().stream().anyMatch(row -> scope(row, operation)
                    && row.assetId().equals(operation.assetId())
                    && row.assetRevision() == operation.assetRevision());
            if (key || source) return false;
            byOperation.put(operation.operationId(), operation);
            return true;
        }
        @Override public Operation lockByIdempotencyKey(Scope scope, String key) {
            return byOperation.values().stream().filter(row -> scope(scope, row)
                    && row.idempotencyKey().equals(key)).findFirst().orElse(null);
        }
        @Override public Operation lockBySource(Scope scope, String asset, long revision) {
            return byOperation.values().stream().filter(row -> scope(scope, row)
                    && row.assetId().equals(asset) && row.assetRevision() == revision).findFirst().orElse(null);
        }
        @Override public Operation lockByOperationId(Scope scope, String conversation, String operation) {
            return findByOperationId(scope, conversation, operation);
        }
        @Override public Operation findByOperationId(Scope scope, String conversation, String operation) {
            Operation row = byOperation.get(operation);
            return row != null && scope(scope, row) && conversation.equals(row.conversationId()) ? row : null;
        }
        @Override public Source findAuthorizedSource(Scope scope, String conversation, String asset, long revision) {
            if (!OWNER.equals(scope)) return null;
            Source row = sources.get(asset);
            return row != null && conversation.equals(row.conversationId()) && revision == row.assetRevision()
                    ? row : null;
        }
        @Override public int markSaving(Scope scope, String id, long expected, long generation, long now) {
            Operation row = byOperation.get(id);
            if (row == null || !scope(scope, row) || row.rowRevision() != expected || "SAVED".equals(row.state()))
                return 0;
            byOperation.put(id, copy(row, generation, "SAVING", null, null, null, null, null, now));
            return 1;
        }
        @Override public int markSaved(Scope scope, String id, long expected, String workspaceOperation,
                String file, int version, long now) {
            if (failNextSavedUpdate) { failNextSavedUpdate = false; return 0; }
            Operation row = byOperation.get(id);
            if (row == null || !scope(scope, row) || row.rowRevision() != expected
                    || !"SAVING".equals(row.state())) return 0;
            byOperation.put(id, copy(row, row.conversationGeneration(), "SAVED", workspaceOperation,
                    file, version, null, null, now));
            return 1;
        }
        @Override public int markPartialFailed(Scope scope, String id, long expected, String code,
                String message, long now) {
            Operation row = byOperation.get(id);
            if (row == null || !scope(scope, row) || row.rowRevision() != expected
                    || "SAVED".equals(row.state())) return 0;
            byOperation.put(id, copy(row, row.conversationGeneration(), "PARTIAL_FAILED", null,
                    null, null, code, message, now));
            return 1;
        }
        Operation only() { return byOperation.values().stream().findFirst().orElseThrow(); }
        private static boolean scope(Scope scope, Operation row) {
            return scope.tenantId().equals(row.tenantId()) && scope.ownerJiacn().equals(row.ownerJiacn())
                    && scope.clientId().equals(row.clientId());
        }
        private static boolean scope(Operation left, Operation right) {
            return left.tenantId().equals(right.tenantId()) && left.ownerJiacn().equals(right.ownerJiacn())
                    && left.clientId().equals(right.clientId());
        }
        private static Operation copy(Operation row, Long generation, String state,
                String workspaceOperation, String file, Integer version, String code, String message, long now) {
            return new Operation(row.operationId(), row.tenantId(), row.ownerJiacn(), row.clientId(),
                    row.conversationId(), generation, row.idempotencyKey(), row.requestSha256(),
                    row.assetId(), row.assetRevision(), state, workspaceOperation, file, version,
                    code, message, row.rowRevision() + 1, row.createdAt(), now);
        }
    }
}
