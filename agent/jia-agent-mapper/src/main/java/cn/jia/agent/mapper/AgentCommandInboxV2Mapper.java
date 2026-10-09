package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.entity.AgentInboxMessage;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Protocol-v2 lane. Every read and CAS carries the complete immutable and claimant scope. */
public interface AgentCommandInboxV2Mapper {
    String DELIVERY_COLUMNS = "id,protocol_version,data_domain_id,execution_pool_id,admission_epoch," +
            "producer_deployment_id,module_id,migration_epoch,claimed_deployment_id,claimed_worker_generation," +
            "command_id,task_id,work_item_id,target_agent_id,command_type,command_payload,command_payload_hash," +
            "status,attempt_count,next_retry_at,lease_owner,lease_until,active_message_id,active_attempt,expires_at," +
            "last_error,version,replay_parent_message_id,replay_requester_id,replay_approver_id,replay_reason," +
            "tenant_id,client_id,create_time,update_time";
    String OUTBOX_COLUMNS = "id,protocol_version,data_domain_id,execution_pool_id,admission_epoch," +
            "producer_deployment_id,module_id,migration_epoch,claimed_deployment_id,claimed_worker_generation," +
            "event_id,message_id,command_id,delivery_id,aggregate_type,aggregate_id,destination,routing_key," +
            "wire_payload,wire_payload_hash,status,attempt_count,next_retry_at,lease_owner,lease_until,active_attempt," +
            "expires_at,publisher_confirm_status,confirmed_at,confirm_error,mandatory_return_status,returned_at," +
            "return_reply_code,return_reply_text,published_at,last_error,version,replay_parent_message_id," +
            "replay_requester_id,replay_approver_id,replay_reason,tenant_id,client_id,create_time,update_time";
    String INBOX_COLUMNS = "id,protocol_version,data_domain_id,execution_pool_id,admission_epoch," +
            "producer_deployment_id,module_id,migration_epoch,claimant_deployment_id,claimant_worker_generation," +
            "consumer_name,message_id,event_id,command_id,delivery_id,wire_payload,wire_payload_hash,status," +
            "result_status,attempt_count,next_retry_at,lease_owner,lease_until,active_attempt,expires_at,processed_at," +
            "last_error,version,claim_delivery_version,claim_inbox_version,claim_started_at," +
            "ack_delivery_version,ack_message_id," +
            "ack_status,ack_at,ack_agent_id,ack_runtime_installation_id,ack_runtime_instance_id," +
            "replay_parent_message_id,replay_requester_id,replay_approver_id,replay_reason," +
            "tenant_id,client_id,create_time,update_time";

    String PRODUCER_SCOPE = " protocol_version=2 AND tenant_id=#{m.tenantId}" +
            " AND data_domain_id=#{m.dataDomainId} AND execution_pool_id=#{m.executionPoolId}" +
            " AND admission_epoch=#{m.admissionEpoch} AND producer_deployment_id=#{m.producerDeploymentId}" +
            " AND module_id=#{m.moduleId} AND migration_epoch=#{m.migrationEpoch} AND client_id=#{m.clientId}" +
            " AND CAST(tenant_id AS BINARY)=CAST(#{m.tenantId} AS BINARY)" +
            " AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{m.tenantId})" +
            " AND CAST(data_domain_id AS BINARY)=CAST(#{m.dataDomainId} AS BINARY)" +
            " AND OCTET_LENGTH(data_domain_id)=OCTET_LENGTH(#{m.dataDomainId})" +
            " AND CAST(execution_pool_id AS BINARY)=CAST(#{m.executionPoolId} AS BINARY)" +
            " AND OCTET_LENGTH(execution_pool_id)=OCTET_LENGTH(#{m.executionPoolId})" +
            " AND CAST(producer_deployment_id AS BINARY)=CAST(#{m.producerDeploymentId} AS BINARY)" +
            " AND OCTET_LENGTH(producer_deployment_id)=OCTET_LENGTH(#{m.producerDeploymentId})" +
            " AND CAST(module_id AS BINARY)=CAST(#{m.moduleId} AS BINARY)" +
            " AND OCTET_LENGTH(module_id)=OCTET_LENGTH(#{m.moduleId})" +
            " AND CAST(client_id AS BINARY)=CAST(#{m.clientId} AS BINARY)" +
            " AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{m.clientId})";
    /** Relay settlement clears its claimant; consumer authority is persisted only in Inbox. */
    String SOURCE_SCOPE = PRODUCER_SCOPE +
            " AND claimed_deployment_id IS NULL" +
            " AND claimed_worker_generation IS NULL";
    String INBOX_SCOPE = PRODUCER_SCOPE +
            " AND claimant_deployment_id=#{m.claimantDeploymentId}" +
            " AND claimant_worker_generation=#{m.claimantWorkerGeneration}" +
            " AND CAST(claimant_deployment_id AS BINARY)=CAST(#{m.claimantDeploymentId} AS BINARY)" +
            " AND OCTET_LENGTH(claimant_deployment_id)=OCTET_LENGTH(#{m.claimantDeploymentId})";

    /** Stable message identity deliberately excludes the replaceable worker claimant. */
    String INBOX_IDENTITY_SCOPE = PRODUCER_SCOPE;

    String TOKEN_SOURCE_SCOPE = " protocol_version=2 AND tenant_id=#{t.tenantId}" +
            " AND data_domain_id=#{t.dataDomainId} AND execution_pool_id=#{t.executionPoolId}" +
            " AND admission_epoch=#{t.admissionEpoch} AND producer_deployment_id=#{t.producerDeploymentId}" +
            " AND module_id=#{t.moduleId} AND migration_epoch=#{t.migrationEpoch} AND client_id=#{t.clientId}" +
            " AND task_id=#{t.taskId} AND target_agent_id=#{t.targetAgentId}" +
            " AND command_type=#{t.commandType}" +
            " AND claimed_deployment_id IS NULL AND claimed_worker_generation IS NULL" +
            " AND CAST(tenant_id AS BINARY)=CAST(#{t.tenantId} AS BINARY)" +
            " AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{t.tenantId})" +
            " AND CAST(data_domain_id AS BINARY)=CAST(#{t.dataDomainId} AS BINARY)" +
            " AND OCTET_LENGTH(data_domain_id)=OCTET_LENGTH(#{t.dataDomainId})" +
            " AND CAST(execution_pool_id AS BINARY)=CAST(#{t.executionPoolId} AS BINARY)" +
            " AND OCTET_LENGTH(execution_pool_id)=OCTET_LENGTH(#{t.executionPoolId})" +
            " AND CAST(producer_deployment_id AS BINARY)=CAST(#{t.producerDeploymentId} AS BINARY)" +
            " AND OCTET_LENGTH(producer_deployment_id)=OCTET_LENGTH(#{t.producerDeploymentId})" +
            " AND CAST(module_id AS BINARY)=CAST(#{t.moduleId} AS BINARY)" +
            " AND OCTET_LENGTH(module_id)=OCTET_LENGTH(#{t.moduleId})" +
            " AND CAST(client_id AS BINARY)=CAST(#{t.clientId} AS BINARY)" +
            " AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{t.clientId})" +
            " AND CAST(task_id AS BINARY)=CAST(#{t.taskId} AS BINARY)" +
            " AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{t.taskId})" +
            " AND CAST(target_agent_id AS BINARY)=CAST(#{t.targetAgentId} AS BINARY)" +
            " AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{t.targetAgentId})" +
            " AND CAST(command_type AS BINARY)=CAST(#{t.commandType} AS BINARY)" +
            " AND OCTET_LENGTH(command_type)=OCTET_LENGTH(#{t.commandType})";

