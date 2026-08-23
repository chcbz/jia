package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** D07 mapper. Every scoped/string predicate is byte-exact and preserves D07 lock order at the service. */
public interface AgentCommandInboxMapper {
    @Select("""
            SELECT id, command_id, task_id, work_item_id, target_agent_id, command_type,
                   command_payload, command_payload_hash, status, attempt_count,
                   next_retry_at, lease_owner, lease_until, active_message_id, active_attempt,
                   expires_at, last_error, version, tenant_id, client_id, create_time, update_time
            FROM agent_command_delivery
            WHERE id=#{deliveryId}
              AND tenant_id=#{tenantId} AND client_id=#{clientId}
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
                   active_attempt, expires_at, publisher_confirm_status,
                   mandatory_return_status, last_error, version,
                   tenant_id, client_id, create_time, update_time
            FROM agent_outbox_event
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND event_id=#{eventId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(event_id AS BINARY)=CAST(#{eventId} AS BINARY)
              AND OCTET_LENGTH(event_id)=OCTET_LENGTH(#{eventId})
            LIMIT 1 FOR UPDATE
            """)
    AgentOutboxEventEntity selectOutboxForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("eventId") String eventId);

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

    @Insert("""
            INSERT INTO agent_consumer_inbox
              (consumer_name,message_id,event_id,command_id,delivery_id,
               wire_payload,wire_payload_hash,status,result_status,attempt_count,
               next_retry_at,lease_owner,lease_until,active_attempt,expires_at,
               processed_at,last_error,version,tenant_id,client_id,create_time,update_time)
            VALUES
              (#{consumerName},#{messageId},#{eventId},#{commandId},#{deliveryId},
               #{wirePayload},#{wirePayloadHash},#{status},#{resultStatus},#{attemptCount},
               #{nextRetryAt},#{leaseOwner},#{leaseUntil},#{activeAttempt},#{expiresAt},
               #{processedAt},#{lastError},#{version},#{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertInbox(AgentConsumerInboxEntity inbox);

    @Update("""
            UPDATE agent_command_delivery
            SET status=#{newStatus}, next_retry_at=#{nextRetryAt},
                lease_owner=NULL, lease_until=NULL, last_error=#{lastError},
                version=version+1, update_time=#{now}
            WHERE id=#{deliveryId} AND version=#{expectedVersion}
              AND active_attempt=#{activeAttempt}
              AND tenant_id=#{tenantId} AND client_id=#{clientId}
              AND active_message_id=#{activeMessageId} AND status=#{expectedStatus}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(active_message_id AS BINARY)=CAST(#{activeMessageId} AS BINARY)
              AND OCTET_LENGTH(active_message_id)=OCTET_LENGTH(#{activeMessageId})
              AND CAST(status AS BINARY)=CAST(#{expectedStatus} AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH(#{expectedStatus})
            """)
    int updateDeliveryDisposition(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("deliveryId") long deliveryId,
            @Param("activeMessageId") String activeMessageId,
            @Param("activeAttempt") int activeAttempt,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("newStatus") String newStatus,
            @Param("nextRetryAt") Long nextRetryAt,
            @Param("lastError") String lastError,
            @Param("now") long now);

    @Update("""
            UPDATE agent_consumer_inbox
            SET status='PROCESSING', result_status=NULL, attempt_count=attempt_count+1,
                next_retry_at=NULL, lease_owner=#{newLeaseOwner}, lease_until=#{newLeaseUntil},
                active_attempt=active_attempt+1, processed_at=NULL, last_error=NULL,
                version=version+1, update_time=#{now}
            WHERE id=#{inboxId} AND version=#{expectedVersion}
              AND active_attempt=#{expectedActiveAttempt}
              AND lease_until=#{expectedLeaseUntil}
              AND tenant_id=#{tenantId} AND client_id=#{clientId}
              AND consumer_name=#{consumerName} AND message_id=#{messageId}
              AND lease_owner=#{expectedLeaseOwner} AND status='PROCESSING'
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(consumer_name AS BINARY)=CAST(#{consumerName} AS BINARY)
              AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{consumerName})
              AND CAST(message_id AS BINARY)=CAST(#{messageId} AS BINARY)
              AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{messageId})
              AND CAST(lease_owner AS BINARY)=CAST(#{expectedLeaseOwner} AS BINARY)
              AND OCTET_LENGTH(lease_owner)=OCTET_LENGTH(#{expectedLeaseOwner})
              AND CAST(status AS BINARY)=CAST('PROCESSING' AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH('PROCESSING')
            """)
    int reclaimProcessingInbox(
            @Param("inboxId") long inboxId,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("consumerName") String consumerName,
            @Param("messageId") String messageId,
            @Param("expectedLeaseOwner") String expectedLeaseOwner,
            @Param("expectedLeaseUntil") long expectedLeaseUntil,
            @Param("expectedActiveAttempt") int expectedActiveAttempt,
            @Param("expectedVersion") long expectedVersion,
            @Param("newLeaseOwner") String newLeaseOwner,
            @Param("newLeaseUntil") long newLeaseUntil,
            @Param("now") long now);

    @Update("""
            UPDATE agent_consumer_inbox
            SET status='PROCESSING', result_status=NULL, attempt_count=attempt_count+1,
                next_retry_at=NULL, lease_owner=#{newLeaseOwner}, lease_until=#{newLeaseUntil},
                active_attempt=active_attempt+1, processed_at=NULL, last_error=NULL,
                version=version+1, update_time=#{now}
            WHERE id=#{inboxId} AND version=#{expectedVersion}
              AND active_attempt=#{expectedActiveAttempt}
              AND next_retry_at=#{expectedNextRetryAt}
              AND tenant_id=#{tenantId} AND client_id=#{clientId}
              AND consumer_name=#{consumerName} AND message_id=#{messageId}
              AND status='RETRY'
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(consumer_name AS BINARY)=CAST(#{consumerName} AS BINARY)
              AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{consumerName})
              AND CAST(message_id AS BINARY)=CAST(#{messageId} AS BINARY)
              AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{messageId})
              AND CAST(status AS BINARY)=CAST('RETRY' AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH('RETRY')
            """)
    int reclaimRetryInbox(
            @Param("inboxId") long inboxId,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("consumerName") String consumerName,
            @Param("messageId") String messageId,
            @Param("expectedNextRetryAt") long expectedNextRetryAt,
            @Param("expectedActiveAttempt") int expectedActiveAttempt,
            @Param("expectedVersion") long expectedVersion,
            @Param("newLeaseOwner") String newLeaseOwner,
            @Param("newLeaseUntil") long newLeaseUntil,
            @Param("now") long now);

    @Update("""
            UPDATE agent_consumer_inbox
            SET status='EXPIRED', result_status='EXPIRED', next_retry_at=NULL,
                lease_owner=NULL, lease_until=NULL, processed_at=#{processedAt},
                last_error=#{lastError}, version=version+1, update_time=#{now}
            WHERE id=#{inboxId} AND version=#{expectedVersion}
              AND active_attempt=#{expectedActiveAttempt}
              AND next_retry_at=#{expectedNextRetryAt}
              AND tenant_id=#{tenantId} AND client_id=#{clientId}
              AND consumer_name=#{consumerName} AND message_id=#{messageId}
              AND status='RETRY'
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(consumer_name AS BINARY)=CAST(#{consumerName} AS BINARY)
              AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{consumerName})
              AND CAST(message_id AS BINARY)=CAST(#{messageId} AS BINARY)
              AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{messageId})
              AND CAST(status AS BINARY)=CAST('RETRY' AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH('RETRY')
            """)
    int expireRetryInbox(
            @Param("inboxId") long inboxId,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("consumerName") String consumerName,
            @Param("messageId") String messageId,
            @Param("expectedNextRetryAt") long expectedNextRetryAt,
            @Param("expectedActiveAttempt") int expectedActiveAttempt,
            @Param("expectedVersion") long expectedVersion,
            @Param("processedAt") long processedAt,
            @Param("lastError") String lastError,
            @Param("now") long now);

    @Update("""
            UPDATE agent_consumer_inbox
            SET status=#{newStatus}, result_status=#{resultStatus},
                next_retry_at=#{nextRetryAt}, lease_owner=NULL, lease_until=NULL,
                processed_at=#{processedAt}, last_error=#{lastError},
                version=version+1, update_time=#{now}
            WHERE id=#{inboxId} AND version=#{expectedVersion}
              AND active_attempt=#{activeAttempt} AND lease_until=#{leaseUntil}
              AND tenant_id=#{tenantId} AND client_id=#{clientId}
              AND consumer_name=#{consumerName} AND message_id=#{messageId}
              AND lease_owner=#{leaseOwner} AND status='PROCESSING'
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(consumer_name AS BINARY)=CAST(#{consumerName} AS BINARY)
              AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{consumerName})
              AND CAST(message_id AS BINARY)=CAST(#{messageId} AS BINARY)
              AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{messageId})
              AND CAST(lease_owner AS BINARY)=CAST(#{leaseOwner} AS BINARY)
              AND OCTET_LENGTH(lease_owner)=OCTET_LENGTH(#{leaseOwner})
              AND CAST(status AS BINARY)=CAST('PROCESSING' AS BINARY)
              AND OCTET_LENGTH(status)=OCTET_LENGTH('PROCESSING')
            """)
    int completeInbox(
            @Param("inboxId") long inboxId,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("consumerName") String consumerName,
            @Param("messageId") String messageId,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") long leaseUntil,
            @Param("activeAttempt") int activeAttempt,
            @Param("expectedVersion") long expectedVersion,
            @Param("newStatus") String newStatus,
            @Param("resultStatus") String resultStatus,
            @Param("nextRetryAt") Long nextRetryAt,
            @Param("processedAt") long processedAt,
            @Param("lastError") String lastError,
            @Param("now") long now);
}
