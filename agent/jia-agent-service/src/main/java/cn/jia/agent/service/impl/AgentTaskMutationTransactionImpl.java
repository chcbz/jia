package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgentTaskMutationTransactionImpl implements AgentTaskMutationTransaction {
    private final AgentTaskMetaDao taskMetaDao;
    private final TransactionTemplate requiredTransaction;

    public AgentTaskMutationTransactionImpl(
            AgentTaskMetaDao taskMetaDao,
            PlatformTransactionManager transactionManager) {
        if (taskMetaDao == null) {
            throw new IllegalArgumentException("taskMetaDao must not be null");
        }
        if (transactionManager == null) {
            throw new IllegalArgumentException("transactionManager must not be null");
        }
        this.taskMetaDao = taskMetaDao;
        this.requiredTransaction = new TransactionTemplate(transactionManager);
        this.requiredTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public <T> T executeWithLockedTaskRoot(
            String tenantId,
            String clientId,
            String taskId,
            LockedTaskMutation<T> mutation) {
        requireScope(tenantId, clientId, taskId);
        if (mutation == null) {
            throw new IllegalArgumentException("mutation must not be null");
        }
        return requiredTransaction.execute(status ->
                mutation.apply(lockAndValidateRoot(tenantId, clientId, taskId)));
    }

    @Override
    public <T> T executeAfterTaskRootReservation(
            String tenantId,
            String clientId,
            String taskId,
            TaskRootReservation reservation,
            ReservedTaskMutation<T> mutation) {
        requireScope(tenantId, clientId, taskId);
        if (reservation == null) {
            throw new IllegalArgumentException("reservation must not be null");
        }
        if (mutation == null) {
            throw new IllegalArgumentException("mutation must not be null");
        }
        return requiredTransaction.execute(status -> {
            int reserved = reservation.reserve();
            if (reserved != 0 && reserved != 1) {
                throw new IllegalStateException(
                        "Task root reservation returned " + reserved + " rows; expected 0 or 1");
            }
            AgentTaskMetaEntity root = lockAndValidateRoot(tenantId, clientId, taskId);
            return mutation.apply(root, reserved == 1);
        });
    }

    private AgentTaskMetaEntity lockAndValidateRoot(
            String tenantId, String clientId, String taskId) {
        AgentTaskMetaEntity root = taskMetaDao.findByTaskIdForUpdate(
                tenantId, clientId, taskId);
        if (root == null) {
            throw new AgentTaskCollaborationException(
                    Reason.NOT_FOUND, "Task not found in requested scope");
        }
        if (!tenantId.equals(root.getTenantId())
                || !clientId.equals(root.getClientId())
                || !taskId.equals(root.getTaskId())
                || root.getTaskVersion() == null
                || root.getTaskVersion() < 0
                || root.getCurrentEventVersion() == null
                || root.getCurrentEventVersion() < 0) {
            throw new AgentTaskCollaborationException(
                    Reason.INVALID_PERSISTED_STATE,
                    "Locked task root does not match the requested scope or version contract");
        }
        return root;
    }

    private void requireScope(String tenantId, String clientId, String taskId) {
        if (!isValidScopeId(tenantId, 50)) {
            throw new IllegalArgumentException("tenantId is invalid");
        }
        if (!isValidScopeId(clientId, 50)) {
            throw new IllegalArgumentException("clientId is invalid");
        }
        if (!isValidScopeId(taskId, 100)) {
            throw new IllegalArgumentException("taskId is invalid");
        }
    }

    boolean isValidScopeId(String value, int maxLength) {
        return value != null
                && !value.isBlank()
                && value.length() <= maxLength
                && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }
}
