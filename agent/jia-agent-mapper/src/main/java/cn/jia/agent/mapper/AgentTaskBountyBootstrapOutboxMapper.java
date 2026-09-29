package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentTaskBountyBootstrapOutboxMapper
        extends BaseMapper<AgentTaskBountyBootstrapOutboxEntity> {
    String COLUMNS = """
            id,bootstrap_id,owner_jiacn,task_id,source_business_action_id,payload_hash,
            requirement_revision,requirement_anchor,assignment_revision,target_agent_id,
            grant_id,grant_version,permitted_operation,reference_summary_json,
            reference_summary_sha256,status,attempt_count,next_retry_at,lease_owner,lease_until,
            admitted_conversation_id,admitted_request_id,last_error_code,version,created_at,
            reconciled_at,tenant_id,client_id,create_time,update_time
            """;
    String EXACT_SCOPE = """
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            """;

    @Select("SELECT " + COLUMNS + " FROM agent_task_bounty_bootstrap_outbox"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND source_business_action_id=#{sourceBusinessActionId}" + EXACT_SCOPE + """
              AND CAST(source_business_action_id AS BINARY)=CAST(#{sourceBusinessActionId} AS BINARY)
              AND OCTET_LENGTH(source_business_action_id)=OCTET_LENGTH(#{sourceBusinessActionId})
             LIMIT 1 FOR UPDATE
            """)
    AgentTaskBountyBootstrapOutboxEntity selectByActionForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("sourceBusinessActionId") String sourceBusinessActionId);

    @Select("SELECT " + COLUMNS + " FROM agent_task_bounty_bootstrap_outbox"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + EXACT_SCOPE + """
              AND ((status IN ('PENDING','RETRY')
                    AND (next_retry_at IS NULL OR next_retry_at<=#{now}))
                   OR (status='CLAIMED' AND lease_until IS NOT NULL AND lease_until<=#{now}))
             ORDER BY id
             LIMIT 1 FOR UPDATE
            """)
    AgentTaskBountyBootstrapOutboxEntity selectClaimableForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("now") long now);

    // Only structurally valid persisted owner scopes are discoverable. Never trust client input.
    // MySQL 8 SKIP LOCKED keeps a busy owner's row from stalling another owner.
    @Select("SELECT " + COLUMNS + " FROM agent_task_bounty_bootstrap_outbox" + """
             WHERE tenant_id='0' AND CAST(tenant_id AS BINARY)=0x30
               AND client_id<>'' AND client_id=TRIM(client_id)
               AND owner_jiacn<>'' AND owner_jiacn<>'0'
               AND owner_jiacn=TRIM(owner_jiacn)
               AND NOT REGEXP_LIKE(client_id, '[[:cntrl:]]')
               AND NOT REGEXP_LIKE(owner_jiacn, '[[:cntrl:]]')
               AND ((status IN ('PENDING','RETRY')
                     AND (next_retry_at IS NULL OR next_retry_at<=#{now}))
                    OR (status='CLAIMED' AND lease_until IS NOT NULL AND lease_until<=#{now}))
             ORDER BY id
             LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    AgentTaskBountyBootstrapOutboxEntity selectClaimableAvailableForUpdate(@Param("now") long now);

    @Select("SELECT " + COLUMNS + " FROM agent_task_bounty_bootstrap_outbox"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND bootstrap_id=#{bootstrapId}" + EXACT_SCOPE + """
              AND CAST(bootstrap_id AS BINARY)=CAST(#{bootstrapId} AS BINARY)
              AND OCTET_LENGTH(bootstrap_id)=OCTET_LENGTH(#{bootstrapId})
             LIMIT 1 FOR UPDATE
            """)
    AgentTaskBountyBootstrapOutboxEntity selectByBootstrapForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("bootstrapId") String bootstrapId);

    @Update("""
            UPDATE agent_task_bounty_bootstrap_outbox
               SET status='CLAIMED', attempt_count=attempt_count+1,
                   next_retry_at=NULL, lease_owner=#{leaseOwner}, lease_until=#{leaseUntil},
                   last_error_code=NULL, version=version+1, update_time=#{now}
             WHERE id=#{intent.id} AND version=#{intent.version}
               AND tenant_id=#{intent.tenantId} AND client_id=#{intent.clientId}
               AND owner_jiacn=#{intent.ownerJiacn}
               AND ((status IN ('PENDING','RETRY')
                     AND (next_retry_at IS NULL OR next_retry_at<=#{now}))
                    OR (status='CLAIMED' AND lease_until IS NOT NULL AND lease_until<=#{now}))
               AND CAST(tenant_id AS BINARY)=CAST(#{intent.tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{intent.tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{intent.clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{intent.clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{intent.ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{intent.ownerJiacn})
            """)
    int claim(@Param("intent") AgentTaskBountyBootstrapOutboxEntity intent,
            @Param("leaseOwner") String leaseOwner, @Param("leaseUntil") long leaseUntil,
            @Param("now") long now);

    @Update("""
            UPDATE agent_task_bounty_bootstrap_outbox
               SET status=#{status}, next_retry_at=#{nextRetryAt},
                   lease_owner=NULL, lease_until=NULL,
                   admitted_conversation_id=#{conversationId}, admitted_request_id=#{requestId},
                   last_error_code=#{errorCode}, reconciled_at=#{reconciledAt},
                   version=version+1, update_time=#{now}
             WHERE id=#{intent.id} AND version=#{intent.version}
               AND tenant_id=#{intent.tenantId} AND client_id=#{intent.clientId}
               AND owner_jiacn=#{intent.ownerJiacn} AND bootstrap_id=#{intent.bootstrapId}
               AND status='CLAIMED' AND attempt_count=#{intent.attemptCount}
               AND lease_owner=#{intent.leaseOwner}
               AND CAST(tenant_id AS BINARY)=CAST(#{intent.tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{intent.tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{intent.clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{intent.clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{intent.ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{intent.ownerJiacn})
               AND CAST(bootstrap_id AS BINARY)=CAST(#{intent.bootstrapId} AS BINARY)
               AND OCTET_LENGTH(bootstrap_id)=OCTET_LENGTH(#{intent.bootstrapId})
               AND CAST(lease_owner AS BINARY)=CAST(#{intent.leaseOwner} AS BINARY)
               AND OCTET_LENGTH(lease_owner)=OCTET_LENGTH(#{intent.leaseOwner})
            """)
    int reconcile(@Param("intent") AgentTaskBountyBootstrapOutboxEntity intent,
            @Param("status") String status, @Param("nextRetryAt") Long nextRetryAt,
            @Param("conversationId") String conversationId, @Param("requestId") String requestId,
            @Param("errorCode") String errorCode, @Param("reconciledAt") Long reconciledAt,
            @Param("now") long now);
}
