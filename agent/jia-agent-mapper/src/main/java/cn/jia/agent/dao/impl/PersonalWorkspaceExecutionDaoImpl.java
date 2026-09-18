package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionInputEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionInputMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionOutputMapper;
import cn.jia.core.util.DateUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;
import java.util.Objects;

@Named
public class PersonalWorkspaceExecutionDaoImpl implements PersonalWorkspaceExecutionDao {
    private final PersonalWorkspaceExecutionMapper executions;
    private final PersonalWorkspaceExecutionInputMapper inputs;
    private final PersonalWorkspaceExecutionOutputMapper outputs;

    @Inject
    public PersonalWorkspaceExecutionDaoImpl(PersonalWorkspaceExecutionMapper executions,
            PersonalWorkspaceExecutionInputMapper inputs,
            PersonalWorkspaceExecutionOutputMapper outputs) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.outputs = Objects.requireNonNull(outputs, "outputs");
    }
    @Override public PersonalWorkspaceExecutionEntity find(String tenantId, String clientId,
            String ownerJiacn, String executionId) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId");
        return executions.findExact(tenantId, clientId, ownerJiacn, executionId);
    }
    @Override public PersonalWorkspaceExecutionEntity findByIdempotency(String tenantId,
            String clientId, String ownerJiacn, String idempotencyKey) {
        scope(tenantId, clientId, ownerJiacn); id(idempotencyKey, "idempotencyKey");
        return executions.findByIdempotency(tenantId, clientId, ownerJiacn, idempotencyKey);
    }
    @Override public PersonalWorkspaceExecutionEntity findByRevokeIdempotency(String tenantId,
            String clientId, String ownerJiacn, String idempotencyKey) {
        scope(tenantId, clientId, ownerJiacn); id(idempotencyKey, "idempotencyKey");
        return executions.findByRevokeIdempotency(tenantId, clientId, ownerJiacn, idempotencyKey);
    }
    @Override public PersonalWorkspaceExecutionEntity findByTaskRun(String tenantId, String clientId,
            String ownerJiacn, String taskId, String runId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId"); id(runId, "runId");
        return executions.findByTaskRun(tenantId, clientId, ownerJiacn, taskId, runId);
    }
    @Override public PersonalWorkspaceExecutionEntity lock(String tenantId, String clientId,
            String ownerJiacn, String executionId) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId");
        return executions.lockExact(tenantId, clientId, ownerJiacn, executionId);
    }
    @Override public PersonalWorkspaceExecutionEntity lockByTaskRun(String tenantId, String clientId,
            String ownerJiacn, String taskId, String runId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId"); id(runId, "runId");
        return executions.lockByTaskRun(tenantId, clientId, ownerJiacn, taskId, runId);
    }
    @Override public List<PersonalWorkspaceExecutionEntity> listQueuedByTarget(String tenantId, String clientId,
            String ownerJiacn, String targetAgentId, int limit) {
        scope(tenantId, clientId, ownerJiacn); id(targetAgentId, "targetAgentId");
        return executions.listQueuedByTarget(tenantId, clientId, ownerJiacn, targetAgentId,
                Math.max(1, Math.min(limit, 16)));
    }
    @Override public void insert(PersonalWorkspaceExecutionEntity execution) { init(execution); executions.insert(execution); }
    @Override public void update(PersonalWorkspaceExecutionEntity execution) { execution.setUpdateTime(DateUtil.nowTime()); executions.updateById(execution); }
    @Override public void insertInput(PersonalWorkspaceExecutionInputEntity input) { init(input); inputs.insert(input); }
    @Override public List<PersonalWorkspaceExecutionInputEntity> listInputs(String tenantId,
            String clientId, String ownerJiacn, String executionId) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId");
        return inputs.listByExecution(tenantId, clientId, ownerJiacn, executionId);
    }
    @Override public PersonalWorkspaceExecutionInputEntity findInput(String tenantId, String clientId,
            String ownerJiacn, String executionId, String inputRef) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId"); id(inputRef, "inputRef");
        return inputs.findByReference(tenantId, clientId, ownerJiacn, executionId, inputRef);
    }
    @Override public PersonalWorkspaceExecutionInputEntity lockInput(String tenantId, String clientId,
            String ownerJiacn, String executionId, String inputRef) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId"); id(inputRef, "inputRef");
        return inputs.lockByReference(tenantId, clientId, ownerJiacn, executionId, inputRef);
    }
    @Override public List<String> listActiveExecutionIdsByFile(String tenantId, String clientId,
            String ownerJiacn, String fileId, int limit) {
        scope(tenantId, clientId, ownerJiacn); id(fileId, "fileId");
        return inputs.listActiveExecutionIdsByFile(tenantId, clientId, ownerJiacn, fileId,
                Math.max(1, Math.min(limit, 100)));
    }
    @Override public void updateInput(PersonalWorkspaceExecutionInputEntity input) { input.setUpdateTime(DateUtil.nowTime()); inputs.updateById(input); }
    @Override public PersonalWorkspaceExecutionOutputEntity lockOutput(String tenantId,
            String clientId, String ownerJiacn, String executionId, String outputId) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId"); id(outputId, "outputId");
        return outputs.lockByOutputId(tenantId, clientId, ownerJiacn, executionId, outputId);
    }
    @Override public List<PersonalWorkspaceExecutionOutputEntity> lockOutputs(String tenantId,
            String clientId, String ownerJiacn, String executionId) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId");
        return outputs.lockByExecution(tenantId, clientId, ownerJiacn, executionId);
    }
    @Override public void insertOutput(PersonalWorkspaceExecutionOutputEntity output) { init(output); outputs.insert(output); }
    @Override public void updateOutput(PersonalWorkspaceExecutionOutputEntity output) { output.setUpdateTime(DateUtil.nowTime()); outputs.updateById(output); }

    private static void init(cn.jia.core.entity.BaseEntity entity) { entity.init4Creation(); }
    private static void scope(String tenantId, String clientId, String ownerJiacn) {
        if (!"0".equals(tenantId)) throw new IllegalArgumentException("tenant");
        id(clientId, "clientId"); id(ownerJiacn, "ownerJiacn"); if ("0".equals(ownerJiacn)) throw new IllegalArgumentException("owner");
    }
    private static void id(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.codePointCount(0, value.length()) > 100 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(name);
    }
}
