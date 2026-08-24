package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentWaitingCommandCandidate;

import java.util.List;

public interface AgentCommandRecoveryDao {
    List<AgentWaitingCommandCandidate> findReconnectCandidates(
            String tenantId, String clientId, String targetAgentId,
            long now, long afterDeliveryId, int limit);
    List<AgentWaitingCommandCandidate> findDueCandidates(long now, long afterDeliveryId, int limit);
    AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, long deliveryId);
    AgentCommandDeliveryEntity lockDeliveryByCommand(String tenantId, String clientId, String commandId);
    List<AgentOutboxEventEntity> lockActiveOutboxes(
            String tenantId, String clientId, long deliveryId, String messageId);
    AgentConsumerInboxEntity lockInbox(
            String tenantId, String clientId, String consumerName, String messageId);
    int reissueDelivery(AgentCommandDeliveryEntity delivery, String newMessageId,
            String requestedBy, String reason, String lastError, long now);
    int insertOutbox(AgentOutboxEventEntity outbox);
    int advanceAck(AgentCommandDeliveryEntity delivery, String newStatus, String lastError, long now);
}
