package cn.jia.agent.service.impl;

import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao.OperationRow;
import cn.jia.agent.entity.PersonalWorkspaceTaskFileLinkEntity;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Named
public class PersonalWorkspaceTaskLinkServiceImpl implements PersonalWorkspaceTaskLinkService {
    private static final int PAGE_SIZE = 50;
    private static final Set<String> USER_ROLES = Set.of("INPUT", "REFERENCE");
    private final PersonalWorkspaceTaskLinkDao dao;

    @Inject
    public PersonalWorkspaceTaskLinkServiceImpl(PersonalWorkspaceTaskLinkDao dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public LinkListView list(Scope scope, String taskId, String cursor) {
        validateScope(scope); id(taskId, 100);
        if (!dao.taskExists(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId)) {
            throw failure(Reason.NOT_FOUND);
        }
        Cursor position = parseCursor(scope, taskId, cursor);
        List<PersonalWorkspaceTaskFileLinkEntity> rows = new ArrayList<>(dao.list(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId,
                position == null ? null : position.createdAt(),
                position == null ? null : position.relationId(), PAGE_SIZE + 1));
        boolean more = rows.size() > PAGE_SIZE;
        if (more) rows = new ArrayList<>(rows.subList(0, PAGE_SIZE));
        String next = more ? cursor(scope, taskId, rows.get(rows.size() - 1)) : null;
        return new LinkListView(rows.stream().map(PersonalWorkspaceTaskLinkServiceImpl::view).toList(), next);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LinkView create(Scope scope, String taskId, CreateCommand command) {
        validateScope(scope); id(taskId, 100);
        if (command == null) throw failure(Reason.BAD_REQUEST);
        id(command.fileId(), 100); positive(command.version()); key(command.idempotencyKey());
        if ("OUTPUT".equals(command.role())) throw failure(Reason.OUTPUT_MANAGED_BY_EXECUTION);
        if (!USER_ROLES.contains(command.role())) throw failure(Reason.BAD_REQUEST);

        requireLockedTask(scope, taskId);
        String requestHash = hash("CREATE", taskId, command.fileId(),
                Integer.toString(command.version()), command.role());
        Claim claim = claim(scope, "CREATE", command.idempotencyKey(), requestHash);
        if (!claim.owner()) return replay(scope, taskId, claim.operation());

        if (!dao.lockFile(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                command.fileId(), true)
                || !dao.versionExists(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                        command.fileId(), command.version())) {
            throw failure(Reason.NOT_FOUND);
        }

        PersonalWorkspaceTaskFileLinkEntity link = dao.lockBySelection(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), taskId, command.fileId(),
                command.version(), command.role());
        if (link == null) {
            long now = System.currentTimeMillis();
            link = new PersonalWorkspaceTaskFileLinkEntity()
                    .setRelationId(identifier("pwtl_"))
                    .setOwnerJiacn(scope.ownerJiacn())
                    .setTaskId(taskId)
                    .setFileId(command.fileId())
                    .setFileVersion(command.version())
                    .setLinkRole(command.role())
                    .setLinkState("ACTIVE")
                    .setRelationRevision(1L)
                    .setCreatedAt(now)
                    .setDetachedAt(null);
            link.setTenantId(scope.tenantId());
            link.setClientId(scope.clientId());
            dao.insert(link);
        } else if ("DETACHED".equals(link.getLinkState())) {
            long next = Math.addExact(link.getRelationRevision(), 1L);
            if (!dao.changeState(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId,
                    link.getRelationId(), "DETACHED", link.getRelationRevision(),
                    "ACTIVE", next, null)) {
                throw failure(Reason.UNAVAILABLE);
            }
            link.setLinkState("ACTIVE").setRelationRevision(next).setDetachedAt(null);
        } else if (!"ACTIVE".equals(link.getLinkState())) {
            throw failure(Reason.UNAVAILABLE);
        }
        if (!dao.bumpFileImpactRevision(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                command.fileId())) {
            throw failure(Reason.UNAVAILABLE);
        }
        commit(scope, claim.operation(), link.getRelationId());
        return view(link);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DetachView detach(Scope scope, String taskId, String relationId,
            String ifMatch, String idempotencyKey) {
        validateScope(scope); id(taskId, 100); id(relationId, 100);
        key(idempotencyKey);
        if (ifMatch == null || ifMatch.isBlank()) throw failure(Reason.PRECONDITION_REQUIRED);
        if (ifMatch.length() > 220 || ifMatch.chars().anyMatch(Character::isISOControl)) {
            throw failure(Reason.LINK_CHANGED);
        }

        requireLockedTask(scope, taskId);
        String requestHash = hash("DELETE", taskId, relationId, ifMatch);
        Claim claim = claim(scope, "DELETE", idempotencyKey, requestHash);
        if (!claim.owner()) return new DetachView(replay(scope, taskId, claim.operation()), true);

        PersonalWorkspaceTaskFileLinkEntity observed = dao.findByRelation(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), taskId, relationId);
        if (observed == null) throw failure(Reason.NOT_FOUND);
        if ("OUTPUT".equals(observed.getLinkRole())) {
            throw failure(Reason.OUTPUT_MANAGED_BY_EXECUTION);
        }
        if (!USER_ROLES.contains(observed.getLinkRole())) throw failure(Reason.UNAVAILABLE);
        if (!dao.lockFile(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                observed.getFileId(), false)) {
            throw failure(Reason.NOT_FOUND);
        }
        PersonalWorkspaceTaskFileLinkEntity link = dao.lockByRelation(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), taskId, relationId);
        if (link == null) throw failure(Reason.NOT_FOUND);
        if ("OUTPUT".equals(link.getLinkRole())) {
            throw failure(Reason.OUTPUT_MANAGED_BY_EXECUTION);
        }
        if (!etag(link).equals(ifMatch)) throw failure(Reason.LINK_CHANGED);
        if ("ACTIVE".equals(link.getLinkState())) {
            long next = Math.addExact(link.getRelationRevision(), 1L);
            long now = System.currentTimeMillis();
            if (!dao.changeState(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId,
                    relationId, "ACTIVE", link.getRelationRevision(), "DETACHED", next, now)) {
                throw failure(Reason.UNAVAILABLE);
            }
            link.setLinkState("DETACHED").setRelationRevision(next).setDetachedAt(now);
        } else if (!"DETACHED".equals(link.getLinkState())) {
            throw failure(Reason.UNAVAILABLE);
        }
        if (!dao.bumpFileImpactRevision(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                link.getFileId())) {
            throw failure(Reason.UNAVAILABLE);
        }
        commit(scope, claim.operation(), relationId);
        return new DetachView(view(link), true);
    }