    String TOKEN_INBOX_IDENTITY_SCOPE = " protocol_version=2 AND tenant_id=#{t.tenantId}" +
            " AND data_domain_id=#{t.dataDomainId} AND execution_pool_id=#{t.executionPoolId}" +
            " AND admission_epoch=#{t.admissionEpoch} AND producer_deployment_id=#{t.producerDeploymentId}" +
            " AND module_id=#{t.moduleId} AND migration_epoch=#{t.migrationEpoch}" +
            " AND claimant_deployment_id=#{t.claimantDeploymentId}" +
            " AND claimant_worker_generation=#{t.claimantWorkerGeneration} AND client_id=#{t.clientId}" +
            " AND consumer_name=#{t.consumerName} AND message_id=#{t.messageId}" +
            " AND event_id=#{t.eventId} AND command_id=#{t.commandId} AND delivery_id=#{t.deliveryId}" +
            " AND expires_at=#{t.expiresAt} AND active_attempt=#{t.activeAttempt}" +
            " AND CAST(tenant_id AS BINARY)=CAST(#{t.tenantId} AS BINARY)" +
            " AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{t.tenantId})" +
            " AND CAST(data_domain_id AS BINARY)=CAST(#{t.dataDomainId} AS BINARY)" +
            " AND OCTET_LENGTH(data_domain_id)=OCTET_LENGTH(#{t.dataDomainId})" +
            " AND CAST(execution_pool_id AS BINARY)=CAST(#{t.executionPoolId} AS BINARY)" +
            " AND OCTET_LENGTH(execution_pool_id)=OCTET_LENGTH(#{t.executionPoolId})" +
            " AND CAST(producer_deployment_id AS BINARY)=CAST(#{t.producerDeploymentId} AS BINARY)" +
            " AND OCTET_LENGTH(producer_deployment_id)=OCTET_LENGTH(#{t.producerDeploymentId})" +
            " AND CAST(module_id AS BINARY)=CAST(#{t.moduleId} AS BINARY)" +
            " AND OCTET_LENGTH(module_id)=OCTET_LENGTH(#{t.moduleId})" +
            " AND CAST(claimant_deployment_id AS BINARY)=CAST(#{t.claimantDeploymentId} AS BINARY)" +
            " AND OCTET_LENGTH(claimant_deployment_id)=OCTET_LENGTH(#{t.claimantDeploymentId})" +
            " AND CAST(client_id AS BINARY)=CAST(#{t.clientId} AS BINARY)" +
            " AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{t.clientId})" +
            " AND CAST(consumer_name AS BINARY)=CAST(#{t.consumerName} AS BINARY)" +
            " AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{t.consumerName})" +
            " AND CAST(message_id AS BINARY)=CAST(#{t.messageId} AS BINARY)" +
            " AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{t.messageId})" +
            " AND CAST(event_id AS BINARY)=CAST(#{t.eventId} AS BINARY)" +
            " AND OCTET_LENGTH(event_id)=OCTET_LENGTH(#{t.eventId})" +
            " AND CAST(command_id AS BINARY)=CAST(#{t.commandId} AS BINARY)" +
            " AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{t.commandId})";

    String TOKEN_INBOX_SCOPE = TOKEN_INBOX_IDENTITY_SCOPE +
            " AND lease_owner=#{t.leaseOwner} AND lease_until=#{t.leaseUntil}" +
            " AND CAST(lease_owner AS BINARY)=CAST(#{t.leaseOwner} AS BINARY)" +
            " AND OCTET_LENGTH(lease_owner)=OCTET_LENGTH(#{t.leaseOwner})";

    String CLAIMANT_INBOX_EXISTS = " AND EXISTS (SELECT 1 FROM agent_consumer_inbox ci" +
            " WHERE ci.id=#{i.id} AND ci.protocol_version=2 AND ci.tenant_id=#{t.tenantId}" +
            " AND ci.data_domain_id=#{t.dataDomainId} AND ci.execution_pool_id=#{t.executionPoolId}" +
            " AND ci.admission_epoch=#{t.admissionEpoch} AND ci.producer_deployment_id=#{t.producerDeploymentId}" +
            " AND ci.module_id=#{t.moduleId} AND ci.migration_epoch=#{t.migrationEpoch}" +
            " AND ci.claimant_deployment_id=#{t.claimantDeploymentId}" +
            " AND ci.claimant_worker_generation=#{t.claimantWorkerGeneration} AND ci.client_id=#{t.clientId}" +
            " AND ci.consumer_name=#{t.consumerName} AND ci.message_id=#{t.messageId}" +
            " AND ci.event_id=#{t.eventId} AND ci.command_id=#{t.commandId} AND ci.delivery_id=#{t.deliveryId}" +
            " AND ci.lease_owner=#{t.leaseOwner} AND ci.lease_until=#{t.leaseUntil}" +
            " AND ci.active_attempt=#{t.activeAttempt} AND ci.version=#{i.version} AND ci.status='PROCESSING'" +
            " AND CAST(ci.tenant_id AS BINARY)=CAST(#{t.tenantId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.tenant_id)=OCTET_LENGTH(#{t.tenantId})" +
            " AND CAST(ci.data_domain_id AS BINARY)=CAST(#{t.dataDomainId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.data_domain_id)=OCTET_LENGTH(#{t.dataDomainId})" +
            " AND CAST(ci.execution_pool_id AS BINARY)=CAST(#{t.executionPoolId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.execution_pool_id)=OCTET_LENGTH(#{t.executionPoolId})" +
            " AND CAST(ci.producer_deployment_id AS BINARY)=CAST(#{t.producerDeploymentId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.producer_deployment_id)=OCTET_LENGTH(#{t.producerDeploymentId})" +
            " AND CAST(ci.module_id AS BINARY)=CAST(#{t.moduleId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.module_id)=OCTET_LENGTH(#{t.moduleId})" +
            " AND CAST(ci.claimant_deployment_id AS BINARY)=CAST(#{t.claimantDeploymentId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.claimant_deployment_id)=OCTET_LENGTH(#{t.claimantDeploymentId})" +
            " AND CAST(ci.client_id AS BINARY)=CAST(#{t.clientId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.client_id)=OCTET_LENGTH(#{t.clientId})" +
            " AND CAST(ci.consumer_name AS BINARY)=CAST(#{t.consumerName} AS BINARY)" +
            " AND OCTET_LENGTH(ci.consumer_name)=OCTET_LENGTH(#{t.consumerName})" +
            " AND CAST(ci.message_id AS BINARY)=CAST(#{t.messageId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.message_id)=OCTET_LENGTH(#{t.messageId})" +
            " AND CAST(ci.event_id AS BINARY)=CAST(#{t.eventId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.event_id)=OCTET_LENGTH(#{t.eventId})" +
            " AND CAST(ci.command_id AS BINARY)=CAST(#{t.commandId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.command_id)=OCTET_LENGTH(#{t.commandId})" +
            " AND CAST(ci.lease_owner AS BINARY)=CAST(#{t.leaseOwner} AS BINARY)" +
            " AND OCTET_LENGTH(ci.lease_owner)=OCTET_LENGTH(#{t.leaseOwner}))";

