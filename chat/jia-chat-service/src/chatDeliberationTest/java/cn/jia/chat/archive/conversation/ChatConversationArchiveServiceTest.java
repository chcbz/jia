package cn.jia.chat.archive.conversation;

import cn.jia.agent.entity.PersonalWorkspaceViews;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.PersonalWorkspaceService;
import cn.jia.chat.archive.conversation.ChatConversationArchiveException.Reason;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Operation;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Scope;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Source;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.TextSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatConversationArchiveServiceTest {
    private static final Scope OWNER = new Scope("0", "owner", "client");
    private static final byte[] ASSET_BYTES = "verified image bytes".getBytes(StandardCharsets.UTF_8);
    private static final String BIRD = "画一只鸟";
    private static final byte[] BIRD_BYTES = BIRD.getBytes(StandardCharsets.UTF_8);
    private final FakeStore store = new FakeStore();
    private final PersonalWorkspaceExecutionService executions = mock(PersonalWorkspaceExecutionService.class);
    private final PersonalWorkspaceService workspace = mock(PersonalWorkspaceService.class);
    private final ChatConversationArchiveService service = new ChatConversationArchiveService(store,
            new ChatConversationArchiveTransactions() {
                @Override public <T> T required(java.util.function.Supplier<T> action) {
                    return action.get();
                }
            }, executions, workspace);

    private ChatConversationArchiveService.Command asset(String key, String asset) {
        return new ChatConversationArchiveService.Command("42", key, asset, 1);
    }

    private ChatConversationArchiveService.Command text(String key, String message,
            int start, int end, String selected) {
        return new ChatConversationArchiveService.Command("42", key, null,
                new ChatConversationArchiveService.TextSelection(
                        message, start, end, sha(selected.getBytes(StandardCharsets.UTF_8))));
    }

    private void readyAsset(String asset) {
        String hash = sha(ASSET_BYTES);
        store.sources.put(asset, new Source(asset, 1, "42", 3, "request-1", 1,
                "step-1", "task-1", "execution-1", "run-1", "output-1",
                "image/png", hash, ASSET_BYTES.length));
        when(executions.readConversationOutput(any(), eq("task-1"), eq("run-1"), eq("output-1")))
                .thenReturn(new PersonalWorkspaceExecutionService.ConversationOutput("execution-1",
                        "output-1", "/untrusted/runtime/path", "image/png", hash,
                        ASSET_BYTES.length, ASSET_BYTES));
        when(workspace.archiveConversationAsset(any(), any()))
                .thenReturn(upload(hash, "image/png", ASSET_BYTES.length));
    }

    private void readyText(String messageId, long revision, String content) {
        store.textSources.put(messageId, new TextSource(messageId, revision, "42", 3, content));
        when(workspace.archiveConversationText(any(), any())).thenAnswer(invocation -> {
            PersonalWorkspaceService.ConversationTextArchiveCommand command = invocation.getArgument(1);
            return upload(command.sha256(), "text/plain", command.content().length);
        });
    }

    @Test
    void assetContractRetainsLegacyWorkspaceKeyAndReceipt() {
        readyAsset("asset_1");
        var receipt = service.archive(OWNER, asset("archive-key-0001", "asset_1"));
        assertEquals("saved", receipt.state());
        assertEquals("assetRef", receipt.items().getFirst().sourceKind());
        assertEquals("asset_1", receipt.items().getFirst().assetId());
        assertNull(receipt.items().getFirst().textSelection());
        verify(workspace).archiveConversationAsset(eq(new PersonalWorkspaceService.Scope("0", "client", "owner")),
                argThat(command -> command.idempotency().key().startsWith("chat-archive-")
                        && command.idempotency().key().length() == 77
                        && command.assetId().equals("asset_1") && command.revision() == 1
                        && java.util.Arrays.equals(command.content(), ASSET_BYTES)));
    }

    @Test
    void exactPersistedBirdSelectionIsSavedAsUtf8TextPlainAndReceiptFreezesRevision() {
        readyText("1675335", 1700000000000L, BIRD);
        var receipt = service.archive(OWNER, text("archive-text-0001", "1675335", 0, 4, BIRD));
        assertEquals("saved", receipt.state());
        var item = receipt.items().getFirst();
        assertEquals("textSelection", item.sourceKind());
        assertEquals("1675335", item.textSelection().messageId());
        assertEquals("1700000000000", item.textSelection().messageRevision());
        assertEquals(0, item.textSelection().startCodePoint());
        assertEquals(4, item.textSelection().endCodePoint());
        assertEquals(sha(BIRD_BYTES), item.textSelection().sha256());

        ArgumentCaptor<PersonalWorkspaceService.ConversationTextArchiveCommand> command =
                ArgumentCaptor.forClass(PersonalWorkspaceService.ConversationTextArchiveCommand.class);
        verify(workspace).archiveConversationText(
                eq(new PersonalWorkspaceService.Scope("0", "client", "owner")), command.capture());
        assertArrayEquals(BIRD_BYTES, command.getValue().content());
        assertEquals(sha(BIRD_BYTES), command.getValue().sha256());
        assertEquals("conversation-text-1675335-0-4.txt", command.getValue().filename());
        assertNotNull(store.only().sourceText());
        assertEquals(BIRD, store.only().sourceText());
    }

    @Test
    void wrongSelectionHashFailsBeforeOperationOrWorkspace() {
        readyText("1675335", 7, BIRD);
        var command = new ChatConversationArchiveService.Command("42", "archive-text-0001", null,
                new ChatConversationArchiveService.TextSelection("1675335", 0, 4, "0".repeat(64)));
        var failure = assertThrows(ChatConversationArchiveException.class,
                () -> service.archive(OWNER, command));
        assertEquals(Reason.INVALID_REQUEST, failure.reason());
        assertTrue(store.byOperation.isEmpty());
        verifyNoInteractions(workspace, executions);
    }

    @Test
    void supplementaryCharacterUsesUnicodeCodePointBoundariesAndRejectsMalformedSurrogates() {
        String content = "A🐦B";
        readyText("9", 8, content);
        service.archive(OWNER, text("archive-text-bird", "9", 1, 2, "🐦"));
        verify(workspace).archiveConversationText(any(), argThat(command ->
                java.util.Arrays.equals("🐦".getBytes(StandardCharsets.UTF_8), command.content())));

        readyText("10", 8, "A\uD83DB");
        var failure = assertThrows(ChatConversationArchiveException.class,
                () -> service.archive(OWNER, text("archive-text-bad-surrogate", "10", 1, 2, "B")));
        assertEquals(Reason.NOT_FOUND_OR_FORBIDDEN, failure.reason());
    }

    @Test
    void unknownWorkspaceResponseRetriesThePersistedSelectionEvenAfterMessageChanges() {
        readyText("1675335", 7, BIRD);
        store.failNextSavedUpdate = true;
        var first = assertThrows(ChatConversationArchiveException.class,
                () -> service.archive(OWNER, text("archive-text-0001", "1675335", 0, 4, BIRD)));
        assertEquals(Reason.PERSISTENCE_ERROR, first.reason());
        assertEquals("SAVING", store.only().state());

        store.textSources.put("1675335", new TextSource("1675335", 8, "42", 3, "消息已改变"));
        var recovered = service.archive(OWNER,
                text("archive-text-0001", "1675335", 0, 4, BIRD));
        assertEquals("saved", recovered.state());
        ArgumentCaptor<PersonalWorkspaceService.ConversationTextArchiveCommand> commands =
                ArgumentCaptor.forClass(PersonalWorkspaceService.ConversationTextArchiveCommand.class);
        verify(workspace, times(2)).archiveConversationText(any(), commands.capture());
        assertArrayEquals(BIRD_BYTES, commands.getAllValues().get(0).content());
        assertArrayEquals(BIRD_BYTES, commands.getAllValues().get(1).content());
        assertEquals(commands.getAllValues().get(0).idempotency().key(),
                commands.getAllValues().get(1).idempotency().key());
    }

    @Test
    void retryNeverHoldsOperationAndMessageLocksInTheSameTransaction() {
        readyText("1675335", 7, BIRD);
        ChatConversationArchiveService tracked = new ChatConversationArchiveService(store,
                new TrackingTransactions(store.events), executions, workspace);
        store.failNextSavedUpdate = true;
        assertThrows(ChatConversationArchiveException.class,
                () -> tracked.archive(OWNER,
                        text("archive-text-lock-order", "1675335", 0, 4, BIRD)));

        store.events.clear();
        assertEquals("saved", tracked.archive(OWNER,
                text("archive-text-lock-order", "1675335", 0, 4, BIRD)).state());

        List<String> transaction = new ArrayList<>();
        for (String event : store.events) {
            if ("tx:start".equals(event)) transaction.clear();
            else if ("tx:end".equals(event)) {
                assertFalse(transaction.contains("operation:key")
                                && transaction.contains("source:message"),
                        "retry must not acquire operation then source in one transaction: "
                                + store.events);
            } else transaction.add(event);
        }
        assertEquals(List.of("tx:start", "operation:key", "tx:end",
                        "tx:start", "source:message", "tx:end"),
                store.events.subList(0, 6), store.events.toString());
    }

    @Test
    void sourceRevocationStopsUnknownResponseRetryBeforeAnotherWorkspaceCall() {
        readyText("1675335", 7, BIRD);
        store.failNextSavedUpdate = true;
        assertThrows(ChatConversationArchiveException.class,
                () -> service.archive(OWNER, text("archive-text-0001", "1675335", 0, 4, BIRD)));
        store.textSources.clear();
        var failure = assertThrows(ChatConversationArchiveException.class,
                () -> service.archive(OWNER, text("archive-text-0001", "1675335", 0, 4, BIRD)));
        assertEquals(Reason.NOT_FOUND_OR_FORBIDDEN, failure.reason());
        verify(workspace, times(1)).archiveConversationText(any(), any());
    }

    @Test
    void sameKeyDifferentPayloadConflictsBeforeSourceOrWorkspace() {
        readyText("1675335", 7, BIRD + "。 ");
        service.archive(OWNER, text("archive-text-0001", "1675335", 0, 4, BIRD));
        clearInvocations(workspace);
        var failure = assertThrows(ChatConversationArchiveException.class,
                () -> service.archive(OWNER, text("archive-text-0001", "1675335", 0, 2, "画一")));
        assertEquals(Reason.IDEMPOTENCY_CONFLICT, failure.reason());
        verifyNoInteractions(workspace);
    }

    @Test
    void differentKeyForSameFrozenSnapshotReturnsWinnerAndDoesNotCreateAnotherFile() {
        readyText("1675335", 7, BIRD);
        var first = service.archive(OWNER, text("archive-text-0001", "1675335", 0, 4, BIRD));
        var duplicate = service.archive(OWNER, text("archive-text-0002", "1675335", 0, 4, BIRD));
        assertEquals(first.operationId(), duplicate.operationId());
        verify(workspace, times(1)).archiveConversationText(any(), any());
    }

    @Test
    void crossOwnerAndConversationCannotResolveMessageOrOperation() {
        readyText("1675335", 7, BIRD);
        assertEquals(Reason.NOT_FOUND_OR_FORBIDDEN, assertThrows(ChatConversationArchiveException.class,
                () -> service.archive(new Scope("0", "foreign", "client"),
                        text("archive-text-0001", "1675335", 0, 4, BIRD))).reason());
        var saved = service.archive(OWNER, text("archive-text-0001", "1675335", 0, 4, BIRD));
        assertThrows(ChatConversationArchiveException.class,
                () -> service.get(OWNER, "43", saved.operationId()));
        assertThrows(ChatConversationArchiveException.class,
                () -> service.get(new Scope("0", "foreign", "client"), "42", saved.operationId()));
    }

    @Test
    void assetSameKeyDifferentPayloadStillConflicts() {
        readyAsset("asset_1");
        service.archive(OWNER, asset("archive-key-0001", "asset_1"));
        var failure = assertThrows(ChatConversationArchiveException.class,
                () -> service.archive(OWNER, asset("archive-key-0001", "asset_2")));
        assertEquals(Reason.IDEMPOTENCY_CONFLICT, failure.reason());
    }

    private static PersonalWorkspaceViews.UploadView upload(String hash, String mime, long length) {
        return new PersonalWorkspaceViews.UploadView(
                new PersonalWorkspaceViews.OperationView("workspace-op-1", "COMMITTED", "pws_file_1", 1, null),
                new PersonalWorkspaceViews.FileView("pws_file_1", "UPLOAD", "AGENT_DELIVERY",
                        "conversation source", "text/plain".equals(mime) ? "TEXT" : "IMAGE",
                        "ACTIVE", 1, 1, 1,
                        new PersonalWorkspaceViews.Capabilities("AVAILABLE", "AVAILABLE", "UNVERIFIED",
                                "AVAILABLE", "AVAILABLE")),
                new PersonalWorkspaceViews.VersionView("pws_file_1", 1, "source", mime,
                        length, hash, 1, "READY"));
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class TrackingTransactions implements ChatConversationArchiveTransactions {
        private final List<String> events;

        private TrackingTransactions(List<String> events) {
            this.events = events;
        }

        @Override public <T> T required(java.util.function.Supplier<T> action) {
            events.add("tx:start");
            try {
                return action.get();
            } finally {
                events.add("tx:end");
            }
        }
    }

    private static final class FakeStore implements ChatConversationArchiveStore {
        private final List<String> events = new ArrayList<>();
        private final Map<String, Operation> byOperation = new LinkedHashMap<>();
        private final Map<String, Source> sources = new LinkedHashMap<>();
        private final Map<String, TextSource> textSources = new LinkedHashMap<>();
        private boolean failNextSavedUpdate;

        @Override public boolean tryInsert(Operation operation) {
            boolean key = byOperation.values().stream().anyMatch(row -> scope(row, operation)
                    && row.idempotencyKey().equals(operation.idempotencyKey()));
            boolean asset = operation.assetId() != null && byOperation.values().stream().anyMatch(row ->
                    scope(row, operation) && operation.assetId().equals(row.assetId())
                            && operation.assetRevision().equals(row.assetRevision()));
            boolean snapshot = operation.sourceSnapshotKey() != null && byOperation.values().stream().anyMatch(row ->
                    scope(row, operation) && operation.sourceSnapshotKey().equals(row.sourceSnapshotKey()));
            if (key || asset || snapshot) return false;
            byOperation.put(operation.operationId(), operation);
            return true;
        }
        @Override public Operation lockByIdempotencyKey(Scope scope, String key) {
            events.add("operation:key");
            return byOperation.values().stream().filter(row -> scope(scope, row)
                    && row.idempotencyKey().equals(key)).findFirst().orElse(null);
        }
        @Override public Operation lockBySource(Scope scope, String asset, long revision) {
            return byOperation.values().stream().filter(row -> scope(scope, row)
                    && asset.equals(row.assetId()) && row.assetRevision() != null
                    && row.assetRevision() == revision).findFirst().orElse(null);
        }
        @Override public Operation lockBySourceSnapshot(Scope scope, String snapshot) {
            return byOperation.values().stream().filter(row -> scope(scope, row)
                    && snapshot.equals(row.sourceSnapshotKey())).findFirst().orElse(null);
        }
        @Override public Operation lockByOperationId(Scope scope, String conversation, String operation) {
            events.add("operation:id");
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
        @Override public TextSource findAuthorizedTextSourceForUpdate(
                Scope scope, String conversation, long messageId) {
            events.add("source:message");
            if (!OWNER.equals(scope)) return null;
            TextSource row = textSources.get(Long.toString(messageId));
            return row != null && conversation.equals(row.conversationId()) ? row : null;
        }
        @Override public int markSaving(Scope scope, String id, long expected, long generation, long now) {
            Operation row = byOperation.get(id);
            if (row == null || !scope(scope, row) || row.rowRevision() != expected
                    || "SAVED".equals(row.state())) return 0;
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
                    row.sourceKind(), row.assetId(), row.assetRevision(), row.messageId(),
                    row.messageRevision(), row.selectionStartCodePoint(), row.selectionEndCodePoint(),
                    row.sourceSha256(), row.sourceSnapshotKey(), row.sourceText(), state,
                    workspaceOperation, file, version, code, message, row.rowRevision() + 1,
                    row.createdAt(), now);
        }
    }
}
