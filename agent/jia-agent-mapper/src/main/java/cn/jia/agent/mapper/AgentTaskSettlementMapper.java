package cn.jia.agent.mapper;

import cn.jia.agent.entity.funding.AgentTaskClaimOperationEntity;
import cn.jia.agent.entity.funding.AgentTaskSettlementEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Root lock precedes all of these operations. Receipt rows are insert-only. */
public interface AgentTaskSettlementMapper {
    String SCOPE = AgentTaskFundingMapper.EXACT_SCOPE;
    String TASK = " AND task_id=#{taskId} AND CAST(task_id AS BINARY)=CAST(#{taskId} AS BINARY)"
            + " AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{taskId}) ";

    @Select("SELECT * FROM agent_task_bounty_settlement WHERE " + SCOPE + TASK + " FOR UPDATE")
    AgentTaskSettlementEntity findForUpdate(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId);

    @Select("SELECT * FROM agent_task_bounty_claim_operation WHERE " + SCOPE + TASK
            + " AND status='COMPLETED' ORDER BY id FOR UPDATE")
    List<AgentTaskClaimOperationEntity> acceptedClaims(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("taskId") String taskId);

    @Insert("""
            INSERT INTO agent_task_bounty_settlement
              (tenant_id,client_id,principal_type,principal_id,idempotency_key,request_hash,task_id,
               quote_id,agent_id,escrow_id,status,gross_micro,actual_compute_micro,platform_fee_micro,
               agent_payout_micro,refunded_micro,task_version,funding_version,escrow_version,settled_at,transaction_ids)
            VALUES (#{tenantId},#{clientId},#{principalType},#{principalId},#{idempotencyKey},#{requestHash},#{taskId},
               #{quoteId},#{agentId},#{escrowId},#{status},#{grossMicro},#{actualComputeMicro},#{platformFeeMicro},
               #{agentPayoutMicro},#{refundedMicro},#{taskVersion},#{fundingVersion},#{escrowVersion},#{settledAt},#{transactionIds})
            """)
    int insert(AgentTaskSettlementEntity receipt);

    @Update("UPDATE agent_task_funding SET funding_status='SETTLED',remaining_micro=0,"
            + "escrow_version=#{escrowVersion},version=version+1,update_time=#{now} WHERE " + SCOPE + TASK
            + " AND funding_status='FUNDS_HELD' AND version=#{version} AND remaining_micro=gross_bounty_amount_micro")
    int markSettled(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId, @Param("version") long version,
            @Param("escrowVersion") long escrowVersion, @Param("now") long now);
}
