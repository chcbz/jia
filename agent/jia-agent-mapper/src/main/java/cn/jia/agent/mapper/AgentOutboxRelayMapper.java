package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** D03 relay mapper: unlocked bounded discovery, then delivery-before-outbox ordered locks/CAS. */
public interface AgentOutboxRelayMapper {
    @Select("""
            SELECT id AS outboxId, tenant_id AS tenantId, client_id AS clientId,
                   delivery_id AS deliveryId,
                   CASE WHEN status='PENDING'
                        THEN COALESCE(next_retry_at, create_time, 0)
                        ELSE next_retry_at END AS eligibleAt
            FROM agent_outbox_event
            WHERE (status='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=#{now}))
               OR (status='RETRY' AND next_retry_at IS NOT NULL AND next_retry_at<=#{now})
            ORDER BY eligibleAt ASC, id ASC
            LIMIT #{limit}
            """)
    List<AgentOutboxCandidate> selectDueCandidates(
            @Param("now") long now, @Param("limit") int limit);

    @Select("""
            SELECT id AS outboxId, tenant_id AS tenantId, client_id AS clientId,
                   delivery_id AS deliveryId, lease_until AS eligibleAt
            FROM agent_outbox_event
            WHERE status='CLAIMED' AND lease_until IS NOT NULL AND lease_until<=#{now}
            ORDER BY lease_until ASC, id ASC
            LIMIT #{limit}
            """)
    List<AgentOutboxCandidate> selectStaleCandidates(
            @Param("now") long now, @Param("limit") int limit);

