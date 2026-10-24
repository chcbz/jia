package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.entity.PersonalWorkspaceTaskFileLinkEntity;
import cn.jia.agent.mapper.PersonalWorkspaceTaskFileLinkMapper;
import cn.jia.core.tenant.TenantId;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Objects;

@Named
public class PersonalWorkspaceTaskLinkDaoImpl implements PersonalWorkspaceTaskLinkDao {
    private final PersonalWorkspaceTaskFileLinkMapper mapper;

    @Inject
    public PersonalWorkspaceTaskLinkDaoImpl(PersonalWorkspaceTaskFileLinkMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public boolean taskExists(String tenantId, String clientId, String ownerJiacn, String taskId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        return mapper.selectTask(tenantId, clientId, ownerJiacn, taskId) != null;
    }

    @Override
    public boolean lockTask(String tenantId, String clientId, String ownerJiacn, String taskId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        return mapper.selectTaskForUpdate(tenantId, clientId, ownerJiacn, taskId) != null;
    }

    @Override
    public boolean lockFile(String tenantId, String clientId, String ownerJiacn,
            String fileId, boolean requireActive) {
        scope(tenantId, clientId, ownerJiacn); id(fileId, "fileId", 100);
        String selected = requireActive
                ? mapper.selectActiveFileForUpdate(tenantId, clientId, ownerJiacn, fileId)
                : mapper.selectFileForUpdate(tenantId, clientId, ownerJiacn, fileId);
        return selected != null;
    }

    @Override
    public boolean versionExists(String tenantId, String clientId, String ownerJiacn,
            String fileId, int version) {
        scope(tenantId, clientId, ownerJiacn); id(fileId, "fileId", 100); version(version);
        return mapper.selectVersion(tenantId, clientId, ownerJiacn, fileId, version) != null;
    }

    @Override
    public PersonalWorkspaceTaskFileLinkEntity findByRelation(String tenantId, String clientId,
            String ownerJiacn, String taskId, String relationId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        id(relationId, "relationId", 100);
        return checked(mapper.selectByRelation(tenantId, clientId, ownerJiacn, taskId, relationId), tenantId, clientId, ownerJiacn, taskId);
    }

    @Override
    public PersonalWorkspaceTaskFileLinkEntity lockByRelation(String tenantId, String clientId,
            String ownerJiacn, String taskId, String relationId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        id(relationId, "relationId", 100);
        return checked(mapper.selectByRelationForUpdate(tenantId, clientId, ownerJiacn, taskId, relationId), tenantId, clientId, ownerJiacn, taskId);
    }

    @Override
    public PersonalWorkspaceTaskFileLinkEntity lockBySelection(String tenantId, String clientId,
            String ownerJiacn, String taskId, String fileId, int fileVersion, String role) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        id(fileId, "fileId", 100); version(fileVersion); id(role, "role", 20);
        return checked(mapper.selectBySelectionForUpdate(tenantId, clientId, ownerJiacn,
                taskId, fileId, fileVersion, role), tenantId, clientId, ownerJiacn, taskId);
    }

