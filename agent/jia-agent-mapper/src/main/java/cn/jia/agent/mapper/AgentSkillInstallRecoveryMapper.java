package cn.jia.agent.mapper;
import cn.jia.agent.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** Native WS discovery and lease/CAS only; no new table, command identity, attempt or refund. */
public interface AgentSkillInstallRecoveryMapper {
    @Select("""
        SELECT d.* FROM agent_command_delivery d JOIN economy_skill_installation i
          ON CAST(i.tenant_id AS BINARY)=CAST(d.tenant_id AS BINARY)
          AND CAST(i.client_id AS BINARY)=CAST(d.client_id AS BINARY)
          AND CAST(i.command_id AS BINARY)=CAST(d.command_id AS BINARY)
        WHERE d.command_type='SKILL_INSTALL' AND i.status='INSTALLING' AND d.expires_at>#{now}
          AND d.status IN ('PENDING','WAITING_AGENT','SENT','RETRY','CONSUMED')
          AND (d.next_retry_at IS NULL OR d.next_retry_at<=#{now})
          AND (d.lease_until IS NULL OR d.lease_until<=#{now})
          AND (d.status='PENDING' OR d.update_time<#{before})
          AND EXISTS (SELECT 1 FROM agent_outbox_event o WHERE o.delivery_id=d.id
            AND CAST(o.destination AS BINARY)=CAST('agent.skill-install.ws.v1' AS BINARY))
        ORDER BY d.update_time,d.id LIMIT 25
        """)
    List<AgentCommandDeliveryEntity> candidates(@Param("now") long now,@Param("before") long before);
    @Update("""
        UPDATE agent_command_delivery SET status='CONSUMED',lease_owner=#{owner},lease_until=#{until},
          next_retry_at=NULL,last_error='SKILL_WS_IN_FLIGHT',version=version+1,update_time=#{now}
        WHERE id=#{d.id} AND CAST(tenant_id AS BINARY)=CAST(#{d.tenantId} AS BINARY)
          AND CAST(client_id AS BINARY)=CAST(#{d.clientId} AS BINARY) AND command_type='SKILL_INSTALL'
          AND CAST(command_id AS BINARY)=CAST(#{d.commandId} AS BINARY)
          AND CAST(owner_jiacn AS BINARY)=CAST(#{d.ownerJiacn} AS BINARY)
          AND CAST(active_message_id AS BINARY)=CAST(#{d.activeMessageId} AS BINARY)
          AND active_attempt=#{d.activeAttempt} AND version=#{d.version} AND version<9223372036854775805
          AND status=#{d.status} AND expires_at>#{now}
          AND (lease_until IS NULL OR lease_until<=#{now})
        """)
    int claimDelivery(@Param("d") AgentCommandDeliveryEntity d,@Param("owner") String owner,@Param("until") long until,@Param("now") long now);
    @Update("""
        UPDATE agent_outbox_event SET status='CLAIMED',lease_owner=#{owner},lease_until=#{until},
          next_retry_at=NULL,attempt_count=attempt_count+1,last_error='SKILL_WS_IN_FLIGHT',version=version+1,update_time=#{now}
        WHERE id=#{o.id} AND CAST(tenant_id AS BINARY)=CAST(#{o.tenantId} AS BINARY)
          AND CAST(client_id AS BINARY)=CAST(#{o.clientId} AS BINARY)
          AND CAST(destination AS BINARY)=CAST('agent.skill-install.ws.v1' AS BINARY)
          AND delivery_id=#{o.deliveryId} AND CAST(message_id AS BINARY)=CAST(#{o.messageId} AS BINARY)
          AND active_attempt=#{o.activeAttempt} AND version=#{o.version} AND version<9223372036854775805
          AND status=#{o.status} AND expires_at>#{now}
          AND (lease_until IS NULL OR lease_until<=#{now})
        """)
    int claimOutbox(@Param("o") AgentOutboxEventEntity o,@Param("owner") String owner,@Param("until") long until,@Param("now") long now);
    @Update("""
        UPDATE agent_command_delivery SET status=#{status},next_retry_at=#{retry},lease_owner=NULL,lease_until=NULL,
          last_error=#{error},version=version+1,update_time=#{now}
        WHERE id=#{d.id} AND CAST(tenant_id AS BINARY)=CAST(#{d.tenantId} AS BINARY)
          AND CAST(client_id AS BINARY)=CAST(#{d.clientId} AS BINARY) AND command_type='SKILL_INSTALL'
          AND CAST(active_message_id AS BINARY)=CAST(#{d.activeMessageId} AS BINARY)
          AND active_attempt=#{d.activeAttempt} AND version=#{d.version} AND version<9223372036854775806
          AND status='CONSUMED' AND CAST(lease_owner AS BINARY)=CAST(#{owner} AS BINARY)
        """)
    int finishDelivery(@Param("d") AgentCommandDeliveryEntity d,@Param("owner") String owner,@Param("status") String status,
                       @Param("retry") Long retry,@Param("error") String error,@Param("now") long now);
    @Update("""
        UPDATE agent_outbox_event SET status=#{status},next_retry_at=#{retry},lease_owner=NULL,lease_until=NULL,
          last_error=#{error},version=version+1,update_time=#{now}
        WHERE id=#{o.id} AND CAST(tenant_id AS BINARY)=CAST(#{o.tenantId} AS BINARY)
          AND CAST(client_id AS BINARY)=CAST(#{o.clientId} AS BINARY)
          AND CAST(destination AS BINARY)=CAST('agent.skill-install.ws.v1' AS BINARY)
          AND CAST(message_id AS BINARY)=CAST(#{o.messageId} AS BINARY)
          AND delivery_id=#{o.deliveryId} AND active_attempt=#{o.activeAttempt}
          AND version=#{o.version} AND version<9223372036854775806
          AND status='CLAIMED' AND CAST(lease_owner AS BINARY)=CAST(#{owner} AS BINARY)
        """)
    int finishOutbox(@Param("o") AgentOutboxEventEntity o,@Param("owner") String owner,@Param("status") String status,
                    @Param("retry") Long retry,@Param("error") String error,@Param("now") long now);
}
