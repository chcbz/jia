package cn.jia.agent.mapper;

import cn.jia.agent.entity.funding.AgentTaskClaimOperationEntity;
import cn.jia.agent.entity.funding.AgentTaskQuoteEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentTaskBountyQuoteMapper {
    String SCOPE = " tenant_id=#{tenantId} AND client_id=#{clientId}"
            + " AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY) AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)"
            + " AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})"
            + " AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId}) ";

    @Insert("""
            INSERT INTO agent_task_bounty_quote
              (quote_id,task_id,agent_id,principal_type,principal_id,idempotency_key,request_hash,
               task_version,price_book_version,task_input_hash,skill_set_hash,model_route_version,
               estimated_input_tokens,estimated_cached_input_tokens,estimated_output_tokens,
               estimated_reasoning_tokens,estimated_compute_micro,worst_compute_micro,
               platform_fee_micro,gross_allocation_micro,estimated_agent_payout_micro,
               worst_agent_payout_micro,minimum_accepted_payout_micro,budget_headroom_micro,
               verified_skill_match,advisory_ability_match,budget_covered,agent_ready,
               recommendation,reason_codes,status,expires_at,tenant_id,client_id,create_time,update_time)
            VALUES
              (#{quoteId},#{taskId},#{agentId},#{principalType},#{principalId},#{idempotencyKey},#{requestHash},
               #{taskVersion},#{priceBookVersion},#{taskInputHash},#{skillSetHash},#{modelRouteVersion},
               #{estimatedInputTokens},#{estimatedCachedInputTokens},#{estimatedOutputTokens},
               #{estimatedReasoningTokens},#{estimatedComputeMicro},#{worstComputeMicro},
               #{platformFeeMicro},#{grossAllocationMicro},#{estimatedAgentPayoutMicro},
               #{worstAgentPayoutMicro},#{minimumAcceptedPayoutMicro},#{budgetHeadroomMicro},
               #{verifiedSkillMatch},#{advisoryAbilityMatch},#{budgetCovered},#{agentReady},
               #{recommendation},#{reasonCodes},#{status},#{expiresAt},#{tenantId},#{clientId},
               #{createTime},#{updateTime})
            """)
    int insertQuote(AgentTaskQuoteEntity quote);

    @Select("SELECT * FROM agent_task_bounty_quote WHERE " + SCOPE
            + " AND principal_type=#{principalType} AND principal_id=#{principalId}"
            + " AND CAST(principal_id AS BINARY)=CAST(#{principalId} AS BINARY)"
            + " AND OCTET_LENGTH(principal_id)=OCTET_LENGTH(#{principalId})"
            + " AND idempotency_key=#{idempotencyKey} LIMIT 1 FOR UPDATE")
    AgentTaskQuoteEntity selectQuoteByActorKeyForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("principalType") String principalType, @Param("principalId") String principalId,
            @Param("idempotencyKey") byte[] idempotencyKey);

    @Select("SELECT * FROM agent_task_bounty_quote WHERE " + SCOPE
            + " AND task_id=#{taskId} AND CAST(task_id AS BINARY)=CAST(#{taskId} AS BINARY)"
            + " AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{taskId})"
            + " AND quote_id=#{quoteId} AND CAST(quote_id AS BINARY)=CAST(#{quoteId} AS BINARY)"
            + " AND OCTET_LENGTH(quote_id)=OCTET_LENGTH(#{quoteId}) LIMIT 1 FOR UPDATE")
    AgentTaskQuoteEntity selectQuoteForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId, @Param("quoteId") String quoteId);

    @Update("""
            UPDATE agent_task_bounty_quote
            SET status='CLAIMED',claimed_at=#{claimedAt},update_time=#{claimedAt}
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY) AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND task_id=#{taskId} AND quote_id=#{quoteId} AND status='OPEN'
            """)
    int markClaimed(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId, @Param("quoteId") String quoteId,
            @Param("claimedAt") long claimedAt);

    @Insert("""
            INSERT INTO agent_task_bounty_claim_operation
              (principal_type,principal_id,idempotency_key,request_hash,task_id,agent_id,quote_id,
               status,tenant_id,client_id,create_time,update_time)
            VALUES
              (#{principalType},#{principalId},#{idempotencyKey},#{requestHash},#{taskId},#{agentId},
               #{quoteId},#{status},#{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    int insertClaimOperation(AgentTaskClaimOperationEntity operation);

    @Select("SELECT * FROM agent_task_bounty_claim_operation WHERE " + SCOPE
            + " AND principal_type=#{principalType} AND principal_id=#{principalId}"
            + " AND CAST(principal_id AS BINARY)=CAST(#{principalId} AS BINARY)"
            + " AND OCTET_LENGTH(principal_id)=OCTET_LENGTH(#{principalId})"
            + " AND idempotency_key=#{idempotencyKey} LIMIT 1 FOR UPDATE")
    AgentTaskClaimOperationEntity selectClaimOperationForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("principalType") String principalType, @Param("principalId") String principalId,
            @Param("idempotencyKey") byte[] idempotencyKey);

    @Update("""
            UPDATE agent_task_bounty_claim_operation
            SET status='COMPLETED',receipt_task_version=#{taskVersion},claimed_at=#{claimedAt},
                update_time=#{claimedAt}
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY) AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND principal_type=#{principalType} AND principal_id=#{principalId}
              AND idempotency_key=#{idempotencyKey} AND status='POSTING'
            """)
    int completeClaimOperation(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("principalType") String principalType, @Param("principalId") String principalId,
            @Param("idempotencyKey") byte[] idempotencyKey,
            @Param("taskVersion") long taskVersion, @Param("claimedAt") long claimedAt);
}
