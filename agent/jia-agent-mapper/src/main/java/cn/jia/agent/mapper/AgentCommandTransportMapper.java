package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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
    @Select("""
            SELECT d.id AS id, d.command_id AS commandId, d.task_id AS taskId,
                   d.work_item_id AS workItemId, d.target_agent_id AS targetAgentId,
                   d.command_type AS commandType, d.status AS status,
                   d.expires_at AS expiresAt, d.create_time AS createTime,
                   d.update_time AS updateTime
            FROM agent_command_delivery d
            INNER JOIN agent_task_meta t
              ON t.tenant_id=d.tenant_id AND t.client_id=d.client_id AND t.task_id=d.task_id
             AND CAST(t.tenant_id AS BINARY)=CAST(d.tenant_id AS BINARY)
             AND CAST(t.client_id AS BINARY)=CAST(d.client_id AS BINARY)
             AND CAST(t.task_id AS BINARY)=CAST(d.task_id AS BINARY)
            INNER JOIN agent_task_member caller
              ON caller.tenant_id=d.tenant_id AND caller.client_id=d.client_id
             AND caller.task_id=d.task_id AND caller.agent_id=#{callerAgentId}
             AND CAST(caller.tenant_id AS BINARY)=CAST(d.tenant_id AS BINARY)
             AND CAST(caller.client_id AS BINARY)=CAST(d.client_id AS BINARY)
             AND CAST(caller.task_id AS BINARY)=CAST(d.task_id AS BINARY)
             AND CAST(caller.agent_id AS BINARY)=CAST(#{callerAgentId} AS BINARY)
             AND ((CAST(caller.member_status AS BINARY)=CAST('accepted' AS BINARY)
                   AND OCTET_LENGTH(caller.member_status)=OCTET_LENGTH('accepted'))
               OR (CAST(caller.member_status AS BINARY)=CAST('working' AS BINARY)
                   AND OCTET_LENGTH(caller.member_status)=OCTET_LENGTH('working'))
               OR (CAST(caller.member_status AS BINARY)=CAST('blocked' AS BINARY)
                   AND OCTET_LENGTH(caller.member_status)=OCTET_LENGTH('blocked')))
             AND ((CAST(caller.member_role AS BINARY)=CAST('coordinator' AS BINARY)
                   AND OCTET_LENGTH(caller.member_role)=OCTET_LENGTH('coordinator'))
               OR (CAST(caller.member_role AS BINARY)=CAST('worker' AS BINARY)
                   AND OCTET_LENGTH(caller.member_role)=OCTET_LENGTH('worker'))
               OR (CAST(caller.member_role AS BINARY)=CAST('reviewer' AS BINARY)
                   AND OCTET_LENGTH(caller.member_role)=OCTET_LENGTH('reviewer'))
               OR (CAST(caller.member_role AS BINARY)=CAST('observer' AS BINARY)
                   AND OCTET_LENGTH(caller.member_role)=OCTET_LENGTH('observer')))
             AND ((CAST(caller.assignment_source AS BINARY)=CAST('manual' AS BINARY)
                   AND OCTET_LENGTH(caller.assignment_source)=OCTET_LENGTH('manual'))
               OR (CAST(caller.assignment_source AS BINARY)=CAST('auto' AS BINARY)
                   AND OCTET_LENGTH(caller.assignment_source)=OCTET_LENGTH('auto'))
               OR (CAST(caller.assignment_source AS BINARY)=CAST('migration' AS BINARY)
                   AND OCTET_LENGTH(caller.assignment_source)=OCTET_LENGTH('migration'))
               OR (CAST(caller.assignment_source AS BINARY)=CAST('legacy' AS BINARY)
                   AND OCTET_LENGTH(caller.assignment_source)=OCTET_LENGTH('legacy')))
            INNER JOIN agent_task_member target
              ON target.tenant_id=d.tenant_id AND target.client_id=d.client_id
             AND target.task_id=d.task_id AND target.agent_id=d.target_agent_id
             AND CAST(target.tenant_id AS BINARY)=CAST(d.tenant_id AS BINARY)
             AND CAST(target.client_id AS BINARY)=CAST(d.client_id AS BINARY)
             AND CAST(target.task_id AS BINARY)=CAST(d.task_id AS BINARY)
             AND CAST(target.agent_id AS BINARY)=CAST(d.target_agent_id AS BINARY)
             AND ((CAST(target.member_status AS BINARY)=CAST('accepted' AS BINARY)
                   AND OCTET_LENGTH(target.member_status)=OCTET_LENGTH('accepted'))
               OR (CAST(target.member_status AS BINARY)=CAST('working' AS BINARY)
                   AND OCTET_LENGTH(target.member_status)=OCTET_LENGTH('working'))
               OR (CAST(target.member_status AS BINARY)=CAST('blocked' AS BINARY)
                   AND OCTET_LENGTH(target.member_status)=OCTET_LENGTH('blocked')))
             AND ((CAST(target.member_role AS BINARY)=CAST('coordinator' AS BINARY)
                   AND OCTET_LENGTH(target.member_role)=OCTET_LENGTH('coordinator'))
               OR (CAST(target.member_role AS BINARY)=CAST('worker' AS BINARY)
                   AND OCTET_LENGTH(target.member_role)=OCTET_LENGTH('worker'))
               OR (CAST(target.member_role AS BINARY)=CAST('reviewer' AS BINARY)
                   AND OCTET_LENGTH(target.member_role)=OCTET_LENGTH('reviewer'))
               OR (CAST(target.member_role AS BINARY)=CAST('observer' AS BINARY)
                   AND OCTET_LENGTH(target.member_role)=OCTET_LENGTH('observer')))
             AND ((CAST(target.assignment_source AS BINARY)=CAST('manual' AS BINARY)
                   AND OCTET_LENGTH(target.assignment_source)=OCTET_LENGTH('manual'))
               OR (CAST(target.assignment_source AS BINARY)=CAST('auto' AS BINARY)
                   AND OCTET_LENGTH(target.assignment_source)=OCTET_LENGTH('auto'))
               OR (CAST(target.assignment_source AS BINARY)=CAST('migration' AS BINARY)
                   AND OCTET_LENGTH(target.assignment_source)=OCTET_LENGTH('migration'))
               OR (CAST(target.assignment_source AS BINARY)=CAST('legacy' AS BINARY)
                   AND OCTET_LENGTH(target.assignment_source)=OCTET_LENGTH('legacy')))
            WHERE d.tenant_id=#{tenantId} AND d.client_id=#{clientId}
              AND d.target_agent_id=#{targetAgentId}
              AND CAST(d.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(d.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(d.client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(d.target_agent_id AS BINARY)=CAST(#{targetAgentId} AS BINARY)
              AND OCTET_LENGTH(d.target_agent_id)=OCTET_LENGTH(#{targetAgentId})
              AND (#{taskId} IS NULL OR (
                    d.task_id=#{taskId}
                AND CAST(d.task_id AS BINARY)=CAST(#{taskId} AS BINARY)
                AND OCTET_LENGTH(d.task_id)=OCTET_LENGTH(#{taskId})))
              AND (#{beforeCreateTime} IS NULL OR d.create_time<#{beforeCreateTime}
                   OR (d.create_time=#{beforeCreateTime} AND d.id<#{beforeId}))
              AND (#{includeTerminal}=TRUE
                   OR d.status NOT IN ('SUCCEEDED','FAILED','REJECTED','EXPIRED','DEAD'))
              AND d.status IN ('PENDING','CLAIMED','PUBLISHED','CONSUMED','SENT','RECEIVED',
                               'STARTED','SUCCEEDED','WAITING_AGENT','RETRY','FAILED',
                               'REJECTED','EXPIRED','DEAD')
              AND ((CAST(t.reward_status AS BINARY)=CAST('open' AS BINARY)
                    AND OCTET_LENGTH(t.reward_status)=OCTET_LENGTH('open'))
                OR (CAST(t.reward_status AS BINARY)=CAST('planning' AS BINARY)
                    AND OCTET_LENGTH(t.reward_status)=OCTET_LENGTH('planning'))
                OR (CAST(t.reward_status AS BINARY)=CAST('assigned' AS BINARY)
                    AND OCTET_LENGTH(t.reward_status)=OCTET_LENGTH('assigned'))
                OR (CAST(t.reward_status AS BINARY)=CAST('running' AS BINARY)
                    AND OCTET_LENGTH(t.reward_status)=OCTET_LENGTH('running'))
                OR (CAST(t.reward_status AS BINARY)=CAST('reviewing' AS BINARY)
                    AND OCTET_LENGTH(t.reward_status)=OCTET_LENGTH('reviewing'))
                OR (CAST(t.reward_status AS BINARY)=CAST('blocked' AS BINARY)
                    AND OCTET_LENGTH(t.reward_status)=OCTET_LENGTH('blocked'))
                OR (CAST(t.reward_status AS BINARY)=CAST('completed' AS BINARY)
                    AND OCTET_LENGTH(t.reward_status)=OCTET_LENGTH('completed'))
                OR (CAST(t.reward_status AS BINARY)=CAST('failed' AS BINARY)
                    AND OCTET_LENGTH(t.reward_status)=OCTET_LENGTH('failed'))
                OR (CAST(t.reward_status AS BINARY)=CAST('cancelled' AS BINARY)
                    AND OCTET_LENGTH(t.reward_status)=OCTET_LENGTH('cancelled'))
                OR (CAST(t.reward_status AS BINARY)=CAST('archived' AS BINARY)
                    AND OCTET_LENGTH(t.reward_status)=OCTET_LENGTH('archived')))
              AND d.create_time IS NOT NULL AND d.update_time IS NOT NULL
            ORDER BY d.create_time DESC, d.id DESC
            LIMIT #{limit}
            """)
    List<AgentCommandMailboxRow> selectMailboxPage(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("callerAgentId") String callerAgentId,
            @Param("targetAgentId") String targetAgentId,
            @Param("taskId") String taskId,
            @Param("beforeCreateTime") Long beforeCreateTime,
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit,
            @Param("includeTerminal") boolean includeTerminal);

}
