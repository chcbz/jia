package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Scoped AMQP-v2 relay mapper. Every discovery and mutation starts from one exact worker lane. */
public interface AgentOutboxRelayMapper {
    int PROTOCOL_VERSION = 2;
    String EXACT_SCOPE = " " + """
            o.tenant_id=#{tenantId}
            AND o.data_domain_id=#{dataDomainId}
            AND o.execution_pool_id=#{executionPoolId}
            AND o.protocol_version=2
            AND o.admission_epoch=#{admissionEpoch}
            AND o.module_id=#{moduleId}
            AND o.migration_epoch=#{migrationEpoch}
            AND CAST(o.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(o.tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(o.data_domain_id AS BINARY)=CAST(#{dataDomainId} AS BINARY)
            AND OCTET_LENGTH(o.data_domain_id)=OCTET_LENGTH(#{dataDomainId})
            AND CAST(o.execution_pool_id AS BINARY)=CAST(#{executionPoolId} AS BINARY)
            AND OCTET_LENGTH(o.execution_pool_id)=OCTET_LENGTH(#{executionPoolId})
            AND CAST(o.module_id AS BINARY)=CAST(#{moduleId} AS BINARY)
            AND OCTET_LENGTH(o.module_id)=OCTET_LENGTH(#{moduleId})
            """ + " ";
    String REQUIRED_V2 = " " + """
            o.producer_deployment_id IS NOT NULL
            AND CHAR_LENGTH(o.producer_deployment_id) BETWEEN 1 AND 100
            AND CAST(o.producer_deployment_id AS BINARY)=CAST(TRIM(o.producer_deployment_id) AS BINARY)
            AND OCTET_LENGTH(o.producer_deployment_id)=OCTET_LENGTH(TRIM(o.producer_deployment_id))
            AND o.admission_epoch>=0 AND o.migration_epoch>=0
            AND ((o.status IN ('PENDING','RETRY')
                    AND o.claimed_deployment_id IS NULL AND o.claimed_worker_generation IS NULL)
                 OR (o.status='CLAIMED'
                    AND CAST(o.claimed_deployment_id AS BINARY)=CAST(#{deploymentId} AS BINARY)
                    AND OCTET_LENGTH(o.claimed_deployment_id)=OCTET_LENGTH(#{deploymentId})
                    AND o.claimed_worker_generation=#{workerGeneration}))
            """ + " ";
    String EXACT_ROW_SCOPE = " " + """
            AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(data_domain_id AS BINARY)=CAST(#{dataDomainId} AS BINARY)
            AND OCTET_LENGTH(data_domain_id)=OCTET_LENGTH(#{dataDomainId})
            AND CAST(execution_pool_id AS BINARY)=CAST(#{executionPoolId} AS BINARY)
            AND OCTET_LENGTH(execution_pool_id)=OCTET_LENGTH(#{executionPoolId})
            AND CAST(module_id AS BINARY)=CAST(#{moduleId} AS BINARY)
            AND OCTET_LENGTH(module_id)=OCTET_LENGTH(#{moduleId})
            """ + " ";
    String ASSOCIATED_DELIVERY = " " + """
            EXISTS (SELECT 1 FROM agent_command_delivery d
                WHERE d.id=o.delivery_id
                  AND d.tenant_id=#{tenantId}
                  AND d.data_domain_id=#{dataDomainId}
                  AND d.execution_pool_id=#{executionPoolId}
                  AND d.protocol_version=2
                  AND d.admission_epoch=#{admissionEpoch}
                  AND d.module_id=#{moduleId}
                  AND d.migration_epoch=#{migrationEpoch}
                  AND CAST(d.tenant_id AS BINARY)=CAST(o.tenant_id AS BINARY)
                  AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(o.tenant_id)
                  AND CAST(d.data_domain_id AS BINARY)=CAST(o.data_domain_id AS BINARY)
                  AND OCTET_LENGTH(d.data_domain_id)=OCTET_LENGTH(o.data_domain_id)
                  AND CAST(d.execution_pool_id AS BINARY)=CAST(o.execution_pool_id AS BINARY)
                  AND OCTET_LENGTH(d.execution_pool_id)=OCTET_LENGTH(o.execution_pool_id)
                  AND CAST(d.producer_deployment_id AS BINARY)=CAST(o.producer_deployment_id AS BINARY)
                  AND OCTET_LENGTH(d.producer_deployment_id)=OCTET_LENGTH(o.producer_deployment_id)
                  AND CAST(d.module_id AS BINARY)=CAST(o.module_id AS BINARY)
                  AND OCTET_LENGTH(d.module_id)=OCTET_LENGTH(o.module_id))
            """ + " ";
    String CANDIDATE_COLUMNS = " " + """
            o.id AS outboxId, o.tenant_id AS tenantId, o.data_domain_id AS dataDomainId,
            o.execution_pool_id AS executionPoolId, o.admission_epoch AS admissionEpoch,
            o.producer_deployment_id AS producerDeploymentId, o.module_id AS moduleId,
            o.migration_epoch AS migrationEpoch, o.protocol_version AS protocolVersion,
            o.client_id AS clientId, o.delivery_id AS deliveryId,
            """ + " ";
    String DELIVERY_COLUMNS = " " + """
            id, protocol_version, data_domain_id, execution_pool_id, admission_epoch,
            producer_deployment_id, module_id, migration_epoch, claimed_deployment_id,
            claimed_worker_generation, command_id, owner_jiacn, task_id, work_item_id,
            target_agent_id, command_type, command_payload, command_payload_hash, status,
            attempt_count, next_retry_at, lease_owner, lease_until, active_message_id,
            active_attempt, expires_at, last_error, version, replay_parent_message_id,
            replay_requester_id, replay_approver_id, replay_reason, tenant_id, client_id,
            create_time, update_time
            """ + " ";
    String OUTBOX_COLUMNS = " " + """
            id, protocol_version, data_domain_id, execution_pool_id, admission_epoch,
            producer_deployment_id, module_id, migration_epoch, claimed_deployment_id,
            claimed_worker_generation, event_id, message_id, command_id, delivery_id,
            aggregate_type, aggregate_id, destination, routing_key, wire_payload,
            wire_payload_hash, status, attempt_count, next_retry_at, lease_owner, lease_until,
            active_attempt, expires_at, publisher_confirm_status, confirmed_at, confirm_error,
            mandatory_return_status, returned_at, return_reply_code, return_reply_text,
            published_at, last_error, version, replay_parent_message_id, replay_requester_id,
            replay_approver_id, replay_reason, tenant_id, client_id, create_time, update_time
            """ + " ";

