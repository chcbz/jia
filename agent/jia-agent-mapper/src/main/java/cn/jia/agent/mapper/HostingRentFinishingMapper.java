package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** Discovery only. Settlement rechecks the binding/lease under the task-root transaction. */
public interface HostingRentFinishingMapper {
    @Select("""
            SELECT b.* FROM economy_hosting_lease l
            JOIN agent_persona_binding b ON b.id=CAST(l.binding_id AS UNSIGNED) AND BINARY CAST(b.id AS CHAR)=BINARY l.binding_id
              AND BINARY b.tenant_id=BINARY l.tenant_id AND BINARY b.client_id=BINARY l.client_id
              AND BINARY b.agent_id=BINARY l.agent_id AND BINARY b.persona_code=BINARY l.persona_code
            WHERE l.tenant_id=#{tenantId} AND BINARY l.tenant_id=BINARY #{tenantId}
              AND l.client_id=#{clientId} AND BINARY l.client_id=BINARY #{clientId}
              AND l.lease_id=#{leaseId} AND BINARY l.lease_id=BINARY #{leaseId}
              AND l.live_slot=1 AND l.status='FINISHING' AND b.status=1
            """)
    AgentPersonaBindingEntity findFinishingBinding(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("leaseId") String leaseId);

    String RELATED = """
            (BINARY t.assigned_agent_id=BINARY #{agentId} OR EXISTS (
               SELECT 1 FROM agent_task_member m
               WHERE BINARY m.tenant_id=BINARY t.tenant_id AND BINARY m.client_id=BINARY t.client_id
                 AND BINARY m.owner_jiacn=BINARY t.owner_jiacn AND BINARY m.task_id=BINARY t.task_id
                 AND BINARY m.agent_id=BINARY #{agentId}
                 AND m.member_status IN ('accepted','working','blocked','failed')))
            """;
    @Select("""
            SELECT t.* FROM agent_task_meta t
            WHERE t.tenant_id=#{tenantId} AND BINARY t.tenant_id=BINARY #{tenantId}
              AND t.client_id=#{clientId} AND BINARY t.client_id=BINARY #{clientId}
              AND t.owner_jiacn=#{ownerJiacn} AND BINARY t.owner_jiacn=BINARY #{ownerJiacn}
              AND t.reward_status IN ('open','planning','assigned','running','reviewing','blocked','failed')
              AND t.id>#{afterId}
            """ + " AND " + RELATED + " ORDER BY t.id LIMIT 100")
    List<AgentTaskMetaEntity> unfinishedPage(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId, @Param("afterId") long afterId);

    @Select("""
            SELECT COUNT(*) FROM agent_task_meta t
            WHERE t.tenant_id=#{tenantId} AND BINARY t.tenant_id=BINARY #{tenantId}
              AND t.client_id=#{clientId} AND BINARY t.client_id=BINARY #{clientId}
              AND t.owner_jiacn=#{ownerJiacn} AND BINARY t.owner_jiacn=BINARY #{ownerJiacn}
              AND t.task_id=#{taskId} AND BINARY t.task_id=BINARY #{taskId}
            """ + " AND " + RELATED)
    int related(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId, @Param("taskId") String taskId);
}
