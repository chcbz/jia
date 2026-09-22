package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.HallRequestDraftDao;
import cn.jia.agent.entity.HallExecutionResultRow;
import cn.jia.agent.entity.HallRequestDraftEntity;
import cn.jia.agent.mapper.HallRequestDraftMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;
import java.util.Objects;

@Named
public class HallRequestDraftDaoImpl implements HallRequestDraftDao {
    private final HallRequestDraftMapper mapper;

    @Inject
    public HallRequestDraftDaoImpl(HallRequestDraftMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override public HallRequestDraftEntity find(String tenantId, String clientId,
            String ownerJiacn, String draftId) {
        scope(tenantId, clientId, ownerJiacn); id(draftId, "draftId", 100);
        return mapper.findExact(tenantId, clientId, ownerJiacn, draftId);
    }
    @Override public HallRequestDraftEntity lock(String tenantId, String clientId,
            String ownerJiacn, String draftId) {
        scope(tenantId, clientId, ownerJiacn); id(draftId, "draftId", 100);
        return mapper.lockExact(tenantId, clientId, ownerJiacn, draftId);
    }
    @Override public HallRequestDraftEntity findBySubmitKey(String tenantId, String clientId,
            String ownerJiacn, String submitKey) {
        scope(tenantId, clientId, ownerJiacn); id(submitKey, "submitKey", 100);
        return mapper.findBySubmitKey(tenantId, clientId, ownerJiacn, submitKey);
    }
    @Override public HallRequestDraftEntity findByCreateKey(String tenantId, String clientId,
            String ownerJiacn, String createKey) {
        scope(tenantId, clientId, ownerJiacn); id(createKey, "createKey", 100);
        return mapper.findByCreateKey(tenantId, clientId, ownerJiacn, createKey);
    }
    @Override public HallRequestDraftEntity findByDiscardKey(String tenantId, String clientId,
            String ownerJiacn, String discardKey) {
        scope(tenantId, clientId, ownerJiacn); id(discardKey, "discardKey", 100);
        return mapper.findByDiscardKey(tenantId, clientId, ownerJiacn, discardKey);
    }
    @Override public List<HallRequestDraftEntity> listEditing(String tenantId, String clientId,
            String ownerJiacn, Long beforeUpdatedAt, String beforeDraftId, int limit) {
        scope(tenantId, clientId, ownerJiacn);
        if ((beforeUpdatedAt == null) != (beforeDraftId == null)
                || (beforeUpdatedAt != null && beforeUpdatedAt < 0)
                || limit < 1 || limit > 51) throw new IllegalArgumentException("cursor");
        if (beforeDraftId != null) id(beforeDraftId, "beforeDraftId", 100);
        return mapper.listEditing(tenantId, clientId, ownerJiacn,
                beforeUpdatedAt, beforeDraftId, limit);
    }
    @Override public void reserveCreate(HallRequestDraftEntity entity) {
        Objects.requireNonNull(entity, "entity"); mapper.reserveCreate(entity);
    }
    @Override public int replaceEditing(String tenantId, String clientId, String ownerJiacn,
            String draftId, long expectedRevision, String title, String instruction,
            String targetAgentId, String outputMime, String inputsJson, long updatedAt) {
        scope(tenantId, clientId, ownerJiacn); id(draftId, "draftId", 100);
        positive(expectedRevision, "revision"); nonnegative(updatedAt, "updatedAt");
        return mapper.replaceEditing(tenantId, clientId, ownerJiacn, draftId,
                expectedRevision, title, instruction, targetAgentId, outputMime, inputsJson, updatedAt);
    }
    @Override public int reserveSubmitIntent(String tenantId, String clientId, String ownerJiacn,
            String draftId, long expectedRevision, String submitKey, String submitHash,
            long updatedAt) {
        scope(tenantId, clientId, ownerJiacn); id(draftId, "draftId", 100);
        id(submitKey, "submitKey", 100); id(submitHash, "submitHash", 64);
        positive(expectedRevision, "revision"); nonnegative(updatedAt, "updatedAt");
        return mapper.reserveSubmitIntent(tenantId, clientId, ownerJiacn, draftId,
                expectedRevision, submitKey, submitHash, updatedAt);
    }
    @Override public int markSubmitted(String tenantId, String clientId, String ownerJiacn,
            String draftId, long expectedRevision, String submitKey, String submitHash,
            String caseId, String submissionRef, String submittedExecutionId, long updatedAt) {
        scope(tenantId, clientId, ownerJiacn); id(draftId, "draftId", 100);
        id(submitKey, "submitKey", 100); id(submitHash, "submitHash", 64);
        if (caseId != null) id(caseId, "caseId", 100);
        id(submissionRef, "submissionRef", 100);
        // TASK_CREATE has no execution. The UPDATE additionally checks the persisted kind,
        // so null cannot turn an execution-bearing draft into a task-only receipt.
        if (submittedExecutionId != null) id(submittedExecutionId, "submittedExecutionId", 100);
        else if (caseId != null) throw new IllegalArgumentException("submittedExecutionId");
        positive(expectedRevision, "revision"); nonnegative(updatedAt, "updatedAt");
        return mapper.markSubmitted(tenantId, clientId, ownerJiacn, draftId,
                expectedRevision, submitKey, submitHash, caseId, submissionRef,
                submittedExecutionId, updatedAt);
    }
    @Override public int discardEditing(String tenantId, String clientId, String ownerJiacn,
            String draftId, long expectedRevision, String discardKey, String discardHash,
            long updatedAt) {
        scope(tenantId, clientId, ownerJiacn); id(draftId, "draftId", 100);
        id(discardKey, "discardKey", 100); id(discardHash, "discardHash", 64);
        positive(expectedRevision, "revision"); nonnegative(updatedAt, "updatedAt");
        return mapper.discardEditing(tenantId, clientId, ownerJiacn, draftId,
                expectedRevision, discardKey, discardHash, updatedAt);
    }
    @Override public boolean privateCommittedOutputExists(String tenantId, String clientId,
            String ownerJiacn, String executionId, String outputId, String fileId, int fileVersion) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId", 100);
        id(outputId, "outputId", 100); id(fileId, "fileId", 100);
        if (fileVersion < 1) throw new IllegalArgumentException("fileVersion");
        return mapper.countPrivateCommittedOutput(tenantId, clientId, ownerJiacn,
                executionId, outputId, fileId, fileVersion) == 1;
    }
    @Override public boolean lockPrivateCommittedOutputExists(String tenantId, String clientId,
            String ownerJiacn, String executionId, String outputId, String fileId, int fileVersion) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId", 100);
        id(outputId, "outputId", 100); id(fileId, "fileId", 100);
        if (fileVersion < 1) throw new IllegalArgumentException("fileVersion");
        return mapper.lockPrivateCommittedOutput(tenantId, clientId, ownerJiacn,
                executionId, outputId, fileId, fileVersion) != null;
    }
    @Override public List<HallExecutionResultRow> listPrivateExecutionResults(String tenantId,
            String clientId, String ownerJiacn, String executionId) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId", 100);
        return mapper.listPrivateExecutionResults(tenantId, clientId, ownerJiacn, executionId);
    }

    private static void scope(String tenant, String client, String owner) {
        if (!"0".equals(tenant)) throw new IllegalArgumentException("tenantId");
        id(client, "clientId", 50); id(owner, "ownerJiacn", 50);
        if ("0".equals(owner)) throw new IllegalArgumentException("ownerJiacn");
    }
    private static void id(String value, String field, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field);
        }
    }
    private static void positive(long value, String field) {
        if (value < 1) throw new IllegalArgumentException(field);
    }
    private static void nonnegative(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field);
    }
}