    @Select("""
            SELECT """ + CANDIDATE_COLUMNS + """
                   CASE WHEN o.status='CLAIMED' THEN o.lease_until
                        WHEN o.status='PENDING' THEN COALESCE(o.next_retry_at,o.create_time,0)
                        ELSE o.next_retry_at END AS eligibleAt,
                   o.version AS outboxVersion, o.status AS outboxStatus
            FROM agent_outbox_event o
            WHERE """ + EXACT_SCOPE + """
              AND """ + REQUIRED_V2 + """
              AND ((o.status='PENDING' AND (o.next_retry_at IS NULL OR o.next_retry_at<=#{now}))
                OR (o.status='RETRY' AND o.next_retry_at IS NOT NULL AND o.next_retry_at<=#{now})
                OR (o.status='CLAIMED' AND o.lease_until IS NOT NULL AND o.lease_until<=#{now}))
              AND (o.delivery_id<=0 OR o.version>=9223372036854775806 OR NOT (
              """ + ASSOCIATED_DELIVERY + """
              ))
            ORDER BY eligibleAt ASC, o.id ASC LIMIT #{limit}
            """)
    List<AgentOutboxCandidate> selectCorruptCandidates(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("admissionEpoch") long admissionEpoch,
            @Param("moduleId") String moduleId, @Param("migrationEpoch") long migrationEpoch,
            @Param("deploymentId") String deploymentId, @Param("workerGeneration") long workerGeneration,
            @Param("now") long now, @Param("limit") int limit);

