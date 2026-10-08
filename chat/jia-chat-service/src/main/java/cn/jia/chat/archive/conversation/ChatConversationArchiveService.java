package cn.jia.chat.archive.conversation;

import cn.jia.agent.entity.PersonalWorkspaceViews;
import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.PersonalWorkspaceService;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Operation;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Scope;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Source;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.TextSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static cn.jia.chat.archive.conversation.ChatConversationArchiveException.Reason;
import static cn.jia.chat.archive.conversation.ChatConversationArchiveStore.ASSET_REF;
import static cn.jia.chat.archive.conversation.ChatConversationArchiveStore.TEXT_SELECTION;

/**
 * Archives one exact persisted conversation source without holding Chat locks across Agent/storage calls.
 * Chat commits the immutable source snapshot before invoking the private workspace.
 */
@Service
@ConditionalOnProperty(prefix = "chat.conversation-archive", name = "enabled", havingValue = "true")
public final class ChatConversationArchiveService {
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("image/png", "png"), Map.entry("image/jpeg", "jpg"),
            Map.entry("image/webp", "webp"), Map.entry("image/gif", "gif"),
            Map.entry("audio/mpeg", "mp3"), Map.entry("audio/ogg", "ogg"),
            Map.entry("audio/wav", "wav"), Map.entry("audio/mp4", "m4a"),
            Map.entry("audio/webm", "webm"), Map.entry("text/plain", "txt"),
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"));
    private static final String SAFE_ASSET_FAILURE_MESSAGE = "Conversation asset was not saved";
    private static final String SAFE_TEXT_FAILURE_MESSAGE = "Conversation text was not saved";

    private final ChatConversationArchiveStore store;
    private final ChatConversationArchiveTransactions transactions;
    private final PersonalWorkspaceExecutionService executions;
    private final PersonalWorkspaceService workspace;

    public ChatConversationArchiveService(ChatConversationArchiveStore store,
            ChatConversationArchiveTransactions transactions,
            PersonalWorkspaceExecutionService executions, PersonalWorkspaceService workspace) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    public record AssetRef(String assetId, long revision) { }
    public record TextSelection(String messageId, int startCodePoint, int endCodePoint,
            String sha256) { }
    public record Command(String conversationId, String idempotencyKey, AssetRef assetRef,
            TextSelection textSelection) {
        public Command(String conversationId, String idempotencyKey, String assetId,
                long assetRevision) {
            this(conversationId, idempotencyKey, new AssetRef(assetId, assetRevision), null);
        }
    }
    public record TextSelectionReceipt(String messageId, String messageRevision,
            int startCodePoint, int endCodePoint, String sha256) { }
    public record ItemReceipt(String sourceKind, String assetId, String revision,
            TextSelectionReceipt textSelection, String state, String fileId, Integer version,
            String errorCode, String message) { }
    public record Receipt(String operationId, String state, String revision,
            List<ItemReceipt> items) { }

    public Receipt archive(Scope scope, Command command) {
        validateScope(scope);
        validateCommand(command);
        return command.assetRef() != null ? archiveAsset(scope, command) : archiveText(scope, command);
    }

