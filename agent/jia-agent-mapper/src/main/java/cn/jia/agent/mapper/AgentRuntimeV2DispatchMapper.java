package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentRuntimeV2DispatchMapper {
    String INBOX_PRODUCER_SCOPE = " " + """
            i.evidence_provenance='R2B1_ENFORCED'
            AND i.tenant_id=#{tenantId} AND i.data_domain_id=#{dataDomainId}
            AND i.execution_pool_id=#{executionPoolId} AND i.client_id=#{commandCallerClientId}
            AND i.producer_deployment_id=#{producerDeploymentId}
            AND i.claimant_deployment_id=#{claimantDeploymentId}
            AND i.claimant_worker_generation=#{claimantWorkerGeneration}
            AND i.admission_epoch=#{admissionEpoch} AND i.module_id=#{moduleId}
            AND i.migration_epoch=#{migrationEpoch}
            AND CAST(i.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(i.tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(i.data_domain_id AS BINARY)=CAST(#{dataDomainId} AS BINARY)
            AND OCTET_LENGTH(i.data_domain_id)=OCTET_LENGTH(#{dataDomainId})
            AND CAST(i.execution_pool_id AS BINARY)=CAST(#{executionPoolId} AS BINARY)
            AND OCTET_LENGTH(i.execution_pool_id)=OCTET_LENGTH(#{executionPoolId})
            AND CAST(i.client_id AS BINARY)=CAST(#{commandCallerClientId} AS BINARY)
            AND OCTET_LENGTH(i.client_id)=OCTET_LENGTH(#{commandCallerClientId})
            AND CAST(i.producer_deployment_id AS BINARY)=CAST(#{producerDeploymentId} AS BINARY)
            AND OCTET_LENGTH(i.producer_deployment_id)=OCTET_LENGTH(#{producerDeploymentId})
            AND CAST(i.claimant_deployment_id AS BINARY)=CAST(#{claimantDeploymentId} AS BINARY)
            AND OCTET_LENGTH(i.claimant_deployment_id)=OCTET_LENGTH(#{claimantDeploymentId})
            AND CAST(i.module_id AS BINARY)=CAST(#{moduleId} AS BINARY)
            AND OCTET_LENGTH(i.module_id)=OCTET_LENGTH(#{moduleId})
            """ + " ";
    String DISPATCH_RECEIVER_SNAPSHOT = " " + """
            i.dispatch_runtime_credential_client_id=#{runtimeCredentialClientId}
            AND i.dispatch_receiver_registration_revision=#{receiverRegistrationRevision}
            AND CAST(i.dispatch_runtime_credential_client_id AS BINARY)=CAST(#{runtimeCredentialClientId} AS BINARY)
            AND OCTET_LENGTH(i.dispatch_runtime_credential_client_id)=OCTET_LENGTH(#{runtimeCredentialClientId})
            """ + " ";
    String BINDING_EXACT = " " + """
            b.evidence_provenance='R2B1_ENFORCED'
            AND b.tenant_id=#{tenantId} AND b.data_domain_id=#{dataDomainId}
            AND b.execution_pool_id=#{executionPoolId} AND b.client_id=#{runtimeCredentialClientId}
            AND b.owner_jiacn=#{ownerJiacn} AND b.agent_id=#{agentId}
            AND b.claimant_deployment_id=#{claimantDeploymentId}
            AND b.claimant_worker_generation=#{claimantWorkerGeneration}
            AND b.receiver_registration_revision=#{receiverRegistrationRevision}
            AND b.admission_epoch=#{admissionEpoch} AND b.module_id=#{moduleId}
            AND b.migration_epoch=#{migrationEpoch}
            AND b.runtime_binding_id=#{runtimeBindingId}
            AND CAST(b.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(b.tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(b.data_domain_id AS BINARY)=CAST(#{dataDomainId} AS BINARY)
            AND OCTET_LENGTH(b.data_domain_id)=OCTET_LENGTH(#{dataDomainId})
            AND CAST(b.execution_pool_id AS BINARY)=CAST(#{executionPoolId} AS BINARY)
            AND OCTET_LENGTH(b.execution_pool_id)=OCTET_LENGTH(#{executionPoolId})
            AND CAST(b.client_id AS BINARY)=CAST(#{runtimeCredentialClientId} AS BINARY)
            AND OCTET_LENGTH(b.client_id)=OCTET_LENGTH(#{runtimeCredentialClientId})
            AND CAST(b.owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
            AND OCTET_LENGTH(b.owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            AND CAST(b.agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
            AND OCTET_LENGTH(b.agent_id)=OCTET_LENGTH(#{agentId})
            AND CAST(b.claimant_deployment_id AS BINARY)=CAST(#{claimantDeploymentId} AS BINARY)
            AND OCTET_LENGTH(b.claimant_deployment_id)=OCTET_LENGTH(#{claimantDeploymentId})
            AND CAST(b.module_id AS BINARY)=CAST(#{moduleId} AS BINARY)
            AND OCTET_LENGTH(b.module_id)=OCTET_LENGTH(#{moduleId})
            AND CAST(b.runtime_binding_id AS BINARY)=CAST(#{runtimeBindingId} AS BINARY)
            AND OCTET_LENGTH(b.runtime_binding_id)=OCTET_LENGTH(#{runtimeBindingId})
            """ + " ";
    String DELIVERY_MATCHES = " " + """
            d.id=i.delivery_id AND d.protocol_version=2 AND d.protocol_version=i.protocol_version
            AND d.tenant_id=#{tenantId} AND d.client_id=#{commandCallerClientId}
            AND d.data_domain_id=#{dataDomainId} AND d.execution_pool_id=#{executionPoolId}
            AND d.producer_deployment_id=#{producerDeploymentId}
            AND d.claimed_deployment_id=#{claimantDeploymentId}
            AND d.claimed_worker_generation=#{claimantWorkerGeneration}
            AND d.admission_epoch=#{admissionEpoch} AND d.module_id=#{moduleId}
            AND d.migration_epoch=#{migrationEpoch} AND d.owner_jiacn=#{ownerJiacn}
            AND d.target_agent_id=#{agentId} AND d.command_id=i.command_id
            AND CAST(d.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(d.data_domain_id AS BINARY)=CAST(#{dataDomainId} AS BINARY)
            AND OCTET_LENGTH(d.data_domain_id)=OCTET_LENGTH(#{dataDomainId})
            AND CAST(d.execution_pool_id AS BINARY)=CAST(#{executionPoolId} AS BINARY)
            AND OCTET_LENGTH(d.execution_pool_id)=OCTET_LENGTH(#{executionPoolId})
            AND CAST(d.client_id AS BINARY)=CAST(#{commandCallerClientId} AS BINARY)
            AND OCTET_LENGTH(d.client_id)=OCTET_LENGTH(#{commandCallerClientId})
            AND CAST(d.producer_deployment_id AS BINARY)=CAST(#{producerDeploymentId} AS BINARY)
            AND OCTET_LENGTH(d.producer_deployment_id)=OCTET_LENGTH(#{producerDeploymentId})
            AND CAST(d.claimed_deployment_id AS BINARY)=CAST(#{claimantDeploymentId} AS BINARY)
            AND OCTET_LENGTH(d.claimed_deployment_id)=OCTET_LENGTH(#{claimantDeploymentId})
            AND CAST(d.module_id AS BINARY)=CAST(#{moduleId} AS BINARY)
            AND OCTET_LENGTH(d.module_id)=OCTET_LENGTH(#{moduleId})
            AND CAST(d.owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
            AND OCTET_LENGTH(d.owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            AND CAST(d.target_agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
            AND OCTET_LENGTH(d.target_agent_id)=OCTET_LENGTH(#{agentId})
            AND CAST(d.command_id AS BINARY)=CAST(i.command_id AS BINARY)
            AND OCTET_LENGTH(d.command_id)=OCTET_LENGTH(i.command_id)
            """ + " ";

    String DELIVERY_SCOPE = " " + """
            d.protocol_version=2 AND d.tenant_id=#{tenantId}
            AND d.client_id=#{commandCallerClientId} AND d.data_domain_id=#{dataDomainId}
            AND d.execution_pool_id=#{executionPoolId}
            AND d.producer_deployment_id=#{producerDeploymentId}
            AND d.claimed_deployment_id=#{claimantDeploymentId}
            AND d.claimed_worker_generation=#{claimantWorkerGeneration}
            AND d.admission_epoch=#{admissionEpoch} AND d.module_id=#{moduleId}
            AND d.migration_epoch=#{migrationEpoch} AND d.owner_jiacn=#{ownerJiacn}
            AND d.target_agent_id=#{agentId}
            AND CAST(d.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(d.client_id AS BINARY)=CAST(#{commandCallerClientId} AS BINARY)
            AND OCTET_LENGTH(d.client_id)=OCTET_LENGTH(#{commandCallerClientId})
            AND CAST(d.data_domain_id AS BINARY)=CAST(#{dataDomainId} AS BINARY)
            AND OCTET_LENGTH(d.data_domain_id)=OCTET_LENGTH(#{dataDomainId})
            AND CAST(d.execution_pool_id AS BINARY)=CAST(#{executionPoolId} AS BINARY)
            AND OCTET_LENGTH(d.execution_pool_id)=OCTET_LENGTH(#{executionPoolId})
            AND CAST(d.producer_deployment_id AS BINARY)=CAST(#{producerDeploymentId} AS BINARY)
            AND OCTET_LENGTH(d.producer_deployment_id)=OCTET_LENGTH(#{producerDeploymentId})
            AND CAST(d.claimed_deployment_id AS BINARY)=CAST(#{claimantDeploymentId} AS BINARY)
            AND OCTET_LENGTH(d.claimed_deployment_id)=OCTET_LENGTH(#{claimantDeploymentId})
            AND CAST(d.module_id AS BINARY)=CAST(#{moduleId} AS BINARY)
            AND OCTET_LENGTH(d.module_id)=OCTET_LENGTH(#{moduleId})
            AND CAST(d.owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
            AND OCTET_LENGTH(d.owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            AND CAST(d.target_agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
            AND OCTET_LENGTH(d.target_agent_id)=OCTET_LENGTH(#{agentId})
            """ + " ";

    @Update("""
            UPDATE agent_consumer_inbox i SET
              runtime_dispatch_id=#{runtimeDispatchId},runtime_binding_id=#{runtimeBindingId},
              runtime_binding_generation=#{bindingGeneration},dispatch_runtime_instance_id=#{runtimeInstanceId},
              dispatch_runtime_credential_client_id=#{runtimeCredentialClientId},
              dispatch_receiver_registration_revision=#{receiverRegistrationRevision},
              dispatch_prepared_at=#{preparedAt},version=version+1,update_time=#{preparedAt}
            WHERE """ + INBOX_PRODUCER_SCOPE + """
              AND i.id=#{inboxId} AND i.protocol_version=2 AND i.version=#{expectedVersion}
              AND i.runtime_dispatch_id IS NULL AND i.runtime_binding_id IS NULL
              AND i.runtime_binding_generation IS NULL AND i.dispatch_runtime_instance_id IS NULL
              AND i.dispatch_runtime_credential_client_id IS NULL
              AND i.dispatch_receiver_registration_revision IS NULL AND i.dispatch_prepared_at IS NULL
              AND i.execution_holder_binding_id IS NULL AND i.execution_holder_binding_generation IS NULL
              AND i.execution_holder_runtime_instance_id IS NULL AND i.execution_current_lease_fact_id IS NULL
              AND EXISTS (SELECT 1 FROM agent_runtime_v2_binding b WHERE """ + BINDING_EXACT + """
                AND b.binding_generation=#{bindingGeneration} AND b.runtime_instance_id=#{runtimeInstanceId}
                AND CAST(b.runtime_instance_id AS BINARY)=CAST(#{runtimeInstanceId} AS BINARY)
                AND OCTET_LENGTH(b.runtime_instance_id)=OCTET_LENGTH(#{runtimeInstanceId}) AND b.status='ACTIVE')
              AND EXISTS (SELECT 1 FROM agent_command_delivery d WHERE """ + DELIVERY_MATCHES + ")")
    int prepare(@Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("producerDeploymentId") String producerDeploymentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("inboxId") long inboxId,
            @Param("expectedVersion") long expectedVersion, @Param("runtimeDispatchId") String runtimeDispatchId,
            @Param("runtimeBindingId") String runtimeBindingId, @Param("bindingGeneration") long bindingGeneration,
            @Param("runtimeInstanceId") String runtimeInstanceId, @Param("preparedAt") long preparedAt);

    @Select("SELECT i.* FROM agent_consumer_inbox i WHERE " + INBOX_PRODUCER_SCOPE
            + " AND " + DISPATCH_RECEIVER_SNAPSHOT
            + " AND i.runtime_dispatch_id=#{runtimeDispatchId} AND i.runtime_binding_id=#{runtimeBindingId}"
            + " AND CAST(i.runtime_dispatch_id AS BINARY)=CAST(#{runtimeDispatchId} AS BINARY)"
            + " AND OCTET_LENGTH(i.runtime_dispatch_id)=OCTET_LENGTH(#{runtimeDispatchId})"
            + " AND EXISTS (SELECT 1 FROM agent_runtime_v2_binding b WHERE " + BINDING_EXACT
            + " AND b.runtime_instance_id=i.dispatch_runtime_instance_id"
            + " AND CAST(b.runtime_instance_id AS BINARY)=CAST(i.dispatch_runtime_instance_id AS BINARY)"
            + " AND OCTET_LENGTH(b.runtime_instance_id)=OCTET_LENGTH(i.dispatch_runtime_instance_id))"
            + " AND EXISTS (SELECT 1 FROM agent_command_delivery d WHERE " + DELIVERY_MATCHES + ") LIMIT 1 FOR UPDATE")
    AgentConsumerInboxEntity lock(@Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("producerDeploymentId") String producerDeploymentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("runtimeBindingId") String runtimeBindingId,
            @Param("runtimeDispatchId") String runtimeDispatchId);

    @Select("SELECT d.* FROM agent_command_delivery d WHERE " + DELIVERY_SCOPE
            + " AND EXISTS (SELECT 1 FROM agent_consumer_inbox i WHERE " + INBOX_PRODUCER_SCOPE
            + " AND " + DISPATCH_RECEIVER_SNAPSHOT
            + " AND i.delivery_id=d.id AND i.command_id=d.command_id"
            + " AND CAST(i.command_id AS BINARY)=CAST(d.command_id AS BINARY)"
            + " AND OCTET_LENGTH(i.command_id)=OCTET_LENGTH(d.command_id)"
            + " AND i.runtime_dispatch_id=#{runtimeDispatchId} AND i.runtime_binding_id=#{runtimeBindingId}"
            + " AND CAST(i.runtime_dispatch_id AS BINARY)=CAST(#{runtimeDispatchId} AS BINARY)"
            + " AND OCTET_LENGTH(i.runtime_dispatch_id)=OCTET_LENGTH(#{runtimeDispatchId})"
            + " AND CAST(i.runtime_binding_id AS BINARY)=CAST(#{runtimeBindingId} AS BINARY)"
            + " AND OCTET_LENGTH(i.runtime_binding_id)=OCTET_LENGTH(#{runtimeBindingId}))"
            + " LIMIT 1 FOR UPDATE")
    AgentCommandDeliveryEntity lockDelivery(@Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId, @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("producerDeploymentId") String producerDeploymentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("runtimeBindingId") String runtimeBindingId,
            @Param("runtimeDispatchId") String runtimeDispatchId);

    @Update("UPDATE agent_command_delivery d SET status='STARTED',next_retry_at=NULL,"
            + "lease_owner=NULL,lease_until=NULL,last_error=NULL,version=version+1,update_time=#{now}"
            + " WHERE " + DELIVERY_SCOPE + " AND d.id=#{deliveryId} AND d.version=#{expectedVersion}"
            + " AND d.status='RECEIVED' AND d.command_id=#{commandId} AND d.active_message_id=#{sourceMessageId}"
            + " AND CAST(d.command_id AS BINARY)=CAST(#{commandId} AS BINARY)"
            + " AND OCTET_LENGTH(d.command_id)=OCTET_LENGTH(#{commandId})"
            + " AND CAST(d.active_message_id AS BINARY)=CAST(#{sourceMessageId} AS BINARY)"
            + " AND OCTET_LENGTH(d.active_message_id)=OCTET_LENGTH(#{sourceMessageId})")
    int advanceDeliveryStarted(@Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId, @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("producerDeploymentId") String producerDeploymentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("deliveryId") long deliveryId,
            @Param("commandId") String commandId, @Param("sourceMessageId") String sourceMessageId,
            @Param("expectedVersion") long expectedVersion, @Param("now") long now);

    @Update("""
            UPDATE agent_consumer_inbox i SET
              ack_delivery_version=#{ackDeliveryVersion},ack_message_id=#{ackMessageId},
              ack_status='STARTED',ack_at=#{ackAt},ack_agent_id=#{agentId},
              ack_runtime_installation_id=#{runtimeInstallationId},
              ack_runtime_instance_id=#{holderRuntimeInstanceId},
              ack_runtime_binding_id=#{holderBindingId},
              ack_runtime_binding_generation=#{holderBindingGeneration},ack_committed_at=#{now},
              execution_started_at=#{startedAt},execution_lease_until=#{leaseUntil},
              execution_lease_revision=#{leaseRevision},execution_holder_binding_id=#{holderBindingId},
              execution_holder_binding_generation=#{holderBindingGeneration},
              execution_holder_runtime_instance_id=#{holderRuntimeInstanceId},
              execution_current_lease_fact_id=#{currentLeaseFactId},version=version+1,update_time=#{now}
            WHERE """ + INBOX_PRODUCER_SCOPE + " AND " + DISPATCH_RECEIVER_SNAPSHOT + """
              AND i.id=#{inboxId} AND i.delivery_id=#{deliveryId} AND i.version=#{expectedVersion}
              AND i.runtime_dispatch_id=#{runtimeDispatchId} AND i.runtime_binding_id=#{runtimeBindingId}
              AND i.runtime_binding_generation=#{dispatchBindingGeneration}
              AND i.execution_started_at IS NULL AND i.execution_lease_until IS NULL
              AND i.execution_lease_revision IS NULL AND i.execution_holder_binding_id IS NULL
              AND i.execution_holder_binding_generation IS NULL
              AND i.execution_holder_runtime_instance_id IS NULL
              AND i.execution_current_lease_fact_id IS NULL
              AND i.ack_delivery_version=#{expectedAckDeliveryVersion}
              AND i.ack_status='RECEIVED' AND i.ack_message_id=#{expectedAckMessageId}
              AND i.ack_runtime_binding_id=#{runtimeBindingId}
              AND i.ack_runtime_binding_generation=#{dispatchBindingGeneration}
              AND i.ack_runtime_instance_id=#{holderRuntimeInstanceId}
              AND CAST(i.runtime_dispatch_id AS BINARY)=CAST(#{runtimeDispatchId} AS BINARY)
              AND OCTET_LENGTH(i.runtime_dispatch_id)=OCTET_LENGTH(#{runtimeDispatchId})
              AND CAST(i.runtime_binding_id AS BINARY)=CAST(#{runtimeBindingId} AS BINARY)
              AND OCTET_LENGTH(i.runtime_binding_id)=OCTET_LENGTH(#{runtimeBindingId})
              AND CAST(i.ack_message_id AS BINARY)=CAST(#{expectedAckMessageId} AS BINARY)
              AND OCTET_LENGTH(i.ack_message_id)=OCTET_LENGTH(#{expectedAckMessageId})
              AND CAST(i.ack_status AS BINARY)=CAST('RECEIVED' AS BINARY)
              AND OCTET_LENGTH(i.ack_status)=OCTET_LENGTH('RECEIVED')
              AND CAST(i.ack_runtime_binding_id AS BINARY)=CAST(#{runtimeBindingId} AS BINARY)
              AND OCTET_LENGTH(i.ack_runtime_binding_id)=OCTET_LENGTH(#{runtimeBindingId})
              AND CAST(i.ack_runtime_instance_id AS BINARY)=CAST(#{holderRuntimeInstanceId} AS BINARY)
              AND OCTET_LENGTH(i.ack_runtime_instance_id)=OCTET_LENGTH(#{holderRuntimeInstanceId})
            """)
    int commitStartedProjection(@Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId, @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("producerDeploymentId") String producerDeploymentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("inboxId") long inboxId,
            @Param("deliveryId") long deliveryId, @Param("expectedVersion") long expectedVersion,
            @Param("runtimeDispatchId") String runtimeDispatchId,
            @Param("runtimeBindingId") String runtimeBindingId,
            @Param("dispatchBindingGeneration") long dispatchBindingGeneration,
            @Param("holderBindingId") String holderBindingId,
            @Param("holderBindingGeneration") long holderBindingGeneration,
            @Param("holderRuntimeInstanceId") String holderRuntimeInstanceId,
            @Param("runtimeInstallationId") String runtimeInstallationId,
            @Param("expectedAckDeliveryVersion") long expectedAckDeliveryVersion,
            @Param("expectedAckMessageId") String expectedAckMessageId,
            @Param("ackDeliveryVersion") long ackDeliveryVersion,
            @Param("ackMessageId") String ackMessageId, @Param("ackAt") long ackAt,
            @Param("currentLeaseFactId") String currentLeaseFactId,
            @Param("leaseRevision") long leaseRevision, @Param("startedAt") long startedAt,
            @Param("leaseUntil") long leaseUntil, @Param("now") long now);

    @Update("""
            UPDATE agent_consumer_inbox i SET
              execution_started_at=#{startedAt},execution_lease_until=#{leaseUntil},
              execution_lease_revision=#{leaseRevision},execution_holder_binding_id=#{holderBindingId},
              execution_holder_binding_generation=#{holderBindingGeneration},
              execution_holder_runtime_instance_id=#{holderRuntimeInstanceId},
              execution_current_lease_fact_id=#{currentLeaseFactId},version=version+1,update_time=#{now}
            WHERE """ + INBOX_PRODUCER_SCOPE + " AND " + DISPATCH_RECEIVER_SNAPSHOT + """
              AND i.id=#{inboxId} AND i.version=#{expectedVersion}
              AND i.runtime_dispatch_id=#{runtimeDispatchId} AND i.runtime_binding_id=#{runtimeBindingId}
              AND i.runtime_binding_generation=#{dispatchBindingGeneration}
              AND ((i.execution_lease_revision IS NULL AND #{expectedLeaseRevision} IS NULL)
                   OR i.execution_lease_revision=#{expectedLeaseRevision})
              AND EXISTS (SELECT 1 FROM agent_runtime_v2_binding b WHERE """ + BINDING_EXACT + """
                AND b.binding_generation=#{holderBindingGeneration}
                AND b.runtime_instance_id=#{holderRuntimeInstanceId} AND b.status='ACTIVE')
              AND EXISTS (SELECT 1 FROM agent_command_delivery d WHERE """ + DELIVERY_MATCHES + ")")
    int projectLease(@Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("producerDeploymentId") String producerDeploymentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("inboxId") long inboxId,
            @Param("expectedVersion") long expectedVersion, @Param("runtimeDispatchId") String runtimeDispatchId,
            @Param("runtimeBindingId") String runtimeBindingId,
            @Param("dispatchBindingGeneration") long dispatchBindingGeneration,
            @Param("holderBindingId") String holderBindingId,
            @Param("holderBindingGeneration") long holderBindingGeneration,
            @Param("holderRuntimeInstanceId") String holderRuntimeInstanceId,
            @Param("currentLeaseFactId") String currentLeaseFactId,
            @Param("expectedLeaseRevision") Long expectedLeaseRevision,
            @Param("leaseRevision") long leaseRevision, @Param("startedAt") long startedAt,
            @Param("leaseUntil") long leaseUntil, @Param("now") long now);
}
