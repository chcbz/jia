package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentCommandTransportMapper {
    @Select("""
            SELECT id, command_id, task_id, work_item_id, target_agent_id, command_type,
                   command_payload, command_payload_hash, status, attempt_count,
                   next_retry_at, lease_owner, lease_until, active_message_id, active_attempt,
                   expires_at, last_error, version, tenant_id, client_id, create_time, update_time
            FROM agent_command_delivery
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND command_id=#{commandId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(command_id AS BINARY)=CAST(#{commandId} AS BINARY)
              AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{commandId})
            LIMIT 1 FOR UPDATE
            """)
    AgentCommandDeliveryEntity selectDeliveryForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("commandId") String commandId);

    @Insert("""
            INSERT INTO agent_command_delivery
              (command_id,task_id,work_item_id,target_agent_id,command_type,
               command_payload,command_payload_hash,status,attempt_count,next_retry_at,
               lease_owner,lease_until,active_message_id,active_attempt,expires_at,last_error,
               version,tenant_id,client_id,create_time,update_time)
            VALUES
              (#{commandId},#{taskId},#{workItemId},#{targetAgentId},#{commandType},
               #{commandPayload},#{commandPayloadHash},#{status},#{attemptCount},#{nextRetryAt},
               #{leaseOwner},#{leaseUntil},#{activeMessageId},#{activeAttempt},#{expiresAt},#{lastError},
               #{version},#{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDelivery(AgentCommandDeliveryEntity delivery);

    @Insert("""
            INSERT INTO agent_outbox_event
              (event_id,message_id,command_id,delivery_id,aggregate_type,aggregate_id,
               destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,
               next_retry_at,lease_owner,lease_until,active_attempt,expires_at,
               publisher_confirm_status,mandatory_return_status,last_error,version,
               tenant_id,client_id,create_time,update_time)
            VALUES
              (#{eventId},#{messageId},#{commandId},#{deliveryId},#{aggregateType},#{aggregateId},
               #{destination},#{routingKey},#{wirePayload},#{wirePayloadHash},#{status},#{attemptCount},
               #{nextRetryAt},#{leaseOwner},#{leaseUntil},#{activeAttempt},#{expiresAt},
               #{publisherConfirmStatus},#{mandatoryReturnStatus},#{lastError},#{version},
               #{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOutbox(AgentOutboxEventEntity outbox);
}
