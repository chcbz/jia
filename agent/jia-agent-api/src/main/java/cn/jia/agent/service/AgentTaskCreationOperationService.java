package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskDTO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owner-scoped atomic creation of one ordinary task and its immutable selected materials.
 * This contract never assigns, starts, funds, grants, or invokes a Provider.
 */
public interface AgentTaskCreationOperationService {
    Result create(Scope scope, String idempotencyKey, CreateCommand command);
    Receipt getByIdempotencyKey(Scope scope, String idempotencyKey);
    Receipt getByIdempotencyKey(Scope scope, String idempotencyKey, int schemaVersion);

    record Scope(String tenantId, String clientId, String ownerJiacn) { }

    /** Presence flags preserve the exact optional JSON-field semantics in the request digest. */
    record CreateCommand(int schemaVersion, String title,
            boolean descriptionPresent, String description,
            boolean requiredAbilitiesPresent, List<String> requiredAbilities,
            boolean rewardPresent, Integer reward,
            List<InputReference> inputRefs) {
        /** Source-compatible v1 callers retain the frozen image-only contract. */
        public CreateCommand(String title, boolean descriptionPresent, String description,
                boolean requiredAbilitiesPresent, List<String> requiredAbilities,
                boolean rewardPresent, Integer reward, List<InputReference> inputRefs) {
            this(1, title, descriptionPresent, description, requiredAbilitiesPresent,
                    requiredAbilities, rewardPresent, reward, inputRefs);
        }
        public CreateCommand {
            requiredAbilities = requiredAbilities == null ? null
                    : Collections.unmodifiableList(new ArrayList<>(requiredAbilities));
            inputRefs = inputRefs == null ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(inputRefs));
        }
    }

    record InputReference(String fileId, int version, String purpose) { }

    record Receipt(int schemaVersion, String operationId, String taskId,
            long requirementRevision, String state, List<InputReference> inputRefs,
            AgentTaskDTO task) {
        public Receipt {
            inputRefs = inputRefs == null ? List.of() : List.copyOf(inputRefs);
        }
    }

    /** v2 browser-facing selection has no technical purpose or image-operation field. */
    record Attachment(String fileId, int version) { }

    record MaterialsReceipt(int schemaVersion, String operationId, String taskId,
            long requirementRevision, String state, List<Attachment> attachments,
            AgentTaskDTO task) {
        public MaterialsReceipt { attachments = List.copyOf(attachments); }
    }

    record Result(Receipt receipt, boolean replay) { }

    final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Failure(Reason reason, Throwable cause) {
            super(reason.name(), cause); this.reason = reason;
        }
        public Reason reason() { return reason; }
    }

    enum Reason {
        BAD_REQUEST,
        FORBIDDEN,
        NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        UNAVAILABLE
    }
}
