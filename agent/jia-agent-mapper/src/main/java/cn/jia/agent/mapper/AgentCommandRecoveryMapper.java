package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentWaitingCommandCandidate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** D06 mapper. Discovery is unlocked; every mutation follows delivery -> active outbox -> Inbox locks. */
public interface AgentCommandRecoveryMapper {
    String DELIVERY_COLUMNS = """
            id, command_id, task_id, work_item_id, target_agent_id, command_type,
            command_payload, command_payload_hash, status, attempt_count,
            next_retry_at, lease_owner, lease_until, active_message_id, active_attempt,
            expires_at, last_error, version, replay_parent_message_id,
            replay_requester_id, replay_approver_id, replay_reason,
            tenant_id, client_id, create_time, update_time
            """;

    String OUTBOX_COLUMNS = """
            id, event_id, message_id, command_id, delivery_id, aggregate_type,
            aggregate_id, destination, routing_key, wire_payload, wire_payload_hash,
            status, attempt_count, next_retry_at, lease_owner, lease_until,
            active_attempt, expires_at, publisher_confirm_status, confirmed_at,
            confirm_error, mandatory_return_status, returned_at, return_reply_code,
            return_reply_text, published_at, last_error, version,
            replay_parent_message_id, replay_requester_id, replay_approver_id, replay_reason,
            tenant_id, client_id, create_time, update_time
            """;