    String SETTLED_ACK_INBOX_EXISTS = " AND EXISTS (SELECT 1 FROM agent_consumer_inbox ci" +
            " WHERE ci.id=#{i.id} AND ci.protocol_version=2 AND ci.tenant_id=#{t.tenantId}" +
            " AND ci.data_domain_id=#{t.dataDomainId} AND ci.execution_pool_id=#{t.executionPoolId}" +
            " AND ci.admission_epoch=#{t.admissionEpoch} AND ci.producer_deployment_id=#{t.producerDeploymentId}" +
            " AND ci.module_id=#{t.moduleId} AND ci.migration_epoch=#{t.migrationEpoch}" +
            " AND ci.claimant_deployment_id=#{t.claimantDeploymentId}" +
            " AND ci.claimant_worker_generation=#{t.claimantWorkerGeneration} AND ci.client_id=#{t.clientId}" +
            " AND ci.consumer_name=#{t.consumerName} AND ci.message_id=#{t.messageId}" +
            " AND ci.event_id=#{t.eventId} AND ci.command_id=#{t.commandId} AND ci.delivery_id=#{t.deliveryId}" +
            " AND ci.expires_at=#{t.expiresAt} AND ci.active_attempt=#{t.activeAttempt}" +
            " AND ci.version=#{i.version} AND ci.status='PROCESSED' AND ci.result_status='SENT'" +
            " AND ci.next_retry_at IS NULL AND ci.lease_owner IS NULL AND ci.lease_until IS NULL" +
            " AND ci.processed_at=#{i.processedAt} AND ci.last_error IS NULL" +
            " AND ci.claim_delivery_version=#{t.deliveryVersion}" +
            " AND ci.claim_inbox_version=#{t.inboxVersion}" +
            " AND ci.claim_started_at=#{i.claimStartedAt}" +
            " AND ((ci.ack_delivery_version IS NULL AND #{i.ackDeliveryVersion} IS NULL)" +
            " OR ci.ack_delivery_version=#{i.ackDeliveryVersion})" +
            " AND ((ci.ack_message_id IS NULL AND #{i.ackMessageId} IS NULL) OR ci.ack_message_id=#{i.ackMessageId})" +
            " AND ((ci.ack_status IS NULL AND #{i.ackStatus} IS NULL) OR ci.ack_status=#{i.ackStatus})" +
            " AND ((ci.ack_at IS NULL AND #{i.ackAt} IS NULL) OR ci.ack_at=#{i.ackAt})" +
            " AND ((ci.ack_agent_id IS NULL AND #{i.ackAgentId} IS NULL) OR ci.ack_agent_id=#{i.ackAgentId})" +
            " AND ((ci.ack_runtime_installation_id IS NULL AND #{i.ackRuntimeInstallationId} IS NULL)" +
            " OR ci.ack_runtime_installation_id=#{i.ackRuntimeInstallationId})" +
            " AND ((ci.ack_runtime_instance_id IS NULL AND #{i.ackRuntimeInstanceId} IS NULL)" +
            " OR ci.ack_runtime_instance_id=#{i.ackRuntimeInstanceId})" +
            " AND CAST(ci.tenant_id AS BINARY)=CAST(#{t.tenantId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.tenant_id)=OCTET_LENGTH(#{t.tenantId})" +
            " AND CAST(ci.data_domain_id AS BINARY)=CAST(#{t.dataDomainId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.data_domain_id)=OCTET_LENGTH(#{t.dataDomainId})" +
            " AND CAST(ci.execution_pool_id AS BINARY)=CAST(#{t.executionPoolId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.execution_pool_id)=OCTET_LENGTH(#{t.executionPoolId})" +
            " AND CAST(ci.producer_deployment_id AS BINARY)=CAST(#{t.producerDeploymentId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.producer_deployment_id)=OCTET_LENGTH(#{t.producerDeploymentId})" +
            " AND CAST(ci.module_id AS BINARY)=CAST(#{t.moduleId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.module_id)=OCTET_LENGTH(#{t.moduleId})" +
            " AND CAST(ci.claimant_deployment_id AS BINARY)=CAST(#{t.claimantDeploymentId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.claimant_deployment_id)=OCTET_LENGTH(#{t.claimantDeploymentId})" +
            " AND CAST(ci.client_id AS BINARY)=CAST(#{t.clientId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.client_id)=OCTET_LENGTH(#{t.clientId})" +
            " AND CAST(ci.consumer_name AS BINARY)=CAST(#{t.consumerName} AS BINARY)" +
            " AND OCTET_LENGTH(ci.consumer_name)=OCTET_LENGTH(#{t.consumerName})" +
            " AND CAST(ci.message_id AS BINARY)=CAST(#{t.messageId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.message_id)=OCTET_LENGTH(#{t.messageId})" +
            " AND CAST(ci.event_id AS BINARY)=CAST(#{t.eventId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.event_id)=OCTET_LENGTH(#{t.eventId})" +
            " AND CAST(ci.command_id AS BINARY)=CAST(#{t.commandId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.command_id)=OCTET_LENGTH(#{t.commandId})" +
            " AND (#{i.ackMessageId} IS NULL OR (CAST(ci.ack_message_id AS BINARY)=CAST(#{i.ackMessageId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.ack_message_id)=OCTET_LENGTH(#{i.ackMessageId})))" +
            " AND (#{i.ackStatus} IS NULL OR (CAST(ci.ack_status AS BINARY)=CAST(#{i.ackStatus} AS BINARY)" +
            " AND OCTET_LENGTH(ci.ack_status)=OCTET_LENGTH(#{i.ackStatus})))" +
            " AND (#{i.ackAgentId} IS NULL OR (CAST(ci.ack_agent_id AS BINARY)=CAST(#{i.ackAgentId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.ack_agent_id)=OCTET_LENGTH(#{i.ackAgentId})))" +
            " AND (#{i.ackRuntimeInstallationId} IS NULL OR" +
            " (CAST(ci.ack_runtime_installation_id AS BINARY)=CAST(#{i.ackRuntimeInstallationId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.ack_runtime_installation_id)=OCTET_LENGTH(#{i.ackRuntimeInstallationId})))" +
            " AND (#{i.ackRuntimeInstanceId} IS NULL OR" +
            " (CAST(ci.ack_runtime_instance_id AS BINARY)=CAST(#{i.ackRuntimeInstanceId} AS BINARY)" +
            " AND OCTET_LENGTH(ci.ack_runtime_instance_id)=OCTET_LENGTH(#{i.ackRuntimeInstanceId}))))";

