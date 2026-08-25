package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDlqEntry;
import cn.jia.agent.entity.AgentCommandMetricCount;
import cn.jia.agent.entity.AgentCommandOperationAuditEntity;
import cn.jia.agent.entity.AgentCommandOperationAuditEntry;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** D09 exact-scope read projections and delivery -> outbox -> Inbox operation locks. */
public interface AgentCommandOperationsMapper {
    String EXACT_SCOPE = " tenant_id=#{tenantId} AND client_id=#{clientId} "
            + "AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY) "
            + "AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId}) "
            + "AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY) "
            + "AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId}) ";

    @Select("SELECT status AS label, COUNT(*) AS count FROM agent_command_delivery WHERE "
            + EXACT_SCOPE + " GROUP BY status ORDER BY status")
    List<AgentCommandMetricCount> countDeliveryStatuses(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId);

    @Select("SELECT status AS label, COUNT(*) AS count FROM agent_consumer_inbox WHERE "
            + EXACT_SCOPE + " GROUP BY status ORDER BY status")
    List<AgentCommandMetricCount> countInboxStatuses(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId);

    @Select("SELECT COALESCE(result_status,'NONE') AS label, COUNT(*) AS count "
            + "FROM agent_consumer_inbox WHERE " + EXACT_SCOPE
            + " GROUP BY result_status ORDER BY result_status")
    List<AgentCommandMetricCount> countInboxResults(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId);

    @Select("SELECT status AS label, COUNT(*) AS count FROM agent_outbox_event WHERE "
            + EXACT_SCOPE + " GROUP BY status ORDER BY status")
    List<AgentCommandMetricCount> countOutboxStatuses(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId);

    @Select("""
            SELECT AVG(GREATEST(d.update_time-o.published_at,0))/1000.0
            FROM agent_command_delivery d
            JOIN agent_outbox_event o ON o.tenant_id=d.tenant_id AND o.client_id=d.client_id
              AND o.delivery_id=d.id AND o.message_id=d.active_message_id
              AND o.id=(SELECT MIN(o2.id) FROM agent_outbox_event o2
                        WHERE o2.tenant_id=d.tenant_id AND o2.client_id=d.client_id
                          AND o2.delivery_id=d.id AND o2.message_id=d.active_message_id)
            WHERE d.tenant_id=#{tenantId} AND d.client_id=#{clientId}
              AND CAST(d.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(d.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(d.client_id)=OCTET_LENGTH(#{clientId})
              AND d.status IN ('RECEIVED','STARTED','SUCCEEDED','FAILED','REJECTED')
              AND d.update_time IS NOT NULL AND o.published_at IS NOT NULL
            """)
    Double averageAckLatencySeconds(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId);

    @Select("SELECT CONCAT(operation_type,':',outcome) AS label, COUNT(*) AS count "
            + "FROM agent_command_operation_audit WHERE " + EXACT_SCOPE
            + " GROUP BY operation_type,outcome ORDER BY operation_type,outcome")
    List<AgentCommandMetricCount> countOperationOutcomes(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId);

    @Select("SELECT COUNT(*) FROM agent_outbox_event WHERE " + EXACT_SCOPE
            + " AND status IN ('PENDING','CLAIMED','RETRY')")
    long countOutboxBacklog(@Param("tenantId") String tenantId, @Param("clientId") String clientId);

    @Select("SELECT MIN(COALESCE(next_retry_at,create_time,update_time)) FROM agent_outbox_event WHERE "
            + EXACT_SCOPE + " AND status IN ('PENDING','CLAIMED','RETRY')")
    Long oldestOutboxEpoch(@Param("tenantId") String tenantId, @Param("clientId") String clientId);

    @Select("SELECT COUNT(*) FROM agent_outbox_event WHERE " + EXACT_SCOPE
            + " AND (status IN ('FAILED','DEAD') OR publisher_confirm_status IN ('NACK','TIMEOUT') "
            + "OR mandatory_return_status='RETURNED')")
    long countPublishFailures(@Param("tenantId") String tenantId, @Param("clientId") String clientId);

