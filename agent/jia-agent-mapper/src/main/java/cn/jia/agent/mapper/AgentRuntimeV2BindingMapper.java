package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentRuntimeV2BindingEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentRuntimeV2BindingMapper {
    String COLUMNS = """
            runtime_binding_id,credential_kind,credential_reference,runtime_installation_id,
            contract_version,registration_subject_sha256,
            registration_authority_id,registration_authority_revision,process_public_key_spki,
            process_public_key_sha256,verifier_version,verifier_key_id,reconnect_verifier,
            current_registration_receipt_id,current_registration_receipt_sha256,
            data_domain_id,execution_pool_id,owner_jiacn,agent_id,
            producer_deployment_id,claimant_deployment_id,claimant_worker_generation,
            receiver_registration_revision,admission_epoch,module_id,migration_epoch,
            runtime_instance_id,binding_generation,status,connected_at,disconnected_at,revoked_at,
            revoke_reason,socket_owner_instance_id,socket_owner_epoch,socket_owner_lease_until,
            current_session_fence,version,tenant_id,client_id,create_time,update_time
            """;
    String VALUES = """
            #{e.runtimeBindingId},#{e.credentialKind},#{e.credentialReference},#{e.runtimeInstallationId},
            #{e.contractVersion},#{e.registrationSubjectSha256},
            #{e.registrationAuthorityId},#{e.registrationAuthorityRevision},
            #{e.processPublicKeySpki},#{e.processPublicKeySha256},#{e.verifierVersion},#{e.verifierKeyId},
            #{e.reconnectVerifier},#{e.currentRegistrationReceiptId},#{e.currentRegistrationReceiptSha256},
            #{dataDomainId},#{executionPoolId},#{ownerJiacn},#{agentId},
            NULL,#{claimantDeploymentId},#{claimantWorkerGeneration},#{receiverRegistrationRevision},
            #{admissionEpoch},#{moduleId},#{migrationEpoch},#{e.runtimeInstanceId},#{e.bindingGeneration},
            #{e.status},#{e.connectedAt},#{e.disconnectedAt},#{e.revokedAt},#{e.revokeReason},
            #{e.socketOwnerInstanceId},#{e.socketOwnerEpoch},#{e.socketOwnerLeaseUntil},
            #{e.currentSessionFence},#{e.version},#{tenantId},#{clientId},#{e.createTime},#{e.updateTime}
            """;

    @Insert("INSERT INTO agent_runtime_v2_binding(" + COLUMNS + ") VALUES(" + VALUES + ")")
    int insert(@Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("e") AgentRuntimeV2BindingEntity entity);

    @Insert("INSERT INTO agent_runtime_v2_binding(" + COLUMNS + ") SELECT " + VALUES + " " + """
              FROM agent_runtime_v1_installation i
             WHERE i.installation_id=#{e.runtimeInstallationId} AND i.status='ACTIVE'
               AND i.tenant_id=#{tenantId} AND i.client_id=#{clientId}
               AND i.canonical_agent_id=#{agentId}
               AND CAST(i.installation_id AS BINARY)=CAST(#{e.runtimeInstallationId} AS BINARY)
               AND OCTET_LENGTH(i.installation_id)=OCTET_LENGTH(#{e.runtimeInstallationId})
               AND CAST(i.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(i.tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(i.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(i.client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(i.canonical_agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
               AND OCTET_LENGTH(i.canonical_agent_id)=OCTET_LENGTH(#{agentId})
            """)
    int insertFromActiveV1Installation(@Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("e") AgentRuntimeV2BindingEntity entity);

    @Select("SELECT * FROM agent_runtime_v2_binding WHERE " + AgentRuntimeV2MapperScope.RECEIVER_EXACT
            + " AND runtime_binding_id=#{runtimeBindingId}"
            + " AND (contract_version<>2 OR evidence_provenance='R2B1_ENFORCED')"
            + " AND CAST(runtime_binding_id AS BINARY)=CAST(#{runtimeBindingId} AS BINARY)"
            + " AND OCTET_LENGTH(runtime_binding_id)=OCTET_LENGTH(#{runtimeBindingId}) LIMIT 1")
    AgentRuntimeV2BindingEntity findExact(@Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId, @Param("executionPoolId") String executionPoolId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("agentId") String agentId, @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("runtimeBindingId") String runtimeBindingId);

    @Select("SELECT * FROM agent_runtime_v2_binding WHERE " + AgentRuntimeV2MapperScope.RECEIVER_EXACT
            + " AND runtime_binding_id=#{runtimeBindingId}"
            + " AND (contract_version<>2 OR evidence_provenance='R2B1_ENFORCED')"
            + " AND CAST(runtime_binding_id AS BINARY)=CAST(#{runtimeBindingId} AS BINARY)"
            + " AND OCTET_LENGTH(runtime_binding_id)=OCTET_LENGTH(#{runtimeBindingId}) LIMIT 1 FOR UPDATE")
    AgentRuntimeV2BindingEntity lockExact(@Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId, @Param("executionPoolId") String executionPoolId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("agentId") String agentId, @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("runtimeBindingId") String runtimeBindingId);

    @Update("""
            UPDATE agent_runtime_v2_binding
               SET status=#{newStatus},disconnected_at=#{disconnectedAt},revoked_at=#{revokedAt},
                   revoke_reason=#{revokeReason},socket_owner_instance_id=NULL,socket_owner_epoch=NULL,
                   socket_owner_lease_until=NULL,current_session_fence=NULL,version=version+1,update_time=#{now}
             WHERE """ + AgentRuntimeV2MapperScope.RECEIVER_EXACT + """
               AND contract_version=1 AND runtime_binding_id=#{runtimeBindingId} AND version=#{expectedVersion}
               AND status=#{expectedStatus} AND binding_generation=#{bindingGeneration}
               AND runtime_instance_id=#{runtimeInstanceId}
               AND CAST(runtime_binding_id AS BINARY)=CAST(#{runtimeBindingId} AS BINARY)
               AND OCTET_LENGTH(runtime_binding_id)=OCTET_LENGTH(#{runtimeBindingId})
               AND CAST(runtime_instance_id AS BINARY)=CAST(#{runtimeInstanceId} AS BINARY)
               AND OCTET_LENGTH(runtime_instance_id)=OCTET_LENGTH(#{runtimeInstanceId})
               AND ((status='ACTIVE' AND #{newStatus}='DISCONNECTED' AND disconnected_at IS NULL
                     AND revoked_at IS NULL AND #{disconnectedAt} BETWEEN connected_at AND #{now}
                     AND #{revokedAt} IS NULL AND #{revokeReason} IS NULL)
                    OR (status='ACTIVE' AND #{newStatus}='REVOKED' AND disconnected_at IS NULL
                     AND revoked_at IS NULL AND #{disconnectedAt} IS NULL
                     AND #{revokedAt} BETWEEN connected_at AND #{now}
                     AND OCTET_LENGTH(#{revokeReason}) BETWEEN 1 AND 500)
                    OR (status='DISCONNECTED' AND #{newStatus}='REVOKED'
                     AND disconnected_at=#{disconnectedAt} AND revoked_at IS NULL
                     AND #{revokedAt} BETWEEN disconnected_at AND #{now}
                     AND OCTET_LENGTH(#{revokeReason}) BETWEEN 1 AND 500))
            """)
    int transition(@Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("runtimeBindingId") String runtimeBindingId,
            @Param("bindingGeneration") long bindingGeneration, @Param("runtimeInstanceId") String runtimeInstanceId,
            @Param("expectedVersion") long expectedVersion, @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus, @Param("disconnectedAt") Long disconnectedAt,
            @Param("revokedAt") Long revokedAt, @Param("revokeReason") String revokeReason,
            @Param("now") long now);

    @Update("""
            UPDATE agent_runtime_v2_binding SET socket_owner_instance_id=#{socketOwnerInstanceId},
                   socket_owner_epoch=#{socketOwnerEpoch},socket_owner_lease_until=#{socketOwnerLeaseUntil},
                   version=version+1,update_time=#{now}
             WHERE """ + AgentRuntimeV2MapperScope.RECEIVER_EXACT + """
               AND contract_version=1 AND runtime_binding_id=#{runtimeBindingId} AND status='ACTIVE'
               AND binding_generation=#{bindingGeneration} AND runtime_instance_id=#{runtimeInstanceId}
               AND version=#{expectedVersion} AND socket_owner_instance_id=#{expectedSocketOwnerInstanceId}
               AND CAST(socket_owner_instance_id AS BINARY)=CAST(#{expectedSocketOwnerInstanceId} AS BINARY)
               AND OCTET_LENGTH(socket_owner_instance_id)=OCTET_LENGTH(#{expectedSocketOwnerInstanceId})
               AND socket_owner_epoch=#{expectedSocketOwnerEpoch}
               AND socket_owner_lease_until=#{expectedSocketOwnerLeaseUntil}
               AND #{socketOwnerLeaseUntil}>socket_owner_lease_until AND #{socketOwnerLeaseUntil}>#{now}
               AND ((#{socketOwnerInstanceId}=#{expectedSocketOwnerInstanceId}
                     AND CAST(#{socketOwnerInstanceId} AS BINARY)=CAST(#{expectedSocketOwnerInstanceId} AS BINARY)
                     AND OCTET_LENGTH(#{socketOwnerInstanceId})=OCTET_LENGTH(#{expectedSocketOwnerInstanceId})
                     AND #{socketOwnerEpoch}=#{expectedSocketOwnerEpoch} AND socket_owner_lease_until>#{now})
                    OR (NOT (#{socketOwnerInstanceId}=#{expectedSocketOwnerInstanceId}
                         AND CAST(#{socketOwnerInstanceId} AS BINARY)=CAST(#{expectedSocketOwnerInstanceId} AS BINARY)
                         AND OCTET_LENGTH(#{socketOwnerInstanceId})=OCTET_LENGTH(#{expectedSocketOwnerInstanceId}))
                     AND socket_owner_lease_until<=#{now} AND #{socketOwnerEpoch}>#{expectedSocketOwnerEpoch}))
               AND CAST(runtime_binding_id AS BINARY)=CAST(#{runtimeBindingId} AS BINARY)
               AND OCTET_LENGTH(runtime_binding_id)=OCTET_LENGTH(#{runtimeBindingId})
            """)
    int moveRoutingLease(@Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("runtimeBindingId") String runtimeBindingId,
            @Param("bindingGeneration") long bindingGeneration, @Param("runtimeInstanceId") String runtimeInstanceId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedSocketOwnerInstanceId") String expectedSocketOwnerInstanceId,
            @Param("expectedSocketOwnerEpoch") long expectedSocketOwnerEpoch,
            @Param("expectedSocketOwnerLeaseUntil") long expectedSocketOwnerLeaseUntil,
            @Param("socketOwnerInstanceId") String socketOwnerInstanceId,
            @Param("socketOwnerEpoch") long socketOwnerEpoch,
            @Param("socketOwnerLeaseUntil") long socketOwnerLeaseUntil, @Param("now") long now);

    @Update("""
            UPDATE agent_runtime_v2_binding SET binding_generation=#{newGeneration},
              runtime_instance_id=#{newRuntimeInstanceId},status=#{newStatus},
              connected_at=#{connectedAt},disconnected_at=#{disconnectedAt},revoked_at=#{revokedAt},
              revoke_reason=#{reason},current_session_fence=#{newSessionFence},
              socket_owner_instance_id=#{newSocketOwnerInstanceId},socket_owner_epoch=#{newSocketOwnerEpoch},
              socket_owner_lease_until=#{newSocketOwnerLeaseUntil},version=version+1,update_time=#{effectiveAt}
            WHERE """ + AgentRuntimeV2MapperScope.RECEIVER_EXACT + """
              AND runtime_binding_id=#{runtimeBindingId} AND contract_version=2 AND version=#{expectedVersion}
              AND evidence_provenance='R2B1_ENFORCED'
              AND CAST(runtime_binding_id AS BINARY)=CAST(#{runtimeBindingId} AS BINARY)
              AND OCTET_LENGTH(runtime_binding_id)=OCTET_LENGTH(#{runtimeBindingId})
              AND binding_generation=#{expectedGeneration} AND status=#{expectedStatus}
              AND runtime_instance_id=#{expectedRuntimeInstanceId}
              AND CAST(runtime_instance_id AS BINARY)=CAST(#{expectedRuntimeInstanceId} AS BINARY)
              AND OCTET_LENGTH(runtime_instance_id)=OCTET_LENGTH(#{expectedRuntimeInstanceId})
              AND ((current_session_fence IS NULL AND #{expectedSessionFence} IS NULL)
                   OR (current_session_fence=#{expectedSessionFence}
                       AND CAST(current_session_fence AS BINARY)=CAST(#{expectedSessionFence} AS BINARY)
                       AND OCTET_LENGTH(current_session_fence)=OCTET_LENGTH(#{expectedSessionFence})))
            """)
    int casGenerationProjection(@Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("runtimeBindingId") String runtimeBindingId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedGeneration") long expectedGeneration,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedRuntimeInstanceId") String expectedRuntimeInstanceId,
            @Param("expectedSessionFence") String expectedSessionFence,
            @Param("newGeneration") long newGeneration,
            @Param("newRuntimeInstanceId") String newRuntimeInstanceId,
            @Param("newStatus") String newStatus, @Param("connectedAt") long connectedAt,
            @Param("disconnectedAt") Long disconnectedAt, @Param("revokedAt") Long revokedAt,
            @Param("reason") String reason, @Param("newSessionFence") String newSessionFence,
            @Param("newSocketOwnerInstanceId") String newSocketOwnerInstanceId,
            @Param("newSocketOwnerEpoch") Long newSocketOwnerEpoch,
            @Param("newSocketOwnerLeaseUntil") Long newSocketOwnerLeaseUntil,
            @Param("effectiveAt") long effectiveAt);
}