    private Receipt archiveAsset(Scope scope, Command command) {
        AssetRef asset = command.assetRef();
        String requestSha = assetRequestSha(command);
        Operation claimed = transactions.required(() -> claimAsset(scope, command, requestSha));
        if ("SAVED".equals(claimed.state())) return receipt(claimed);

        Source source = store.findAuthorizedSource(scope, command.conversationId(),
                asset.assetId(), asset.revision());
        if (!validSource(scope, command, source)) {
            failPermanently(scope, command.conversationId(), claimed.operationId(), "SOURCE_UNAVAILABLE");
            throw unavailable();
        }

        PersonalWorkspaceExecutionService.ConversationOutput output;
        try {
            output = executions.readConversationOutput(executionScope(scope), source.taskId(),
                    source.runId(), source.outputId());
        } catch (PersonalWorkspaceExecutionService.Failure failure) {
            if (failure.getReason() == PersonalWorkspaceExecutionService.Reason.CAPABILITY_UNAVAILABLE
                    || failure.getReason() == PersonalWorkspaceExecutionService.Reason.STORAGE_UNAVAILABLE) {
                throw temporary(failure);
            }
            failPermanently(scope, command.conversationId(), claimed.operationId(), "SOURCE_UNAVAILABLE");
            throw unavailable();
        }
        if (!verifiedOutput(source, output)) {
            failPermanently(scope, command.conversationId(), claimed.operationId(), "SOURCE_MISMATCH");
            throw unavailable();
        }

        String extension = EXTENSIONS.get(source.contentMimeType());
        if (extension == null) {
            failPermanently(scope, command.conversationId(), claimed.operationId(), "UNSUPPORTED_MEDIA");
            throw new ChatConversationArchiveException(Reason.UNSUPPORTED,
                    "Conversation asset format is not supported by the workspace");
        }

        Operation saving = transactions.required(() -> markSaving(scope, command.conversationId(),
                claimed.operationId(), source.conversationGeneration()));
        if ("SAVED".equals(saving.state())) return receipt(saving);

        PersonalWorkspaceViews.UploadView upload;
        try {
            String filename = "conversation-asset-" + source.assetId() + "." + extension;
            upload = workspace.archiveConversationAsset(workspaceScope(scope),
                    new PersonalWorkspaceService.ConversationArchiveCommand(
                            new PersonalWorkspaceService.Idempotency(assetWorkspaceKey(scope, source)),
                            source.assetId(), source.assetRevision(), source.sha256(), filename,
                            filename, source.contentMimeType(), output.bytes()));
        } catch (PersonalWorkspaceException failure) {
            return handleWorkspaceFailure(scope, command, saving, failure);
        } catch (RuntimeException failure) {
            throw temporary(failure);
        }
        Confirmed confirmed = confirmed(source.sha256(), source.contentMimeType(),
                source.byteLength(), upload);
        Operation saved = transactions.required(() -> markSaved(scope, command.conversationId(),
                saving.operationId(), confirmed));
        return receipt(saved);
    }

    private Receipt archiveText(Scope scope, Command command) {
        String requestSha = textRequestSha(command);
        PreparedText prepared = transactions.required(() -> prepareText(scope, command, requestSha));
        Operation claimed = prepared.operation();
        if ("SAVED".equals(claimed.state())) return receipt(claimed);

        // Never hold an archive-operation row lock while acquiring the source-message lock.
        // New claims use source -> operation; retries recheck source authority in this separate
        // transaction before the operation CAS, preventing an operation -> source inversion.
        transactions.required(() -> {
            requireCurrentTextAuthority(scope, command, claimed);
            return null;
        });
        Operation saving = transactions.required(() -> markSaving(scope, command.conversationId(),
                claimed.operationId(), prepared.conversationGeneration()));
        if ("SAVED".equals(saving.state())) return receipt(saving);

        byte[] bytes = prepared.bytes();
        PersonalWorkspaceViews.UploadView upload;
        try {
            String filename = "conversation-text-" + saving.messageId() + "-"
                    + saving.selectionStartCodePoint() + "-" + saving.selectionEndCodePoint() + ".txt";
            upload = workspace.archiveConversationText(workspaceScope(scope),
                    new PersonalWorkspaceService.ConversationTextArchiveCommand(
                            new PersonalWorkspaceService.Idempotency(textWorkspaceKey(scope, saving)),
                            saving.sourceSnapshotKey(), saving.sourceSha256(), filename, filename, bytes));
        } catch (PersonalWorkspaceException failure) {
            return handleWorkspaceFailure(scope, command, saving, failure);
        } catch (RuntimeException failure) {
            throw temporary(failure);
        }
        Confirmed confirmed = confirmed(saving.sourceSha256(), "text/plain", bytes.length, upload);
        Operation saved = transactions.required(() -> markSaved(scope, command.conversationId(),
                saving.operationId(), confirmed));
        return receipt(saved);
    }

