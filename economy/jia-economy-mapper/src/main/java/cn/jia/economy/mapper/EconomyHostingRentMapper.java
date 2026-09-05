package cn.jia.economy.mapper;

import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity;
import cn.jia.economy.entity.EconomyHostingRentPlanEntity;
import cn.jia.economy.entity.EconomyHostingRentQuoteEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Curated hosting-rent mapper. Plan/quote rows have no update/delete API. */
public interface EconomyHostingRentMapper {
    String EXACT_SCOPE = " tenant_id=#{tenantId} AND client_id=#{clientId}"
            + " AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})"
            + " AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId}) ";

    @Insert("""
            INSERT INTO economy_hosting_rent_plan(
                plan_id,plan_version,amount_micro,period_seconds,quote_ttl_seconds,
                currency,status,tenant_id,client_id,create_time)
            VALUES(#{planId},#{planVersion},#{amountMicro},#{periodSeconds},#{quoteTtlSeconds},
                #{currency},#{status},#{tenantId},#{clientId},#{createTime})
            """)
    int insertPlanVersion(EconomyHostingRentPlanEntity plan);

    @Select("""
            SELECT id,plan_id,plan_version,amount_micro,period_seconds,quote_ttl_seconds,
                   currency,status,tenant_id,client_id,create_time
            FROM economy_hosting_rent_plan
            WHERE """ + EXACT_SCOPE + """
              AND plan_id=#{planId} AND plan_version=#{planVersion}
              AND OCTET_LENGTH(plan_id)=OCTET_LENGTH(#{planId})
            LIMIT 1 FOR UPDATE
            """)
    EconomyHostingRentPlanEntity selectPlanForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("planId") String planId,
            @Param("planVersion") long planVersion);

    @Insert("""
            INSERT INTO economy_hosting_rent_quote(
                quote_id,quote_purpose,plan_id,plan_version,amount_micro,period_seconds,
                principal_type,principal_id,persona_code,agent_id,lease_id,expected_lease_version,
                idempotency_key,request_hash,expires_at,tenant_id,client_id,create_time)
            VALUES(#{quoteId},#{quotePurpose},#{planId},#{planVersion},#{amountMicro},#{periodSeconds},
                #{principalType},#{principalId},#{personaCode},#{agentId},#{leaseId},#{expectedLeaseVersion},
                #{idempotencyKey},#{requestHash},#{expiresAt},#{tenantId},#{clientId},#{createTime})
            """)
    int insertQuote(EconomyHostingRentQuoteEntity quote);

    @Select("""
            SELECT id,quote_id,quote_purpose,plan_id,plan_version,amount_micro,period_seconds,
                   principal_type,principal_id,persona_code,agent_id,lease_id,expected_lease_version,
                   idempotency_key,request_hash,expires_at,tenant_id,client_id,create_time
            FROM economy_hosting_rent_quote
            WHERE """ + EXACT_SCOPE + """
              AND principal_type=#{principalType} AND principal_id=#{principalId}
              AND idempotency_key=#{idempotencyKey}
              AND OCTET_LENGTH(principal_type)=OCTET_LENGTH(#{principalType})
              AND OCTET_LENGTH(principal_id)=OCTET_LENGTH(#{principalId})
              AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
            LIMIT 1 FOR UPDATE
            """)
    EconomyHostingRentQuoteEntity selectQuoteByActorKeyForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("principalType") String principalType,
            @Param("principalId") String principalId,
            @Param("idempotencyKey") byte[] idempotencyKey);

    @Select("""
            SELECT id,quote_id,quote_purpose,plan_id,plan_version,amount_micro,period_seconds,
                   principal_type,principal_id,persona_code,agent_id,lease_id,expected_lease_version,
                   idempotency_key,request_hash,expires_at,tenant_id,client_id,create_time
            FROM economy_hosting_rent_quote
            WHERE """ + EXACT_SCOPE + """
              AND quote_id=#{quoteId} AND OCTET_LENGTH(quote_id)=OCTET_LENGTH(#{quoteId})
            LIMIT 1 FOR UPDATE
            """)
    EconomyHostingRentQuoteEntity selectQuoteForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("quoteId") String quoteId);

    @Insert("""
            INSERT INTO economy_hosting_lease(
                lease_id,principal_type,principal_id,persona_code,agent_id,binding_id,
                plan_id,plan_version,amount_micro,period_seconds,status,paid_from,paid_through,
                latest_intent_id,version,tenant_id,client_id,create_time,update_time)
            VALUES(#{leaseId},#{principalType},#{principalId},#{personaCode},#{agentId},#{bindingId},
                #{planId},#{planVersion},#{amountMicro},#{periodSeconds},#{status},#{paidFrom},#{paidThrough},
                #{latestIntentId},#{version},#{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    int insertLease(EconomyHostingLeaseEntity lease);

    @Select("""
            SELECT id,lease_id,principal_type,principal_id,persona_code,agent_id,binding_id,
                   plan_id,plan_version,amount_micro,period_seconds,status,paid_from,paid_through,
                   latest_intent_id,version,tenant_id,client_id,create_time,update_time
            FROM economy_hosting_lease
            WHERE """ + EXACT_SCOPE + """
              AND lease_id=#{leaseId} AND OCTET_LENGTH(lease_id)=OCTET_LENGTH(#{leaseId})
            LIMIT 1 FOR UPDATE
            """)
    EconomyHostingLeaseEntity selectLeaseForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("leaseId") String leaseId);

    @Select("""
            SELECT id,lease_id,principal_type,principal_id,persona_code,agent_id,binding_id,
                   plan_id,plan_version,amount_micro,period_seconds,status,paid_from,paid_through,
                   latest_intent_id,version,tenant_id,client_id,create_time,update_time
            FROM economy_hosting_lease
            WHERE """ + EXACT_SCOPE + """
              AND live_slot=1 AND agent_id=#{agentId} AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId})
            LIMIT 1 FOR UPDATE
            """)
    EconomyHostingLeaseEntity selectLiveLeaseByAgentForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("agentId") String agentId);

    @Update("""
            UPDATE economy_hosting_lease
            SET status='ACTIVE',paid_from=#{paidFrom},paid_through=#{paidThrough},
                version=#{newVersion},update_time=#{now}
            WHERE id=#{lease.id} AND """ + EXACT_SCOPE + """
              AND lease_id=#{lease.leaseId} AND latest_intent_id=#{lease.latestIntentId}
              AND status='PROVISIONING' AND live_slot=1 AND paid_from IS NULL AND paid_through IS NULL
              AND version=#{lease.version}
              AND OCTET_LENGTH(lease_id)=OCTET_LENGTH(#{lease.leaseId})
              AND OCTET_LENGTH(latest_intent_id)=OCTET_LENGTH(#{lease.latestIntentId})
            """)
    int markLeaseActive(
            @Param("lease") EconomyHostingLeaseEntity lease,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("paidFrom") long paidFrom,
            @Param("paidThrough") long paidThrough,
            @Param("newVersion") long newVersion,
            @Param("now") long now);

    @Update("""
            UPDATE economy_hosting_lease
            SET status='REFUNDED',live_slot=NULL,version=#{newVersion},update_time=#{now}
            WHERE id=#{lease.id} AND """ + EXACT_SCOPE + """
              AND lease_id=#{lease.leaseId} AND latest_intent_id=#{lease.latestIntentId}
              AND status='PROVISIONING' AND live_slot=1 AND paid_from IS NULL AND paid_through IS NULL
              AND version=#{lease.version}
              AND OCTET_LENGTH(lease_id)=OCTET_LENGTH(#{lease.leaseId})
              AND OCTET_LENGTH(latest_intent_id)=OCTET_LENGTH(#{lease.latestIntentId})
            """)
    int markLeaseRefunded(
            @Param("lease") EconomyHostingLeaseEntity lease,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("newVersion") long newVersion,
            @Param("now") long now);

    @Insert("""
            INSERT INTO economy_hosting_provisioning_intent(
                intent_id,lease_id,quote_id,quote_purpose,principal_type,principal_id,
                persona_code,agent_id,amount_micro,period_seconds,status,
                reserve_idempotency_key,reserve_request_hash,reserve_transaction_id,reserved_at,escrow_version,
                capture_idempotency_key,capture_request_hash,capture_transaction_id,captured_at,
                refund_idempotency_key,refund_request_hash,refund_transaction_id,refunded_at,
                outcome_evidence_ref,service_ready_at,paid_from,paid_through,version,tenant_id,client_id,create_time,update_time)
            VALUES(#{intentId},#{leaseId},#{quoteId},#{quotePurpose},#{principalType},#{principalId},
                #{personaCode},#{agentId},#{amountMicro},#{periodSeconds},#{status},
                #{reserveIdempotencyKey},#{reserveRequestHash},#{reserveTransactionId},#{reservedAt},#{escrowVersion},
                #{captureIdempotencyKey},#{captureRequestHash},#{captureTransactionId},#{capturedAt},
                #{refundIdempotencyKey},#{refundRequestHash},#{refundTransactionId},#{refundedAt},
                #{outcomeEvidenceRef},#{serviceReadyAt},#{paidFrom},#{paidThrough},#{version},#{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    int insertIntent(EconomyHostingProvisioningIntentEntity intent);

    String INTENT_COLUMNS = " id,intent_id,lease_id,quote_id,quote_purpose,principal_type,principal_id,"
            + "persona_code,agent_id,amount_micro,period_seconds,status,"
            + "reserve_idempotency_key,reserve_request_hash,reserve_transaction_id,reserved_at,escrow_version,"
            + "capture_idempotency_key,capture_request_hash,capture_transaction_id,captured_at,"
            + "refund_idempotency_key,refund_request_hash,refund_transaction_id,refunded_at,"
            + "outcome_evidence_ref,service_ready_at,paid_from,paid_through,version,tenant_id,client_id,create_time,update_time ";

    @Select("SELECT " + INTENT_COLUMNS + " FROM economy_hosting_provisioning_intent WHERE "
            + EXACT_SCOPE + " AND quote_id=#{quoteId} AND OCTET_LENGTH(quote_id)=OCTET_LENGTH(#{quoteId})"
            + " LIMIT 1 FOR UPDATE")
    EconomyHostingProvisioningIntentEntity selectIntentByQuoteForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("quoteId") String quoteId);

    @Select("SELECT " + INTENT_COLUMNS + " FROM economy_hosting_provisioning_intent WHERE "
            + EXACT_SCOPE + " AND intent_id=#{intentId} AND OCTET_LENGTH(intent_id)=OCTET_LENGTH(#{intentId})"
            + " LIMIT 1 FOR UPDATE")
    EconomyHostingProvisioningIntentEntity selectIntentForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("intentId") String intentId);

    @Update("""
            UPDATE economy_hosting_provisioning_intent
            SET status='PROVISIONING_UNKNOWN',outcome_evidence_ref=#{evidenceRef},
                version=#{newVersion},update_time=#{now}
            WHERE id=#{intent.id} AND """ + EXACT_SCOPE + """
              AND intent_id=#{intent.intentId} AND status='FUNDS_RESERVED' AND version=#{intent.version}
              AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL
              AND OCTET_LENGTH(intent_id)=OCTET_LENGTH(#{intent.intentId})
            """)
    int markIntentProvisioningUnknown(
            @Param("intent") EconomyHostingProvisioningIntentEntity intent,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("evidenceRef") String evidenceRef,
            @Param("newVersion") long newVersion,
            @Param("now") long now);

    @Update("""
            UPDATE economy_hosting_provisioning_intent
            SET status='FAILED_NO_EFFECT',outcome_evidence_ref=#{evidenceRef},
                version=#{newVersion},update_time=#{now}
            WHERE id=#{intent.id} AND """ + EXACT_SCOPE + """
              AND intent_id=#{intent.intentId} AND status IN ('FUNDS_RESERVED','PROVISIONING_UNKNOWN')
              AND version=#{intent.version} AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL
              AND OCTET_LENGTH(intent_id)=OCTET_LENGTH(#{intent.intentId})
            """)
    int markIntentFailedNoEffect(
            @Param("intent") EconomyHostingProvisioningIntentEntity intent,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("evidenceRef") String evidenceRef,
            @Param("newVersion") long newVersion,
            @Param("now") long now);

    @Update("""
            UPDATE economy_hosting_provisioning_intent
            SET status='SERVICE_READY',outcome_evidence_ref=#{evidenceRef},service_ready_at=#{now},
                version=#{newVersion},update_time=#{now}
            WHERE id=#{intent.id} AND """ + EXACT_SCOPE + """
              AND intent_id=#{intent.intentId} AND status IN ('FUNDS_RESERVED','PROVISIONING_UNKNOWN')
              AND version=#{intent.version} AND service_ready_at IS NULL
              AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL
              AND OCTET_LENGTH(intent_id)=OCTET_LENGTH(#{intent.intentId})
            """)
    int markIntentServiceReady(
            @Param("intent") EconomyHostingProvisioningIntentEntity intent,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("evidenceRef") String evidenceRef,
            @Param("newVersion") long newVersion,
            @Param("now") long now);

    @Update("""
            UPDATE economy_hosting_provisioning_intent
            SET status='ACTIVE',paid_from=#{intent.paidFrom},paid_through=#{intent.paidThrough},
                capture_idempotency_key=#{idempotencyKey},
                capture_request_hash=#{requestHash},capture_transaction_id=#{transactionId},
                captured_at=#{occurredAt},escrow_version=#{escrowVersion},version=#{newVersion},update_time=#{occurredAt}
            WHERE id=#{intent.id} AND """ + EXACT_SCOPE + """
              AND intent_id=#{intent.intentId} AND status='SERVICE_READY' AND service_ready_at IS NOT NULL AND version=#{intent.version}
              AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL
              AND OCTET_LENGTH(intent_id)=OCTET_LENGTH(#{intent.intentId})
            """)
    int markIntentActive(
            @Param("intent") EconomyHostingProvisioningIntentEntity intent,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("idempotencyKey") byte[] idempotencyKey,
            @Param("requestHash") byte[] requestHash,
            @Param("transactionId") String transactionId,
            @Param("occurredAt") long occurredAt,
            @Param("escrowVersion") long escrowVersion,
            @Param("newVersion") long newVersion);

    @Update("""
            UPDATE economy_hosting_provisioning_intent
            SET status='REFUNDED',refund_idempotency_key=#{idempotencyKey},
                refund_request_hash=#{requestHash},refund_transaction_id=#{transactionId},
                refunded_at=#{occurredAt},escrow_version=#{escrowVersion},version=#{newVersion},update_time=#{occurredAt}
            WHERE id=#{intent.id} AND """ + EXACT_SCOPE + """
              AND intent_id=#{intent.intentId} AND status='FAILED_NO_EFFECT' AND version=#{intent.version}
              AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL
              AND OCTET_LENGTH(intent_id)=OCTET_LENGTH(#{intent.intentId})
            """)
    int markIntentRefunded(
            @Param("intent") EconomyHostingProvisioningIntentEntity intent,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("idempotencyKey") byte[] idempotencyKey,
            @Param("requestHash") byte[] requestHash,
            @Param("transactionId") String transactionId,
            @Param("occurredAt") long occurredAt,
            @Param("escrowVersion") long escrowVersion,
            @Param("newVersion") long newVersion);

    @Select("SELECT * FROM economy_hosting_lease WHERE " + EXACT_SCOPE
            + " AND agent_id=#{agentId} AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId})"
            + " ORDER BY id DESC LIMIT 1")
    EconomyHostingLeaseEntity selectLatestLease(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("agentId") String agentId);

    @Select("SELECT " + INTENT_COLUMNS + " FROM economy_hosting_provisioning_intent WHERE " + EXACT_SCOPE
            + " AND intent_id=#{intentId} AND OCTET_LENGTH(intent_id)=OCTET_LENGTH(#{intentId}) LIMIT 1")
    EconomyHostingProvisioningIntentEntity selectIntent(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("intentId") String intentId);

    @Select("SELECT " + INTENT_COLUMNS + " FROM economy_hosting_provisioning_intent"
            + " WHERE quote_purpose='INITIAL' AND status IN ('FUNDS_RESERVED','PROVISIONING_UNKNOWN','SERVICE_READY','FAILED_NO_EFFECT')"
            + " AND id>#{afterId} ORDER BY id LIMIT 100")
    java.util.List<EconomyHostingProvisioningIntentEntity> selectPendingIntents(@Param("afterId") long afterId);

    @Update("UPDATE economy_hosting_lease SET binding_id=#{bindingId} WHERE " + EXACT_SCOPE
            + " AND lease_id=#{leaseId} AND OCTET_LENGTH(lease_id)=OCTET_LENGTH(#{leaseId})"
            + " AND status='PROVISIONING' AND binding_id IS NULL AND live_slot=1")
    int attachBinding(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("leaseId") String leaseId, @Param("bindingId") String bindingId);

    @Update("""
            UPDATE economy_hosting_lease SET paid_from=#{paidFrom},paid_through=#{paidThrough},
                latest_intent_id=#{intentId},plan_id=#{quote.planId},plan_version=#{quote.planVersion},
                amount_micro=#{quote.amountMicro},period_seconds=#{quote.periodSeconds},
                version=version+1,update_time=#{now}
            WHERE """ + EXACT_SCOPE + """
              AND id=#{lease.id} AND lease_id=#{lease.leaseId} AND status='ACTIVE' AND live_slot=1
              AND version=#{lease.version} AND latest_intent_id=#{lease.latestIntentId}
              AND OCTET_LENGTH(lease_id)=OCTET_LENGTH(#{lease.leaseId})
              AND OCTET_LENGTH(latest_intent_id)=OCTET_LENGTH(#{lease.latestIntentId})
            """)
    int renewLease(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("lease") EconomyHostingLeaseEntity lease, @Param("quote") EconomyHostingRentQuoteEntity quote,
            @Param("intentId") String intentId, @Param("paidFrom") long paidFrom,
            @Param("paidThrough") long paidThrough, @Param("now") long now);
}
