package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentWorkItemReassignmentDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentWorkItemReassignmentEntity;
import cn.jia.agent.mapper.AgentWorkItemReassignmentMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named
public class AgentWorkItemReassignmentDaoImpl implements AgentWorkItemReassignmentDao {
    private final AgentWorkItemReassignmentMapper mapper;

    @Inject
    public AgentWorkItemReassignmentDaoImpl(AgentWorkItemReassignmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AgentWorkItemReassignmentEntity findByReassignmentIdForUpdate(
            String tenantId, String clientId, String taskId, String workItemId,
            String reassignmentId) {
        require(tenantId, clientId, taskId, workItemId, reassignmentId);
        return mapper.selectReceiptForUpdate(
                tenantId, clientId, taskId, workItemId, reassignmentId);
    }

    @Override
    public AgentWorkItemReassignmentEntity findLatestByWorkItemForUpdate(
            String tenantId, String clientId, String taskId, String workItemId) {
        require(tenantId, clientId, taskId, workItemId, "latest");
        return mapper.selectLatestReceiptForUpdate(tenantId, clientId, taskId, workItemId);
    }

    @Override
    public AgentCommandDeliveryEntity findSourceCommand(
            String tenantId, String clientId, String commandId) {
        require(tenantId, clientId, commandId, "source", "source");
        return mapper.selectSourceCommand(tenantId, clientId, commandId);
    }

    @Override
    public int insert(AgentWorkItemReassignmentEntity receipt) {
        if (receipt == null) throw new IllegalArgumentException("receipt is required");
        require(receipt.getTenantId(), receipt.getClientId(), receipt.getTaskId(),
                receipt.getWorkItemId(), receipt.getReassignmentId());
        return mapper.insertReceipt(receipt);
    }

    private void require(String tenantId, String clientId, String first, String second, String third) {
        if (!exact(tenantId, 50) || !exact(clientId, 50)
                || !exact(first, 100) || !exact(second, 100) || !exact(third, 100)) {
            throw new IllegalArgumentException("reassignment DAO scope or identity is invalid");
        }
    }

    private boolean exact(String value, int max) {
        return value != null && !value.isBlank() && !hasUnpairedSurrogate(value)
                && value.equals(value.strip()) && value.codePointCount(0, value.length()) <= max
                && value.chars().noneMatch(Character::isISOControl);
    }

    private boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }
}