    @Select("SELECT COUNT(DISTINCT d.id) FROM agent_command_delivery d "
            + "JOIN agent_outbox_event o ON o.tenant_id=d.tenant_id AND o.client_id=d.client_id "
            + "AND o.delivery_id=d.id AND o.message_id=d.active_message_id "
            + "AND o.id=(SELECT MIN(o2.id) FROM agent_outbox_event o2 "
            + "WHERE o2.tenant_id=d.tenant_id AND o2.client_id=d.client_id "
            + "AND o2.delivery_id=d.id AND o2.message_id=d.active_message_id) "
            + "LEFT JOIN agent_consumer_inbox i ON i.tenant_id=d.tenant_id AND i.client_id=d.client_id "
            + "AND i.delivery_id=d.id AND i.message_id=d.active_message_id "
            + "AND i.consumer_name='agent-command-dispatch-v1' "
            + "WHERE d.tenant_id=#{tenantId} AND d.client_id=#{clientId} "
            + "AND CAST(d.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY) "
            + "AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(#{tenantId}) "
            + "AND CAST(d.client_id AS BINARY)=CAST(#{clientId} AS BINARY) "
            + "AND OCTET_LENGTH(d.client_id)=OCTET_LENGTH(#{clientId}) "
            + "AND (d.status IN ('DEAD','FAILED') OR o.status IN ('DEAD','FAILED') "
            + "OR i.status='DEAD' OR i.result_status='DEAD')")
    long countDlq(@Param("tenantId") String tenantId, @Param("clientId") String clientId);

    @Select("SELECT COUNT(*) FROM agent_command_delivery WHERE " + EXACT_SCOPE
            + " AND status='WAITING_AGENT' AND next_retry_at IS NOT NULL AND next_retry_at<=#{now}")
    long countWaitingDue(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("now") long now);

    @Select("SELECT COUNT(*) FROM agent_command_delivery WHERE " + EXACT_SCOPE + " AND status='SENT'")
    long countSentUnacknowledged(@Param("tenantId") String tenantId, @Param("clientId") String clientId);

    @Select("SELECT COUNT(*) FROM agent_command_delivery WHERE " + EXACT_SCOPE
            + " AND status IN ('WAITING_AGENT','SENT') AND expires_at>#{now}")
    long countReconnectQueueDepth(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("now") long now);

    @Select("SELECT COUNT(*) FROM agent_command_delivery WHERE " + EXACT_SCOPE
            + " AND status NOT IN ('SUCCEEDED','FAILED','EXPIRED','DEAD') "
            + "AND expires_at>#{now} AND expires_at<=#{before}")
    long countExpiryProximity(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("now") long now, @Param("before") long before);

    @Select("""
            SELECT d.id AS deliveryId,d.command_id AS commandId,o.event_id AS eventId,
                   o.message_id AS messageId,d.task_id AS taskId,d.target_agent_id AS targetAgentId,
                   d.status AS deliveryStatus,o.status AS outboxStatus,i.status AS inboxStatus,
                   i.result_status AS inboxResultStatus,d.active_attempt AS activeAttempt,
                   o.attempt_count AS publishAttemptCount,LOWER(HEX(o.wire_payload_hash)) AS wireSha256,
                   o.published_at AS publishedAt,i.processed_at AS processedAt,d.expires_at AS expiresAt,
                   GREATEST(COALESCE(d.update_time,0),COALESCE(o.update_time,0),COALESCE(i.update_time,0)) AS updatedAt
            FROM agent_command_delivery d
            JOIN agent_outbox_event o ON o.tenant_id=d.tenant_id AND o.client_id=d.client_id
              AND o.delivery_id=d.id AND o.message_id=d.active_message_id
              AND o.id=(SELECT MIN(o2.id) FROM agent_outbox_event o2
                        WHERE o2.tenant_id=d.tenant_id AND o2.client_id=d.client_id
                          AND o2.delivery_id=d.id AND o2.message_id=d.active_message_id)
            LEFT JOIN agent_consumer_inbox i ON i.tenant_id=d.tenant_id AND i.client_id=d.client_id
              AND i.consumer_name='agent-command-dispatch-v1' AND i.delivery_id=d.id
              AND i.message_id=d.active_message_id
            WHERE d.tenant_id=#{tenantId} AND d.client_id=#{clientId} AND d.id>#{afterDeliveryId}
              AND CAST(d.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(d.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(d.client_id)=OCTET_LENGTH(#{clientId})
              AND (d.status IN ('DEAD','FAILED') OR o.status IN ('DEAD','FAILED')
                   OR i.status='DEAD' OR i.result_status='DEAD')
            ORDER BY d.id ASC LIMIT #{limit}
            """)
    List<AgentCommandDlqEntry> listDlq(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("afterDeliveryId") long afterDeliveryId, @Param("limit") int limit);

