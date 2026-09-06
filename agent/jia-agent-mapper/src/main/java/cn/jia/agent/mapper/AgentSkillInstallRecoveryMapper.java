package cn.jia.agent.mapper;
import cn.jia.agent.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** Same-wire recovery only. No new command/message/attempt, task mutation or inferred refund. */
public interface AgentSkillInstallRecoveryMapper {
    @Select("""
        SELECT d.* FROM agent_command_delivery d JOIN economy_skill_installation i
          ON BINARY i.tenant_id=BINARY d.tenant_id AND BINARY i.client_id=BINARY d.client_id
          AND BINARY i.command_id=BINARY d.command_id
        WHERE d.command_type='SKILL_INSTALL' AND i.status='INSTALLING' AND d.expires_at>#{now}
          AND d.status IN ('WAITING_AGENT','SENT','RETRY','CONSUMED') AND d.update_time<#{before}
        ORDER BY d.update_time,d.id LIMIT 25
        """)
    List<AgentCommandDeliveryEntity> candidates(@Param("now") long now,@Param("before") long before);
    @Update("""
        UPDATE agent_command_delivery SET status='RETRY',next_retry_at=#{retry},last_error='SKILL_SAME_WIRE_RECOVERY',
          version=version+1,update_time=#{now}
        WHERE id=#{d.id} AND BINARY tenant_id=BINARY #{d.tenantId} AND BINARY client_id=BINARY #{d.clientId}
          AND command_type='SKILL_INSTALL' AND active_message_id=#{d.activeMessageId} AND active_attempt=#{d.activeAttempt}
          AND version=#{d.version} AND status=#{d.status} AND status IN ('WAITING_AGENT','SENT')
          AND lease_owner IS NULL AND lease_until IS NULL AND expires_at>#{retry}
        """)
    int retryDelivery(@Param("d") AgentCommandDeliveryEntity d,@Param("now") long now,@Param("retry") long retry);
    @Update("""
        UPDATE agent_consumer_inbox SET status='RETRY',result_status='RETRY',next_retry_at=#{retry},
          processed_at=#{now},last_error='SKILL_SAME_WIRE_RECOVERY',version=version+1,update_time=#{now}
        WHERE id=#{i.id} AND BINARY tenant_id=BINARY #{i.tenantId} AND BINARY client_id=BINARY #{i.clientId}
          AND version=#{i.version} AND status=#{i.status} AND status IN ('WAITING_AGENT','PROCESSED')
          AND lease_owner IS NULL AND lease_until IS NULL
        """)
    int retryInbox(@Param("i") AgentConsumerInboxEntity i,@Param("now") long now,@Param("retry") long retry);
}
