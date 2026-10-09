package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskProviderCostConsentEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentTaskProviderCostConsentMapper
        extends BaseMapper<AgentTaskProviderCostConsentEntity> {
    /** Legacy v1 projection excludes optional v3 columns so default-off nodes remain compatible
     * with the pristine provider-consent schema. */
    String COLUMNS = """
            id,consent_id,owner_jiacn,task_id,target_agent_id,idempotency_key,request_digest,
            assignment_idempotency_key,assignment_base_hash,task_version,requirement_revision,
            requirement_sha256,input_snapshot_digest,input_snapshot_json,provider_lane,binding_id,
            binding_epoch,model_id,custody,operator_issuer,operator_policy_revision,pricing_mode,
            max_outbound_request_attempts,expires_at,state,version,bound_grant_id,
            bound_grant_version,bound_assignment_revision,reserved_execution_id,reserved_run_id,
            consumed_lease_id,consumed_at,revoke_idempotency_key,revoke_request_digest,revoked_at,
            created_at,tenant_id,client_id,create_time,update_time
            """;
    /** Called only behind the v3 feature/schema readiness boundary. */
    String FOLLOWUP_COLUMNS = """
            id,consent_id,consent_purpose,operation_grant_id,execution_intent_id,conversation_id,
            conversation_generation,operation,instruction_sha256,source_snapshot_sha256,
            owner_payload_sha256,runtime_input_snapshot_sha256,owner_jiacn,task_id,target_agent_id,
            idempotency_key,request_digest,assignment_idempotency_key,assignment_base_hash,
            task_version,requirement_revision,requirement_sha256,input_snapshot_digest,
            input_snapshot_json,provider_lane,binding_id,binding_epoch,model_id,custody,
            operator_issuer,operator_policy_revision,pricing_mode,max_outbound_request_attempts,
            expires_at,state,version,bound_grant_id,bound_grant_version,bound_assignment_revision,
            reserved_execution_id,reserved_run_id,consumed_lease_id,consumed_at,
            revoke_idempotency_key,revoke_request_digest,revoked_at,created_at,tenant_id,client_id,
            create_time,update_time
            """;
    @Select("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
            + "AND table_name='agent_task_provider_cost_consent' AND column_name='consent_purpose'")
    int countConsentPurposeColumn();

    String EXACT_SCOPE = """
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
              AND CAST(task_id AS BINARY)=CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{taskId})
            """;

    @Select("SELECT " + COLUMNS + " FROM agent_task_provider_cost_consent"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId} AND idempotency_key=#{idempotencyKey}" + EXACT_SCOPE + """
              AND CAST(idempotency_key AS BINARY)=CAST(#{idempotencyKey} AS BINARY)
              AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
             LIMIT 1
            """)
    AgentTaskProviderCostConsentEntity selectByIdempotencyKey(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT " + COLUMNS + " FROM agent_task_provider_cost_consent"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId} AND idempotency_key=#{idempotencyKey}" + EXACT_SCOPE + """
              AND CAST(idempotency_key AS BINARY)=CAST(#{idempotencyKey} AS BINARY)
              AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
             LIMIT 1 FOR UPDATE
            """)
    AgentTaskProviderCostConsentEntity selectByIdempotencyKeyForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT " + COLUMNS + " FROM agent_task_provider_cost_consent"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId} AND consent_id=#{consentId}" + EXACT_SCOPE + """
              AND CAST(consent_id AS BINARY)=CAST(#{consentId} AS BINARY)
              AND OCTET_LENGTH(consent_id)=OCTET_LENGTH(#{consentId})
             LIMIT 1
            """)
    AgentTaskProviderCostConsentEntity selectByConsent(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId, @Param("consentId") String consentId);

    @Select("SELECT " + COLUMNS + " FROM agent_task_provider_cost_consent"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId} AND consent_id=#{consentId}" + EXACT_SCOPE + """
              AND CAST(consent_id AS BINARY)=CAST(#{consentId} AS BINARY)
              AND OCTET_LENGTH(consent_id)=OCTET_LENGTH(#{consentId})
             LIMIT 1 FOR UPDATE
            """)
    AgentTaskProviderCostConsentEntity selectByConsentForUpdate(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId, @Param("consentId") String consentId);

    @Select("SELECT " + FOLLOWUP_COLUMNS + " FROM agent_task_provider_cost_consent"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId} AND idempotency_key=#{idempotencyKey}" + EXACT_SCOPE + """
              AND CAST(idempotency_key AS BINARY)=CAST(#{idempotencyKey} AS BINARY)
              AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
             LIMIT 1
            """)
    AgentTaskProviderCostConsentEntity selectPurposeAwareByIdempotencyKey(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT " + FOLLOWUP_COLUMNS + " FROM agent_task_provider_cost_consent"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId} AND idempotency_key=#{idempotencyKey}" + EXACT_SCOPE + """
              AND CAST(idempotency_key AS BINARY)=CAST(#{idempotencyKey} AS BINARY)
              AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
             LIMIT 1 FOR UPDATE
            """)
    AgentTaskProviderCostConsentEntity selectPurposeAwareByIdempotencyKeyForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT " + FOLLOWUP_COLUMNS + " FROM agent_task_provider_cost_consent"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId} AND consent_id=#{consentId}" + EXACT_SCOPE + """
              AND CAST(consent_id AS BINARY)=CAST(#{consentId} AS BINARY)
              AND OCTET_LENGTH(consent_id)=OCTET_LENGTH(#{consentId})
             LIMIT 1
            """)
    AgentTaskProviderCostConsentEntity selectPurposeAwareByConsent(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId, @Param("consentId") String consentId);

    @Select("SELECT " + FOLLOWUP_COLUMNS + " FROM agent_task_provider_cost_consent"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId} AND consent_id=#{consentId}" + EXACT_SCOPE + """
              AND CAST(consent_id AS BINARY)=CAST(#{consentId} AS BINARY)
              AND OCTET_LENGTH(consent_id)=OCTET_LENGTH(#{consentId})
             LIMIT 1 FOR UPDATE
            """)
    AgentTaskProviderCostConsentEntity selectPurposeAwareByConsentForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId,
            @Param("consentId") String consentId);

    @Select("SELECT " + FOLLOWUP_COLUMNS + " FROM agent_task_provider_cost_consent"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId} AND consent_id=#{consentId}" + EXACT_SCOPE + """
              AND consent_purpose IN ('FOLLOWUP_EXECUTE','ORDINARY_ACTION')
              AND CAST(consent_id AS BINARY)=CAST(#{consentId} AS BINARY)
              AND OCTET_LENGTH(consent_id)=OCTET_LENGTH(#{consentId})
             LIMIT 1
            """)
    AgentTaskProviderCostConsentEntity selectFollowupByConsent(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId, @Param("consentId") String consentId);

    @Select("SELECT " + FOLLOWUP_COLUMNS + " FROM agent_task_provider_cost_consent"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId} AND consent_id=#{consentId}" + EXACT_SCOPE + """
              AND consent_purpose IN ('FOLLOWUP_EXECUTE','ORDINARY_ACTION')
              AND CAST(consent_id AS BINARY)=CAST(#{consentId} AS BINARY)
              AND OCTET_LENGTH(consent_id)=OCTET_LENGTH(#{consentId})
             LIMIT 1 FOR UPDATE
            """)
    AgentTaskProviderCostConsentEntity selectFollowupByConsentForUpdate(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId, @Param("consentId") String consentId);

    @Update("""
            UPDATE agent_task_provider_cost_consent
               SET state='BOUND',version=version+1,bound_grant_id=#{row.boundGrantId},
                   bound_grant_version=#{row.boundGrantVersion},
                   bound_assignment_revision=#{row.boundAssignmentRevision},
                   update_time=#{row.updateTime}
             WHERE tenant_id=#{row.tenantId} AND client_id=#{row.clientId}
               AND owner_jiacn=#{row.ownerJiacn} AND task_id=#{row.taskId}
               AND consent_id=#{row.consentId} AND state='ISSUED' AND version=#{expectedVersion}
               AND CAST(tenant_id AS BINARY)=CAST(#{row.tenantId} AS BINARY)
               AND CAST(client_id AS BINARY)=CAST(#{row.clientId} AS BINARY)
               AND CAST(owner_jiacn AS BINARY)=CAST(#{row.ownerJiacn} AS BINARY)
               AND CAST(task_id AS BINARY)=CAST(#{row.taskId} AS BINARY)
               AND CAST(consent_id AS BINARY)=CAST(#{row.consentId} AS BINARY)
            """)
    int bind(@Param("row") AgentTaskProviderCostConsentEntity row,
            @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE agent_task_provider_cost_consent
               SET state='RESERVED',version=version+1,reserved_execution_id=#{row.reservedExecutionId},
                   reserved_run_id=#{row.reservedRunId},update_time=#{row.updateTime}
             WHERE tenant_id=#{row.tenantId} AND client_id=#{row.clientId}
               AND owner_jiacn=#{row.ownerJiacn} AND task_id=#{row.taskId}
               AND consent_id=#{row.consentId} AND state='BOUND' AND version=#{expectedVersion}
               AND CAST(tenant_id AS BINARY)=CAST(#{row.tenantId} AS BINARY)
               AND CAST(client_id AS BINARY)=CAST(#{row.clientId} AS BINARY)
               AND CAST(owner_jiacn AS BINARY)=CAST(#{row.ownerJiacn} AS BINARY)
               AND CAST(task_id AS BINARY)=CAST(#{row.taskId} AS BINARY)
               AND CAST(consent_id AS BINARY)=CAST(#{row.consentId} AS BINARY)
            """)
    int reserve(@Param("row") AgentTaskProviderCostConsentEntity row,
            @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE agent_task_provider_cost_consent
               SET state='CONSUMED',version=version+1,consumed_lease_id=#{row.consumedLeaseId},
                   consumed_at=#{row.consumedAt},update_time=#{row.updateTime}
             WHERE tenant_id=#{row.tenantId} AND client_id=#{row.clientId}
               AND owner_jiacn=#{row.ownerJiacn} AND task_id=#{row.taskId}
               AND consent_id=#{row.consentId} AND state='RESERVED' AND version=#{expectedVersion}
               AND CAST(tenant_id AS BINARY)=CAST(#{row.tenantId} AS BINARY)
               AND CAST(client_id AS BINARY)=CAST(#{row.clientId} AS BINARY)
               AND CAST(owner_jiacn AS BINARY)=CAST(#{row.ownerJiacn} AS BINARY)
               AND CAST(task_id AS BINARY)=CAST(#{row.taskId} AS BINARY)
               AND CAST(consent_id AS BINARY)=CAST(#{row.consentId} AS BINARY)
            """)
    int consume(@Param("row") AgentTaskProviderCostConsentEntity row,
            @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE agent_task_provider_cost_consent
               SET state='REVOKED',version=version+1,
                   revoke_idempotency_key=#{row.revokeIdempotencyKey},
                   revoke_request_digest=#{row.revokeRequestDigest},revoked_at=#{row.revokedAt},
                   update_time=#{row.updateTime}
             WHERE tenant_id=#{row.tenantId} AND client_id=#{row.clientId}
               AND owner_jiacn=#{row.ownerJiacn} AND task_id=#{row.taskId}
               AND consent_id=#{row.consentId} AND state IN ('ISSUED','BOUND','RESERVED')
               AND version=#{expectedVersion}
               AND CAST(tenant_id AS BINARY)=CAST(#{row.tenantId} AS BINARY)
               AND CAST(client_id AS BINARY)=CAST(#{row.clientId} AS BINARY)
               AND CAST(owner_jiacn AS BINARY)=CAST(#{row.ownerJiacn} AS BINARY)
               AND CAST(task_id AS BINARY)=CAST(#{row.taskId} AS BINARY)
               AND CAST(consent_id AS BINARY)=CAST(#{row.consentId} AS BINARY)
            """)
    int revoke(@Param("row") AgentTaskProviderCostConsentEntity row,
            @Param("expectedVersion") long expectedVersion);
    @Update("""
            UPDATE agent_task_provider_cost_consent SET state='BOUND',version=version+1,
              bound_grant_id=#{row.boundGrantId},bound_grant_version=#{row.boundGrantVersion},
              bound_assignment_revision=#{row.boundAssignmentRevision},update_time=#{row.updateTime}
            WHERE tenant_id=#{row.tenantId} AND client_id=#{row.clientId} AND owner_jiacn=#{row.ownerJiacn}
              AND task_id=#{row.taskId} AND consent_id=#{row.consentId}
              AND consent_purpose IN ('FOLLOWUP_EXECUTE','ORDINARY_ACTION') AND operation_grant_id=#{row.operationGrantId}
              AND state='ISSUED' AND version=#{expectedVersion}
              AND CAST(tenant_id AS BINARY)=CAST(#{row.tenantId} AS BINARY)
              AND CAST(client_id AS BINARY)=CAST(#{row.clientId} AS BINARY)
              AND CAST(owner_jiacn AS BINARY)=CAST(#{row.ownerJiacn} AS BINARY)
              AND CAST(task_id AS BINARY)=CAST(#{row.taskId} AS BINARY)
              AND CAST(consent_id AS BINARY)=CAST(#{row.consentId} AS BINARY)
            """)
    int bindFollowup(@Param("row")AgentTaskProviderCostConsentEntity row,@Param("expectedVersion")long version);
    @Update("""
            UPDATE agent_task_provider_cost_consent SET state='RESERVED',version=version+1,
              reserved_execution_id=#{row.reservedExecutionId},reserved_run_id=#{row.reservedRunId},
              runtime_input_snapshot_sha256=#{row.runtimeInputSnapshotSha256},update_time=#{row.updateTime}
            WHERE tenant_id=#{row.tenantId} AND client_id=#{row.clientId} AND owner_jiacn=#{row.ownerJiacn}
              AND task_id=#{row.taskId} AND consent_id=#{row.consentId}
              AND consent_purpose IN ('FOLLOWUP_EXECUTE','ORDINARY_ACTION') AND operation_grant_id=#{row.operationGrantId}
              AND state='BOUND' AND version=#{expectedVersion}
              AND CAST(tenant_id AS BINARY)=CAST(#{row.tenantId} AS BINARY)
              AND CAST(client_id AS BINARY)=CAST(#{row.clientId} AS BINARY)
              AND CAST(owner_jiacn AS BINARY)=CAST(#{row.ownerJiacn} AS BINARY)
              AND CAST(task_id AS BINARY)=CAST(#{row.taskId} AS BINARY)
              AND CAST(consent_id AS BINARY)=CAST(#{row.consentId} AS BINARY)
            """)
    int reserveFollowup(@Param("row")AgentTaskProviderCostConsentEntity row,@Param("expectedVersion")long version);
    @Update("""
            UPDATE agent_task_provider_cost_consent SET state='CONSUMED',version=version+1,
              consumed_lease_id=#{row.consumedLeaseId},consumed_at=#{row.consumedAt},update_time=#{row.updateTime}
            WHERE tenant_id=#{row.tenantId} AND client_id=#{row.clientId} AND owner_jiacn=#{row.ownerJiacn}
              AND task_id=#{row.taskId} AND consent_id=#{row.consentId}
              AND consent_purpose IN ('FOLLOWUP_EXECUTE','ORDINARY_ACTION') AND operation_grant_id=#{row.operationGrantId}
              AND state='RESERVED' AND version=#{expectedVersion}
              AND CAST(tenant_id AS BINARY)=CAST(#{row.tenantId} AS BINARY)
              AND CAST(client_id AS BINARY)=CAST(#{row.clientId} AS BINARY)
              AND CAST(owner_jiacn AS BINARY)=CAST(#{row.ownerJiacn} AS BINARY)
              AND CAST(task_id AS BINARY)=CAST(#{row.taskId} AS BINARY)
              AND CAST(consent_id AS BINARY)=CAST(#{row.consentId} AS BINARY)
            """)
    int consumeFollowup(@Param("row")AgentTaskProviderCostConsentEntity row,@Param("expectedVersion")long version);
    @Update("""
            UPDATE agent_task_provider_cost_consent SET state='REVOKED',version=version+1,
              revoke_idempotency_key=#{row.revokeIdempotencyKey},revoke_request_digest=#{row.revokeRequestDigest},
              revoked_at=#{row.revokedAt},update_time=#{row.updateTime}
            WHERE tenant_id=#{row.tenantId} AND client_id=#{row.clientId} AND owner_jiacn=#{row.ownerJiacn}
              AND task_id=#{row.taskId} AND consent_id=#{row.consentId}
              AND consent_purpose IN ('FOLLOWUP_EXECUTE','ORDINARY_ACTION') AND operation_grant_id=#{row.operationGrantId}
              AND state IN ('ISSUED','BOUND','RESERVED') AND version=#{expectedVersion}
              AND CAST(tenant_id AS BINARY)=CAST(#{row.tenantId} AS BINARY)
              AND CAST(client_id AS BINARY)=CAST(#{row.clientId} AS BINARY)
              AND CAST(owner_jiacn AS BINARY)=CAST(#{row.ownerJiacn} AS BINARY)
              AND CAST(task_id AS BINARY)=CAST(#{row.taskId} AS BINARY)
              AND CAST(consent_id AS BINARY)=CAST(#{row.consentId} AS BINARY)
            """)
    int revokeFollowup(@Param("row")AgentTaskProviderCostConsentEntity row,@Param("expectedVersion")long version);
}