    @Select("""
            SELECT id, command_id, task_id, work_item_id, target_agent_id, command_type,
                   command_payload, command_payload_hash, status, attempt_count,
                   next_retry_at, lease_owner, lease_until, active_message_id, active_attempt,
                   expires_at, last_error, version, replay_parent_message_id,
                   replay_requester_id, replay_approver_id, replay_reason,
                   tenant_id, client_id, create_time, update_time
            FROM agent_command_delivery
            WHERE id=#{deliveryId} AND tenant_id=#{tenantId} AND client_id=#{clientId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
            LIMIT 1 FOR UPDATE
            """)
    AgentCommandDeliveryEntity selectDeliveryForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("deliveryId") long deliveryId);

    @Select("""
            SELECT id, event_id, message_id, command_id, delivery_id, aggregate_type,
                   aggregate_id, destination, routing_key, wire_payload, wire_payload_hash,
                   status, attempt_count, next_retry_at, lease_owner, lease_until,
                   active_attempt, expires_at, publisher_confirm_status, confirmed_at,
                   confirm_error, mandatory_return_status, returned_at, return_reply_code,
                   return_reply_text, published_at, last_error, version,
                   replay_parent_message_id, replay_requester_id, replay_approver_id, replay_reason,
                   tenant_id, client_id, create_time, update_time
            FROM agent_outbox_event
            WHERE id=#{outboxId} AND tenant_id=#{tenantId} AND client_id=#{clientId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
            LIMIT 1 FOR UPDATE
            """)
    AgentOutboxEventEntity selectOutboxForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("outboxId") long outboxId);

    @Update("""
            UPDATE agent_command_delivery
            SET lease_owner=#{leaseOwner}, lease_until=#{leaseUntil}, last_error=#{lastError},
                version=version+1, update_time=#{now}
            WHERE id=#{delivery.id} AND version=#{delivery.version}
              AND tenant_id=#{delivery.tenantId} AND client_id=#{delivery.clientId}
              AND command_id=#{delivery.commandId} AND active_message_id=#{delivery.activeMessageId}
              AND active_attempt=#{delivery.activeAttempt} AND status=#{delivery.status}
              AND CAST(tenant_id AS BINARY)=CAST(#{delivery.tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{delivery.tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{delivery.clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{delivery.clientId})
              AND CAST(command_id AS BINARY)=CAST(#{delivery.commandId} AS BINARY)
              AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{delivery.commandId})
              AND CAST(active_message_id AS BINARY)=CAST(#{delivery.activeMessageId} AS BINARY)
              AND OCTET_LENGTH(active_message_id)=OCTET_LENGTH(#{delivery.activeMessageId})
              AND CAST(status AS BINARY)=CAST(#{delivery.status} AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH(#{delivery.status})
            """)
    int claimDelivery(
            @Param("delivery") AgentCommandDeliveryEntity delivery,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") long leaseUntil,
            @Param("lastError") String lastError,
            @Param("now") long now);

    @Update("""
            UPDATE agent_outbox_event
            SET status='CLAIMED', attempt_count=attempt_count+1,
                next_retry_at=NULL, lease_owner=#{leaseOwner}, lease_until=#{leaseUntil},
                active_attempt=active_attempt+1,
                publisher_confirm_status='PENDING', confirmed_at=NULL, confirm_error=NULL,
                mandatory_return_status='PENDING', returned_at=NULL,
                return_reply_code=NULL, return_reply_text=NULL, published_at=NULL,
                last_error=#{lastError}, version=version+1, update_time=#{now}
            WHERE id=#{outbox.id} AND version=#{outbox.version}
              AND active_attempt=#{outbox.activeAttempt} AND attempt_count=#{outbox.attemptCount}
              AND tenant_id=#{outbox.tenantId} AND client_id=#{outbox.clientId}
              AND event_id=#{outbox.eventId} AND message_id=#{outbox.messageId}
              AND command_id=#{outbox.commandId} AND delivery_id=#{outbox.deliveryId}
              AND status=#{outbox.status}
              AND CAST(tenant_id AS BINARY)=CAST(#{outbox.tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{outbox.tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{outbox.clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{outbox.clientId})
              AND CAST(event_id AS BINARY)=CAST(#{outbox.eventId} AS BINARY)
              AND OCTET_LENGTH(event_id)=OCTET_LENGTH(#{outbox.eventId})
              AND CAST(message_id AS BINARY)=CAST(#{outbox.messageId} AS BINARY)
              AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{outbox.messageId})
              AND CAST(command_id AS BINARY)=CAST(#{outbox.commandId} AS BINARY)
              AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{outbox.commandId})
              AND CAST(status AS BINARY)=CAST(#{outbox.status} AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH(#{outbox.status})
            """)
    int claimOutbox(
            @Param("outbox") AgentOutboxEventEntity outbox,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") long leaseUntil,
            @Param("lastError") String lastError,
            @Param("now") long now);

    @Update("""
            UPDATE agent_command_delivery
            SET status=#{newStatus}, next_retry_at=#{nextRetryAt},
                lease_owner=NULL, lease_until=NULL, last_error=#{lastError},
                version=version+1, update_time=#{now}
            WHERE id=#{delivery.id} AND version=#{delivery.version}
              AND tenant_id=#{delivery.tenantId} AND client_id=#{delivery.clientId}
              AND active_attempt=#{delivery.activeAttempt} AND status=#{delivery.status}
              AND CAST(tenant_id AS BINARY)=CAST(#{delivery.tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{delivery.tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{delivery.clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{delivery.clientId})
              AND CAST(status AS BINARY)=CAST(#{delivery.status} AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH(#{delivery.status})
            """)
    int disposeDelivery(
            @Param("delivery") AgentCommandDeliveryEntity delivery,
            @Param("newStatus") String newStatus,
            @Param("nextRetryAt") Long nextRetryAt,
            @Param("lastError") String lastError,
            @Param("now") long now);

    @Update("""
            UPDATE agent_outbox_event
            SET status=#{newStatus}, next_retry_at=#{nextRetryAt},
                lease_owner=NULL, lease_until=NULL,
                publisher_confirm_status=#{confirmStatus}, confirmed_at=#{confirmedAt},
                confirm_error=#{confirmError}, mandatory_return_status=#{returnStatus},
                returned_at=#{returnedAt}, return_reply_code=#{returnReplyCode},
                return_reply_text=#{returnReplyText}, published_at=#{publishedAt},
                last_error=#{lastError}, version=version+1, update_time=#{now}
            WHERE id=#{outbox.id} AND version=#{outbox.version}
              AND active_attempt=#{outbox.activeAttempt} AND attempt_count=#{outbox.attemptCount}
              AND tenant_id=#{outbox.tenantId} AND client_id=#{outbox.clientId}
              AND status=#{outbox.status}
              AND CAST(tenant_id AS BINARY)=CAST(#{outbox.tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{outbox.tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{outbox.clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{outbox.clientId})
              AND CAST(status AS BINARY)=CAST(#{outbox.status} AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH(#{outbox.status})
            """)
    int disposeOutbox(
            @Param("outbox") AgentOutboxEventEntity outbox,
            @Param("newStatus") String newStatus,
            @Param("nextRetryAt") Long nextRetryAt,
            @Param("confirmStatus") String confirmStatus,
            @Param("confirmedAt") Long confirmedAt,
            @Param("confirmError") String confirmError,
            @Param("returnStatus") String returnStatus,
            @Param("returnedAt") Long returnedAt,
            @Param("returnReplyCode") Integer returnReplyCode,
            @Param("returnReplyText") String returnReplyText,
            @Param("publishedAt") Long publishedAt,
            @Param("lastError") String lastError,
            @Param("now") long now);
}
