package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskCreationOperationDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskCreationOperationEntity;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCreationOperationService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.security.TenantClaimPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** REQUIRED transaction sidecar around the existing ordinary task and task-link proxies. */
@Named
public class AgentTaskCreationOperationServiceImpl
        implements AgentTaskCreationOperationService {
    private static final Set<String> IMAGE_MIME_TYPES = Set.of("image/jpeg", "image/png");
    private static final Comparator<InputReference> REFERENCE_ORDER =
            Comparator.comparing(InputReference::fileId,
                    AgentTaskCreationOperationServiceImpl::compareUtf8Binary)
                    .thenComparingInt(InputReference::version);

    private final AgentTaskCreationOperationDao operations;
    private final AgentService agents;
    private final AgentTaskRequirementSnapshotService requirements;
    private final PersonalWorkspaceTaskLinkService links;
    private final PersonalWorkspaceTaskLinkDao linkRows;
    private final PersonalWorkspaceDao workspace;
    private final ObjectMapper json;

    @Inject
    public AgentTaskCreationOperationServiceImpl(AgentTaskCreationOperationDao operations,
            AgentService agents, AgentTaskRequirementSnapshotService requirements,
            PersonalWorkspaceTaskLinkService links, PersonalWorkspaceTaskLinkDao linkRows,
            PersonalWorkspaceDao workspace, ObjectMapper json) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.requirements = Objects.requireNonNull(requirements, "requirements");
        this.links = Objects.requireNonNull(links, "links");
        this.linkRows = Objects.requireNonNull(linkRows, "linkRows");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result create(Scope scope, String idempotencyKey, CreateCommand command) {
        requireWriteTransaction();
        requireAuthenticatedScope(scope);
        ValidRequest valid = validate(idempotencyKey, command);
        String refsJson = refsJson(valid.references());
        String requestHash = requestHash(valid);
        String proposedOperationId = identifier("atco_");
        try {
            AgentTaskCreationOperationEntity operation = operations.reserveAndLock(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), idempotencyKey,
                    requestHash, proposedOperationId, refsJson, System.currentTimeMillis());
            validateOperationScope(operation, scope, idempotencyKey);
            if (!constantTimeEquals(requestHash, operation.getRequestHash())) {
                throw failure(Reason.IDEMPOTENCY_CONFLICT);
            }
            if (!Objects.equals(valid.references(),
                    parsePersistedRefs(operation.getInputRefsJson()))) {
                throw failure(Reason.UNAVAILABLE);
            }
            boolean owner = proposedOperationId.equals(operation.getOperationId());
            if (!owner) {
                if (!"COMMITTED".equals(operation.getOperationState())) {
                    throw failure(Reason.UNAVAILABLE);
                }
                return new Result(receipt(scope, operation), true);
            }
            if (!"PROCESSING".equals(operation.getOperationState())
                    || operation.getTaskId() != null
                    || operation.getRequirementRevision() != null
                    || operation.getCompletedAt() != null) {
                throw failure(Reason.UNAVAILABLE);
            }

            AgentTaskDTO created = agents.createTask(taskRequest(valid));
            String taskId = requireCreatedTask(created, scope, valid);
            AgentTaskExecutionGrantService.Scope requirementScope = requirementScope(scope);
            AgentTaskRequirementSnapshotService.Snapshot snapshot =
                    requirements.read(requirementScope, taskId, 1);
            requireCreationSnapshot(snapshot, scope, taskId, valid);

            PersonalWorkspaceTaskLinkService.Scope linkScope = new PersonalWorkspaceTaskLinkService.Scope(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn());
            for (int index = 0; index < valid.references().size(); index++) {
                InputReference reference = valid.references().get(index);
                requireReferenceVersionLocked(scope, taskId, reference);
                PersonalWorkspaceTaskLinkService.LinkView linked = links.create(linkScope, taskId,
                        new PersonalWorkspaceTaskLinkService.CreateCommand(reference.fileId(),
                                reference.version(), "REFERENCE",
                                operation.getOperationId() + ":ref:" + index));
                requireExactLink(linked, taskId, reference);
            }

            long completedAt = System.currentTimeMillis();
            if (!operations.complete(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                    operation.getOperationId(), requestHash, refsJson, taskId, 1, completedAt)) {
                throw failure(Reason.UNAVAILABLE);
            }
            operation.setOperationState("COMMITTED").setTaskId(taskId)
                    .setRequirementRevision(1L).setCompletedAt(completedAt);
            return new Result(new Receipt(1, operation.getOperationId(), taskId, 1,
                    "COMMITTED", valid.references(), created), false);
        } catch (Failure failure) {
            throw failure;
        } catch (PersonalWorkspaceTaskLinkService.Failure failure) {
            throw switch (failure.getReason()) {
                case NOT_FOUND -> failure(Reason.NOT_FOUND, failure);
                case BAD_REQUEST, OUTPUT_MANAGED_BY_EXECUTION,
                        PRECONDITION_REQUIRED, LINK_CHANGED -> failure(Reason.BAD_REQUEST, failure);
                case IDEMPOTENCY_CONFLICT, UNAVAILABLE -> failure(Reason.UNAVAILABLE, failure);
            };
        } catch (DataAccessException | TransactionException failure) {
            throw failure(Reason.UNAVAILABLE, failure);
        } catch (IllegalArgumentException failure) {
            throw failure(Reason.BAD_REQUEST, failure);
        } catch (RuntimeException failure) {
            throw failure(Reason.UNAVAILABLE, failure);
        }
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Receipt getByIdempotencyKey(Scope scope, String idempotencyKey) {
        requireAuthenticatedScope(scope);
        requireKey(idempotencyKey);
        try {
            AgentTaskCreationOperationEntity operation = operations.find(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), idempotencyKey);
            if (operation == null || !"COMMITTED".equals(operation.getOperationState())) {
                throw failure(Reason.NOT_FOUND);
            }
            validateOperationScope(operation, scope, idempotencyKey);
            return receipt(scope, operation);
        } catch (Failure failure) {
            throw failure;
        } catch (DataAccessException | TransactionException failure) {
            throw failure(Reason.UNAVAILABLE, failure);
        } catch (RuntimeException failure) {
            throw failure(Reason.UNAVAILABLE, failure);
        }
    }

    private Receipt receipt(Scope scope, AgentTaskCreationOperationEntity operation) {
        if (!"COMMITTED".equals(operation.getOperationState())
                || operation.getRequirementRevision() == null
                || operation.getRequirementRevision() != 1
                || !validId(operation.getOperationId(), 100)
                || !validId(operation.getTaskId(), 100)
                || operation.getCompletedAt() == null
                || operation.getCreatedAt() == null
                || operation.getCreatedAt() <= 0
                || operation.getCompletedAt() < operation.getCreatedAt()
                || operation.getRequestHash() == null
                || !operation.getRequestHash().matches("[0-9a-f]{64}")) {
            throw failure(Reason.UNAVAILABLE);
        }
        List<InputReference> references = parsePersistedRefs(operation.getInputRefsJson());
        if (!linkRows.taskExists(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                operation.getTaskId())) {
            throw failure(Reason.NOT_FOUND);
        }
        AgentTaskRequirementSnapshotService.Snapshot snapshot = requirements.read(
                requirementScope(scope), operation.getTaskId(), 1);
        if (snapshot == null || snapshot.revision() != 1
                || !"CREATE".equals(snapshot.source())
                || !Objects.equals(scope.tenantId(), snapshot.tenantId())
                || !Objects.equals(scope.clientId(), snapshot.clientId())
                || !Objects.equals(scope.ownerJiacn(), snapshot.ownerJiacn())
                || !Objects.equals(operation.getTaskId(), snapshot.taskId())) {
            throw failure(Reason.UNAVAILABLE);
        }
        AgentTaskDTO task = agents.getTask(operation.getTaskId());
        requireTaskProjection(task, scope, operation.getTaskId());
        return new Receipt(1, operation.getOperationId(), operation.getTaskId(), 1,
                "COMMITTED", references, task);
    }

    private void requireReferenceVersionLocked(Scope scope, String taskId,
            InputReference reference) {
        if (!linkRows.lockTask(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId)) {
            throw failure(Reason.NOT_FOUND);
        }
        PersonalWorkspaceFileEntity file = workspace.lockFile(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), reference.fileId());
        if (file == null || !Objects.equals(reference.fileId(), file.getFileId())
                || !Objects.equals(scope.tenantId(), file.getTenantId())
                || !Objects.equals(scope.clientId(), file.getClientId())
                || !Objects.equals(scope.ownerJiacn(), file.getOwnerJiacn())
                || !"ACTIVE".equals(file.getState())) {
            throw failure(Reason.NOT_FOUND);
        }
        PersonalWorkspaceVersionEntity version = workspace.findVersion(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), reference.fileId(), reference.version());
        if (version == null || !Objects.equals(reference.fileId(), version.getFileId())
                || !Objects.equals(reference.version(), version.getVersion())
                || !Objects.equals(scope.tenantId(), version.getTenantId())
                || !Objects.equals(scope.clientId(), version.getClientId())
                || !Objects.equals(scope.ownerJiacn(), version.getOwnerJiacn())
                || !IMAGE_MIME_TYPES.contains(version.getContentMimeType())) {
            throw failure(Reason.NOT_FOUND);
        }
    }

    private static void requireExactLink(PersonalWorkspaceTaskLinkService.LinkView linked,
            String taskId, InputReference reference) {
        if (linked == null || !Objects.equals(taskId, linked.taskId())
                || !Objects.equals(reference.fileId(), linked.fileId())
                || reference.version() != linked.version()
                || !"REFERENCE".equals(linked.role())
                || !"ACTIVE".equals(linked.state())
                || linked.relationRevision() < 1) {
            throw failure(Reason.UNAVAILABLE);
        }
    }

    private static String requireCreatedTask(AgentTaskDTO task, Scope scope,
            ValidRequest request) {
        List<String> expectedAbilities = request.requiredAbilities() == null
                ? List.of() : request.requiredAbilities();
        if (task == null || !validId(task.getId(), 100)
                || !Objects.equals(scope.tenantId(), task.getTenantId())
                || !Objects.equals(scope.clientId(), task.getClientId())
                || !Objects.equals(request.title(), task.getTitle())
                || !Objects.equals(request.description(), task.getDescription())
                || !Objects.equals(expectedAbilities, task.getRequiredAbilities())
                || !Objects.equals(request.reward(), task.getReward())
                || task.getFunding() != null) {
            throw failure(Reason.UNAVAILABLE);
        }
        return task.getId();
    }

    private static void requireTaskProjection(AgentTaskDTO task, Scope scope, String taskId) {
        if (task == null || !Objects.equals(taskId, task.getId())
                || !Objects.equals(scope.tenantId(), task.getTenantId())
                || !Objects.equals(scope.clientId(), task.getClientId())) {
            throw failure(Reason.NOT_FOUND);
        }
    }

    private static void requireCreationSnapshot(AgentTaskRequirementSnapshotService.Snapshot snapshot,
            Scope scope, String taskId, ValidRequest request) {
        if (snapshot == null || snapshot.revision() != 1
                || !"CREATE".equals(snapshot.source())
                || !Objects.equals(scope.tenantId(), snapshot.tenantId())
                || !Objects.equals(scope.clientId(), snapshot.clientId())
                || !Objects.equals(scope.ownerJiacn(), snapshot.ownerJiacn())
                || !Objects.equals(taskId, snapshot.taskId())
                || !Objects.equals(request.title(), snapshot.title())
                || !Objects.equals(request.description(), snapshot.description())) {
            throw failure(Reason.UNAVAILABLE);
        }
    }

    private static AgentTaskCreateDTO taskRequest(ValidRequest request) {
        AgentTaskCreateDTO task = new AgentTaskCreateDTO();
        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setRequiredAbilities(request.requiredAbilities());
        task.setReward(request.reward());
        return task;
    }

    private ValidRequest validate(String idempotencyKey, CreateCommand command) {
        requireKey(idempotencyKey);
        if (command == null || command.title() == null || command.title().isBlank()) {
            throw failure(Reason.BAD_REQUEST);
        }
        requireOriginalText(command.title(), false);
        if (command.description() != null) requireOriginalText(command.description(), true);
        if (!command.descriptionPresent() && command.description() != null
                || !command.requiredAbilitiesPresent() && command.requiredAbilities() != null
                || !command.rewardPresent() && command.reward() != null) {
            throw failure(Reason.BAD_REQUEST);
        }
        List<String> abilities = command.requiredAbilities();
        if (abilities != null) {
            for (String ability : abilities) {
                if (ability == null || hasUnpairedSurrogate(ability)) {
                    throw failure(Reason.BAD_REQUEST);
                }
            }
            abilities = List.copyOf(abilities);
        }
        List<InputReference> references = new ArrayList<>(command.inputRefs());
        if (references.size() > 32) throw failure(Reason.BAD_REQUEST);
        Set<String> selected = new HashSet<>();
        for (InputReference reference : references) {
            if (reference == null || !validId(reference.fileId(), 100)
                    || reference.version() < 1
                    || !"REFERENCE".equals(reference.purpose())
                    || !selected.add(reference.fileId() + "\u0000" + reference.version()
                            + "\u0000REFERENCE")) {
                throw failure(Reason.BAD_REQUEST);
            }
        }
        references.sort(REFERENCE_ORDER);
        return new ValidRequest(command.title(), command.descriptionPresent(),
                command.description(), command.requiredAbilitiesPresent(), abilities,
                command.rewardPresent(), command.reward(), List.copyOf(references));
    }

    private String refsJson(List<InputReference> references) {
        try {
            ArrayNode array = json.createArrayNode();
            for (InputReference reference : references) {
                ObjectNode item = array.addObject();
                item.put("fileId", reference.fileId());
                item.put("version", reference.version());
                item.put("purpose", "REFERENCE");
            }
            return json.writeValueAsString(array);
        } catch (Exception failure) {
            throw failure(Reason.UNAVAILABLE, failure);
        }
    }

    private List<InputReference> parsePersistedRefs(String value) {
        try {
            JsonNode root = json.readTree(value);
            if (root == null || !root.isArray() || root.size() > 32) {
                throw failure(Reason.UNAVAILABLE);
            }
            List<InputReference> references = new ArrayList<>();
            Set<String> selected = new HashSet<>();
            for (JsonNode node : root) {
                if (!node.isObject() || node.size() != 3
                        || !node.has("fileId") || !node.has("version") || !node.has("purpose")
                        || !node.get("fileId").isTextual()
                        || !node.get("version").isIntegralNumber()
                        || !node.get("version").canConvertToInt()
                        || !node.get("purpose").isTextual()) {
                    throw failure(Reason.UNAVAILABLE);
                }
                InputReference reference = new InputReference(node.get("fileId").textValue(),
                        node.get("version").intValue(), node.get("purpose").textValue());
                if (!validId(reference.fileId(), 100) || reference.version() < 1
                        || !"REFERENCE".equals(reference.purpose())
                        || !selected.add(reference.fileId() + "\u0000" + reference.version()
                                + "\u0000REFERENCE")) {
                    throw failure(Reason.UNAVAILABLE);
                }
                references.add(reference);
            }
            List<InputReference> canonical = references.stream().sorted(REFERENCE_ORDER).toList();
            if (!references.equals(canonical)) {
                throw failure(Reason.UNAVAILABLE);
            }
            return canonical;
        } catch (Failure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure(Reason.UNAVAILABLE, failure);
        }
    }

    private static int compareUtf8Binary(String left, String right) {
        byte[] leftBytes = utf8(left);
        byte[] rightBytes = utf8(right);
        int shared = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < shared; index++) {
            int compared = Integer.compare(Byte.toUnsignedInt(leftBytes[index]),
                    Byte.toUnsignedInt(rightBytes[index]));
            if (compared != 0) return compared;
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    private static String requestHash(ValidRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, "AGENT_TASK_CREATION_OPERATION_V1");
            add(digest, request.title());
            optional(digest, request.descriptionPresent(), request.description());
            digest.update((byte) (request.requiredAbilitiesPresent() ? 1 : 0));
            if (request.requiredAbilitiesPresent()) {
                if (request.requiredAbilities() == null) {
                    digest.update((byte) 0);
                } else {
                    digest.update((byte) 1);
                    digest.update(ByteBuffer.allocate(4).putInt(request.requiredAbilities().size()).array());
                    for (String ability : request.requiredAbilities()) add(digest, ability);
                }
            }
            digest.update((byte) (request.rewardPresent() ? 1 : 0));
            if (request.rewardPresent()) {
                if (request.reward() == null) digest.update((byte) 0);
                else {
                    digest.update((byte) 1);
                    digest.update(ByteBuffer.allocate(4).putInt(request.reward()).array());
                }
            }
            digest.update(ByteBuffer.allocate(4).putInt(request.references().size()).array());
            for (InputReference reference : request.references()) {
                add(digest, reference.fileId());
                digest.update(ByteBuffer.allocate(4).putInt(reference.version()).array());
                add(digest, "REFERENCE");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void optional(MessageDigest digest, boolean present, String value) {
        digest.update((byte) (present ? 1 : 0));
        if (!present) return;
        if (value == null) digest.update((byte) 0);
        else { digest.update((byte) 1); add(digest, value); }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = utf8(value);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static byte[] utf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException failure) {
            throw failure(Reason.BAD_REQUEST, failure);
        }
    }

    private static void requireOriginalText(String value, boolean nullable) {
        if (value == null) {
            if (!nullable) throw failure(Reason.BAD_REQUEST);
            return;
        }
        if (hasUnpairedSurrogate(value) || utf8(value).length > 16_777_215) {
            throw failure(Reason.BAD_REQUEST);
        }
    }

    private static void requireKey(String value) {
        if (!validId(value, 100)) throw failure(Reason.BAD_REQUEST);
    }

    private static boolean validId(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= max
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        if (value == null) return false;
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) return true;
        }
        return false;
    }

    private static void requireAuthenticatedScope(Scope scope) {
        if (scope == null || !"0".equals(scope.tenantId())
                || !validId(scope.clientId(), 50) || !validId(scope.ownerJiacn(), 50)
                || "0".equals(scope.ownerJiacn())) {
            throw failure(Reason.FORBIDDEN);
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
            throw failure(Reason.FORBIDDEN);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        final String tenant;
        try {
            tenant = TenantClaimPolicy.resolve(claims, "0");
        } catch (IllegalArgumentException invalid) {
            throw failure(Reason.FORBIDDEN, invalid);
        }
        EsContext context = EsContextHolder.getContext();
        if (!Objects.equals("0", tenant)
                || !Objects.equals(scope.ownerJiacn(), claims.get("jiacn"))
                || !Objects.equals(scope.clientId(), claims.get("client_id"))
                || context == null || !Objects.equals("0", context.getTenantId())
                || !Objects.equals(scope.ownerJiacn(), context.getJiacn())
                || !Objects.equals(scope.clientId(), context.getClientId())) {
            throw failure(Reason.FORBIDDEN);
        }
    }

    private static void validateOperationScope(AgentTaskCreationOperationEntity operation,
            Scope scope, String idempotencyKey) {
        if (operation == null
                || !Objects.equals(scope.tenantId(), operation.getTenantId())
                || !Objects.equals(scope.clientId(), operation.getClientId())
                || !Objects.equals(scope.ownerJiacn(), operation.getOwnerJiacn())
                || !Objects.equals(idempotencyKey, operation.getIdempotencyKey())
                || !validId(operation.getOperationId(), 100)) {
            throw failure(Reason.UNAVAILABLE);
        }
    }

    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw failure(Reason.UNAVAILABLE);
        }
    }

    private static AgentTaskExecutionGrantService.Scope requirementScope(Scope scope) {
        return new AgentTaskExecutionGrantService.Scope(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn());
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left == null ? new byte[0]
                        : left.getBytes(StandardCharsets.US_ASCII),
                right == null ? new byte[0] : right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String identifier(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static Failure failure(Reason reason) { return new Failure(reason); }
    private static Failure failure(Reason reason, Throwable cause) {
        return new Failure(reason, cause);
    }

    private record ValidRequest(String title,
            boolean descriptionPresent, String description,
            boolean requiredAbilitiesPresent, List<String> requiredAbilities,
            boolean rewardPresent, Integer reward,
            List<InputReference> references) { }
}
