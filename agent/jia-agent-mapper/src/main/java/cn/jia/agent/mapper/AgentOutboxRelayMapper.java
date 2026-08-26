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
    String DISCOVERY_ROW_VALID = """
            o.delivery_id>0
            AND CHAR_LENGTH(o.tenant_id) BETWEEN 1 AND 50
            AND CHAR_LENGTH(o.client_id) BETWEEN 1 AND 50
            AND CAST(o.tenant_id AS BINARY)=CAST(TRIM(o.tenant_id) AS BINARY)
            AND OCTET_LENGTH(o.tenant_id)=OCTET_LENGTH(TRIM(o.tenant_id))
            AND CAST(o.client_id AS BINARY)=CAST(TRIM(o.client_id) AS BINARY)
            AND OCTET_LENGTH(o.client_id)=OCTET_LENGTH(TRIM(o.client_id))
            AND o.tenant_id NOT REGEXP CONCAT('[',CHAR(92),'p{Cc}]')
            AND o.client_id NOT REGEXP CONCAT('[',CHAR(92),'p{Cc}]')
            AND o.version<9223372036854775806
            AND EXISTS (
                SELECT 1 FROM agent_command_delivery d
                WHERE d.id=o.delivery_id
                  AND CAST(d.tenant_id AS BINARY)=CAST(o.tenant_id AS BINARY)
                  AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(o.tenant_id)
                  AND CAST(d.client_id AS BINARY)=CAST(o.client_id AS BINARY)
                  AND OCTET_LENGTH(d.client_id)=OCTET_LENGTH(o.client_id)
            )
            AND NOT EXISTS (
                SELECT 1 FROM agent_command_delivery d
                WHERE d.id=o.delivery_id AND d.version>=9223372036854775806
                  AND CAST(d.tenant_id AS BINARY)=CAST(o.tenant_id AS BINARY)
                  AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(o.tenant_id)
                  AND CAST(d.client_id AS BINARY)=CAST(o.client_id AS BINARY)
                  AND OCTET_LENGTH(d.client_id)=OCTET_LENGTH(o.client_id)
                  AND CAST(d.command_id AS BINARY)=CAST(o.command_id AS BINARY)
                  AND OCTET_LENGTH(d.command_id)=OCTET_LENGTH(o.command_id)
                  AND CAST(d.active_message_id AS BINARY)=CAST(o.message_id AS BINARY)
                  AND OCTET_LENGTH(d.active_message_id)=OCTET_LENGTH(o.message_id)
                  AND CAST(d.task_id AS BINARY)=CAST(o.aggregate_id AS BINARY)
                  AND OCTET_LENGTH(d.task_id)=OCTET_LENGTH(o.aggregate_id)
                  AND CAST(o.aggregate_type AS BINARY)=CAST('task' AS BINARY)
                  AND OCTET_LENGTH(o.aggregate_type)=4
            )
            """;

    @Select("""
            SELECT o.id AS outboxId, o.tenant_id AS tenantId, o.client_id AS clientId,
                   o.delivery_id AS deliveryId,
                   CASE WHEN o.status='CLAIMED' THEN o.lease_until
                        WHEN o.status='PENDING' THEN COALESCE(o.next_retry_at,o.create_time,0)
                        ELSE o.next_retry_at END AS eligibleAt,
                   o.version AS outboxVersion, o.status AS outboxStatus
            FROM agent_outbox_event o
            WHERE ((o.status='PENDING' AND (o.next_retry_at IS NULL OR o.next_retry_at<=#{now}))
                OR (o.status='RETRY' AND o.next_retry_at IS NOT NULL AND o.next_retry_at<=#{now})
                OR (o.status='CLAIMED' AND o.lease_until IS NOT NULL AND o.lease_until<=#{now}))
              AND NOT (
            """ + DISCOVERY_ROW_VALID + """
              )
            ORDER BY eligibleAt ASC, o.id ASC
            LIMIT #{limit}
            """)
    List<AgentOutboxCandidate> selectCorruptCandidates(
            @Param("now") long now, @Param("limit") int limit);

    @Select("""
            SELECT o.id AS outboxId, o.tenant_id AS tenantId, o.client_id AS clientId,
                   o.delivery_id AS deliveryId,
                   CASE WHEN o.status='PENDING'
                        THEN COALESCE(o.next_retry_at,o.create_time,0)
                        ELSE o.next_retry_at END AS eligibleAt,
                   o.version AS outboxVersion, o.status AS outboxStatus
            FROM agent_outbox_event o
            WHERE ((o.status='PENDING' AND (o.next_retry_at IS NULL OR o.next_retry_at<=#{now}))
               OR (o.status='RETRY' AND o.next_retry_at IS NOT NULL AND o.next_retry_at<=#{now}))
              AND (
            """ + DISCOVERY_ROW_VALID + """
              )
            ORDER BY eligibleAt ASC, o.id ASC
            LIMIT #{limit}
            """)
    List<AgentOutboxCandidate> selectDueCandidates(
            @Param("now") long now, @Param("limit") int limit);

    @Select("""
            SELECT o.id AS outboxId, o.tenant_id AS tenantId, o.client_id AS clientId,
                   o.delivery_id AS deliveryId, o.lease_until AS eligibleAt,
                   o.version AS outboxVersion, o.status AS outboxStatus
            FROM agent_outbox_event o
            WHERE o.status='CLAIMED' AND o.lease_until IS NOT NULL AND o.lease_until<=#{now}
              AND (
            """ + DISCOVERY_ROW_VALID + """
              )
            ORDER BY o.lease_until ASC, o.id ASC
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
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
              AND delivery_id=#{deliveryId} AND active_attempt=#{previousAttempt}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
            ORDER BY id ASC LIMIT 2 FOR UPDATE
            """)
    List<AgentOutboxEventEntity> selectPreviousAttemptOutboxesForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("deliveryId") long deliveryId,
            @Param("previousAttempt") int previousAttempt);

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
            WHERE id=#{outboxId} AND delivery_id=#{deliveryId}
              AND version=#{outboxVersion} AND status=#{outboxStatus}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(status AS BINARY)=CAST(#{outboxStatus} AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH(#{outboxStatus})
            LIMIT 1 FOR UPDATE
            """)
    AgentOutboxEventEntity selectOutboxForQuarantine(
            @Param("outboxId") long outboxId,
            @Param("deliveryId") long deliveryId,
            @Param("outboxVersion") long outboxVersion,
            @Param("outboxStatus") String outboxStatus,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId);

    @Update("""
            UPDATE agent_command_delivery
            SET lease_owner=#{leaseOwner}, lease_until=#{leaseUntil}, last_error=#{lastError},
                version=version+1, update_time=#{now}
            WHERE id=#{delivery.id} AND version=#{delivery.version}
              AND version<9223372036854775806
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
                publisher_confirm_status='PENDING', confirmed_at=NULL, confirm_error=NULL,
                mandatory_return_status='PENDING', returned_at=NULL,
                return_reply_code=NULL, return_reply_text=NULL, published_at=NULL,
                last_error=#{lastError}, version=version+1, update_time=#{now}
            WHERE id=#{outbox.id} AND version=#{outbox.version}
              AND version<9223372036854775806
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
              AND version<9223372036854775807
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
              AND version<9223372036854775807
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

    @Update("""
            UPDATE agent_command_delivery
            SET status='DEAD', next_retry_at=NULL, lease_owner=NULL, lease_until=NULL,
                last_error=#{errorCode},
                version=version+CASE WHEN version<9223372036854775807
                                     THEN 1 ELSE 0 END,
                update_time=#{now}
            WHERE id=#{delivery.id} AND version=#{delivery.version}
              AND tenant_id=#{delivery.tenantId} AND client_id=#{delivery.clientId}
              AND command_id=#{delivery.commandId}
              AND active_message_id=#{delivery.activeMessageId}
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
    int quarantineDelivery(
            @Param("delivery") AgentCommandDeliveryEntity delivery,
            @Param("errorCode") String errorCode,
            @Param("now") long now);

    @Update("""
            UPDATE agent_outbox_event
            SET status='DEAD', next_retry_at=NULL, lease_owner=NULL, lease_until=NULL,
                publisher_confirm_status='NONE', confirmed_at=NULL, confirm_error=NULL,
                mandatory_return_status='NONE', returned_at=NULL,
                return_reply_code=NULL, return_reply_text=NULL, published_at=NULL,
                last_error=#{errorCode},
                version=version+CASE WHEN version<9223372036854775807
                                     THEN 1 ELSE 0 END,
                update_time=#{now}
            WHERE id=#{outbox.id} AND version=#{outbox.version}
              AND delivery_id=#{outbox.deliveryId}
              AND active_attempt=#{outbox.activeAttempt} AND attempt_count=#{outbox.attemptCount}
              AND status=#{outbox.status}
              AND CAST(tenant_id AS BINARY)=CAST(#{outbox.tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{outbox.tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{outbox.clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{outbox.clientId})
              AND CAST(status AS BINARY)=CAST(#{outbox.status} AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH(#{outbox.status})
            """)
    int quarantineOutbox(
            @Param("outbox") AgentOutboxEventEntity outbox,
            @Param("errorCode") String errorCode,
            @Param("now") long now);
}