    private Receipt handleWorkspaceFailure(Scope scope, Command command, Operation saving,
            PersonalWorkspaceException failure) {
        if (failure.getReason() == PersonalWorkspaceException.Reason.PROCESSING) return receipt(saving);
        if (failure.getReason() == PersonalWorkspaceException.Reason.UNSUPPORTED
                || failure.getReason() == PersonalWorkspaceException.Reason.BAD_REQUEST) {
            failPermanently(scope, command.conversationId(), saving.operationId(), "UNSUPPORTED_MEDIA");
            throw new ChatConversationArchiveException(Reason.UNSUPPORTED,
                    "Conversation source format is not supported by the workspace");
        }
        if (failure.getReason() == PersonalWorkspaceException.Reason.IDEMPOTENCY_CONFLICT
                || failure.getReason() == PersonalWorkspaceException.Reason.STORAGE_CORRUPT) {
            failPermanently(scope, command.conversationId(), saving.operationId(), "WORKSPACE_CONFLICT");
            throw new ChatConversationArchiveException(Reason.IDEMPOTENCY_CONFLICT,
                    "Conversation archive operation conflicts with a prior save");
        }
        throw temporary(failure);
    }

    /** Strictly read-only: no execution, provider, workspace or storage call. */
    public Receipt get(Scope scope, String conversationId, String operationId) {
        validateScope(scope);
        exactId(conversationId, "conversationId", 100);
        exactId(operationId, "operationId", 64);
        Operation operation = store.findByOperationId(scope, conversationId, operationId);
        if (operation == null || !validPersisted(operation)) throw unavailable();
        return receipt(operation);
    }

    private Operation claimAsset(Scope scope, Command command, String requestSha) {
        Operation existing = existingByKey(scope, command, requestSha);
        if (existing != null) return existing;
        AssetRef asset = command.assetRef();
        long now = System.currentTimeMillis();
        Operation proposed = operation(command, scope, requestSha, now, null,
                asset.assetId(), asset.revision(), null, null, null, null, null, null, null);
        if (store.tryInsert(proposed)) return proposed;
        existing = existingByKey(scope, command, requestSha);
        if (existing != null) return existing;
        Operation bySource = store.lockBySource(scope, asset.assetId(), asset.revision());
        if (bySource != null && sameAssetSource(command, requestSha, bySource)) return bySource;
        throw persistence();
    }

    private PreparedText prepareText(Scope scope, Command command, String requestSha) {
        Operation existing = existingByKey(scope, command, requestSha);
        if (existing != null) {
            return "SAVED".equals(existing.state())
                    ? new PreparedText(existing, null) : preparedFromPersisted(existing);
        }

        TextMaterial material = resolveText(scope, command);
        long now = System.currentTimeMillis();
        TextSelection text = command.textSelection();
        Operation proposed = operation(command, scope, requestSha, now,
                material.conversationGeneration(), null, null, text.messageId(),
                material.messageRevision(), text.startCodePoint(), text.endCodePoint(),
                text.sha256(), material.snapshotKey(), material.sourceText());
        if (store.tryInsert(proposed)) return preparedFromPersisted(proposed);

        existing = existingByKey(scope, command, requestSha);
        if (existing != null) {
            return "SAVED".equals(existing.state())
                    ? new PreparedText(existing, null) : preparedFromPersisted(existing);
        }
        Operation bySnapshot = store.lockBySourceSnapshot(scope, material.snapshotKey());
        if (bySnapshot != null) {
            requireSameTextSnapshot(command, requestSha, material, bySnapshot);
            return "SAVED".equals(bySnapshot.state())
                    ? new PreparedText(bySnapshot, null) : preparedFromPersisted(bySnapshot);
        }
        throw persistence();
    }