    @Select("""
            SELECT id AS deliveryId, tenant_id AS tenantId, client_id AS clientId,
                   target_agent_id AS targetAgentId,
                   CASE WHEN expires_at<=#{now} THEN TRUE ELSE FALSE END AS expiryCandidate
            FROM agent_command_delivery
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
              AND target_agent_id=#{targetAgentId}
              AND status IN ('WAITING_AGENT','SENT') AND expires_at>#{now}
              AND id>#{afterDeliveryId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(target_agent_id AS BINARY)=CAST(#{targetAgentId} AS BINARY)
              AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{targetAgentId})
              AND (CAST(status AS BINARY)=CAST('WAITING_AGENT' AS BINARY)
                   OR CAST(status AS BINARY)=CAST('SENT' AS BINARY))
              AND OCTET_LENGTH(status)=CHAR_LENGTH(status)
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<AgentWaitingCommandCandidate> selectReconnectCandidates(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("targetAgentId") String targetAgentId,
            @Param("now") long now,
            @Param("afterDeliveryId") long afterDeliveryId,
            @Param("limit") int limit);

    @Select("""
            SELECT id AS deliveryId, tenant_id AS tenantId, client_id AS clientId,
                   target_agent_id AS targetAgentId,
                   CASE WHEN expires_at<=#{now} THEN TRUE ELSE FALSE END AS expiryCandidate
            FROM agent_command_delivery
            WHERE (
                  (status='WAITING_AGENT' AND (
                      expires_at<=#{now}
                      OR (next_retry_at IS NOT NULL AND next_retry_at<=#{now} AND expires_at>#{now})))
                  OR (status='SENT' AND (
                      expires_at<=#{now}
                      OR (update_time<=#{sentBefore} AND expires_at>#{now})))
              ) AND id>#{afterDeliveryId}
              AND (CAST(status AS BINARY)=CAST('WAITING_AGENT' AS BINARY)
                   OR CAST(status AS BINARY)=CAST('SENT' AS BINARY))
              AND OCTET_LENGTH(status)=CHAR_LENGTH(status)
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<AgentWaitingCommandCandidate> selectDueCandidates(
            @Param("now") long now,
            @Param("sentBefore") long sentBefore,
            @Param("afterDeliveryId") long afterDeliveryId,
            @Param("limit") int limit);

    @Select("SELECT " + DELIVERY_COLUMNS + " FROM agent_command_delivery " +
            "WHERE id=#{deliveryId} AND tenant_id=#{tenantId} AND client_id=#{clientId} " +
            "AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY) " +
            "AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId}) " +
            "AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY) " +
            "AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId}) LIMIT 1 FOR UPDATE")
    AgentCommandDeliveryEntity selectDeliveryForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("deliveryId") long deliveryId);

    @Select("SELECT " + DELIVERY_COLUMNS + " FROM agent_command_delivery " +
            "WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND command_id=#{commandId} " +
            "AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY) " +
            "AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId}) " +
            "AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY) " +
            "AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId}) " +
            "AND CAST(command_id AS BINARY)=CAST(#{commandId} AS BINARY) " +
            "AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{commandId}) LIMIT 1 FOR UPDATE")
    AgentCommandDeliveryEntity selectDeliveryByCommandForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("commandId") String commandId);

    @Select("SELECT " + OUTBOX_COLUMNS + " FROM agent_outbox_event " +
            "WHERE tenant_id=#{tenantId} AND client_id=#{clientId} " +
            "AND delivery_id=#{deliveryId} AND message_id=#{messageId} " +
            "AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY) " +
            "AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId}) " +
            "AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY) " +
            "AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId}) " +
            "AND CAST(message_id AS BINARY)=CAST(#{messageId} AS BINARY) " +
            "AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{messageId}) ORDER BY id ASC LIMIT 2 FOR UPDATE")
    List<AgentOutboxEventEntity> selectActiveOutboxesForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("deliveryId") long deliveryId,
            @Param("messageId") String messageId);

    @Select("SELECT " + OUTBOX_COLUMNS + " FROM agent_outbox_event " +
            "WHERE tenant_id=#{tenantId} AND client_id=#{clientId} " +
            "AND delivery_id=#{deliveryId} AND active_attempt=#{previousAttempt} " +
            "AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY) " +
            "AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId}) " +
            "AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY) " +
            "AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId}) " +
            "ORDER BY id ASC LIMIT 2 FOR UPDATE")
    List<AgentOutboxEventEntity> selectPreviousAttemptOutboxesForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("deliveryId") long deliveryId,
            @Param("previousAttempt") int previousAttempt);

    @Select("""
            SELECT id, consumer_name, message_id, event_id, command_id, delivery_id,
                   wire_payload, wire_payload_hash, status, result_status, attempt_count,
                   next_retry_at, lease_owner, lease_until, active_attempt, expires_at,
                   processed_at, last_error, version, replay_parent_message_id,
                   replay_requester_id, replay_approver_id, replay_reason,
                   tenant_id, client_id, create_time, update_time
            FROM agent_consumer_inbox
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
              AND consumer_name=#{consumerName} AND message_id=#{messageId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(consumer_name AS BINARY)=CAST(#{consumerName} AS BINARY)
              AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{consumerName})
              AND CAST(message_id AS BINARY)=CAST(#{messageId} AS BINARY)
              AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{messageId})
            LIMIT 1 FOR UPDATE
            """)
    AgentConsumerInboxEntity selectInboxForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("consumerName") String consumerName,
            @Param("messageId") String messageId);

    @Update("""
            UPDATE agent_command_delivery
            SET status='PENDING', attempt_count=attempt_count+1, next_retry_at=NULL,
                lease_owner=NULL, lease_until=NULL, active_message_id=#{newMessageId},
                active_attempt=active_attempt+1, last_error=#{lastError},
                replay_parent_message_id=#{parentMessageId},
                replay_requester_id=#{requestedBy}, replay_approver_id=NULL,
                replay_reason=#{reason}, version=version+1, update_time=#{now}
            WHERE id=#{delivery.id} AND version=#{delivery.version}
              AND tenant_id=#{delivery.tenantId} AND client_id=#{delivery.clientId}
              AND command_id=#{delivery.commandId} AND task_id=#{delivery.taskId}
              AND target_agent_id=#{delivery.targetAgentId}
              AND active_message_id=#{delivery.activeMessageId}
              AND active_attempt=#{delivery.activeAttempt}
              AND attempt_count=#{delivery.attemptCount}
              AND ((next_retry_at=#{delivery.nextRetryAt})
                   OR (next_retry_at IS NULL AND #{delivery.nextRetryAt} IS NULL))
              AND status=#{delivery.status}
              AND CAST(tenant_id AS BINARY)=CAST(#{delivery.tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{delivery.tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{delivery.clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{delivery.clientId})
              AND CAST(command_id AS BINARY)=CAST(#{delivery.commandId} AS BINARY)
              AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{delivery.commandId})
              AND CAST(task_id AS BINARY)=CAST(#{delivery.taskId} AS BINARY)
              AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{delivery.taskId})
              AND CAST(target_agent_id AS BINARY)=CAST(#{delivery.targetAgentId} AS BINARY)
              AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{delivery.targetAgentId})
              AND CAST(active_message_id AS BINARY)=CAST(#{delivery.activeMessageId} AS BINARY)
              AND OCTET_LENGTH(active_message_id)=OCTET_LENGTH(#{delivery.activeMessageId})
              AND CAST(status AS BINARY)=CAST(#{delivery.status} AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH(#{delivery.status})
            """)
    int reissueDelivery(
            @Param("delivery") AgentCommandDeliveryEntity delivery,
            @Param("newMessageId") String newMessageId,
            @Param("parentMessageId") String parentMessageId,
            @Param("requestedBy") String requestedBy,
            @Param("reason") String reason,
            @Param("lastError") String lastError,
            @Param("now") long now);

    @Update("""
            UPDATE agent_command_delivery
            SET status='EXPIRED', next_retry_at=NULL, lease_owner=NULL, lease_until=NULL,
                last_error=#{lastError}, version=version+1, update_time=#{now}
            WHERE id=#{delivery.id} AND version=#{delivery.version}
              AND tenant_id=#{delivery.tenantId} AND client_id=#{delivery.clientId}
              AND command_id=#{delivery.commandId} AND task_id=#{delivery.taskId}
              AND target_agent_id=#{delivery.targetAgentId}
              AND active_message_id=#{delivery.activeMessageId}
              AND active_attempt=#{delivery.activeAttempt} AND status=#{delivery.status}
              AND CAST(tenant_id AS BINARY)=CAST(#{delivery.tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{delivery.tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{delivery.clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{delivery.clientId})
              AND CAST(active_message_id AS BINARY)=CAST(#{delivery.activeMessageId} AS BINARY)
              AND OCTET_LENGTH(active_message_id)=OCTET_LENGTH(#{delivery.activeMessageId})
              AND CAST(status AS BINARY)=CAST(#{delivery.status} AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH(#{delivery.status})
            """)
    int expireDelivery(
            @Param("delivery") AgentCommandDeliveryEntity delivery,
            @Param("lastError") String lastError,
            @Param("now") long now);

    @Update("""
            UPDATE agent_consumer_inbox
            SET status='EXPIRED', result_status='EXPIRED', next_retry_at=NULL,
                lease_owner=NULL, lease_until=NULL, processed_at=#{now},
                last_error=#{lastError}, version=version+1, update_time=#{now}
            WHERE id=#{inbox.id} AND version=#{inbox.version}
              AND active_attempt=#{inbox.activeAttempt}
              AND tenant_id=#{inbox.tenantId} AND client_id=#{inbox.clientId}
              AND consumer_name=#{inbox.consumerName} AND message_id=#{inbox.messageId}
              AND status='WAITING_AGENT' AND result_status='WAITING_AGENT'
              AND CAST(tenant_id AS BINARY)=CAST(#{inbox.tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{inbox.tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{inbox.clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{inbox.clientId})
              AND CAST(consumer_name AS BINARY)=CAST(#{inbox.consumerName} AS BINARY)
              AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{inbox.consumerName})
              AND CAST(message_id AS BINARY)=CAST(#{inbox.messageId} AS BINARY)
              AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{inbox.messageId})
              AND CAST(status AS BINARY)=CAST('WAITING_AGENT' AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH('WAITING_AGENT')
            """)
    int expireWaitingInbox(
            @Param("inbox") AgentConsumerInboxEntity inbox,
            @Param("lastError") String lastError,
            @Param("now") long now);

    @Insert("""
            INSERT INTO agent_outbox_event
              (event_id,message_id,command_id,delivery_id,aggregate_type,aggregate_id,
               destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,
               next_retry_at,lease_owner,lease_until,active_attempt,expires_at,
               publisher_confirm_status,mandatory_return_status,last_error,version,
               replay_parent_message_id,replay_requester_id,replay_approver_id,replay_reason,
               tenant_id,client_id,create_time,update_time)
            VALUES
              (#{eventId},#{messageId},#{commandId},#{deliveryId},#{aggregateType},#{aggregateId},
               #{destination},#{routingKey},#{wirePayload},#{wirePayloadHash},#{status},#{attemptCount},
               #{nextRetryAt},#{leaseOwner},#{leaseUntil},#{activeAttempt},#{expiresAt},
               #{publisherConfirmStatus},#{mandatoryReturnStatus},#{lastError},#{version},
               #{replayParentMessageId},#{replayRequesterId},#{replayApproverId},#{replayReason},
               #{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOutbox(AgentOutboxEventEntity outbox);

    @Update("""
            UPDATE agent_command_delivery
            SET status=#{newStatus}, next_retry_at=NULL, lease_owner=NULL, lease_until=NULL,
                last_error=#{lastError}, version=version+1, update_time=#{now}
            WHERE id=#{delivery.id} AND version=#{delivery.version}
              AND tenant_id=#{delivery.tenantId} AND client_id=#{delivery.clientId}
              AND command_id=#{delivery.commandId} AND task_id=#{delivery.taskId}
              AND target_agent_id=#{delivery.targetAgentId}
              AND active_message_id=#{delivery.activeMessageId}
              AND active_attempt=#{delivery.activeAttempt} AND status=#{delivery.status}
              AND CAST(tenant_id AS BINARY)=CAST(#{delivery.tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{delivery.tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{delivery.clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{delivery.clientId})
              AND CAST(command_id AS BINARY)=CAST(#{delivery.commandId} AS BINARY)
              AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{delivery.commandId})
              AND CAST(task_id AS BINARY)=CAST(#{delivery.taskId} AS BINARY)
              AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{delivery.taskId})
              AND CAST(target_agent_id AS BINARY)=CAST(#{delivery.targetAgentId} AS BINARY)
              AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{delivery.targetAgentId})
              AND CAST(active_message_id AS BINARY)=CAST(#{delivery.activeMessageId} AS BINARY)
              AND OCTET_LENGTH(active_message_id)=OCTET_LENGTH(#{delivery.activeMessageId})
              AND CAST(status AS BINARY)=CAST(#{delivery.status} AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH(#{delivery.status})
            """)
    int advanceAck(
            @Param("delivery") AgentCommandDeliveryEntity delivery,
            @Param("newStatus") String newStatus,
            @Param("lastError") String lastError,
            @Param("now") long now);
}
