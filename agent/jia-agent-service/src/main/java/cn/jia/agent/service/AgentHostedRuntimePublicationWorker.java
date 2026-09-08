package cn.jia.agent.service;

import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Rereads the committed runtime projection before a hosted after-commit publication. */
@Service
@RequiredArgsConstructor
public class AgentHostedRuntimePublicationWorker {
    private final AgentRuntimeDao runtimeDao;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public AgentRuntimeEntity revalidateForPublication(
            AgentHostedBindingTransaction.Scope scope, String agentId, long bindingId) {
        if (scope == null || agentId == null || agentId.isBlank()
                || !agentId.equals(agentId.strip()) || bindingId <= 0) {
            return null;
        }
        AgentRuntimeEntity current = runtimeDao.findByAgentId(agentId);
        if (current == null
                || !Objects.equals(current.getAgentId(), agentId)
                || !Objects.equals(current.getBindingId(), bindingId)
                || !Objects.equals(current.getClientId(), scope.clientId())
                || !Objects.equals(current.getOwnerJiacn(), scope.ownerJiacn())) {
            return null;
        }
        return current;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public AgentRuntimeEntity revalidateDetachedForPublication(
            AgentHostedBindingTransaction.Scope scope, String agentId, long runtimeId) {
        if (scope == null || agentId == null || agentId.isBlank()
                || !agentId.equals(agentId.strip()) || runtimeId <= 0) {
            return null;
        }
        AgentRuntimeEntity current = runtimeDao.findByAgentId(agentId);
        if (current == null
                || !Objects.equals(current.getId(), runtimeId)
                || !Objects.equals(current.getAgentId(), agentId)
                || current.getBindingId() != null
                || current.getClientId() != null
                || current.getOwnerJiacn() != null
                || current.getPersonaCode() != null
                || current.getPersonaName() != null
                || current.getEndpoint() != null
                || current.getTokenHash() != null
                || current.getCurrentTaskId() != null
                || current.getCurrentTaskTitle() != null
                || current.getErrorMessage() != null
                || !Objects.equals(current.getStatus(), "offline")) {
            return null;
        }
        return current;
    }
}