    private Operation existingByKey(Scope scope, Command command, String requestSha) {
        Operation byKey = store.lockByIdempotencyKey(scope, command.idempotencyKey());
        if (byKey == null) return null;
        if (!sameRequest(byKey, command, requestSha)) {
            throw new ChatConversationArchiveException(Reason.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key is already bound to another conversation source");
        }
        if (!validPersisted(byKey)) throw persistence();
        return byKey;
    }

    private void requireCurrentTextAuthority(Scope scope, Command command, Operation operation) {
        long messageId;
        try {
            messageId = Long.parseLong(operation.messageId());
        } catch (RuntimeException malformed) {
            throw persistence();
        }
        TextSource current = store.findAuthorizedTextSourceForUpdate(
                scope, command.conversationId(), messageId);
        if (current == null || !operation.messageId().equals(current.messageId())
                || !operation.conversationId().equals(current.conversationId())
                || !Objects.equals(operation.conversationGeneration(), current.conversationGeneration())) {
            throw unavailable();
        }
    }

    private PreparedText preparedFromPersisted(Operation operation) {
        if (!validPersisted(operation) || !TEXT_SELECTION.equals(operation.sourceKind())
                || operation.sourceText() == null || operation.conversationGeneration() == null) {
            throw persistence();
        }
        byte[] bytes = strictUtf8(operation.sourceText());
        if (bytes.length == 0 || !operation.sourceSha256().equals(digest(bytes))
                || operation.sourceText().codePointCount(0, operation.sourceText().length())
                        != operation.selectionEndCodePoint() - operation.selectionStartCodePoint()) {
            throw persistence();
        }
        return new PreparedText(operation, bytes);
    }

    private TextMaterial resolveText(Scope scope, Command command) {
        TextSelection selection = command.textSelection();
        long messageId;
        try {
            messageId = Long.parseLong(selection.messageId());
        } catch (NumberFormatException invalid) {
            throw invalid("Invalid textSelection.messageId");
        }
        TextSource source = store.findAuthorizedTextSourceForUpdate(
                scope, command.conversationId(), messageId);
        if (source == null || source.messageRevision() < 1 || source.conversationGeneration() < 1
                || !selection.messageId().equals(source.messageId())
                || !command.conversationId().equals(source.conversationId())
                || source.content() == null || !validUnicodeScalar(source.content())) throw unavailable();
        int codePoints = source.content().codePointCount(0, source.content().length());
        if (selection.endCodePoint() > codePoints) {
            throw invalid("textSelection code-point range is invalid");
        }
        int start = source.content().offsetByCodePoints(0, selection.startCodePoint());
        int end = source.content().offsetByCodePoints(0, selection.endCodePoint());
        String selected = source.content().substring(start, end);
        byte[] bytes = strictUtf8(selected);
        if (bytes.length == 0 || !selection.sha256().equals(digest(bytes))) {
            throw invalid("textSelection sha256 does not match the persisted message");
        }
        String snapshotKey = digestParts(TEXT_SELECTION, scope.tenantId(), scope.ownerJiacn(),
                scope.clientId(), command.conversationId(),
                Long.toString(source.conversationGeneration()), source.messageId(),
                Long.toString(source.messageRevision()), Integer.toString(selection.startCodePoint()),
                Integer.toString(selection.endCodePoint()), selection.sha256());
        return new TextMaterial(source.messageRevision(), source.conversationGeneration(),
                snapshotKey, selected);
    }

    private static Operation operation(Command command, Scope scope, String requestSha, long now,
            Long conversationGeneration, String assetId, Long assetRevision, String messageId,
            Long messageRevision, Integer start, Integer end, String sourceSha,
            String snapshotKey, String sourceText) {
        return new Operation("arc_" + UUID.randomUUID().toString().replace("-", ""),
                scope.tenantId(), scope.ownerJiacn(), scope.clientId(), command.conversationId(),
                conversationGeneration, command.idempotencyKey(), requestSha,
                command.assetRef() == null ? TEXT_SELECTION : ASSET_REF,
                assetId, assetRevision, messageId, messageRevision, start, end, sourceSha,
                snapshotKey, sourceText, "PENDING", null, null, null, null, null, 1, now, now);
    }

    private void requireSameTextSnapshot(Command command, String requestSha, TextMaterial material,
            Operation operation) {
        TextSelection text = command.textSelection();
        if (!validPersisted(operation) || !TEXT_SELECTION.equals(operation.sourceKind())
                || !command.conversationId().equals(operation.conversationId())
                || !requestSha.equals(operation.requestSha256())
                || !material.snapshotKey().equals(operation.sourceSnapshotKey())
                || !text.messageId().equals(operation.messageId())
                || !Objects.equals(material.messageRevision(), operation.messageRevision())
                || !Objects.equals(material.conversationGeneration(), operation.conversationGeneration())
                || !Objects.equals(text.startCodePoint(), operation.selectionStartCodePoint())
                || !Objects.equals(text.endCodePoint(), operation.selectionEndCodePoint())
                || !text.sha256().equals(operation.sourceSha256())) throw persistence();
    }

    private static boolean sameAssetSource(Command command, String requestSha, Operation operation) {
        AssetRef asset = command.assetRef();
        return validPersisted(operation) && ASSET_REF.equals(operation.sourceKind())
                && command.conversationId().equals(operation.conversationId())
                && requestSha.equals(operation.requestSha256())
                && asset.assetId().equals(operation.assetId())
                && Objects.equals(asset.revision(), operation.assetRevision());
    }

    private Operation markSaving(Scope scope, String conversationId, String operationId, long generation) {
        Operation current = store.lockByOperationId(scope, conversationId, operationId);
        if (current == null || !validPersisted(current)) throw unavailable();
        if ("SAVED".equals(current.state())) return current;
        if (TEXT_SELECTION.equals(current.sourceKind())
                && !Objects.equals(current.conversationGeneration(), generation)) throw persistence();
        if (store.markSaving(scope, operationId, current.rowRevision(), generation,
                System.currentTimeMillis()) != 1) throw persistence();
        Operation updated = store.lockByOperationId(scope, conversationId, operationId);
        if (updated == null || !"SAVING".equals(updated.state())
                || !Objects.equals(updated.conversationGeneration(), generation)
                || updated.rowRevision() != current.rowRevision() + 1) throw persistence();
        return updated;
    }

    private Operation markSaved(Scope scope, String conversationId, String operationId, Confirmed confirmed) {
        Operation current = store.lockByOperationId(scope, conversationId, operationId);
        if (current == null || !validPersisted(current)) throw unavailable();
        if ("SAVED".equals(current.state())) {
            if (!confirmed.matches(current)) throw persistence();
            return current;
        }
        if (!"SAVING".equals(current.state()) || store.markSaved(scope, operationId,
                current.rowRevision(), confirmed.workspaceOperationId(), confirmed.fileId(),
                confirmed.version(), System.currentTimeMillis()) != 1) throw persistence();
        Operation updated = store.lockByOperationId(scope, conversationId, operationId);
        if (updated == null || !"SAVED".equals(updated.state()) || !confirmed.matches(updated)
                || updated.rowRevision() != current.rowRevision() + 1) throw persistence();
        return updated;
    }

    private void failPermanently(Scope scope, String conversationId, String operationId, String code) {
        transactions.required(() -> {
            Operation current = store.lockByOperationId(scope, conversationId, operationId);
            if (current != null && !"SAVED".equals(current.state())) {
                int updated = store.markPartialFailed(scope, operationId, current.rowRevision(), code,
                        TEXT_SELECTION.equals(current.sourceKind())
                                ? SAFE_TEXT_FAILURE_MESSAGE : SAFE_ASSET_FAILURE_MESSAGE,
                        System.currentTimeMillis());
                if (updated != 1) throw persistence();
            }
            return null;
        });
    }

    private Confirmed confirmed(String expectedSha, String expectedMime, long expectedLength,
            PersonalWorkspaceViews.UploadView upload) {
        if (upload == null || upload.operation() == null || upload.file() == null || upload.version() == null)
            throw temporary(null);
        var operation = upload.operation();
        var file = upload.file();
        var version = upload.version();
        if (!"COMMITTED".equals(operation.state()) || !exact(operation.operationId(), 100)
                || !exactWebId(operation.fileId(), 128) || operation.fileVersion() == null
                || operation.fileVersion() < 1 || !operation.fileId().equals(file.fileId())
                || operation.fileVersion() != version.version() || !file.fileId().equals(version.fileId())
                || !"ACTIVE".equals(file.state()) || version.version() < 1
                || !expectedSha.equals(version.sha256())
                || !expectedMime.equals(version.contentMimeType())
                || expectedLength != version.byteLength()) {
            throw new ChatConversationArchiveException(Reason.TEMPORARILY_UNAVAILABLE,
                    "Workspace did not confirm the archived file version");
        }
        return new Confirmed(operation.operationId(), file.fileId(), version.version());
    }

    private record Confirmed(String workspaceOperationId, String fileId, int version) {
        boolean matches(Operation operation) {
            return workspaceOperationId.equals(operation.workspaceOperationId())
                    && fileId.equals(operation.fileId()) && operation.fileVersion() != null
                    && version == operation.fileVersion();
        }
    }
    private record TextMaterial(long messageRevision, long conversationGeneration,
            String snapshotKey, String sourceText) { }
    private record PreparedText(Operation operation, byte[] bytes) {
        private PreparedText { bytes = bytes == null ? null : bytes.clone(); }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
        long conversationGeneration() {
            if (operation.conversationGeneration() == null) throw persistence();
            return operation.conversationGeneration();
        }
    }

    private static boolean validSource(Scope scope, Command command, Source source) {
        AssetRef asset = command.assetRef();
        return source != null && asset.assetId().equals(source.assetId())
                && asset.revision() == source.assetRevision()
                && command.conversationId().equals(source.conversationId())
                && source.conversationGeneration() >= 1 && exact(source.requestId(), 100)
                && source.requestRevision() >= 1 && exact(source.stepId(), 64)
                && exact(source.taskId(), 100) && exact(source.executionId(), 100)
                && exact(source.runId(), 100) && exact(source.outputId(), 100)
                && source.contentMimeType() != null
                && source.contentMimeType().matches("[a-z0-9.+-]+/[a-z0-9.+-]+")
                && source.sha256() != null && source.sha256().matches("[0-9a-f]{64}")
                && source.byteLength() > 0 && exact(scope.tenantId(), 50)
                && exact(scope.ownerJiacn(), 50) && exact(scope.clientId(), 50);
    }

    private static boolean verifiedOutput(Source source,
            PersonalWorkspaceExecutionService.ConversationOutput output) {
        if (output == null || !source.executionId().equals(output.executionId())
                || !source.outputId().equals(output.outputId())
                || !source.contentMimeType().equals(output.contentMimeType())
                || !source.sha256().equals(output.sha256()) || source.byteLength() != output.byteLength())
            return false;
        byte[] bytes = output.bytes();
        return bytes != null && bytes.length == source.byteLength()
                && source.sha256().equals(digest(bytes));
    }

    private static boolean sameRequest(Operation operation, Command command, String requestSha) {
        if (!command.conversationId().equals(operation.conversationId())
                || !requestSha.equals(operation.requestSha256())) return false;
        if (command.assetRef() != null) {
            return ASSET_REF.equals(operation.sourceKind())
                    && command.assetRef().assetId().equals(operation.assetId())
                    && Objects.equals(command.assetRef().revision(), operation.assetRevision());
        }
        TextSelection text = command.textSelection();
        return TEXT_SELECTION.equals(operation.sourceKind())
                && text.messageId().equals(operation.messageId())
                && Objects.equals(text.startCodePoint(), operation.selectionStartCodePoint())
                && Objects.equals(text.endCodePoint(), operation.selectionEndCodePoint())
                && text.sha256().equals(operation.sourceSha256());
    }

    private static boolean validPersisted(Operation operation) {
        if (operation == null || !exactWebId(operation.operationId(), 128)
                || !exact(operation.tenantId(), 50) || !exact(operation.ownerJiacn(), 50)
                || !exact(operation.clientId(), 50) || !exactWebId(operation.conversationId(), 100)
                || !exact(operation.idempotencyKey(), 160)
                || operation.requestSha256() == null || !operation.requestSha256().matches("[0-9a-f]{64}")
                || operation.rowRevision() < 1 || !List.of("PENDING", "SAVING", "SAVED", "PARTIAL_FAILED")
                        .contains(operation.state())) return false;
        if (ASSET_REF.equals(operation.sourceKind())) {
            if (!exactWebId(operation.assetId(), 64) || operation.assetRevision() == null
                    || operation.assetRevision() < 1 || operation.messageId() != null
                    || operation.messageRevision() != null || operation.selectionStartCodePoint() != null
                    || operation.selectionEndCodePoint() != null || operation.sourceSha256() != null
                    || operation.sourceSnapshotKey() != null || operation.sourceText() != null) return false;
        } else if (TEXT_SELECTION.equals(operation.sourceKind())) {
            if (operation.assetId() != null || operation.assetRevision() != null
                    || operation.conversationGeneration() == null || operation.conversationGeneration() < 1
                    || operation.messageId() == null || !operation.messageId().matches("[1-9][0-9]{0,18}")
                    || operation.messageRevision() == null || operation.messageRevision() < 1
                    || operation.selectionStartCodePoint() == null || operation.selectionStartCodePoint() < 0
                    || operation.selectionEndCodePoint() == null
                    || operation.selectionEndCodePoint() <= operation.selectionStartCodePoint()
                    || operation.sourceSha256() == null
                    || !operation.sourceSha256().matches("[0-9a-f]{64}")
                    || operation.sourceSnapshotKey() == null
                    || !operation.sourceSnapshotKey().matches("[0-9a-f]{64}")
                    || operation.sourceText() == null || operation.sourceText().isEmpty()
                    || !validUnicodeScalar(operation.sourceText())) return false;
            byte[] bytes = operation.sourceText().getBytes(StandardCharsets.UTF_8);
            if (!operation.sourceSha256().equals(digest(bytes))
                    || operation.sourceText().codePointCount(0, operation.sourceText().length())
                            != operation.selectionEndCodePoint() - operation.selectionStartCodePoint()) return false;
        } else return false;
        if ("SAVED".equals(operation.state())) {
            return operation.conversationGeneration() != null && operation.conversationGeneration() >= 1
                    && exact(operation.workspaceOperationId(), 100)
                    && exactWebId(operation.fileId(), 128)
                    && operation.fileVersion() != null && operation.fileVersion() >= 1;
        }
        return operation.workspaceOperationId() == null
                && operation.fileId() == null && operation.fileVersion() == null;
    }

    private static Receipt receipt(Operation operation) {
        if (!validPersisted(operation)) throw persistence();
        String state = operation.state().toLowerCase(java.util.Locale.ROOT);
        String itemState = switch (operation.state()) {
            case "PENDING" -> "pending";
            case "SAVING" -> "saving";
            case "SAVED" -> "saved";
            case "PARTIAL_FAILED" -> "failed";
            default -> throw persistence();
        };
        TextSelectionReceipt selection = TEXT_SELECTION.equals(operation.sourceKind())
                ? new TextSelectionReceipt(operation.messageId(),
                        Long.toString(operation.messageRevision()), operation.selectionStartCodePoint(),
                        operation.selectionEndCodePoint(), operation.sourceSha256()) : null;
        ItemReceipt item = new ItemReceipt(operation.sourceKind(), operation.assetId(),
                operation.assetRevision() == null ? null : Long.toString(operation.assetRevision()),
                selection, itemState, operation.fileId(), operation.fileVersion(),
                operation.errorCode(), operation.message());
        return new Receipt(operation.operationId(), state, Long.toString(operation.rowRevision()), List.of(item));
    }

    /** Legacy asset digest is intentionally byte-for-byte stable. */
    private static String assetRequestSha(Command command) {
        return digestParts(command.conversationId(), command.assetRef().assetId(),
                Long.toString(command.assetRef().revision()));
    }

    private static String textRequestSha(Command command) {
        TextSelection text = command.textSelection();
        return digestParts(command.conversationId(), TEXT_SELECTION, text.messageId(),
                Integer.toString(text.startCodePoint()), Integer.toString(text.endCodePoint()), text.sha256());
    }

    /** Legacy asset workspace idempotency is intentionally byte-for-byte stable. */
    private static String assetWorkspaceKey(Scope scope, Source source) {
        return "chat-archive-" + digestParts(scope.tenantId(), scope.ownerJiacn(), scope.clientId(),
                source.assetId(), Long.toString(source.assetRevision()));
    }

    private static String textWorkspaceKey(Scope scope, Operation operation) {
        return "chat-archive-" + digestParts(scope.tenantId(), scope.ownerJiacn(), scope.clientId(),
                TEXT_SELECTION, operation.sourceSnapshotKey());
    }

    private static PersonalWorkspaceExecutionService.OwnerScope executionScope(Scope scope) {
        return new PersonalWorkspaceExecutionService.OwnerScope(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn());
    }

    private static PersonalWorkspaceService.Scope workspaceScope(Scope scope) {
        return new PersonalWorkspaceService.Scope(scope.tenantId(), scope.clientId(), scope.ownerJiacn());
    }

    private static void validateScope(Scope scope) {
        if (scope == null || !exact(scope.tenantId(), 50) || !exact(scope.ownerJiacn(), 50)
                || !exact(scope.clientId(), 50) || "0".equals(scope.ownerJiacn())) throw unavailable();
    }

    private static void validateCommand(Command command) {
        if (command == null) throw invalid("Missing archive request");
        exactId(command.conversationId(), "conversationId", 100);
        if ((command.assetRef() == null) == (command.textSelection() == null))
            throw invalid("Exactly one archive source is required");
        if (command.assetRef() != null) {
            exactId(command.assetRef().assetId(), "assetId", 64);
            if (command.assetRef().revision() < 1) throw invalid("assetRef.revision must be positive");
        } else {
            TextSelection text = command.textSelection();
            if (text.messageId() == null || !text.messageId().matches("[1-9][0-9]{0,18}"))
                throw invalid("Invalid textSelection.messageId");
            try {
                if (Long.parseLong(text.messageId()) < 1) throw invalid("Invalid textSelection.messageId");
            } catch (NumberFormatException invalid) {
                throw invalid("Invalid textSelection.messageId");
            }
            if (text.startCodePoint() < 0 || text.endCodePoint() <= text.startCodePoint())
                throw invalid("Invalid textSelection code-point range");
            if (text.sha256() == null || !text.sha256().matches("[0-9a-f]{64}"))
                throw invalid("Invalid textSelection.sha256");
        }
        if (command.idempotencyKey() == null
                || !command.idempotencyKey().matches("[A-Za-z0-9._~:/+\\-]{8,160}"))
            throw invalid("Invalid Idempotency-Key");
    }

    private static void exactId(String value, String name, int max) {
        if (!exactWebId(value, max)) throw invalid("Invalid " + name);
    }

    private static boolean exactWebId(String value, int max) {
        return value != null && value.length() <= max
                && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0," + (max - 1) + "}");
    }

    private static boolean exact(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && validUnicodeScalar(value) && value.codePointCount(0, value.length()) <= max
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean validUnicodeScalar(String value) {
        if (value == null) return false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1)))
                    return false;
                index++;
            } else if (Character.isLowSurrogate(current)) return false;
        }
        return true;
    }

    private static byte[] strictUtf8(String value) {
        if (!validUnicodeScalar(value)) throw persistence();
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String digestParts(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ChatConversationArchiveException invalid(String message) {
        return new ChatConversationArchiveException(Reason.INVALID_REQUEST, message);
    }
    private static ChatConversationArchiveException unavailable() {
        return new ChatConversationArchiveException(Reason.NOT_FOUND_OR_FORBIDDEN,
                "Conversation archive source is unavailable");
    }
    private static ChatConversationArchiveException persistence() {
        return new ChatConversationArchiveException(Reason.PERSISTENCE_ERROR,
                "Conversation archive state is unavailable");
    }
    private static ChatConversationArchiveException temporary(Throwable cause) {
        return new ChatConversationArchiveException(Reason.TEMPORARILY_UNAVAILABLE,
                "Conversation archive is temporarily unavailable", cause);
    }
}
