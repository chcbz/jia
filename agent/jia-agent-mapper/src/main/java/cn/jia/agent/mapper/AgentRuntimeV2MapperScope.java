package cn.jia.agent.mapper;

/** Exact binary scope predicates for runtime-v2 persistence mappers. */
interface AgentRuntimeV2MapperScope {
    /** Stable receiver identity for audit/history reads. Mutable generations are verified after load, never predicates. */
    String HISTORICAL_RECEIVER_IDENTITY = " " + """
            tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
            AND execution_pool_id=#{executionPoolId} AND client_id=#{runtimeCredentialClientId}
            AND owner_jiacn=#{ownerJiacn} AND agent_id=#{agentId}
            AND runtime_binding_id=#{runtimeBindingId}
            AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(data_domain_id AS BINARY)=CAST(#{dataDomainId} AS BINARY)
            AND OCTET_LENGTH(data_domain_id)=OCTET_LENGTH(#{dataDomainId})
            AND CAST(execution_pool_id AS BINARY)=CAST(#{executionPoolId} AS BINARY)
            AND OCTET_LENGTH(execution_pool_id)=OCTET_LENGTH(#{executionPoolId})
            AND CAST(client_id AS BINARY)=CAST(#{runtimeCredentialClientId} AS BINARY)
            AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{runtimeCredentialClientId})
            AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
            AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            AND CAST(agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
            AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId})
            AND CAST(runtime_binding_id AS BINARY)=CAST(#{runtimeBindingId} AS BINARY)
            AND OCTET_LENGTH(runtime_binding_id)=OCTET_LENGTH(#{runtimeBindingId})
            """ + " ";

    /** Stable producer/receiver identity for immutable execution history. */
    String HISTORICAL_EXECUTION_IDENTITY = " " + """
            tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
            AND execution_pool_id=#{executionPoolId} AND client_id=#{commandCallerClientId}
            AND runtime_credential_client_id=#{runtimeCredentialClientId}
            AND owner_jiacn=#{ownerJiacn} AND agent_id=#{agentId}
            AND runtime_binding_id=#{runtimeBindingId}
            AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(data_domain_id AS BINARY)=CAST(#{dataDomainId} AS BINARY)
            AND OCTET_LENGTH(data_domain_id)=OCTET_LENGTH(#{dataDomainId})
            AND CAST(execution_pool_id AS BINARY)=CAST(#{executionPoolId} AS BINARY)
            AND OCTET_LENGTH(execution_pool_id)=OCTET_LENGTH(#{executionPoolId})
            AND CAST(client_id AS BINARY)=CAST(#{commandCallerClientId} AS BINARY)
            AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{commandCallerClientId})
            AND CAST(runtime_credential_client_id AS BINARY)=CAST(#{runtimeCredentialClientId} AS BINARY)
            AND OCTET_LENGTH(runtime_credential_client_id)=OCTET_LENGTH(#{runtimeCredentialClientId})
            AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
            AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            AND CAST(agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
            AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId})
            AND CAST(runtime_binding_id AS BINARY)=CAST(#{runtimeBindingId} AS BINARY)
            AND OCTET_LENGTH(runtime_binding_id)=OCTET_LENGTH(#{runtimeBindingId})
            """ + " ";

    String SPLIT_EXACT = " " + """
            tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
            AND execution_pool_id=#{executionPoolId} AND client_id=#{commandCallerClientId}
            AND runtime_credential_client_id=#{runtimeCredentialClientId}
            AND owner_jiacn=#{ownerJiacn} AND agent_id=#{agentId}
            AND producer_deployment_id=#{producerDeploymentId}
            AND claimant_deployment_id=#{claimantDeploymentId}
            AND claimant_worker_generation=#{claimantWorkerGeneration}
            AND receiver_registration_revision=#{receiverRegistrationRevision}
            AND admission_epoch=#{admissionEpoch} AND module_id=#{moduleId}
            AND migration_epoch=#{migrationEpoch}
            AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(data_domain_id AS BINARY)=CAST(#{dataDomainId} AS BINARY)
            AND OCTET_LENGTH(data_domain_id)=OCTET_LENGTH(#{dataDomainId})
            AND CAST(execution_pool_id AS BINARY)=CAST(#{executionPoolId} AS BINARY)
            AND OCTET_LENGTH(execution_pool_id)=OCTET_LENGTH(#{executionPoolId})
            AND CAST(client_id AS BINARY)=CAST(#{commandCallerClientId} AS BINARY)
            AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{commandCallerClientId})
            AND CAST(runtime_credential_client_id AS BINARY)=CAST(#{runtimeCredentialClientId} AS BINARY)
            AND OCTET_LENGTH(runtime_credential_client_id)=OCTET_LENGTH(#{runtimeCredentialClientId})
            AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
            AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            AND CAST(agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
            AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId})
            AND CAST(producer_deployment_id AS BINARY)=CAST(#{producerDeploymentId} AS BINARY)
            AND OCTET_LENGTH(producer_deployment_id)=OCTET_LENGTH(#{producerDeploymentId})
            AND CAST(claimant_deployment_id AS BINARY)=CAST(#{claimantDeploymentId} AS BINARY)
            AND OCTET_LENGTH(claimant_deployment_id)=OCTET_LENGTH(#{claimantDeploymentId})
            AND CAST(module_id AS BINARY)=CAST(#{moduleId} AS BINARY)
            AND OCTET_LENGTH(module_id)=OCTET_LENGTH(#{moduleId})
            """ + " ";