    @Override
    public boolean hasActiveExecutionInputLink(String tenantId, String clientId,
            String ownerJiacn, String taskId, String fileId, int fileVersion) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        id(fileId, "fileId", 100); version(fileVersion);
        return mapper.selectActiveExecutionInputRelation(tenantId, clientId, ownerJiacn,
                taskId, fileId, fileVersion) != null;
    }

    @Override
    public List<PersonalWorkspaceTaskFileLinkEntity> list(String tenantId, String clientId,
            String ownerJiacn, String taskId, Long beforeCreatedAt,
            String afterRelationId, int limit) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        if ((beforeCreatedAt == null) != (afterRelationId == null)) throw new IllegalArgumentException("cursor");
        if (beforeCreatedAt != null) {
            if (beforeCreatedAt < 0) throw new IllegalArgumentException("cursor");
            id(afterRelationId, "cursor", 100);
        }
        List<PersonalWorkspaceTaskFileLinkEntity> rows = mapper.selectLinks(tenantId, clientId, ownerJiacn, taskId,
                beforeCreatedAt, afterRelationId, Math.max(1, Math.min(limit, 100)));
        rows.forEach(row -> checked(row, tenantId, clientId, ownerJiacn, taskId)); return rows;
    }

    @Override
    public List<PersonalWorkspaceTaskFileLinkEntity> listActiveByFile(String tenantId,
            String clientId, String ownerJiacn, String fileId, int limit) {
        scope(tenantId, clientId, ownerJiacn); id(fileId, "fileId", 100);
        List<PersonalWorkspaceTaskFileLinkEntity> rows = mapper.selectActiveByFile(tenantId, clientId, ownerJiacn, fileId,
                Math.max(1, Math.min(limit, 100)));
        rows.forEach(row -> checked(row, tenantId, clientId, ownerJiacn, row.getTaskId())); return rows;
    }

    @Override
    public boolean bumpFileImpactRevision(String tenantId, String clientId,
            String ownerJiacn, String fileId) {
        scope(tenantId, clientId, ownerJiacn); id(fileId, "fileId", 100);
        return mapper.bumpFileMetadataRevision(tenantId, clientId, ownerJiacn,
                fileId, System.currentTimeMillis()) == 1;
    }

    @Override
    public void insert(PersonalWorkspaceTaskFileLinkEntity link) {
        Objects.requireNonNull(link, "link");
        scope(link.getTenantId(), link.getClientId(), link.getOwnerJiacn());
        id(link.getTaskId(), "taskId", 100); id(link.getFileId(), "fileId", 100);
        id(link.getRelationId(), "relationId", 100); version(link.getFileVersion());
        link.init4Creation();
        mapper.insert(link);
    }

    @Override
    public boolean changeState(String tenantId, String clientId, String ownerJiacn,
            String taskId, String relationId, String expectedState, long expectedRevision,
            String nextState, long nextRevision, Long detachedAt) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId", 100);
        id(relationId, "relationId", 100); id(expectedState, "expectedState", 20);
        id(nextState, "nextState", 20);
        if (expectedRevision < 1 || nextRevision != expectedRevision + 1) {
            throw new IllegalArgumentException("revision");
        }
        return mapper.updateState(tenantId, clientId, ownerJiacn, taskId, relationId,
                expectedState, expectedRevision, nextState, nextRevision, detachedAt,
                System.currentTimeMillis()) == 1;
    }

    @Override
    public OperationRow reserveOperation(String tenantId, String clientId, String ownerJiacn,
            String operationType, String idempotencyKey, String requestHash,
            String proposedOperationId, long now) {
        scope(tenantId, clientId, ownerJiacn); id(operationType, "operationType", 20);
        id(idempotencyKey, "idempotencyKey", 100); hash(requestHash);
        id(proposedOperationId, "operationId", 100);
        try {
            mapper.reserveOperation(tenantId, clientId, ownerJiacn, operationType,
                    idempotencyKey, requestHash, proposedOperationId, now);
        } catch (DuplicateKeyException ignored) {
            // Idempotent replay: the exact scoped row is locked and returned below.
        }
        OperationRow row = mapper.selectOperationForUpdate(tenantId, clientId, ownerJiacn,
                operationType, idempotencyKey);
        if (row == null) throw new IllegalStateException("task-link operation reservation missing");
        if (!tenantId.equals(row.getTenantId()) || !clientId.equals(row.getClientId())
                || !ownerJiacn.equals(row.getOwnerJiacn()))
            throw new IllegalStateException("task-link operation scope projection mismatch");
        return row;
    }

    @Override
    public boolean completeOperation(String tenantId, String clientId, String ownerJiacn,
            String operationId, String relationId, long completedAt) {
        scope(tenantId, clientId, ownerJiacn); id(operationId, "operationId", 100);
        id(relationId, "relationId", 100);
        return mapper.commitOperation(tenantId, clientId, ownerJiacn,
                operationId, relationId, completedAt) == 1;
    }

    private static void scope(String tenantId, String clientId, String ownerJiacn) {
        TenantId.of(tenantId);
        id(clientId, "clientId", 50); id(ownerJiacn, "ownerJiacn", 50);
        if ("0".equals(ownerJiacn)) throw new IllegalArgumentException("ownerJiacn");
    }

    private static PersonalWorkspaceTaskFileLinkEntity checked(
            PersonalWorkspaceTaskFileLinkEntity row, String tenantId, String clientId,
            String ownerJiacn, String taskId) {
        if (row != null && (!tenantId.equals(row.getTenantId()) || !clientId.equals(row.getClientId())
                || !ownerJiacn.equals(row.getOwnerJiacn()) || !taskId.equals(row.getTaskId()))) {
            throw new IllegalStateException("task file link scope projection mismatch");
        }
        return row;
    }

    private static void version(int value) {
        if (value < 1) throw new IllegalArgumentException("version");
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash");
        }
    }

    private static void id(String value, String name, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name);
        }
    }
}
