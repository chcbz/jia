package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentWorkItemReassignmentEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentWorkItemReassignmentMapper {
    String RECEIPT_COLUMNS = """
            id, reassignment_id, request_sha256, task_id, work_item_id,
            operator_subject, coordinator_agent_id, previous_agent_id, target_agent_id,
            source_command_id, command_id, message_id, outbox_event_id,
            expected_work_item_version, result_work_item_version, task_version,
            lease_fence_sha256, previous_lease_until, lease_until,
            attempt_count, max_attempts, tenant_id, client_id, create_time, update_time
            """;

    @Select("""
            SELECT """ + RECEIPT_COLUMNS + """
            FROM agent_work_item_reassignment
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
              AND task_id=#{taskId} AND work_item_id=#{workItemId}
              AND reassignment_id=#{reassignmentId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY)=CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{taskId})
              AND CAST(work_item_id AS BINARY)=CAST(#{workItemId} AS BINARY)
              AND OCTET_LENGTH(work_item_id)=OCTET_LENGTH(#{workItemId})
              AND CAST(reassignment_id AS BINARY)=CAST(#{reassignmentId} AS BINARY)
              AND OCTET_LENGTH(reassignment_id)=OCTET_LENGTH(#{reassignmentId})
            LIMIT 1 FOR UPDATE
            """)
    AgentWorkItemReassignmentEntity selectReceiptForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId, @Param("workItemId") String workItemId,
            @Param("reassignmentId") String reassignmentId);

    @Select("""
            SELECT """ + RECEIPT_COLUMNS + """
            FROM agent_work_item_reassignment
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
              AND task_id=#{taskId} AND work_item_id=#{workItemId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY)=CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{taskId})
              AND CAST(work_item_id AS BINARY)=CAST(#{workItemId} AS BINARY)
              AND OCTET_LENGTH(work_item_id)=OCTET_LENGTH(#{workItemId})
            ORDER BY id DESC LIMIT 1 FOR UPDATE
            """)
    AgentWorkItemReassignmentEntity selectLatestReceiptForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId, @Param("workItemId") String workItemId);

    @Select("""
            SELECT id, command_id, task_id, work_item_id, target_agent_id, command_type,
                   command_payload, command_payload_hash, status, attempt_count,
                   next_retry_at, lease_owner, lease_until, active_message_id, active_attempt,
                   expires_at, last_error, version, replay_parent_message_id,
                   replay_requester_id, replay_approver_id, replay_reason,
                   tenant_id, client_id, create_time, update_time
            FROM agent_command_delivery
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND command_id=#{commandId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(command_id AS BINARY)=CAST(#{commandId} AS BINARY)
              AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{commandId})
            LIMIT 1
            """)
    AgentCommandDeliveryEntity selectSourceCommand(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("commandId") String commandId);

    @Insert("""
            INSERT INTO agent_work_item_reassignment
              (reassignment_id, request_sha256, task_id, work_item_id,
               operator_subject, coordinator_agent_id, previous_agent_id, target_agent_id,
               source_command_id, command_id, message_id, outbox_event_id,
               expected_work_item_version, result_work_item_version, task_version,
               lease_fence_sha256, previous_lease_until, lease_until,
               attempt_count, max_attempts, tenant_id, client_id, create_time, update_time)
            VALUES
              (#{reassignmentId}, #{requestSha256}, #{taskId}, #{workItemId},
               #{operatorSubject}, #{coordinatorAgentId}, #{previousAgentId}, #{targetAgentId},
               #{sourceCommandId}, #{commandId}, #{messageId}, #{outboxEventId},
               #{expectedWorkItemVersion}, #{resultWorkItemVersion}, #{taskVersion},
               #{leaseFenceSha256}, #{previousLeaseUntil}, #{leaseUntil},
               #{attemptCount}, #{maxAttempts}, #{tenantId}, #{clientId},
               #{createTime}, #{updateTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertReceipt(AgentWorkItemReassignmentEntity receipt);
}
