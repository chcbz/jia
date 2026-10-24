package cn.jia.agent.dao;
import cn.jia.agent.entity.*;
import java.util.List;
public interface AgentCommandInboxV2Dao {
    AgentCommandDeliveryEntity lockDelivery(AgentInboxMessage message);
    AgentOutboxEventEntity lockOutbox(AgentInboxMessage message);
    List<AgentOutboxEventEntity> lockPrevious(AgentInboxMessage message,int activeAttempt);
    AgentConsumerInboxEntity lockInbox(AgentInboxMessage message);
    int insert(AgentConsumerInboxEntity inbox);
    int transitionDelivery(AgentInboxMessage message,int attempt,long version,String expectedStatus,
            String newStatus,Long nextRetryAt,String error,long now);
    int reclaimProcessingInbox(AgentInboxMessage message,AgentConsumerInboxEntity inbox,
            String owner,long leaseUntil,long now);
    int takeoverProcessingInbox(AgentInboxMessage message, AgentConsumerInboxEntity inbox,
            String owner, long leaseUntil, long deliveryVersion, long now);
    int reclaimRetryInbox(AgentInboxMessage message,AgentConsumerInboxEntity inbox,
            String owner,long leaseUntil,long deliveryVersion,long now);
    int takeoverRetryInbox(AgentInboxMessage message, AgentConsumerInboxEntity inbox,
            String owner, long leaseUntil, long deliveryVersion, long now);
    int expireProcessingInbox(AgentInboxMessage message,AgentConsumerInboxEntity inbox,
            String error,long now);
    int expireRetryInbox(AgentInboxMessage message,AgentConsumerInboxEntity inbox,
            String error,long now);
    int settleInbox(AgentInboxClaimToken token,String status,String result,Long nextRetryAt,String error,long now);
    int settleDeliveryFromClaim(AgentInboxClaimToken token, AgentConsumerInboxEntity inbox,
            String expectedStatus, String newStatus, Long nextRetryAt, String error, long now);
    int advanceAcknowledgement(AgentInboxClaimToken token, AgentConsumerInboxEntity inbox,
            long expectedDeliveryVersion, String expectedStatus, String newStatus,
            String error, long now);
    int advanceSettledAcknowledgement(AgentInboxClaimToken token,
            AgentConsumerInboxEntity inbox, long expectedDeliveryVersion,
            String expectedStatus, String newStatus, String error, long now);
    int recordAcknowledgement(AgentInboxClaimToken token, AgentConsumerInboxEntity inbox,
            String ackMessageId, String ackStatus, long ackAt, String agentId,
            String runtimeInstallationId, String runtimeInstanceId,
            long ackDeliveryVersion, long now);
    int recordSettledAcknowledgement(AgentInboxClaimToken token,
            AgentConsumerInboxEntity inbox, String ackMessageId, String ackStatus,
            long ackAt, String agentId, String runtimeInstallationId,
            String runtimeInstanceId, long ackDeliveryVersion, long now);
    int settleInboxAfterAcknowledgement(AgentInboxClaimToken token,
            AgentConsumerInboxEntity inbox, long now);
}