    @Select("""
            SELECT """ + CANDIDATE_COLUMNS + """
                   CASE WHEN o.status='PENDING' THEN COALESCE(o.next_retry_at,o.create_time,0)
                        ELSE o.next_retry_at END AS eligibleAt,
                   o.version AS outboxVersion, o.status AS outboxStatus
            FROM agent_outbox_event o
            WHERE """ + EXACT_SCOPE + """
              AND """ + REQUIRED_V2 + """
              AND ((o.status='PENDING' AND (o.next_retry_at IS NULL OR o.next_retry_at<=#{now}))
                OR (o.status='RETRY' AND o.next_retry_at IS NOT NULL AND o.next_retry_at<=#{now}))
              AND o.delivery_id>0 AND o.version<9223372036854775806
              AND """ + ASSOCIATED_DELIVERY + """
            ORDER BY eligibleAt ASC, o.id ASC LIMIT #{limit}
            """)
    List<AgentOutboxCandidate> selectDueCandidates(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("admissionEpoch") long admissionEpoch,
            @Param("moduleId") String moduleId, @Param("migrationEpoch") long migrationEpoch,
            @Param("deploymentId") String deploymentId, @Param("workerGeneration") long workerGeneration,
            @Param("now") long now, @Param("limit") int limit);

    @Select("""
            SELECT """ + CANDIDATE_COLUMNS + """
                   o.lease_until AS eligibleAt, o.version AS outboxVersion, o.status AS outboxStatus
            FROM agent_outbox_event o
            WHERE """ + EXACT_SCOPE + """
              AND """ + REQUIRED_V2 + """
              AND o.status='CLAIMED' AND o.lease_until IS NOT NULL AND o.lease_until<=#{now}
              AND o.delivery_id>0 AND o.version<9223372036854775806
              AND """ + ASSOCIATED_DELIVERY + """
            ORDER BY o.lease_until ASC, o.id ASC LIMIT #{limit}
            """)
    List<AgentOutboxCandidate> selectStaleCandidates(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("admissionEpoch") long admissionEpoch,
            @Param("moduleId") String moduleId, @Param("migrationEpoch") long migrationEpoch,
            @Param("deploymentId") String deploymentId, @Param("workerGeneration") long workerGeneration,
            @Param("now") long now, @Param("limit") int limit);

    @Select("""
            SELECT """ + DELIVERY_COLUMNS + """
            FROM agent_command_delivery
            WHERE tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
              AND execution_pool_id=#{executionPoolId} AND protocol_version=2
              AND admission_epoch=#{admissionEpoch} AND module_id=#{moduleId}
              AND migration_epoch=#{migrationEpoch}
            """ + EXACT_ROW_SCOPE + """
              AND id=#{deliveryId}
            LIMIT 1 FOR UPDATE
            """)
    AgentCommandDeliveryEntity selectDeliveryForUpdate(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("admissionEpoch") long admissionEpoch,
            @Param("moduleId") String moduleId, @Param("migrationEpoch") long migrationEpoch,
            @Param("deliveryId") long deliveryId);

    @Select("""
            SELECT """ + OUTBOX_COLUMNS + """
            FROM agent_outbox_event
            WHERE tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
              AND execution_pool_id=#{executionPoolId} AND protocol_version=2
              AND admission_epoch=#{admissionEpoch} AND module_id=#{moduleId}
              AND migration_epoch=#{migrationEpoch}
            """ + EXACT_ROW_SCOPE + """
              AND id=#{outboxId}
            LIMIT 1 FOR UPDATE
            """)
    AgentOutboxEventEntity selectOutboxForUpdate(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("admissionEpoch") long admissionEpoch,
            @Param("moduleId") String moduleId, @Param("migrationEpoch") long migrationEpoch,
            @Param("outboxId") long outboxId);

    @Select("""
            SELECT """ + OUTBOX_COLUMNS + """
            FROM agent_outbox_event
            WHERE tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
              AND execution_pool_id=#{executionPoolId} AND protocol_version=2
              AND admission_epoch=#{admissionEpoch} AND module_id=#{moduleId}
              AND migration_epoch=#{migrationEpoch} AND delivery_id=#{deliveryId}
            """ + EXACT_ROW_SCOPE + """
              AND active_attempt=#{previousAttempt}
            ORDER BY id ASC LIMIT 2 FOR UPDATE
            """)
    List<AgentOutboxEventEntity> selectPreviousAttemptOutboxesForUpdate(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("admissionEpoch") long admissionEpoch,
            @Param("moduleId") String moduleId, @Param("migrationEpoch") long migrationEpoch,
            @Param("deliveryId") long deliveryId, @Param("previousAttempt") int previousAttempt);