    /** Deprecated combined R1 predicate for receipt and execution-lease facts only. */
    String EXACT = " " + """
            tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
            AND execution_pool_id=#{executionPoolId} AND client_id=#{clientId}
            AND owner_jiacn=#{ownerJiacn} AND agent_id=#{agentId}
            AND producer_deployment_id=#{producerDeploymentId}
            AND claimant_deployment_id=#{claimantDeploymentId}
            AND claimant_worker_generation=#{claimantWorkerGeneration}
            AND admission_epoch=#{admissionEpoch} AND module_id=#{moduleId}
            AND migration_epoch=#{migrationEpoch}
            AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(data_domain_id AS BINARY)=CAST(#{dataDomainId} AS BINARY)
            AND OCTET_LENGTH(data_domain_id)=OCTET_LENGTH(#{dataDomainId})
            AND CAST(execution_pool_id AS BINARY)=CAST(#{executionPoolId} AS BINARY)
            AND OCTET_LENGTH(execution_pool_id)=OCTET_LENGTH(#{executionPoolId})
            AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
            AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
            AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
            AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            AND CAST(agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
            AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId})
            AND CAST(producer_deployment_id AS BINARY)=CAST(#{producerDeploymentId} AS BINARY)
            AND OCTET_LENGTH(producer_deployment_id)=OCTET_LENGTH(#{producerDeploymentId})
            AND CAST(claimant_deployment_id AS BINARY)=CAST(#{claimantDeploymentId} AS BINARY)
            AND OCTET_LENGTH(claimant_deployment_id)=OCTET_LENGTH(#{claimantDeploymentId})
            AND CAST(module_id AS BINARY)=CAST(#{moduleId} AS BINARY)
            AND OCTET_LENGTH(module_id)=OCTET_LENGTH(#{moduleId})
            """ + " ";

    String RECEIVER_EXACT = " " + """
            tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
            AND execution_pool_id=#{executionPoolId} AND client_id=#{clientId}
            AND owner_jiacn=#{ownerJiacn} AND agent_id=#{agentId}
            AND claimant_deployment_id=#{claimantDeploymentId}
            AND claimant_worker_generation=#{claimantWorkerGeneration}
            AND receiver_registration_revision=#{receiverRegistrationRevision}
            AND admission_epoch=#{admissionEpoch} AND module_id=#{moduleId}
            AND migration_epoch=#{migrationEpoch}
            AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(data_domain_id AS BINARY)=CAST(#{dataDomainId} AS BINARY)
            AND OCTET_LENGTH(data_domain_id)=OCTET_LENGTH(#{dataDomainId})
            AND CAST(execution_pool_id AS BINARY)=CAST(#{executionPoolId} AS BINARY)
            AND OCTET_LENGTH(execution_pool_id)=OCTET_LENGTH(#{executionPoolId})
            AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
            AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
            AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
            AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            AND CAST(agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
            AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId})
            AND CAST(claimant_deployment_id AS BINARY)=CAST(#{claimantDeploymentId} AS BINARY)
            AND OCTET_LENGTH(claimant_deployment_id)=OCTET_LENGTH(#{claimantDeploymentId})
            AND CAST(module_id AS BINARY)=CAST(#{moduleId} AS BINARY)
            AND OCTET_LENGTH(module_id)=OCTET_LENGTH(#{moduleId})
            """ + " ";

    /** R1 dispatch bridge: only receiver fields, never caller client or producer deployment. */
    String BINDING_EXACT = " " + """
            b.tenant_id=#{tenantId} AND b.data_domain_id=#{dataDomainId}
            AND b.execution_pool_id=#{executionPoolId}
            AND b.owner_jiacn=#{ownerJiacn} AND b.agent_id=#{agentId}
            AND b.claimant_deployment_id=#{claimantDeploymentId}
            AND b.claimant_worker_generation=#{claimantWorkerGeneration}
            AND b.admission_epoch=#{admissionEpoch} AND b.module_id=#{moduleId}
            AND b.migration_epoch=#{migrationEpoch}
            AND CAST(b.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(b.tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(b.data_domain_id AS BINARY)=CAST(#{dataDomainId} AS BINARY)
            AND OCTET_LENGTH(b.data_domain_id)=OCTET_LENGTH(#{dataDomainId})
            AND CAST(b.execution_pool_id AS BINARY)=CAST(#{executionPoolId} AS BINARY)
            AND OCTET_LENGTH(b.execution_pool_id)=OCTET_LENGTH(#{executionPoolId})
            AND CAST(b.owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
            AND OCTET_LENGTH(b.owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            AND CAST(b.agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
            AND OCTET_LENGTH(b.agent_id)=OCTET_LENGTH(#{agentId})
            AND CAST(b.claimant_deployment_id AS BINARY)=CAST(#{claimantDeploymentId} AS BINARY)
            AND OCTET_LENGTH(b.claimant_deployment_id)=OCTET_LENGTH(#{claimantDeploymentId})
            AND CAST(b.module_id AS BINARY)=CAST(#{moduleId} AS BINARY)
            AND OCTET_LENGTH(b.module_id)=OCTET_LENGTH(#{moduleId})
            """ + " ";
}
