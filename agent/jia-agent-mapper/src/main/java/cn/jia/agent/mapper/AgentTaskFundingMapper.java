package cn.jia.agent.mapper;

import cn.jia.agent.entity.funding.AgentTaskFundingEntity;
import cn.jia.agent.entity.funding.AgentTaskFundingOperationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentTaskFundingMapper {
    String EXACT_SCOPE = " tenant_id=#{tenantId} AND client_id=#{clientId}"
            + " AND BINARY tenant_id=BINARY #{tenantId} AND BINARY client_id=BINARY #{clientId}"
            + " AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})"
            + " AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId}) ";

    @Insert("""
            INSERT INTO agent_task_funding_operation
                (principal_type,principal_id,idempotency_key,request_hash,task_id,status,
                 tenant_id,client_id,create_time,update_time)
            VALUES
                (#{principalType},#{principalId},#{idempotencyKey},#{requestHash},#{taskId},#{status},
                 #{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    int insertOperation(AgentTaskFundingOperationEntity operation);

    @Select("SELECT * FROM agent_task_funding_operation WHERE " + EXACT_SCOPE
            + " AND principal_type=#{principalType} AND principal_id=#{principalId}"
            + " AND BINARY principal_id=BINARY #{principalId}"
            + " AND OCTET_LENGTH(principal_id)=OCTET_LENGTH(#{principalId})"
            + " AND idempotency_key=#{idempotencyKey} LIMIT 1 FOR UPDATE")
    AgentTaskFundingOperationEntity selectOperationForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("principalType") String principalType, @Param("principalId") String principalId,
            @Param("idempotencyKey") byte[] idempotencyKey);

    @Update("UPDATE agent_task_funding_operation SET task_id=#{finalTaskId},update_time=#{now} WHERE "
            + EXACT_SCOPE + " AND task_id=#{reservedTaskId} AND status='POSTING'")
    int rekeyOperation(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("reservedTaskId") String reservedTaskId, @Param("finalTaskId") String finalTaskId,
            @Param("now") long now);

    @Update("""
            UPDATE agent_task_funding_operation
            SET status='COMPLETED',reserve_transaction_id=#{transactionId},receipt_task_version=0,
                receipt_created_at=#{createdAt},receipt_updated_at=#{updatedAt},update_time=#{updatedAt}
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
              AND BINARY tenant_id=BINARY #{tenantId} AND BINARY client_id=BINARY #{clientId}
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND task_id=#{taskId} AND status='POSTING'
            """)
    int completeOperation(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId, @Param("transactionId") String transactionId,
            @Param("createdAt") long createdAt, @Param("updatedAt") long updatedAt);

    @Insert("""
            INSERT INTO agent_task_funding
                (task_id,funding_mode,funding_status,payer_principal_type,payer_principal_id,
                 settlement_policy,gross_bounty_amount_micro,remaining_micro,required_skill_requirements,
                 version,tenant_id,client_id,create_time,update_time)
            VALUES
                (#{taskId},#{fundingMode},#{fundingStatus},#{payerPrincipalType},#{payerPrincipalId},
                 #{settlementPolicy},#{grossBountyAmountMicro},#{remainingMicro},#{requiredSkillRequirements},
                 #{version},#{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    int insertFunding(AgentTaskFundingEntity funding);

    @Select("SELECT * FROM agent_task_funding WHERE " + EXACT_SCOPE
            + " AND task_id=#{taskId} AND BINARY task_id=BINARY #{taskId}"
            + " AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{taskId}) LIMIT 1")
    AgentTaskFundingEntity selectFunding(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("taskId") String taskId);

    @Select("SELECT * FROM agent_task_funding WHERE " + EXACT_SCOPE
            + " AND task_id=#{taskId} AND BINARY task_id=BINARY #{taskId}"
            + " AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{taskId}) LIMIT 1 FOR UPDATE")
    AgentTaskFundingEntity selectFundingForUpdate(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("taskId") String taskId);

    @Update("""
            UPDATE agent_task_funding
            SET funding_status='FUNDS_HELD',escrow_id=#{escrowId},escrow_version=#{escrowVersion},
                reserve_transaction_id=#{transactionId},version=1,update_time=#{now}
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
              AND BINARY tenant_id=BINARY #{tenantId} AND BINARY client_id=BINARY #{clientId}
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND task_id=#{taskId} AND funding_status='RESERVING' AND version=0
            """)
    int markFundsHeld(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId, @Param("escrowId") String escrowId,
            @Param("escrowVersion") long escrowVersion, @Param("transactionId") String transactionId,
            @Param("now") long now);

    @Update("""
            UPDATE agent_task_funding
            SET funding_status='REFUNDED',remaining_micro=0,escrow_version=#{escrowVersion},
                cancel_idempotency_key=#{idempotencyKey},cancel_request_hash=#{requestHash},
                refund_transaction_id=#{transactionId},cancel_refunded_micro=#{refundedMicro},
                cancel_task_version=#{cancelTaskVersion},refunded_at=#{refundedAt},
                version=version+1,update_time=#{refundedAt}
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
              AND BINARY tenant_id=BINARY #{tenantId} AND BINARY client_id=BINARY #{clientId}
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND task_id=#{taskId} AND funding_status='FUNDS_HELD' AND version=#{expectedVersion}
            """)
    int markRefunded(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId, @Param("expectedVersion") long expectedVersion,
            @Param("escrowVersion") long escrowVersion, @Param("idempotencyKey") byte[] idempotencyKey,
            @Param("requestHash") byte[] requestHash, @Param("transactionId") String transactionId,
            @Param("refundedMicro") long refundedMicro, @Param("cancelTaskVersion") long cancelTaskVersion,
            @Param("refundedAt") long refundedAt);

    @Select("SELECT COUNT(*) FROM agent_task_member WHERE " + EXACT_SCOPE + " AND task_id=#{taskId}")
    int countMembers(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId);

    @Select("SELECT COUNT(*) FROM agent_task_work_item WHERE " + EXACT_SCOPE + " AND task_id=#{taskId}")
    int countWorkItems(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId);
}
