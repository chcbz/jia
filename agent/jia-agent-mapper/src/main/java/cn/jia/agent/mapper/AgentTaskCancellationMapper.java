package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

/** GSS-CANCEL-20261010: current scoped facts, no DDL/payload reads; only unleased RETRY bootstrap closes. */
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

    // Provider authorization is not escrow funding. Inspect only terminal lifecycle facts,
    // not credential/input payloads; consumed/revoked history is preserved without any write.
    @Select("SELECT tenant_id,client_id,owner_jiacn,task_id,consent_id,state,version,created_at,expires_at,"
            + "bound_grant_id,bound_grant_version,bound_assignment_revision,reserved_execution_id,reserved_run_id,"
            + "consumed_lease_id,consumed_at,revoke_idempotency_key,revoke_request_digest,revoked_at"
            + " FROM agent_task_provider_cost_consent" + SCOPE
            + " ORDER BY CAST(consent_id AS BINARY),id FOR UPDATE")
    List<cn.jia.agent.entity.AgentTaskProviderCostConsentEntity> lockCostConsents(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    // Only historical, closed CONVERSATION output is compatible with an unstarted business task.
    @Select("SELECT 1 FROM agent_personal_workspace_execution" + SCOPE + """
            AND (execution_mode IS NULL OR CAST(execution_mode AS BINARY)<>CAST('CONVERSATION' AS BINARY)
              OR OCTET_LENGTH(execution_mode)<>12
              OR execution_state IS NULL OR CAST(execution_state AS BINARY)<>CAST('OUTPUT_COMMITTED' AS BINARY)
              OR OCTET_LENGTH(execution_state)<>16
              OR conversation_lease_expires_at>#{now}
              OR (conversation_lease_expires_at IS NULL AND (conversation_lease_token IS NOT NULL
                  OR conversation_lease_runtime_id IS NOT NULL))
              OR lease_token IS NOT NULL
              OR lease_expires_at IS NOT NULL OR work_item_id IS NOT NULL)
            LIMIT 1 FOR UPDATE
            """)
    Long execution(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId, @Param("now") long now);

    @Select("SELECT 1 FROM agent_selected_output_finalization" + SCOPE + " LIMIT 1 FOR UPDATE")
    Long finalization(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    @Select("SELECT 1 FROM agent_task_formal_delivery"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND task_id=#{taskId}"
            + " AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)"
            + " AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})"
            + " AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)"
            + " AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})"
            + AgentTaskExecutionGrantMapper.EXACT_TASK + " LIMIT 1 FOR UPDATE")
    Long delivery(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    @Select("SELECT 1 FROM agent_command_delivery" + SCOPE + " LIMIT 1 FOR UPDATE")
    Long command(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    @Select("SELECT * FROM agent_task_bounty_bootstrap_outbox" + SCOPE + " ORDER BY id FOR UPDATE")
    List<cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity> lockBootstraps(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    // RETRY has real attempts; use the existing DEAD disposition, without inventing an attempt.
    // PENDING(0 attempts) and CLAIMED are outside this bounded queue-cancellation slice.
    @Update("""
            UPDATE agent_task_bounty_bootstrap_outbox
            SET status='DEAD',next_retry_at=NULL,last_error_code='TASK_CANCELLED',
                reconciled_at=GREATEST(#{now},created_at),version=version+1,update_time=#{now}
            """ + SCOPE + """
            AND id=#{id} AND version=#{version} AND status='RETRY' AND attempt_count>0
            AND lease_owner IS NULL AND lease_until IS NULL
            AND admitted_conversation_id IS NULL AND admitted_request_id IS NULL
            """)
    int cancelRetryBootstrap(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId, @Param("id") long id,
            @Param("version") long version, @Param("now") long now);

    @Select("""
            SELECT 1 FROM chat_turn t JOIN chat_conversation c
              ON CAST(t.conversation_id AS BINARY)=CAST(CAST(c.id AS CHAR) AS BINARY)
              AND t.tenant_id=c.tenant_id AND t.client_id=c.client_id AND t.owner_jiacn=c.jiacn
              AND CAST(t.tenant_id AS BINARY)=CAST(c.tenant_id AS BINARY)
              AND OCTET_LENGTH(t.tenant_id)=OCTET_LENGTH(c.tenant_id)
              AND CAST(t.client_id AS BINARY)=CAST(c.client_id AS BINARY)
              AND OCTET_LENGTH(t.client_id)=OCTET_LENGTH(c.client_id)
              AND CAST(t.owner_jiacn AS BINARY)=CAST(c.jiacn AS BINARY)
              AND OCTET_LENGTH(t.owner_jiacn)=OCTET_LENGTH(c.jiacn)
            WHERE
            c.tenant_id=#{tenantId} AND c.client_id=#{clientId} AND c.jiacn=#{ownerJiacn} AND c.task_id=#{taskId}
            AND CAST(c.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(c.tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(c.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
            AND OCTET_LENGTH(c.client_id)=OCTET_LENGTH(#{clientId})
            AND CAST(c.jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
            AND OCTET_LENGTH(c.jiacn)=OCTET_LENGTH(#{ownerJiacn})
            AND CAST(c.task_id AS BINARY)=CAST(#{taskId} AS BINARY)
            AND OCTET_LENGTH(c.task_id)=OCTET_LENGTH(#{taskId})
 AND (t.state IS NULL OR CAST(t.state AS BINARY) NOT IN (CAST('PUBLISHED' AS BINARY),CAST('FAILED' AS BINARY),CAST('CANCELLED' AS BINARY)))
            LIMIT 1 FOR UPDATE
            """)
    Long chatTurn(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    @Select("""
            SELECT 1 FROM chat_request t JOIN chat_conversation c
              ON CAST(t.conversation_id AS BINARY)=CAST(CAST(c.id AS CHAR) AS BINARY)
              AND t.tenant_id=c.tenant_id AND t.client_id=c.client_id AND t.owner_jiacn=c.jiacn
              AND CAST(t.tenant_id AS BINARY)=CAST(c.tenant_id AS BINARY)
              AND OCTET_LENGTH(t.tenant_id)=OCTET_LENGTH(c.tenant_id)
              AND CAST(t.client_id AS BINARY)=CAST(c.client_id AS BINARY)
              AND OCTET_LENGTH(t.client_id)=OCTET_LENGTH(c.client_id)
              AND CAST(t.owner_jiacn AS BINARY)=CAST(c.jiacn AS BINARY)
              AND OCTET_LENGTH(t.owner_jiacn)=OCTET_LENGTH(c.jiacn)
            WHERE
            c.tenant_id=#{tenantId} AND c.client_id=#{clientId} AND c.jiacn=#{ownerJiacn} AND c.task_id=#{taskId}
            AND CAST(c.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(c.tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(c.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
            AND OCTET_LENGTH(c.client_id)=OCTET_LENGTH(#{clientId})
            AND CAST(c.jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
            AND OCTET_LENGTH(c.jiacn)=OCTET_LENGTH(#{ownerJiacn})
            AND CAST(c.task_id AS BINARY)=CAST(#{taskId} AS BINARY)
            AND OCTET_LENGTH(c.task_id)=OCTET_LENGTH(#{taskId})
 AND (t.aggregate_state IS NULL OR CAST(t.aggregate_state AS BINARY) NOT IN (CAST('COMPLETED' AS BINARY),CAST('PARTIAL' AS BINARY),CAST('OUTPUT_COMMITTED' AS BINARY),CAST('FAILED' AS BINARY),CAST('CANCELLED' AS BINARY)))
            LIMIT 1 FOR UPDATE
            """)
    Long chatRequest(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    @Select("SELECT 1 FROM chat_interaction_step" + SCOPE + """
            AND (state IS NULL OR CAST(state AS BINARY) NOT IN (
                CAST('COMPLETED' AS BINARY),CAST('OUTPUT_COMMITTED' AS BINARY),CAST('FAILED' AS BINARY),CAST('CANCELLED' AS BINARY)))
            LIMIT 1 FOR UPDATE
            """)
    Long chatStep(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    @Select("""
            SELECT 1 FROM chat_dispatch_outbox d JOIN chat_turn t ON d.turn_id=t.turn_id
              JOIN chat_conversation c ON CAST(t.conversation_id AS BINARY)=CAST(CAST(c.id AS CHAR) AS BINARY)
            WHERE c.task_id=#{taskId} AND c.tenant_id=#{tenantId} AND c.client_id=#{clientId} AND c.jiacn=#{ownerJiacn}
              AND CAST(c.task_id AS BINARY)=CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(c.task_id)=OCTET_LENGTH(#{taskId})
              AND CAST(c.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(c.tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(c.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(c.client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(c.jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(c.jiacn)=OCTET_LENGTH(#{ownerJiacn})
              AND CAST(t.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(t.tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(t.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(t.client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(t.owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(t.owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
              AND CAST(d.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(d.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(d.client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(d.owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(d.owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
              AND (d.event_type IS NULL OR CAST(d.event_type AS BINARY)<>CAST('CANCEL_REQUESTED' AS BINARY))
              AND (d.status IS NULL OR CAST(d.status AS BINARY) NOT IN (CAST('SENT' AS BINARY),CAST('DEAD' AS BINARY))
                OR d.lease_owner IS NOT NULL OR d.lease_until IS NOT NULL)
            LIMIT 1 FOR UPDATE
            """)
    Long chatDispatch(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    // Outbox has no owner column. Exact tenant/client/task aggregate is conservative;
    // do not ignore orphaned transport rows merely because a delivery is missing.
    @Select("""
            SELECT 1 FROM agent_outbox_event
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND aggregate_id=#{taskId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(aggregate_id AS BINARY)=CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(aggregate_id)=OCTET_LENGTH(#{taskId})
            LIMIT 1 FOR UPDATE
            """)
    Long outbox(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

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
