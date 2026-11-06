package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

/** GSS-CANCEL-20261010: current scoped facts, no DDL/payload reads; owner cancellation fences unadmitted bootstrap by its locked version. */
public interface AgentTaskCancellationMapper {
    String SCOPE = " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId}" + AgentTaskExecutionGrantMapper.EXACT_SCOPE
            + AgentTaskExecutionGrantMapper.EXACT_TASK;

    @Select("SELECT * FROM agent_task_member" + SCOPE + " ORDER BY CAST(agent_id AS BINARY), id FOR UPDATE")
    List<AgentTaskMemberEntity> lockMembers(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    @Select("SELECT * FROM agent_task_execution_grant" + SCOPE + " ORDER BY CAST(grant_id AS BINARY), id FOR UPDATE")
    List<AgentTaskExecutionGrantEntity> lockGrants(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    @Select("SELECT 1 FROM agent_task_funding" + SCOPE + " LIMIT 1 FOR UPDATE")
    Long funding(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    @Select("SELECT 1 FROM agent_task_funding_operation" + SCOPE + " LIMIT 1 FOR UPDATE")
    Long fundingOperation(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    @Select("SELECT * FROM agent_task_bounty_bootstrap_outbox" + SCOPE + " ORDER BY id FOR UPDATE")
    List<cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity> lockBootstraps(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    // Owner cancellation closes admission under the same task-root lock as Chat admission.
    // The locked version fences worker claim/reconcile; preserve attempts, invalidate its old lease.
    @Update("""
            UPDATE agent_task_bounty_bootstrap_outbox
            SET status='DEAD',next_retry_at=NULL,last_error_code='TASK_CANCELLED',
                lease_owner=NULL,lease_until=NULL,
                reconciled_at=GREATEST(#{now},created_at),version=version+1,update_time=#{now}
            """ + SCOPE + """
            AND id=#{id} AND version=#{version} AND status IN ('PENDING','RETRY','CLAIMED')
            AND admitted_conversation_id IS NULL AND admitted_request_id IS NULL
            """)
    int cancelUnadmittedBootstrap(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId, @Param("id") long id,
            @Param("version") long version, @Param("now") long now);

    // Only the task occupation projection is released, never an installation/process/session.
    @Update("""
            UPDATE agent_runtime
            SET current_task_id=NULL, current_task_title=NULL,
                status=CASE WHEN CAST(status AS BINARY)=CAST('busy' AS BINARY)
                    AND OCTET_LENGTH(status)=4 THEN 'online' ELSE status END,
                update_time=#{now}
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
              AND current_task_id=#{taskId}
            """ + AgentTaskExecutionGrantMapper.EXACT_SCOPE + """
              AND CAST(current_task_id AS BINARY)=CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(current_task_id)=OCTET_LENGTH(#{taskId})
            """)
    int releaseTaskOccupation(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId, @Param("now") long now);
}