    @Select("""
            SELECT id,operation_id AS operationId,phase,operation_type AS operationType,
                   task_id AS taskId,target_agent_id AS targetAgentId,command_id AS commandId,
                   source_message_id AS sourceMessageId,new_message_id AS newMessageId,
                   delivery_id AS deliveryId,source_attempt AS sourceAttempt,new_attempt AS newAttempt,
                   LOWER(HEX(wire_hash)) AS wireSha256,requester_id AS requesterId,
                   approver_id AS approverId,reason,ticket_reference AS ticketReference,
                   requested_at AS requestedAt,completed_at AS completedAt,outcome,error_code AS errorCode,
                   created_by AS createdBy,created_at AS createdAt
            FROM agent_command_operation_audit
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND id>#{afterId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
            ORDER BY id ASC LIMIT #{limit}
            """)
    List<AgentCommandOperationAuditEntry> listAudit(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("afterId") long afterId, @Param("limit") int limit);

    @Select("SELECT " + AgentCommandRecoveryMapper.DELIVERY_COLUMNS
            + " FROM agent_command_delivery WHERE id=#{deliveryId} AND " + EXACT_SCOPE
            + " LIMIT 1 FOR UPDATE")
    AgentCommandDeliveryEntity lockDelivery(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("deliveryId") long deliveryId);

    @Select("SELECT " + AgentCommandRecoveryMapper.OUTBOX_COLUMNS
            + " FROM agent_outbox_event WHERE delivery_id=#{deliveryId} AND message_id=#{messageId} AND "
            + EXACT_SCOPE + " AND CAST(message_id AS BINARY)=CAST(#{messageId} AS BINARY) "
            + "AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{messageId}) ORDER BY id ASC LIMIT 2 FOR UPDATE")
    List<AgentOutboxEventEntity> lockActiveOutboxes(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("deliveryId") long deliveryId, @Param("messageId") String messageId);

    @Select("SELECT " + AgentCommandRecoveryMapper.OUTBOX_COLUMNS
            + " FROM agent_outbox_event WHERE delivery_id=#{deliveryId} AND active_attempt=#{previousAttempt} AND "
            + EXACT_SCOPE + " ORDER BY id ASC LIMIT 2 FOR UPDATE")
    List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("deliveryId") long deliveryId, @Param("previousAttempt") int previousAttempt);

    @Select("""
            SELECT id,consumer_name,message_id,event_id,command_id,delivery_id,wire_payload,
                   wire_payload_hash,status,result_status,attempt_count,next_retry_at,lease_owner,
                   lease_until,active_attempt,expires_at,processed_at,last_error,version,
                   replay_parent_message_id,replay_requester_id,replay_approver_id,replay_reason,
                   tenant_id,client_id,create_time,update_time
            FROM agent_consumer_inbox
            WHERE consumer_name=#{consumerName} AND message_id=#{messageId} AND
            """ + EXACT_SCOPE + " AND CAST(consumer_name AS BINARY)=CAST(#{consumerName} AS BINARY) "
            + "AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{consumerName}) "
            + "AND CAST(message_id AS BINARY)=CAST(#{messageId} AS BINARY) "
            + "AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{messageId}) LIMIT 1 FOR UPDATE")
    AgentConsumerInboxEntity lockInbox(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("consumerName") String consumerName, @Param("messageId") String messageId);

    @Insert("""
            INSERT INTO agent_command_operation_audit(
                operation_id,phase,operation_type,tenant_id,client_id,task_id,target_agent_id,
                command_id,source_message_id,new_message_id,delivery_id,source_attempt,new_attempt,
                wire_hash,requester_id,approver_id,reason,ticket_reference,requested_at,completed_at,
                outcome,error_code,created_by,created_at)
            VALUES(#{operationId},#{phase},#{operationType},#{tenantId},#{clientId},#{taskId},#{targetAgentId},
                #{commandId},#{sourceMessageId},#{newMessageId},#{deliveryId},#{sourceAttempt},#{newAttempt},
                #{wireHash},#{requesterId},#{approverId},#{reason},#{ticketReference},#{requestedAt},#{completedAt},
                #{outcome},#{errorCode},#{createdBy},#{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAudit(AgentCommandOperationAuditEntity audit);
}
