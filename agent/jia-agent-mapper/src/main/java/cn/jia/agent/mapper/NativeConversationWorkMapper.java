package cn.jia.agent.mapper;

import cn.jia.agent.entity.NativeConversationWorkAvailable;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface NativeConversationWorkMapper {
    // Target-only projection: never materialize task snapshots or skill packages for notification.
    // Partition predicates apply in SQL, before DISTINCT; no cross-tenant scan + Java filtering.
    @Select("""
        <script>
        SELECT DISTINCT tenant_id AS tenantId, client_id AS clientId, target_agent_id AS agentId
          FROM agent_personal_workspace_execution
         WHERE tenant_id=#{tenantId} AND BINARY tenant_id=BINARY #{tenantId}
           AND execution_mode='CONVERSATION' AND execution_state='QUEUED'
           AND execution_protocol_version=5 AND conversation_provider_started_at IS NULL
           AND (
             <foreach collection="targets" item="target" separator=" OR ">
               (client_id=#{target.clientId} AND BINARY client_id=BINARY #{target.clientId}
                 AND target_agent_id=#{target.agentId} AND BINARY target_agent_id=BINARY #{target.agentId})
             </foreach>
           )
        </script>
        """)
    List<NativeConversationWorkAvailable> pendingFor(@Param("tenantId") String tenantId,
            @Param("targets") List<NativeConversationWorkAvailable> targets);
}