    @Select("SELECT " + DELIVERY_COLUMNS + " FROM agent_command_delivery WHERE id=#{m.deliveryId} AND " + SOURCE_SCOPE + " LIMIT 1 FOR UPDATE")
    AgentCommandDeliveryEntity lockDelivery(@Param("m") AgentInboxMessage message);

    @Select("SELECT " + OUTBOX_COLUMNS + " FROM agent_outbox_event WHERE event_id=#{m.eventId} AND " + SOURCE_SCOPE +
            " AND CAST(event_id AS BINARY)=CAST(#{m.eventId} AS BINARY) AND OCTET_LENGTH(event_id)=OCTET_LENGTH(#{m.eventId}) LIMIT 1 FOR UPDATE")
    AgentOutboxEventEntity lockOutbox(@Param("m") AgentInboxMessage message);

    @Select("SELECT " + OUTBOX_COLUMNS + " FROM agent_outbox_event WHERE delivery_id=#{m.deliveryId}" +
            " AND active_attempt=#{activeAttempt}-1 AND " + SOURCE_SCOPE + " ORDER BY id LIMIT 2 FOR UPDATE")
    List<AgentOutboxEventEntity> lockPrevious(@Param("m") AgentInboxMessage message, @Param("activeAttempt") int activeAttempt);

    @Select("SELECT " + INBOX_COLUMNS + " FROM agent_consumer_inbox WHERE consumer_name=#{m.consumerName}" +
            " AND message_id=#{m.messageId} AND " + INBOX_IDENTITY_SCOPE +
            " AND CAST(consumer_name AS BINARY)=CAST(#{m.consumerName} AS BINARY) AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{m.consumerName})" +
            " AND CAST(message_id AS BINARY)=CAST(#{m.messageId} AS BINARY) AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{m.messageId}) LIMIT 1 FOR UPDATE")
    AgentConsumerInboxEntity lockInbox(@Param("m") AgentInboxMessage message);

