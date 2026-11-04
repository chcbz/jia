package cn.jia.economy.mapper;
import cn.jia.economy.entity.skill.*;
import org.apache.ibatis.annotations.*;

/** Only purchase quotes, orders and trade receipts; no skill lifecycle writes. */
public interface SkillTradeMapper {
    String EXACT_SCOPE = " tenant_id=#{tenantId} AND client_id=#{clientId}"
            + " AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})"
            + " AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId}) ";
    String S = EXACT_SCOPE;


    @Insert("""
            INSERT INTO economy_skill_purchase_quote(
                quote_id,actor_type,actor_id,idempotency_key,request_hash,product_version_id,
                target_agent_id,expected_agent_version,expected_price_micro,
                approved_permissions_manifest,approved_permissions_sha256,deployment_restriction,
                expires_at,tenant_id,client_id,create_time)
            VALUES(
                #{quoteId},#{actorType},#{actorId},#{idempotencyKey},#{requestHash},#{productVersionId},
                #{targetAgentId},#{expectedAgentVersion},#{expectedPriceMicro},
                #{approvedPermissionsManifest},#{approvedPermissionsSha256},#{deploymentRestriction},
                #{expiresAt},#{tenantId},#{clientId},#{createTime})
            """)
    int insertPurchaseQuote(SkillPurchaseQuoteEntity quote);