    private void requireLockedTask(Scope scope, String taskId) {
        if (!dao.lockTask(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId)) {
            throw failure(Reason.NOT_FOUND);
        }
    }

    private Claim claim(Scope scope, String type, String idempotencyKey, String requestHash) {
        String proposed = identifier("pwtlo_");
        OperationRow operation = dao.reserveOperation(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), type, idempotencyKey, requestHash,
                proposed, System.currentTimeMillis());
        if (!MessageDigest.isEqual(bytes(requestHash), bytes(operation.getRequestHash()))) {
            throw failure(Reason.IDEMPOTENCY_CONFLICT);
        }
        boolean owner = proposed.equals(operation.getOperationId());
        if (!owner && !"COMMITTED".equals(operation.getOperationState())) {
            throw failure(Reason.UNAVAILABLE);
        }
        return new Claim(operation, owner);
    }

    private LinkView replay(Scope scope, String taskId, OperationRow operation) {
        if (!"COMMITTED".equals(operation.getOperationState())
                || operation.getRelationId() == null) {
            throw failure(Reason.UNAVAILABLE);
        }
        PersonalWorkspaceTaskFileLinkEntity link = dao.findByRelation(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), taskId, operation.getRelationId());
        if (link == null) throw failure(Reason.UNAVAILABLE);
        return view(link);
    }

    private void commit(Scope scope, OperationRow operation, String relationId) {
        if (!dao.completeOperation(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                operation.getOperationId(), relationId, System.currentTimeMillis())) {
            throw failure(Reason.UNAVAILABLE);
        }
    }

    public static String etag(LinkView link) {
        return "\"" + link.relationId() + ":" + link.relationRevision() + "\"";
    }

    private static String etag(PersonalWorkspaceTaskFileLinkEntity link) {
        return "\"" + link.getRelationId() + ":" + link.getRelationRevision() + "\"";
    }

    private static LinkView view(PersonalWorkspaceTaskFileLinkEntity link) {
        return new LinkView(link.getRelationId(), link.getTaskId(), link.getFileId(),
                link.getFileVersion(), link.getLinkRole(), link.getLinkState(),
                link.getRelationRevision(), link.getCreatedAt());
    }

    private static String cursor(Scope scope, String taskId,
            PersonalWorkspaceTaskFileLinkEntity link) {
        String plain = scopeHash(scope, taskId) + "\n" + link.getCreatedAt()
                + "\n" + link.getRelationId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor parseCursor(Scope scope, String taskId, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String plain = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = plain.split("\\n", -1);
            if (parts.length != 3 || !scopeHash(scope, taskId).equals(parts[0])) {
                throw failure(Reason.BAD_REQUEST);
            }
            long createdAt = Long.parseLong(parts[1]);
            if (createdAt < 0) throw failure(Reason.BAD_REQUEST);
            id(parts[2], 100);
            return new Cursor(createdAt, parts[2]);
        } catch (IllegalArgumentException malformed) {
            throw failure(Reason.BAD_REQUEST);
        }
    }

    private static String scopeHash(Scope scope, String taskId) {
        return hash("CURSOR", scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId);
    }

    private static void validateScope(Scope scope) {
        if (scope == null || !"0".equals(scope.tenantId())) throw failure(Reason.BAD_REQUEST);
        id(scope.clientId(), 50); id(scope.ownerJiacn(), 50);
        if ("0".equals(scope.ownerJiacn())) throw failure(Reason.BAD_REQUEST);
    }

    private static void positive(int value) {
        if (value < 1) throw failure(Reason.BAD_REQUEST);
    }

    private static void key(String value) {
        id(value, 100);
    }

    private static void id(String value, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw failure(Reason.BAD_REQUEST);
        }
    }

    private static String identifier(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] part = Objects.requireNonNullElse(value, "").getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(part.length).array());
                digest.update(part);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.US_ASCII);
    }

    private static Failure failure(Reason reason) {
        return new Failure(reason);
    }

    private record Claim(OperationRow operation, boolean owner) { }
    private record Cursor(long createdAt, String relationId) { }
}