    @Select("""
            SELECT """ + OUTBOX_COLUMNS + """
            FROM agent_outbox_event
            WHERE tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
              AND execution_pool_id=#{executionPoolId} AND protocol_version=2
              AND admission_epoch=#{admissionEpoch} AND module_id=#{moduleId}
              AND migration_epoch=#{migrationEpoch} AND id=#{candidate.outboxId}
            """ + EXACT_ROW_SCOPE + """
              AND delivery_id=#{candidate.deliveryId} AND version=#{candidate.outboxVersion}
              AND status=#{candidate.outboxStatus}
              AND ((status IN ('PENDING','RETRY') AND claimed_deployment_id IS NULL
                    AND claimed_worker_generation IS NULL)
                   OR (status='CLAIMED' AND claimed_deployment_id=#{deploymentId}
                    AND CAST(claimed_deployment_id AS BINARY)=CAST(#{deploymentId} AS BINARY)
                    AND OCTET_LENGTH(claimed_deployment_id)=OCTET_LENGTH(#{deploymentId})
                    AND claimed_worker_generation=#{workerGeneration}))
            LIMIT 1 FOR UPDATE
            """)
    AgentOutboxEventEntity selectOutboxForQuarantine(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("admissionEpoch") long admissionEpoch,
            @Param("moduleId") String moduleId, @Param("migrationEpoch") long migrationEpoch,
            @Param("deploymentId") String deploymentId, @Param("workerGeneration") long workerGeneration,
            @Param("candidate") AgentOutboxCandidate candidate);