    @Insert("INSERT INTO agent_consumer_inbox(protocol_version,data_domain_id,execution_pool_id,admission_epoch," +
            "producer_deployment_id,module_id,migration_epoch,claimant_deployment_id,claimant_worker_generation," +
            "consumer_name,message_id,event_id,command_id,delivery_id,wire_payload,wire_payload_hash,status," +
            "result_status,attempt_count,next_retry_at,lease_owner,lease_until,active_attempt,expires_at,processed_at," +
            "last_error,version,claim_delivery_version,claim_inbox_version,claim_started_at," +
            "ack_delivery_version,ack_message_id," +
            "ack_status,ack_at,ack_agent_id,ack_runtime_installation_id,ack_runtime_instance_id," +
            "tenant_id,client_id,create_time,update_time) VALUES(#{protocolVersion},#{dataDomainId}," +
            "#{executionPoolId},#{admissionEpoch},#{producerDeploymentId},#{moduleId},#{migrationEpoch}," +
            "#{claimantDeploymentId},#{claimantWorkerGeneration},#{consumerName},#{messageId},#{eventId},#{commandId}," +
            "#{deliveryId},#{wirePayload},#{wirePayloadHash},#{status},#{resultStatus},#{attemptCount},#{nextRetryAt}," +
            "#{leaseOwner},#{leaseUntil},#{activeAttempt},#{expiresAt},#{processedAt},#{lastError},#{version}," +
            "#{claimDeliveryVersion},#{claimInboxVersion},#{claimStartedAt},#{ackDeliveryVersion},#{ackMessageId},#{ackStatus},#{ackAt}," +
            "#{ackAgentId},#{ackRuntimeInstallationId},#{ackRuntimeInstanceId}," +
            "#{tenantId},#{clientId},#{createTime},#{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentConsumerInboxEntity inbox);

    @Update("UPDATE agent_command_delivery SET status=#{newStatus},next_retry_at=#{nextRetryAt},lease_owner=NULL,lease_until=NULL," +
            "last_error=#{error},version=version+1,update_time=#{now} WHERE id=#{m.deliveryId} AND active_message_id=#{m.messageId}" +
            " AND active_attempt=#{attempt} AND status=#{expectedStatus} AND version=#{version} AND " + SOURCE_SCOPE +
            " AND task_id=#{m.taskId} AND target_agent_id=#{m.targetAgentId} AND command_type=#{m.commandType}" +
            " AND CAST(task_id AS BINARY)=CAST(#{m.taskId} AS BINARY)" +
            " AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{m.taskId})" +
            " AND CAST(target_agent_id AS BINARY)=CAST(#{m.targetAgentId} AS BINARY)" +
            " AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{m.targetAgentId})" +
            " AND CAST(command_type AS BINARY)=CAST(#{m.commandType} AS BINARY)" +
            " AND OCTET_LENGTH(command_type)=OCTET_LENGTH(#{m.commandType})" +
            " AND CAST(active_message_id AS BINARY)=CAST(#{m.messageId} AS BINARY) AND OCTET_LENGTH(active_message_id)=OCTET_LENGTH(#{m.messageId})" +
            " AND CAST(status AS BINARY)=CAST(#{expectedStatus} AS BINARY) AND OCTET_LENGTH(status)=OCTET_LENGTH(#{expectedStatus})")
    int transitionDelivery(@Param("m") AgentInboxMessage message, @Param("attempt") int attempt,
            @Param("version") long version, @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus, @Param("nextRetryAt") Long nextRetryAt,
            @Param("error") String error, @Param("now") long now);

    @Update("UPDATE agent_consumer_inbox SET status='PROCESSING',result_status=NULL,attempt_count=attempt_count+1," +
            "next_retry_at=NULL,lease_owner=#{owner},lease_until=#{leaseUntil},active_attempt=active_attempt+1," +
            "processed_at=NULL,last_error=NULL,claim_inbox_version=version+1,claim_started_at=#{now}," +
            "ack_delivery_version=NULL,ack_message_id=NULL,ack_status=NULL,ack_at=NULL,ack_agent_id=NULL," +
            "ack_runtime_installation_id=NULL,ack_runtime_instance_id=NULL,version=version+1,update_time=#{now}" +
            " WHERE id=#{i.id} AND " + INBOX_SCOPE +
            " AND consumer_name=#{m.consumerName} AND message_id=#{m.messageId} AND status='PROCESSING'" +
            " AND lease_owner=#{i.leaseOwner} AND lease_until=#{i.leaseUntil} AND active_attempt=#{i.activeAttempt} AND version=#{i.version}" +
            " AND CAST(consumer_name AS BINARY)=CAST(#{m.consumerName} AS BINARY) AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{m.consumerName})" +
            " AND CAST(message_id AS BINARY)=CAST(#{m.messageId} AS BINARY) AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{m.messageId})" +
            " AND CAST(lease_owner AS BINARY)=CAST(#{i.leaseOwner} AS BINARY) AND OCTET_LENGTH(lease_owner)=OCTET_LENGTH(#{i.leaseOwner})")
    int reclaimProcessingInbox(@Param("m") AgentInboxMessage message, @Param("i") AgentConsumerInboxEntity inbox,
            @Param("owner") String owner, @Param("leaseUntil") long leaseUntil, @Param("now") long now);

    @Update("UPDATE agent_consumer_inbox SET claimant_deployment_id=#{m.claimantDeploymentId}," +
            "claimant_worker_generation=#{m.claimantWorkerGeneration},status='PROCESSING',result_status=NULL," +
            "attempt_count=attempt_count+1,next_retry_at=NULL,lease_owner=#{owner},lease_until=#{leaseUntil}," +
            "active_attempt=active_attempt+1,processed_at=NULL,last_error=NULL," +
            "claim_delivery_version=#{deliveryVersion},claim_inbox_version=version+1,claim_started_at=#{now}," +
            "ack_delivery_version=NULL,ack_message_id=NULL,ack_status=NULL,ack_at=NULL,ack_agent_id=NULL," +
            "ack_runtime_installation_id=NULL,ack_runtime_instance_id=NULL,version=version+1,update_time=#{now}" +
            " WHERE id=#{i.id} AND " + INBOX_IDENTITY_SCOPE +
            " AND consumer_name=#{m.consumerName} AND message_id=#{m.messageId} AND status='PROCESSING'" +
            " AND claimant_deployment_id=#{i.claimantDeploymentId}" +
            " AND claimant_worker_generation=#{i.claimantWorkerGeneration}" +
            " AND lease_owner=#{i.leaseOwner} AND lease_until=#{i.leaseUntil}" +
            " AND active_attempt=#{i.activeAttempt} AND version=#{i.version}" +
            " AND CAST(claimant_deployment_id AS BINARY)=CAST(#{i.claimantDeploymentId} AS BINARY)" +
            " AND OCTET_LENGTH(claimant_deployment_id)=OCTET_LENGTH(#{i.claimantDeploymentId})" +
            " AND CAST(consumer_name AS BINARY)=CAST(#{m.consumerName} AS BINARY)" +
            " AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{m.consumerName})" +
            " AND CAST(message_id AS BINARY)=CAST(#{m.messageId} AS BINARY)" +
            " AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{m.messageId})" +
            " AND CAST(lease_owner AS BINARY)=CAST(#{i.leaseOwner} AS BINARY)" +
            " AND OCTET_LENGTH(lease_owner)=OCTET_LENGTH(#{i.leaseOwner})")
    int takeoverProcessingInbox(@Param("m") AgentInboxMessage message,
            @Param("i") AgentConsumerInboxEntity inbox, @Param("owner") String owner,
            @Param("leaseUntil") long leaseUntil, @Param("deliveryVersion") long deliveryVersion,
            @Param("now") long now);

    @Update("UPDATE agent_consumer_inbox SET status='PROCESSING',result_status=NULL,attempt_count=attempt_count+1," +
            "next_retry_at=NULL,lease_owner=#{owner},lease_until=#{leaseUntil},active_attempt=active_attempt+1," +
            "processed_at=NULL,last_error=NULL,claim_delivery_version=#{deliveryVersion}," +
            "claim_inbox_version=version+1,claim_started_at=#{now},ack_delivery_version=NULL,ack_message_id=NULL,ack_status=NULL,ack_at=NULL," +
            "ack_agent_id=NULL,ack_runtime_installation_id=NULL,ack_runtime_instance_id=NULL," +
            "version=version+1,update_time=#{now} WHERE id=#{i.id} AND " + INBOX_SCOPE +
            " AND consumer_name=#{m.consumerName} AND message_id=#{m.messageId} AND status='RETRY' AND result_status='RETRY'" +
            " AND lease_owner IS NULL AND lease_until IS NULL AND next_retry_at=#{i.nextRetryAt} AND processed_at=#{i.processedAt}" +
            " AND active_attempt=#{i.activeAttempt} AND version=#{i.version}" +
            " AND CAST(consumer_name AS BINARY)=CAST(#{m.consumerName} AS BINARY) AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{m.consumerName})" +
            " AND CAST(message_id AS BINARY)=CAST(#{m.messageId} AS BINARY) AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{m.messageId})")
    int reclaimRetryInbox(@Param("m") AgentInboxMessage message, @Param("i") AgentConsumerInboxEntity inbox,
            @Param("owner") String owner, @Param("leaseUntil") long leaseUntil,
            @Param("deliveryVersion") long deliveryVersion, @Param("now") long now);

    @Update("UPDATE agent_consumer_inbox SET claimant_deployment_id=#{m.claimantDeploymentId}," +
            "claimant_worker_generation=#{m.claimantWorkerGeneration},status='PROCESSING',result_status=NULL," +
            "attempt_count=attempt_count+1,next_retry_at=NULL,lease_owner=#{owner},lease_until=#{leaseUntil}," +
            "active_attempt=active_attempt+1,processed_at=NULL,last_error=NULL," +
            "claim_delivery_version=#{deliveryVersion},claim_inbox_version=version+1,claim_started_at=#{now}," +
            "ack_delivery_version=NULL,ack_message_id=NULL,ack_status=NULL,ack_at=NULL,ack_agent_id=NULL," +
            "ack_runtime_installation_id=NULL,ack_runtime_instance_id=NULL,version=version+1,update_time=#{now}" +
            " WHERE id=#{i.id} AND " + INBOX_IDENTITY_SCOPE +
            " AND consumer_name=#{m.consumerName} AND message_id=#{m.messageId}" +
            " AND status='RETRY' AND result_status='RETRY'" +
            " AND claimant_deployment_id=#{i.claimantDeploymentId}" +
            " AND claimant_worker_generation=#{i.claimantWorkerGeneration}" +
            " AND lease_owner IS NULL AND lease_until IS NULL" +
            " AND next_retry_at=#{i.nextRetryAt} AND processed_at=#{i.processedAt}" +
            " AND active_attempt=#{i.activeAttempt} AND version=#{i.version}" +
            " AND CAST(claimant_deployment_id AS BINARY)=CAST(#{i.claimantDeploymentId} AS BINARY)" +
            " AND OCTET_LENGTH(claimant_deployment_id)=OCTET_LENGTH(#{i.claimantDeploymentId})" +
            " AND CAST(consumer_name AS BINARY)=CAST(#{m.consumerName} AS BINARY)" +
            " AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{m.consumerName})" +
            " AND CAST(message_id AS BINARY)=CAST(#{m.messageId} AS BINARY)" +
            " AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{m.messageId})")
    int takeoverRetryInbox(@Param("m") AgentInboxMessage message,
            @Param("i") AgentConsumerInboxEntity inbox, @Param("owner") String owner,
            @Param("leaseUntil") long leaseUntil, @Param("deliveryVersion") long deliveryVersion,
            @Param("now") long now);

    @Update("UPDATE agent_consumer_inbox SET status='EXPIRED',result_status='EXPIRED',next_retry_at=NULL,lease_owner=NULL," +
            "lease_until=NULL,processed_at=#{now},last_error=#{error},version=version+1,update_time=#{now}" +
            " WHERE id=#{i.id} AND " + INBOX_IDENTITY_SCOPE + " AND consumer_name=#{m.consumerName} AND message_id=#{m.messageId}" +
            " AND claimant_deployment_id=#{i.claimantDeploymentId}" +
            " AND claimant_worker_generation=#{i.claimantWorkerGeneration}" +
            " AND status='PROCESSING' AND lease_owner=#{i.leaseOwner} AND lease_until=#{i.leaseUntil}" +
            " AND active_attempt=#{i.activeAttempt} AND version=#{i.version}" +
            " AND CAST(claimant_deployment_id AS BINARY)=CAST(#{i.claimantDeploymentId} AS BINARY)" +
            " AND OCTET_LENGTH(claimant_deployment_id)=OCTET_LENGTH(#{i.claimantDeploymentId})" +
            " AND CAST(consumer_name AS BINARY)=CAST(#{m.consumerName} AS BINARY) AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{m.consumerName})" +
            " AND CAST(message_id AS BINARY)=CAST(#{m.messageId} AS BINARY) AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{m.messageId})" +
            " AND CAST(lease_owner AS BINARY)=CAST(#{i.leaseOwner} AS BINARY) AND OCTET_LENGTH(lease_owner)=OCTET_LENGTH(#{i.leaseOwner})")
    int expireProcessingInbox(@Param("m") AgentInboxMessage message, @Param("i") AgentConsumerInboxEntity inbox,
            @Param("error") String error, @Param("now") long now);

    @Update("UPDATE agent_consumer_inbox SET status='EXPIRED',result_status='EXPIRED',next_retry_at=NULL,lease_owner=NULL," +
            "lease_until=NULL,processed_at=#{now},last_error=#{error},version=version+1,update_time=#{now}" +
            " WHERE id=#{i.id} AND " + INBOX_IDENTITY_SCOPE + " AND consumer_name=#{m.consumerName} AND message_id=#{m.messageId}" +
            " AND claimant_deployment_id=#{i.claimantDeploymentId}" +
            " AND claimant_worker_generation=#{i.claimantWorkerGeneration}" +
            " AND status='RETRY' AND result_status='RETRY' AND lease_owner IS NULL AND lease_until IS NULL" +
            " AND next_retry_at=#{i.nextRetryAt} AND processed_at=#{i.processedAt} AND active_attempt=#{i.activeAttempt} AND version=#{i.version}" +
            " AND CAST(claimant_deployment_id AS BINARY)=CAST(#{i.claimantDeploymentId} AS BINARY)" +
            " AND OCTET_LENGTH(claimant_deployment_id)=OCTET_LENGTH(#{i.claimantDeploymentId})" +
            " AND CAST(consumer_name AS BINARY)=CAST(#{m.consumerName} AS BINARY) AND OCTET_LENGTH(consumer_name)=OCTET_LENGTH(#{m.consumerName})" +
            " AND CAST(message_id AS BINARY)=CAST(#{m.messageId} AS BINARY) AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{m.messageId})")
    int expireRetryInbox(@Param("m") AgentInboxMessage message, @Param("i") AgentConsumerInboxEntity inbox,
            @Param("error") String error, @Param("now") long now);

    @Update("UPDATE agent_consumer_inbox SET status=#{status},result_status=#{result},next_retry_at=#{nextRetryAt}," +
            "lease_owner=NULL,lease_until=NULL,processed_at=#{now},last_error=#{error},version=version+1,update_time=#{now}" +
            " WHERE id=#{t.inboxId} AND " + TOKEN_INBOX_SCOPE +
            " AND version=#{t.inboxVersion} AND status='PROCESSING'")
    int settleInbox(@Param("t") AgentInboxClaimToken token, @Param("status") String status,
            @Param("result") String result, @Param("nextRetryAt") Long nextRetryAt,
            @Param("error") String error, @Param("now") long now);

    @Update("UPDATE agent_command_delivery SET status=#{newStatus},next_retry_at=#{nextRetryAt}," +
            "lease_owner=NULL,lease_until=NULL,last_error=#{error},version=version+1,update_time=#{now}" +
            " WHERE id=#{t.deliveryId} AND command_id=#{t.commandId} AND active_message_id=#{t.messageId}" +
            " AND active_attempt=#{t.deliveryActiveAttempt} AND version=#{t.deliveryVersion}" +
            " AND status=#{expectedStatus} AND " + TOKEN_SOURCE_SCOPE +
            " AND CAST(command_id AS BINARY)=CAST(#{t.commandId} AS BINARY)" +
            " AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{t.commandId})" +
            " AND CAST(active_message_id AS BINARY)=CAST(#{t.messageId} AS BINARY)" +
            " AND OCTET_LENGTH(active_message_id)=OCTET_LENGTH(#{t.messageId})" +
            " AND CAST(status AS BINARY)=CAST(#{expectedStatus} AS BINARY)" +
            " AND OCTET_LENGTH(status)=OCTET_LENGTH(#{expectedStatus})" + CLAIMANT_INBOX_EXISTS)
    int settleDeliveryFromClaim(@Param("t") AgentInboxClaimToken token,
            @Param("i") AgentConsumerInboxEntity inbox,
            @Param("expectedStatus") String expectedStatus, @Param("newStatus") String newStatus,
            @Param("nextRetryAt") Long nextRetryAt, @Param("error") String error,
            @Param("now") long now);

    @Update("UPDATE agent_command_delivery SET status=#{newStatus},next_retry_at=NULL,lease_owner=NULL," +
            "lease_until=NULL,last_error=#{error},version=version+1,update_time=#{now}" +
            " WHERE id=#{t.deliveryId} AND command_id=#{t.commandId} AND active_message_id=#{t.messageId}" +
            " AND active_attempt=#{t.deliveryActiveAttempt} AND version=#{expectedDeliveryVersion}" +
            " AND status=#{expectedStatus} AND " + TOKEN_SOURCE_SCOPE +
            " AND CAST(command_id AS BINARY)=CAST(#{t.commandId} AS BINARY)" +
            " AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{t.commandId})" +
            " AND CAST(active_message_id AS BINARY)=CAST(#{t.messageId} AS BINARY)" +
            " AND OCTET_LENGTH(active_message_id)=OCTET_LENGTH(#{t.messageId})" +
            " AND CAST(status AS BINARY)=CAST(#{expectedStatus} AS BINARY)" +
            " AND OCTET_LENGTH(status)=OCTET_LENGTH(#{expectedStatus})" + CLAIMANT_INBOX_EXISTS)
    int advanceAcknowledgement(@Param("t") AgentInboxClaimToken token,
            @Param("i") AgentConsumerInboxEntity inbox,
            @Param("expectedDeliveryVersion") long expectedDeliveryVersion,
            @Param("expectedStatus") String expectedStatus, @Param("newStatus") String newStatus,
            @Param("error") String error, @Param("now") long now);

    @Update("UPDATE agent_command_delivery SET status=#{newStatus},next_retry_at=NULL,lease_owner=NULL," +
            "lease_until=NULL,last_error=#{error},version=version+1,update_time=#{now}" +
            " WHERE id=#{t.deliveryId} AND command_id=#{t.commandId} AND active_message_id=#{t.messageId}" +
            " AND active_attempt=#{t.deliveryActiveAttempt} AND version=#{expectedDeliveryVersion}" +
            " AND status=#{expectedStatus} AND " + TOKEN_SOURCE_SCOPE +
            " AND CAST(command_id AS BINARY)=CAST(#{t.commandId} AS BINARY)" +
            " AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{t.commandId})" +
            " AND CAST(active_message_id AS BINARY)=CAST(#{t.messageId} AS BINARY)" +
            " AND OCTET_LENGTH(active_message_id)=OCTET_LENGTH(#{t.messageId})" +
            " AND CAST(status AS BINARY)=CAST(#{expectedStatus} AS BINARY)" +
            " AND OCTET_LENGTH(status)=OCTET_LENGTH(#{expectedStatus})" + SETTLED_ACK_INBOX_EXISTS)
    int advanceSettledAcknowledgement(@Param("t") AgentInboxClaimToken token,
            @Param("i") AgentConsumerInboxEntity inbox,
            @Param("expectedDeliveryVersion") long expectedDeliveryVersion,
            @Param("expectedStatus") String expectedStatus, @Param("newStatus") String newStatus,
            @Param("error") String error, @Param("now") long now);

    @Update("UPDATE agent_consumer_inbox SET ack_delivery_version=#{ackDeliveryVersion}," +
            "ack_message_id=#{ackMessageId},ack_status=#{ackStatus},ack_at=#{ackAt},ack_agent_id=#{agentId}," +
            "ack_runtime_installation_id=#{runtimeInstallationId},ack_runtime_instance_id=#{runtimeInstanceId}," +
            "version=version+1,update_time=#{now} WHERE id=#{i.id} AND " + TOKEN_INBOX_SCOPE +
            " AND status='PROCESSING' AND version=#{i.version}" +
            " AND claim_delivery_version=#{t.deliveryVersion} AND claim_inbox_version=#{t.inboxVersion}" +
            " AND ((ack_delivery_version IS NULL AND #{i.ackDeliveryVersion} IS NULL) OR ack_delivery_version=#{i.ackDeliveryVersion})" +
            " AND ((ack_message_id IS NULL AND #{i.ackMessageId} IS NULL) OR ack_message_id=#{i.ackMessageId})" +
            " AND ((ack_status IS NULL AND #{i.ackStatus} IS NULL) OR ack_status=#{i.ackStatus})" +
            " AND ((ack_at IS NULL AND #{i.ackAt} IS NULL) OR ack_at=#{i.ackAt})" +
            " AND ((ack_agent_id IS NULL AND #{i.ackAgentId} IS NULL) OR ack_agent_id=#{i.ackAgentId})" +
            " AND ((ack_runtime_installation_id IS NULL AND #{i.ackRuntimeInstallationId} IS NULL)" +
            " OR ack_runtime_installation_id=#{i.ackRuntimeInstallationId})" +
            " AND ((ack_runtime_instance_id IS NULL AND #{i.ackRuntimeInstanceId} IS NULL)" +
            " OR ack_runtime_instance_id=#{i.ackRuntimeInstanceId})" +
            " AND (#{i.ackMessageId} IS NULL OR (CAST(ack_message_id AS BINARY)=CAST(#{i.ackMessageId} AS BINARY)" +
            " AND OCTET_LENGTH(ack_message_id)=OCTET_LENGTH(#{i.ackMessageId})))" +
            " AND (#{i.ackStatus} IS NULL OR (CAST(ack_status AS BINARY)=CAST(#{i.ackStatus} AS BINARY)" +
            " AND OCTET_LENGTH(ack_status)=OCTET_LENGTH(#{i.ackStatus})))" +
            " AND (#{i.ackAgentId} IS NULL OR (CAST(ack_agent_id AS BINARY)=CAST(#{i.ackAgentId} AS BINARY)" +
            " AND OCTET_LENGTH(ack_agent_id)=OCTET_LENGTH(#{i.ackAgentId})))" +
            " AND (#{i.ackRuntimeInstallationId} IS NULL OR" +
            " (CAST(ack_runtime_installation_id AS BINARY)=CAST(#{i.ackRuntimeInstallationId} AS BINARY)" +
            " AND OCTET_LENGTH(ack_runtime_installation_id)=OCTET_LENGTH(#{i.ackRuntimeInstallationId})))" +
            " AND (#{i.ackRuntimeInstanceId} IS NULL OR" +
            " (CAST(ack_runtime_instance_id AS BINARY)=CAST(#{i.ackRuntimeInstanceId} AS BINARY)" +
            " AND OCTET_LENGTH(ack_runtime_instance_id)=OCTET_LENGTH(#{i.ackRuntimeInstanceId})))")
    int recordAcknowledgement(@Param("t") AgentInboxClaimToken token,
            @Param("i") AgentConsumerInboxEntity inbox, @Param("ackMessageId") String ackMessageId,
            @Param("ackStatus") String ackStatus, @Param("ackAt") long ackAt,
            @Param("agentId") String agentId, @Param("runtimeInstallationId") String runtimeInstallationId,
            @Param("runtimeInstanceId") String runtimeInstanceId,
            @Param("ackDeliveryVersion") long ackDeliveryVersion, @Param("now") long now);

    @Update("UPDATE agent_consumer_inbox SET ack_delivery_version=#{ackDeliveryVersion}," +
            "ack_message_id=#{ackMessageId},ack_status=#{ackStatus},ack_at=#{ackAt},ack_agent_id=#{agentId}," +
            "ack_runtime_installation_id=#{runtimeInstallationId},ack_runtime_instance_id=#{runtimeInstanceId}," +
            "version=version+1,update_time=#{now} WHERE id=#{i.id} AND " + TOKEN_INBOX_IDENTITY_SCOPE +
            " AND status='PROCESSED' AND result_status='SENT' AND next_retry_at IS NULL" +
            " AND lease_owner IS NULL AND lease_until IS NULL AND processed_at=#{i.processedAt}" +
            " AND last_error IS NULL AND version=#{i.version}" +
            " AND claim_delivery_version=#{t.deliveryVersion} AND claim_inbox_version=#{t.inboxVersion}" +
            " AND claim_started_at=#{i.claimStartedAt}" +
            " AND ((ack_delivery_version IS NULL AND #{i.ackDeliveryVersion} IS NULL) OR ack_delivery_version=#{i.ackDeliveryVersion})" +
            " AND ((ack_message_id IS NULL AND #{i.ackMessageId} IS NULL) OR ack_message_id=#{i.ackMessageId})" +
            " AND ((ack_status IS NULL AND #{i.ackStatus} IS NULL) OR ack_status=#{i.ackStatus})" +
            " AND ((ack_at IS NULL AND #{i.ackAt} IS NULL) OR ack_at=#{i.ackAt})" +
            " AND ((ack_agent_id IS NULL AND #{i.ackAgentId} IS NULL) OR ack_agent_id=#{i.ackAgentId})" +
            " AND ((ack_runtime_installation_id IS NULL AND #{i.ackRuntimeInstallationId} IS NULL)" +
            " OR ack_runtime_installation_id=#{i.ackRuntimeInstallationId})" +
            " AND ((ack_runtime_instance_id IS NULL AND #{i.ackRuntimeInstanceId} IS NULL)" +
            " OR ack_runtime_instance_id=#{i.ackRuntimeInstanceId})" +
            " AND (#{i.ackMessageId} IS NULL OR (CAST(ack_message_id AS BINARY)=CAST(#{i.ackMessageId} AS BINARY)" +
            " AND OCTET_LENGTH(ack_message_id)=OCTET_LENGTH(#{i.ackMessageId})))" +
            " AND (#{i.ackStatus} IS NULL OR (CAST(ack_status AS BINARY)=CAST(#{i.ackStatus} AS BINARY)" +
            " AND OCTET_LENGTH(ack_status)=OCTET_LENGTH(#{i.ackStatus})))" +
            " AND (#{i.ackAgentId} IS NULL OR (CAST(ack_agent_id AS BINARY)=CAST(#{i.ackAgentId} AS BINARY)" +
            " AND OCTET_LENGTH(ack_agent_id)=OCTET_LENGTH(#{i.ackAgentId})))" +
            " AND (#{i.ackRuntimeInstallationId} IS NULL OR" +
            " (CAST(ack_runtime_installation_id AS BINARY)=CAST(#{i.ackRuntimeInstallationId} AS BINARY)" +
            " AND OCTET_LENGTH(ack_runtime_installation_id)=OCTET_LENGTH(#{i.ackRuntimeInstallationId})))" +
            " AND (#{i.ackRuntimeInstanceId} IS NULL OR" +
            " (CAST(ack_runtime_instance_id AS BINARY)=CAST(#{i.ackRuntimeInstanceId} AS BINARY)" +
            " AND OCTET_LENGTH(ack_runtime_instance_id)=OCTET_LENGTH(#{i.ackRuntimeInstanceId})))")
    int recordSettledAcknowledgement(@Param("t") AgentInboxClaimToken token,
            @Param("i") AgentConsumerInboxEntity inbox, @Param("ackMessageId") String ackMessageId,
            @Param("ackStatus") String ackStatus, @Param("ackAt") long ackAt,
            @Param("agentId") String agentId, @Param("runtimeInstallationId") String runtimeInstallationId,
            @Param("runtimeInstanceId") String runtimeInstanceId,
            @Param("ackDeliveryVersion") long ackDeliveryVersion, @Param("now") long now);

    @Update("UPDATE agent_consumer_inbox SET status='PROCESSED',result_status='SENT',next_retry_at=NULL," +
            "lease_owner=NULL,lease_until=NULL,processed_at=#{now},last_error=NULL,version=version+1,update_time=#{now}" +
            " WHERE id=#{i.id} AND " + TOKEN_INBOX_SCOPE +
            " AND status='PROCESSING' AND version=#{i.version}" +
            " AND claim_delivery_version=#{t.deliveryVersion} AND claim_inbox_version=#{t.inboxVersion}" +
            " AND ack_delivery_version=#{i.ackDeliveryVersion} AND ack_message_id=#{i.ackMessageId}" +
            " AND ack_status=#{i.ackStatus} AND ack_at=#{i.ackAt} AND ack_agent_id=#{i.ackAgentId}" +
            " AND ack_runtime_installation_id=#{i.ackRuntimeInstallationId}" +
            " AND ack_runtime_instance_id=#{i.ackRuntimeInstanceId}" +
            " AND CAST(ack_message_id AS BINARY)=CAST(#{i.ackMessageId} AS BINARY)" +
            " AND OCTET_LENGTH(ack_message_id)=OCTET_LENGTH(#{i.ackMessageId})" +
            " AND CAST(ack_status AS BINARY)=CAST(#{i.ackStatus} AS BINARY)" +
            " AND OCTET_LENGTH(ack_status)=OCTET_LENGTH(#{i.ackStatus})" +
            " AND CAST(ack_agent_id AS BINARY)=CAST(#{i.ackAgentId} AS BINARY)" +
            " AND OCTET_LENGTH(ack_agent_id)=OCTET_LENGTH(#{i.ackAgentId})" +
            " AND CAST(ack_runtime_installation_id AS BINARY)=CAST(#{i.ackRuntimeInstallationId} AS BINARY)" +
            " AND OCTET_LENGTH(ack_runtime_installation_id)=OCTET_LENGTH(#{i.ackRuntimeInstallationId})" +
            " AND CAST(ack_runtime_instance_id AS BINARY)=CAST(#{i.ackRuntimeInstanceId} AS BINARY)" +
            " AND OCTET_LENGTH(ack_runtime_instance_id)=OCTET_LENGTH(#{i.ackRuntimeInstanceId})")
    int settleInboxAfterAcknowledgement(@Param("t") AgentInboxClaimToken token,
            @Param("i") AgentConsumerInboxEntity inbox, @Param("now") long now);
}
