package cn.jia.chat.archive.conversation;

import cn.jia.agent.entity.PersonalWorkspaceViews;
import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.PersonalWorkspaceService;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Operation;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Scope;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Source;
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

/**
 * Archives one exact persisted conversation asset without holding Chat locks across Agent/storage calls.
 * No GET path invokes this workflow; status projection is a direct Chat database read.
 */
@Service
@ConditionalOnProperty(prefix = "chat.conversation-archive", name = "enabled", havingValue = "true")
public final class ChatConversationArchiveService {
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "text/plain", "txt",
            "application/pdf", "pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
    private static final String SAFE_FAILURE_MESSAGE = "Conversation asset was not saved";

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

    public record Command(String conversationId, String idempotencyKey, String assetId,
            long assetRevision) { }
    public record ItemReceipt(String assetId, String revision, String state, String fileId,
            Integer version, String errorCode, String message) { }
    public record Receipt(String operationId, String state, String revision,
            List<ItemReceipt> items) { }

    public Receipt archive(Scope scope, Command command) {
        validateScope(scope);
        validateCommand(command);
        String requestSha = digestParts(command.conversationId(), command.assetId(),
                Long.toString(command.assetRevision()));
        Operation claimed = transactions.required(() -> claim(scope, command, requestSha));
        if ("SAVED".equals(claimed.state())) return receipt(claimed);

        Source source = store.findAuthorizedSource(scope, command.conversationId(),
                command.assetId(), command.assetRevision());
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
                            new PersonalWorkspaceService.Idempotency(workspaceKey(scope, source)),
                            source.assetId(), source.assetRevision(), source.sha256(), filename,
                            filename, source.contentMimeType(), output.bytes()));
        } catch (PersonalWorkspaceException failure) {
            if (failure.getReason() == PersonalWorkspaceException.Reason.PROCESSING) {
                return receipt(saving);
            }
            if (failure.getReason() == PersonalWorkspaceException.Reason.UNSUPPORTED
                    || failure.getReason() == PersonalWorkspaceException.Reason.BAD_REQUEST) {
                failPermanently(scope, command.conversationId(), saving.operationId(), "UNSUPPORTED_MEDIA");
                throw new ChatConversationArchiveException(Reason.UNSUPPORTED,
                        "Conversation asset format is not supported by the workspace");
            }
            if (failure.getReason() == PersonalWorkspaceException.Reason.IDEMPOTENCY_CONFLICT
                    || failure.getReason() == PersonalWorkspaceException.Reason.STORAGE_CORRUPT) {
                failPermanently(scope, command.conversationId(), saving.operationId(), "WORKSPACE_CONFLICT");
                throw new ChatConversationArchiveException(Reason.IDEMPOTENCY_CONFLICT,
                        "Conversation archive operation conflicts with a prior save");
            }
            throw temporary(failure);
        } catch (RuntimeException failure) {
            throw temporary(failure);
        }
        Confirmed confirmed = confirmed(source, upload);
        Operation saved = transactions.required(() -> markSaved(scope, command.conversationId(),
                saving.operationId(), confirmed));
        return receipt(saved);
    }

    /** Strictly read-only: no execution, task-root, provider, workspace or storage call. */
    public Receipt get(Scope scope, String conversationId, String operationId) {
        validateScope(scope);
        exactId(conversationId, "conversationId", 100);
        exactId(operationId, "operationId", 64);
        Operation operation = store.findByOperationId(scope, conversationId, operationId);
        if (operation == null || !validPersisted(operation)) throw unavailable();
        return receipt(operation);
    }

    private Operation claim(Scope scope, Command command, String requestSha) {
        long now = System.currentTimeMillis();
        String operationId = "arc_" + UUID.randomUUID().toString().replace("-", "");
        Operation proposed = new Operation(operationId, scope.tenantId(), scope.ownerJiacn(),
                scope.clientId(), command.conversationId(), null, command.idempotencyKey(),
                requestSha, command.assetId(), command.assetRevision(), "PENDING", null, null,
                null, null, null, 1, now, now);
        if (store.tryInsert(proposed)) return proposed;

        Operation byKey = store.lockByIdempotencyKey(scope, command.idempotencyKey());
        if (byKey != null) {
            if (!sameRequest(byKey, command, requestSha)) {
                throw new ChatConversationArchiveException(Reason.IDEMPOTENCY_CONFLICT,
                        "Idempotency-Key is already bound to another conversation asset");
            }
            if (!validPersisted(byKey)) throw persistence();
            return byKey;
        }
        Operation bySource = store.lockBySource(scope, command.assetId(), command.assetRevision());
        if (bySource != null) {
            if (!command.conversationId().equals(bySource.conversationId())
                    || !requestSha.equals(bySource.requestSha256()) || !validPersisted(bySource)) {
                throw persistence();
            }
            return bySource;
        }
        throw persistence();
    }

    private Operation markSaving(Scope scope, String conversationId, String operationId, long generation) {
        Operation current = store.lockByOperationId(scope, conversationId, operationId);
        if (current == null || !validPersisted(current)) throw unavailable();
        if ("SAVED".equals(current.state())) return current;
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
                        SAFE_FAILURE_MESSAGE, System.currentTimeMillis());
                if (updated != 1) throw persistence();
            }
            return null;
        });
    }

    private Confirmed confirmed(Source source, PersonalWorkspaceViews.UploadView upload) {
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
                || !source.sha256().equals(version.sha256())
                || !source.contentMimeType().equals(version.contentMimeType())
                || source.byteLength() != version.byteLength()) {
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

    private static boolean validSource(Scope scope, Command command, Source source) {
        return source != null && command.assetId().equals(source.assetId())
                && command.assetRevision() == source.assetRevision()
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
        return command.conversationId().equals(operation.conversationId())
                && command.assetId().equals(operation.assetId())
                && command.assetRevision() == operation.assetRevision()
                && requestSha.equals(operation.requestSha256());
    }

    private static boolean validPersisted(Operation operation) {
        if (operation == null || !exactWebId(operation.operationId(), 128)
                || !exact(operation.tenantId(), 50) || !exact(operation.ownerJiacn(), 50)
                || !exact(operation.clientId(), 50) || !exact(operation.conversationId(), 100)
                || !exact(operation.idempotencyKey(), 160)
                || operation.requestSha256() == null || !operation.requestSha256().matches("[0-9a-f]{64}")
                || !exact(operation.assetId(), 64) || operation.assetRevision() < 1
                || operation.rowRevision() < 1 || !List.of("PENDING", "SAVING", "SAVED", "PARTIAL_FAILED")
                        .contains(operation.state())) return false;
        if ("SAVED".equals(operation.state())) {
            return operation.conversationGeneration() != null && operation.conversationGeneration() >= 1
                    && exact(operation.workspaceOperationId(), 100)
                    && exactWebId(operation.fileId(), 128)
                    && operation.fileVersion() != null && operation.fileVersion() >= 1;
        }
        return operation.fileId() == null && operation.fileVersion() == null;
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
        ItemReceipt item = new ItemReceipt(operation.assetId(), Long.toString(operation.assetRevision()),
                itemState, operation.fileId(), operation.fileVersion(), operation.errorCode(), operation.message());
        return new Receipt(operation.operationId(), state, Long.toString(operation.rowRevision()), List.of(item));
    }

    private static String workspaceKey(Scope scope, Source source) {
        return "chat-archive-" + digestParts(scope.tenantId(), scope.ownerJiacn(), scope.clientId(),
                source.assetId(), Long.toString(source.assetRevision()));
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
        exactId(command.assetId(), "assetId", 64);
        if (command.assetRevision() < 1) throw invalid("assetRef.revision must be positive");
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
                && value.codePointCount(0, value.length()) <= max
                && value.chars().noneMatch(Character::isISOControl);
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
                "Conversation asset is unavailable");
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