    @Update("""
            UPDATE agent_command_delivery SET lease_owner=#{leaseOwner}, lease_until=#{leaseUntil},
                claimed_deployment_id=#{deploymentId}, claimed_worker_generation=#{workerGeneration},
                last_error=#{lastError}, version=version+1, update_time=#{now}
            WHERE tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
              AND execution_pool_id=#{executionPoolId} AND protocol_version=2
              AND admission_epoch=#{admissionEpoch} AND module_id=#{moduleId}
              AND migration_epoch=#{migrationEpoch} AND id=#{delivery.id} AND version=#{delivery.version}
            """ + EXACT_ROW_SCOPE + """
              AND active_attempt=#{delivery.activeAttempt} AND status=#{delivery.status}
              AND ((claimed_deployment_id IS NULL AND claimed_worker_generation IS NULL)
                   OR (claimed_deployment_id=#{deploymentId}
                       AND CAST(claimed_deployment_id AS BINARY)=CAST(#{deploymentId} AS BINARY)
                       AND OCTET_LENGTH(claimed_deployment_id)=OCTET_LENGTH(#{deploymentId})
                       AND claimed_worker_generation=#{workerGeneration}))
            """)
    int claimDelivery(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("admissionEpoch") long admissionEpoch,
            @Param("moduleId") String moduleId, @Param("migrationEpoch") long migrationEpoch,
            @Param("deploymentId") String deploymentId, @Param("workerGeneration") long workerGeneration,
            @Param("delivery") AgentCommandDeliveryEntity delivery, @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") long leaseUntil, @Param("lastError") String lastError, @Param("now") long now);

    @Update("""
            UPDATE agent_outbox_event SET status='CLAIMED', attempt_count=attempt_count+1,
                next_retry_at=NULL, lease_owner=#{leaseOwner}, lease_until=#{leaseUntil},
                claimed_deployment_id=#{deploymentId}, claimed_worker_generation=#{workerGeneration},
                publisher_confirm_status='PENDING', confirmed_at=NULL, confirm_error=NULL,
                mandatory_return_status='PENDING', returned_at=NULL, return_reply_code=NULL,
                return_reply_text=NULL, published_at=NULL, last_error=#{lastError},
                version=version+1, update_time=#{now}
            WHERE tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
              AND execution_pool_id=#{executionPoolId} AND protocol_version=2
              AND admission_epoch=#{admissionEpoch} AND module_id=#{moduleId}
              AND migration_epoch=#{migrationEpoch} AND id=#{outbox.id} AND version=#{outbox.version}
            """ + EXACT_ROW_SCOPE + """
              AND active_attempt=#{outbox.activeAttempt} AND attempt_count=#{outbox.attemptCount}
              AND status=#{outbox.status}
              AND ((claimed_deployment_id IS NULL AND claimed_worker_generation IS NULL)
                   OR (claimed_deployment_id=#{deploymentId}
                       AND CAST(claimed_deployment_id AS BINARY)=CAST(#{deploymentId} AS BINARY)
                       AND OCTET_LENGTH(claimed_deployment_id)=OCTET_LENGTH(#{deploymentId})
                       AND claimed_worker_generation=#{workerGeneration}))
            """)
    int claimOutbox(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("admissionEpoch") long admissionEpoch,
            @Param("moduleId") String moduleId, @Param("migrationEpoch") long migrationEpoch,
            @Param("deploymentId") String deploymentId, @Param("workerGeneration") long workerGeneration,
            @Param("outbox") AgentOutboxEventEntity outbox, @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") long leaseUntil, @Param("lastError") String lastError, @Param("now") long now);

    @Update("""
            UPDATE agent_command_delivery SET status=#{newStatus}, next_retry_at=#{nextRetryAt},
                lease_owner=NULL, lease_until=NULL, claimed_deployment_id=NULL,
                claimed_worker_generation=NULL, last_error=#{lastError}, version=version+1, update_time=#{now}
            WHERE tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
              AND execution_pool_id=#{executionPoolId} AND protocol_version=2
              AND admission_epoch=#{admissionEpoch} AND module_id=#{moduleId}
              AND migration_epoch=#{migrationEpoch} AND id=#{delivery.id} AND version=#{delivery.version}
            """ + EXACT_ROW_SCOPE + """
              AND active_attempt=#{delivery.activeAttempt} AND status=#{delivery.status}
              AND ((claimed_deployment_id IS NULL AND claimed_worker_generation IS NULL)
                   OR (claimed_deployment_id=#{deploymentId}
                       AND CAST(claimed_deployment_id AS BINARY)=CAST(#{deploymentId} AS BINARY)
                       AND OCTET_LENGTH(claimed_deployment_id)=OCTET_LENGTH(#{deploymentId})
                       AND claimed_worker_generation=#{workerGeneration}))
            """)
    int disposeDelivery(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("admissionEpoch") long admissionEpoch,
            @Param("moduleId") String moduleId, @Param("migrationEpoch") long migrationEpoch,
            @Param("deploymentId") String deploymentId, @Param("workerGeneration") long workerGeneration,
            @Param("delivery") AgentCommandDeliveryEntity delivery, @Param("newStatus") String newStatus,
            @Param("nextRetryAt") Long nextRetryAt, @Param("lastError") String lastError,
            @Param("now") long now);

    @Update("""
            UPDATE agent_outbox_event SET status=#{newStatus}, next_retry_at=#{nextRetryAt},
                lease_owner=NULL, lease_until=NULL, claimed_deployment_id=NULL,
                claimed_worker_generation=NULL, publisher_confirm_status=#{confirmStatus},
                confirmed_at=#{confirmedAt}, confirm_error=#{confirmError},
                mandatory_return_status=#{returnStatus}, returned_at=#{returnedAt},
                return_reply_code=#{returnReplyCode}, return_reply_text=#{returnReplyText},
                published_at=#{publishedAt}, last_error=#{lastError}, version=version+1, update_time=#{now}
            WHERE tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
              AND execution_pool_id=#{executionPoolId} AND protocol_version=2
              AND admission_epoch=#{admissionEpoch} AND module_id=#{moduleId}
              AND migration_epoch=#{migrationEpoch} AND id=#{outbox.id} AND version=#{outbox.version}
            """ + EXACT_ROW_SCOPE + """
              AND active_attempt=#{outbox.activeAttempt} AND attempt_count=#{outbox.attemptCount}
              AND status=#{outbox.status}
              AND ((claimed_deployment_id IS NULL AND claimed_worker_generation IS NULL)
                   OR (claimed_deployment_id=#{deploymentId}
                       AND CAST(claimed_deployment_id AS BINARY)=CAST(#{deploymentId} AS BINARY)
                       AND OCTET_LENGTH(claimed_deployment_id)=OCTET_LENGTH(#{deploymentId})
                       AND claimed_worker_generation=#{workerGeneration}))
            """)
    int disposeOutbox(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("admissionEpoch") long admissionEpoch,
            @Param("moduleId") String moduleId, @Param("migrationEpoch") long migrationEpoch,
            @Param("deploymentId") String deploymentId, @Param("workerGeneration") long workerGeneration,
            @Param("outbox") AgentOutboxEventEntity outbox, @Param("newStatus") String newStatus,
            @Param("nextRetryAt") Long nextRetryAt, @Param("confirmStatus") String confirmStatus,
            @Param("confirmedAt") Long confirmedAt, @Param("confirmError") String confirmError,
            @Param("returnStatus") String returnStatus, @Param("returnedAt") Long returnedAt,
            @Param("returnReplyCode") Integer returnReplyCode, @Param("returnReplyText") String returnReplyText,
            @Param("publishedAt") Long publishedAt, @Param("lastError") String lastError, @Param("now") long now);

    @Update("""
            UPDATE agent_command_delivery SET status='DEAD', next_retry_at=NULL, lease_owner=NULL,
                lease_until=NULL, claimed_deployment_id=NULL, claimed_worker_generation=NULL,
                last_error=#{errorCode}, version=version+1, update_time=#{now}
            WHERE tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
              AND execution_pool_id=#{executionPoolId} AND protocol_version=2
              AND admission_epoch=#{admissionEpoch} AND module_id=#{moduleId}
              AND migration_epoch=#{migrationEpoch} AND id=#{delivery.id} AND version=#{delivery.version}
            """ + EXACT_ROW_SCOPE + """
              AND status=#{delivery.status}
              AND ((claimed_deployment_id IS NULL AND claimed_worker_generation IS NULL)
                   OR (claimed_deployment_id=#{deploymentId} AND claimed_worker_generation=#{workerGeneration}
                       AND CAST(claimed_deployment_id AS BINARY)=CAST(#{deploymentId} AS BINARY)
                       AND OCTET_LENGTH(claimed_deployment_id)=OCTET_LENGTH(#{deploymentId})))
            """)
    int quarantineDelivery(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("admissionEpoch") long admissionEpoch,
            @Param("moduleId") String moduleId, @Param("migrationEpoch") long migrationEpoch,
            @Param("deploymentId") String deploymentId, @Param("workerGeneration") long workerGeneration,
            @Param("delivery") AgentCommandDeliveryEntity delivery, @Param("errorCode") String errorCode,
            @Param("now") long now);

    @Update("""
            UPDATE agent_outbox_event SET status='DEAD', next_retry_at=NULL, lease_owner=NULL,
                lease_until=NULL, claimed_deployment_id=NULL, claimed_worker_generation=NULL,
                publisher_confirm_status='NONE', confirmed_at=NULL, confirm_error=NULL,
                mandatory_return_status='NONE', returned_at=NULL, return_reply_code=NULL,
                return_reply_text=NULL, published_at=NULL, last_error=#{errorCode},
                version=version+1, update_time=#{now}
            WHERE tenant_id=#{tenantId} AND data_domain_id=#{dataDomainId}
              AND execution_pool_id=#{executionPoolId} AND protocol_version=2
              AND admission_epoch=#{admissionEpoch} AND module_id=#{moduleId}
              AND migration_epoch=#{migrationEpoch} AND id=#{outbox.id} AND version=#{outbox.version}
            """ + EXACT_ROW_SCOPE + """
              AND status=#{outbox.status}
              AND ((claimed_deployment_id IS NULL AND claimed_worker_generation IS NULL)
                   OR (claimed_deployment_id=#{deploymentId} AND claimed_worker_generation=#{workerGeneration}
                       AND CAST(claimed_deployment_id AS BINARY)=CAST(#{deploymentId} AS BINARY)
                       AND OCTET_LENGTH(claimed_deployment_id)=OCTET_LENGTH(#{deploymentId})))
            """)
    int quarantineOutbox(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("admissionEpoch") long admissionEpoch,
            @Param("moduleId") String moduleId, @Param("migrationEpoch") long migrationEpoch,
            @Param("deploymentId") String deploymentId, @Param("workerGeneration") long workerGeneration,
            @Param("outbox") AgentOutboxEventEntity outbox, @Param("errorCode") String errorCode,
            @Param("now") long now);
}
