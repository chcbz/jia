package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentExecutionReportEntity;
import cn.jia.agent.entity.AgentExecutionReportHeadEntity;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentExecutionReportMapper {
    @Select("""
            SELECT id,command_id,owner_jiacn,task_id,work_item_id,target_agent_id,command_type,
                   command_payload,command_payload_hash,status,attempt_count,next_retry_at,
                   lease_owner,lease_until,active_message_id,active_attempt,expires_at,last_error,
                   version,replay_parent_message_id,replay_requester_id,replay_approver_id,
                   replay_reason,tenant_id,client_id,create_time,update_time
              FROM agent_command_delivery
             WHERE tenant_id='0' AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND command_id=#{commandId}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(command_id AS BINARY)=CAST(#{commandId} AS BINARY)
               AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{commandId})
             LIMIT 1 FOR UPDATE
            """)
    AgentCommandDeliveryEntity lockTransportDelivery(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("commandId") String commandId);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND target_agent_id=#{agentId}
               AND CONCAT('pwe_cmd_',SHA2(CONCAT('command',CHAR(10),execution_id),256))=#{commandId}
               AND CONCAT('pwe_msg_',SHA2(CONCAT('message',CHAR(10),execution_id),256))=#{dispatchMessageId}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(target_agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
               AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{agentId})
             LIMIT 1 FOR UPDATE
            """)
    PersonalWorkspaceExecutionEntity lockExecutionByCommand(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("agentId") String agentId, @Param("commandId") String commandId,
            @Param("dispatchMessageId") String dispatchMessageId);

    String EXACT_SCOPE = """
            tenant_id='0' AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
            AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
            AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
            AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
            AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
            AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
            AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            """;

    @Select("""
            SELECT id,owner_jiacn,agent_id,runtime_instance_id,execution_ref,command_id,attempt,last_sequence,
                   committed_version,terminal_report_id,terminal_result_ref,
                   tenant_id,client_id,create_time,update_time
              FROM agent_execution_report_head
             WHERE """ + EXACT_SCOPE + """
               AND agent_id=#{agentId} AND command_id=#{commandId} AND attempt=#{attempt}
               AND CAST(agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
               AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId})
               AND CAST(command_id AS BINARY)=CAST(#{commandId} AS BINARY)
               AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{commandId})
             LIMIT 1 FOR UPDATE
            """)
    AgentExecutionReportHeadEntity lockHead(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("agentId") String agentId, @Param("commandId") String commandId,
            @Param("attempt") int attempt);

    @Insert("""
            INSERT INTO agent_execution_report_head
              (owner_jiacn,agent_id,runtime_instance_id,execution_ref,command_id,attempt,last_sequence,
               committed_version,terminal_report_id,terminal_result_ref,
               tenant_id,client_id,create_time,update_time)
            VALUES
              (#{ownerJiacn},#{agentId},#{runtimeInstanceId},#{executionRef},#{commandId},#{attempt},#{lastSequence},
               #{committedVersion},#{terminalReportId},#{terminalResultRef},
               #{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertHead(AgentExecutionReportHeadEntity head);

    @Select("""
            SELECT id,report_id,owner_jiacn,agent_id,runtime_instance_id,message_type,message_id,
                   command_id,dispatch_message_id,execution_ref,grant_revision,attempt,fencing_token,
                   sequence,occurred_at,semantic_hash,payload_json,result_ref,committed_version,
                   tenant_id,client_id,create_time,update_time
              FROM agent_execution_report_inbox
             WHERE """ + EXACT_SCOPE + """
               AND agent_id=#{agentId} AND report_id=#{reportId}
               AND CAST(agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
               AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId})
               AND CAST(report_id AS BINARY)=CAST(#{reportId} AS BINARY)
               AND OCTET_LENGTH(report_id)=OCTET_LENGTH(#{reportId})
             LIMIT 1 FOR UPDATE
            """)
    AgentExecutionReportEntity lockReport(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("agentId") String agentId, @Param("reportId") String reportId);

    @Insert("""
            INSERT INTO agent_execution_report_inbox
              (report_id,owner_jiacn,agent_id,runtime_instance_id,message_type,message_id,
               command_id,dispatch_message_id,execution_ref,grant_revision,attempt,fencing_token,
               sequence,occurred_at,semantic_hash,payload_json,result_ref,committed_version,
               tenant_id,client_id,create_time,update_time)
            VALUES
              (#{reportId},#{ownerJiacn},#{agentId},#{runtimeInstanceId},#{messageType},#{messageId},
               #{commandId},#{dispatchMessageId},#{executionRef},#{grantRevision},#{attempt},#{fencingToken},
               #{sequence},#{occurredAt},#{semanticHash},#{payloadJson},#{resultRef},#{committedVersion},
               #{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertReport(AgentExecutionReportEntity report);

    @Update("""
            UPDATE agent_execution_report_head
               SET last_sequence=#{nextSequence},committed_version=#{nextVersion},
                   terminal_report_id=#{terminalReportId},terminal_result_ref=#{terminalResultRef},
                   update_time=#{now}
             WHERE id=#{head.id} AND """ + EXACT_SCOPE + """
               AND agent_id=#{agentId} AND command_id=#{commandId} AND attempt=#{attempt}
               AND runtime_instance_id=#{head.runtimeInstanceId} AND execution_ref=#{head.executionRef}
               AND last_sequence=#{head.lastSequence} AND committed_version=#{head.committedVersion}
               AND CAST(agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
               AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId})
               AND CAST(command_id AS BINARY)=CAST(#{commandId} AS BINARY)
               AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{commandId})
               AND CAST(runtime_instance_id AS BINARY)=CAST(#{head.runtimeInstanceId} AS BINARY)
               AND OCTET_LENGTH(runtime_instance_id)=OCTET_LENGTH(#{head.runtimeInstanceId})
               AND CAST(execution_ref AS BINARY)=CAST(#{head.executionRef} AS BINARY)
               AND OCTET_LENGTH(execution_ref)=OCTET_LENGTH(#{head.executionRef})
            """)
    int advanceHead(@Param("head") AgentExecutionReportHeadEntity head,
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("commandId") String commandId, @Param("attempt") int attempt,
            @Param("nextSequence") long nextSequence, @Param("nextVersion") long nextVersion,
            @Param("terminalReportId") String terminalReportId,
            @Param("terminalResultRef") String terminalResultRef, @Param("now") long now);
}