    @Select("""
            SELECT id,quote_id,actor_type,actor_id,idempotency_key,request_hash,product_version_id,
                   target_agent_id,expected_agent_version,expected_price_micro,
                   approved_permissions_manifest,approved_permissions_sha256,deployment_restriction,
                   expires_at,tenant_id,client_id,create_time
            FROM economy_skill_purchase_quote
            WHERE """ + EXACT_SCOPE + """
              AND actor_type=#{actorType} AND actor_id=#{actorId} AND idempotency_key=#{idempotencyKey}
              AND OCTET_LENGTH(actor_type)=OCTET_LENGTH(#{actorType})
              AND OCTET_LENGTH(actor_id)=OCTET_LENGTH(#{actorId})
              AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
            LIMIT 1 FOR UPDATE
            """)
    SkillPurchaseQuoteEntity selectPurchaseQuoteByActorKeyForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("actorType") String actorType,
            @Param("actorId") String actorId,
            @Param("idempotencyKey") byte[] idempotencyKey);

    @Select("""
            SELECT id,quote_id,actor_type,actor_id,idempotency_key,request_hash,product_version_id,
                   target_agent_id,expected_agent_version,expected_price_micro,
                   approved_permissions_manifest,approved_permissions_sha256,deployment_restriction,
                   expires_at,tenant_id,client_id,create_time
            FROM economy_skill_purchase_quote
            WHERE """ + EXACT_SCOPE + """
              AND quote_id=#{quoteId} AND actor_type=#{actorType} AND actor_id=#{actorId}
              AND OCTET_LENGTH(quote_id)=OCTET_LENGTH(#{quoteId})
              AND OCTET_LENGTH(actor_type)=OCTET_LENGTH(#{actorType})
              AND OCTET_LENGTH(actor_id)=OCTET_LENGTH(#{actorId})
            LIMIT 1 FOR UPDATE
            """)
    SkillPurchaseQuoteEntity selectPurchaseQuoteForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("quoteId") String quoteId,
            @Param("actorType") String actorType,
            @Param("actorId") String actorId);

    @Insert("""
            INSERT INTO economy_skill_order(
                order_id,quote_id,product_version_id,target_agent_id,buyer_type,buyer_id,
                seller_type,seller_id,price_micro,expected_agent_version,permission_grant_version,
                approved_permissions_manifest,approved_permissions_sha256,escrow_id,reserve_transaction_id,
                capture_transaction_id,refund_transaction_id,status,version,tenant_id,client_id,
                held_at,installing_at,active_at,refunded_at,update_time)
            VALUES(
                #{orderId},#{quoteId},#{productVersionId},#{targetAgentId},#{buyerType},#{buyerId},
                #{sellerType},#{sellerId},#{priceMicro},#{expectedAgentVersion},#{permissionGrantVersion},
                #{approvedPermissionsManifest},#{approvedPermissionsSha256},#{escrowId},#{reserveTransactionId},
                #{captureTransactionId},#{refundTransactionId},#{status},#{version},#{tenantId},#{clientId},
                #{heldAt},#{installingAt},#{activeAt},#{refundedAt},#{updateTime})
            """)
    int insertOrder(SkillOrderEntity order);

    @Select("""
            SELECT id,order_id,quote_id,product_version_id,target_agent_id,buyer_type,buyer_id,
                   seller_type,seller_id,price_micro,expected_agent_version,permission_grant_version,
                   approved_permissions_manifest,approved_permissions_sha256,escrow_id,reserve_transaction_id,
                   capture_transaction_id,refund_transaction_id,status,version,tenant_id,client_id,
                   held_at,installing_at,active_at,refunded_at,update_time
            FROM economy_skill_order
            WHERE """ + EXACT_SCOPE + """
              AND order_id=#{orderId}
              AND OCTET_LENGTH(order_id)=OCTET_LENGTH(#{orderId})
            LIMIT 1 FOR UPDATE
            """)
    SkillOrderEntity selectOrderForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("orderId") String orderId);

    @Select("""
            SELECT id,order_id,quote_id,product_version_id,target_agent_id,buyer_type,buyer_id,
                   seller_type,seller_id,price_micro,expected_agent_version,permission_grant_version,
                   approved_permissions_manifest,approved_permissions_sha256,escrow_id,reserve_transaction_id,
                   capture_transaction_id,refund_transaction_id,status,version,tenant_id,client_id,
                   held_at,installing_at,active_at,refunded_at,update_time
            FROM economy_skill_order
            WHERE """ + EXACT_SCOPE + """
              AND order_id=#{orderId} AND buyer_type=#{buyerType} AND buyer_id=#{buyerId}
              AND OCTET_LENGTH(order_id)=OCTET_LENGTH(#{orderId})
              AND OCTET_LENGTH(buyer_type)=OCTET_LENGTH(#{buyerType})
              AND OCTET_LENGTH(buyer_id)=OCTET_LENGTH(#{buyerId})
            LIMIT 1
            """)
    SkillOrderEntity selectOrderByBuyer(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("buyerType") String buyerType,
            @Param("buyerId") String buyerId,
            @Param("orderId") String orderId);

    @Update("""
            UPDATE economy_skill_order
            SET status='INSTALLING',installing_at=#{now},version=version+1,update_time=#{now}
            WHERE """ + EXACT_SCOPE + """
              AND order_id=#{orderId} AND status='FUNDS_HELD' AND version=#{expectedVersion}
              AND OCTET_LENGTH(order_id)=OCTET_LENGTH(#{orderId})
            """)
    int markOrderInstalling(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("orderId") String orderId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") long now);

    @Update("""
            UPDATE economy_skill_order
            SET status='ACTIVE',capture_transaction_id=#{captureTransactionId},active_at=#{now},
                version=version+1,update_time=#{now}
            WHERE """ + EXACT_SCOPE + """
              AND order_id=#{orderId} AND status='INSTALLING' AND version=#{expectedVersion}
              AND ((price_micro=0 AND #{captureTransactionId} IS NULL)
                   OR (price_micro>0 AND #{captureTransactionId} IS NOT NULL))
              AND OCTET_LENGTH(order_id)=OCTET_LENGTH(#{orderId})
            """)
    int markOrderActive(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("orderId") String orderId,
            @Param("expectedVersion") long expectedVersion,
            @Param("captureTransactionId") String captureTransactionId,
            @Param("now") long now);

    @Update("""
            UPDATE economy_skill_order
            SET status='REFUNDED',refund_transaction_id=#{refundTransactionId},refunded_at=#{now},
                version=version+1,update_time=#{now}
            WHERE """ + EXACT_SCOPE + """
              AND order_id=#{orderId} AND status IN ('FUNDS_HELD','INSTALLING')
              AND version=#{expectedVersion} AND capture_transaction_id IS NULL
              AND ((price_micro=0 AND #{refundTransactionId} IS NULL)
                   OR (price_micro>0 AND #{refundTransactionId} IS NOT NULL))
              AND OCTET_LENGTH(order_id)=OCTET_LENGTH(#{orderId})
            """)
    int markOrderRefunded(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("orderId") String orderId,
            @Param("expectedVersion") long expectedVersion,
            @Param("refundTransactionId") String refundTransactionId,
            @Param("now") long now);

    @Insert("""
            INSERT INTO economy_skill_order_receipt(
                order_id,actor_type,actor_id,idempotency_key,request_hash,order_version,order_status,
                price_micro,permission_grant_version,approved_permissions_sha256,escrow_id,
                tenant_id,client_id,create_time)
            VALUES(
                #{orderId},#{actorType},#{actorId},#{idempotencyKey},#{requestHash},#{orderVersion},#{orderStatus},
                #{priceMicro},#{permissionGrantVersion},#{approvedPermissionsSha256},#{escrowId},
                #{tenantId},#{clientId},#{createTime})
            """)
    int insertOrderReceipt(SkillOrderReceiptEntity receipt);

    @Select("""
            SELECT id,order_id,actor_type,actor_id,idempotency_key,request_hash,order_version,order_status,
                   price_micro,permission_grant_version,approved_permissions_sha256,escrow_id,
                   tenant_id,client_id,create_time
            FROM economy_skill_order_receipt
            WHERE """ + EXACT_SCOPE + """
              AND actor_type=#{actorType} AND actor_id=#{actorId} AND idempotency_key=#{idempotencyKey}
              AND OCTET_LENGTH(actor_type)=OCTET_LENGTH(#{actorType})
              AND OCTET_LENGTH(actor_id)=OCTET_LENGTH(#{actorId})
              AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
            LIMIT 1 FOR UPDATE
            """)
    SkillOrderReceiptEntity selectOrderReceiptByActorKeyForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("actorType") String actorType,
            @Param("actorId") String actorId,
            @Param("idempotencyKey") byte[] idempotencyKey);
    @Select("SELECT * FROM economy_skill_order WHERE " + S + " AND order_id=#{orderId} AND OCTET_LENGTH(order_id)=OCTET_LENGTH(#{orderId})")
    SkillOrderEntity order(@Param("tenantId") String t,@Param("clientId") String c,@Param("orderId") String o);
    @Select("SELECT order_id FROM economy_skill_order WHERE " + S + " AND quote_id=#{quoteId} AND OCTET_LENGTH(quote_id)=OCTET_LENGTH(#{quoteId})")
    String orderForQuote(@Param("tenantId") String t,@Param("clientId") String c,@Param("quoteId") String q);
    @Select("SELECT COUNT(*) FROM economy_skill_order WHERE "+S+" AND target_agent_id=#{agentId} AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{agentId}) AND status IN ('FUNDS_HELD','INSTALLING')")
    int pendingInstalls(@Param("tenantId") String t,@Param("clientId") String c,@Param("agentId") String a);
}
