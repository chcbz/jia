package cn.jia.economy.mapper;

import cn.jia.economy.entity.skill.SkillEntitlementEntity;
import cn.jia.economy.entity.skill.SkillInstallationEntity;
import cn.jia.economy.entity.skill.SkillOrderEntity;
import cn.jia.economy.entity.skill.SkillOrderReceiptEntity;
import cn.jia.economy.entity.skill.SkillProductEntity;
import cn.jia.economy.entity.skill.SkillProductVersionEntity;
import cn.jia.economy.entity.skill.SkillPurchaseQuoteEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Curated W07 persistence surface for W08 catalog publication and W09 purchase/install handling.
 * Every lookup is scoped by the exact tenant/client tuple; immutable rows expose no update/delete API.
 */
public interface EconomySkillMarketplaceMapper {
    String EXACT_SCOPE = " tenant_id=#{tenantId} AND client_id=#{clientId}"
            + " AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})"
            + " AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId}) ";

    @Insert("""
            INSERT INTO economy_skill_product(
                product_id,seller_type,seller_id,creator_agent_id,name,description,status,
                current_product_version_id,version,tenant_id,client_id,create_time,update_time)
            VALUES(
                #{productId},#{sellerType},#{sellerId},#{creatorAgentId},#{name},#{description},#{status},
                #{currentProductVersionId},#{version},#{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    int insertProduct(SkillProductEntity product);

    @Select("""
            SELECT id,product_id,seller_type,seller_id,creator_agent_id,name,description,status,
                   current_product_version_id,version,tenant_id,client_id,create_time,update_time
            FROM economy_skill_product
            WHERE """ + EXACT_SCOPE + """
              AND product_id=#{productId}
              AND OCTET_LENGTH(product_id)=OCTET_LENGTH(#{productId})
            LIMIT 1 FOR UPDATE
            """)
    SkillProductEntity selectProductForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("productId") String productId);

    @Select("""
            SELECT id,product_id,seller_type,seller_id,creator_agent_id,name,description,status,
                   current_product_version_id,version,tenant_id,client_id,create_time,update_time
            FROM economy_skill_product
            WHERE """ + EXACT_SCOPE + """
              AND status='PUBLISHED'
            ORDER BY name ASC,product_id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<SkillProductEntity> selectPublishedProducts(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    @Update("""
            UPDATE economy_skill_product
            SET status='PUBLISHED',current_product_version_id=#{productVersionId},
                version=version+1,update_time=#{now}
            WHERE """ + EXACT_SCOPE + """
              AND product_id=#{productId} AND version=#{expectedVersion}
              AND status IN ('DRAFT','PUBLISHED')
              AND OCTET_LENGTH(product_id)=OCTET_LENGTH(#{productId})
              AND EXISTS (
                  SELECT 1 FROM economy_skill_product_version v
                  WHERE v.tenant_id=#{tenantId} AND v.client_id=#{clientId}
                    AND v.product_id=#{productId} AND v.product_version_id=#{productVersionId}
                    AND v.review_status='APPROVED'
                    AND OCTET_LENGTH(v.tenant_id)=OCTET_LENGTH(#{tenantId})
                    AND OCTET_LENGTH(v.client_id)=OCTET_LENGTH(#{clientId})
                    AND OCTET_LENGTH(v.product_id)=OCTET_LENGTH(#{productId})
                    AND OCTET_LENGTH(v.product_version_id)=OCTET_LENGTH(#{productVersionId})
              )
            """)
    int publishProductVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("productId") String productId,
            @Param("productVersionId") String productVersionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") long now);

    @Insert("""
            INSERT INTO economy_skill_product_version(
                product_version_id,product_id,version_sequence,skill_key,skill_version,price_micro,
                package_sha256,package_size,approved_permissions_manifest,approved_permissions_sha256,
                deployment_restriction,review_status,tenant_id,client_id,create_time)
            VALUES(
                #{productVersionId},#{productId},#{versionSequence},#{skillKey},#{skillVersion},#{priceMicro},
                #{packageSha256},#{packageSize},#{approvedPermissionsManifest},#{approvedPermissionsSha256},
                #{deploymentRestriction},#{reviewStatus},#{tenantId},#{clientId},#{createTime})
            """)
    int insertProductVersion(SkillProductVersionEntity version);

    @Select("""
            SELECT id,product_version_id,product_id,version_sequence,skill_key,skill_version,price_micro,
                   package_sha256,package_size,approved_permissions_manifest,approved_permissions_sha256,
                   deployment_restriction,review_status,tenant_id,client_id,create_time
            FROM economy_skill_product_version
            WHERE """ + EXACT_SCOPE + """
              AND product_version_id=#{productVersionId} AND review_status='APPROVED'
              AND OCTET_LENGTH(product_version_id)=OCTET_LENGTH(#{productVersionId})
            LIMIT 1 FOR UPDATE
            """)
    SkillProductVersionEntity selectApprovedProductVersionForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("productVersionId") String productVersionId);

    @Select("""
            SELECT v.id,v.product_version_id,v.product_id,v.version_sequence,v.skill_key,v.skill_version,
                   v.price_micro,v.package_sha256,v.package_size,v.approved_permissions_manifest,
                   v.approved_permissions_sha256,v.deployment_restriction,v.review_status,
                   v.tenant_id,v.client_id,v.create_time
            FROM economy_skill_product_version v
            JOIN economy_skill_product p
              ON p.tenant_id=v.tenant_id AND p.client_id=v.client_id AND p.product_id=v.product_id
             AND OCTET_LENGTH(p.tenant_id)=OCTET_LENGTH(v.tenant_id)
             AND OCTET_LENGTH(p.client_id)=OCTET_LENGTH(v.client_id)
             AND OCTET_LENGTH(p.product_id)=OCTET_LENGTH(v.product_id)
            WHERE v.tenant_id=#{tenantId} AND v.client_id=#{clientId}
              AND OCTET_LENGTH(v.tenant_id)=OCTET_LENGTH(#{tenantId})
              AND OCTET_LENGTH(v.client_id)=OCTET_LENGTH(#{clientId})
              AND v.product_version_id=#{productVersionId}
              AND OCTET_LENGTH(v.product_version_id)=OCTET_LENGTH(#{productVersionId})
              AND v.review_status='APPROVED' AND p.status='PUBLISHED'
              AND p.current_product_version_id=v.product_version_id
              AND OCTET_LENGTH(p.current_product_version_id)=OCTET_LENGTH(v.product_version_id)
            LIMIT 1
            """)
    SkillProductVersionEntity selectPurchasableProductVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("productVersionId") String productVersionId);

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

    @Insert("""
            INSERT INTO economy_skill_installation(
                installation_id,order_id,product_version_id,target_agent_id,schema_version,message_type,
                message_id,request_id,command_type,command_id,attempt,fencing_token,delivery_epoch,
                skill_key,skill_version,package_size,package_sha256,download_path,status,failure_code,
                version,tenant_id,client_id,create_time,started_at,installed_at,failed_at,update_time)
            VALUES(
                #{installationId},#{orderId},#{productVersionId},#{targetAgentId},#{schemaVersion},#{messageType},
                #{messageId},#{requestId},#{commandType},#{commandId},#{attempt},#{fencingToken},#{deliveryEpoch},
                #{skillKey},#{skillVersion},#{packageSize},#{packageSha256},#{downloadPath},#{status},#{failureCode},
                #{version},#{tenantId},#{clientId},#{createTime},#{startedAt},#{installedAt},#{failedAt},#{updateTime})
            """)
    int insertInstallation(SkillInstallationEntity installation);

    @Select("""
            SELECT id,installation_id,order_id,product_version_id,target_agent_id,schema_version,message_type,
                   message_id,request_id,command_type,command_id,attempt,fencing_token,delivery_epoch,
                   skill_key,skill_version,package_size,package_sha256,download_path,status,failure_code,
                   version,tenant_id,client_id,create_time,started_at,installed_at,failed_at,update_time
            FROM economy_skill_installation
            WHERE """ + EXACT_SCOPE + """
              AND target_agent_id=#{targetAgentId} AND installation_id=#{installationId}
              AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{targetAgentId})
              AND OCTET_LENGTH(installation_id)=OCTET_LENGTH(#{installationId})
            LIMIT 1 FOR UPDATE
            """)
    SkillInstallationEntity selectInstallationForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("targetAgentId") String targetAgentId,
            @Param("installationId") String installationId);

    @Update("""
            UPDATE economy_skill_installation
            SET status='INSTALLING',started_at=#{now},version=version+1,update_time=#{now}
            WHERE """ + EXACT_SCOPE + """
              AND installation_id=#{installationId} AND target_agent_id=#{targetAgentId}
              AND command_id=#{commandId} AND attempt=#{attempt} AND fencing_token=#{fencingToken}
              AND delivery_epoch=#{deliveryEpoch} AND status='REQUESTED' AND version=#{expectedVersion}
              AND OCTET_LENGTH(installation_id)=OCTET_LENGTH(#{installationId})
              AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{targetAgentId})
              AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{commandId})
            """)
    int markInstallationInstalling(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("targetAgentId") String targetAgentId,
            @Param("installationId") String installationId,
            @Param("commandId") String commandId,
            @Param("attempt") int attempt,
            @Param("fencingToken") long fencingToken,
            @Param("deliveryEpoch") long deliveryEpoch,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") long now);

    @Update("""
            UPDATE economy_skill_installation
            SET status='SUCCEEDED',installed_at=#{installedAt},version=version+1,update_time=#{installedAt}
            WHERE """ + EXACT_SCOPE + """
              AND installation_id=#{installationId} AND target_agent_id=#{targetAgentId}
              AND command_id=#{commandId} AND attempt=#{attempt} AND fencing_token=#{fencingToken}
              AND delivery_epoch=#{deliveryEpoch} AND status='INSTALLING' AND version=#{expectedVersion}
              AND package_sha256=#{packageSha256}
              AND OCTET_LENGTH(installation_id)=OCTET_LENGTH(#{installationId})
              AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{targetAgentId})
              AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{commandId})
            """)
    int markInstallationSucceeded(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("targetAgentId") String targetAgentId,
            @Param("installationId") String installationId,
            @Param("commandId") String commandId,
            @Param("attempt") int attempt,
            @Param("fencingToken") long fencingToken,
            @Param("deliveryEpoch") long deliveryEpoch,
            @Param("expectedVersion") long expectedVersion,
            @Param("packageSha256") byte[] packageSha256,
            @Param("installedAt") long installedAt);

    @Update("""
            UPDATE economy_skill_installation
            SET status='FAILED',failure_code=#{failureCode},failed_at=#{failedAt},
                version=version+1,update_time=#{failedAt}
            WHERE """ + EXACT_SCOPE + """
              AND installation_id=#{installationId} AND target_agent_id=#{targetAgentId}
              AND command_id=#{commandId} AND attempt=#{attempt} AND fencing_token=#{fencingToken}
              AND delivery_epoch=#{deliveryEpoch} AND status IN ('REQUESTED','INSTALLING')
              AND version=#{expectedVersion}
              AND OCTET_LENGTH(installation_id)=OCTET_LENGTH(#{installationId})
              AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{targetAgentId})
              AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{commandId})
            """)
    int markInstallationFailed(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("targetAgentId") String targetAgentId,
            @Param("installationId") String installationId,
            @Param("commandId") String commandId,
            @Param("attempt") int attempt,
            @Param("fencingToken") long fencingToken,
            @Param("deliveryEpoch") long deliveryEpoch,
            @Param("expectedVersion") long expectedVersion,
            @Param("failureCode") String failureCode,
            @Param("failedAt") long failedAt);

    @Insert("""
            INSERT INTO economy_skill_entitlement(
                entitlement_id,order_id,installation_id,product_version_id,target_agent_id,
                skill_key,skill_version,permission_grant_version,approved_permissions_manifest,
                approved_permissions_sha256,status,version,tenant_id,client_id,
                create_time,activated_at,failed_at,update_time)
            VALUES(
                #{entitlementId},#{orderId},#{installationId},#{productVersionId},#{targetAgentId},
                #{skillKey},#{skillVersion},#{permissionGrantVersion},#{approvedPermissionsManifest},
                #{approvedPermissionsSha256},#{status},#{version},#{tenantId},#{clientId},
                #{createTime},#{activatedAt},#{failedAt},#{updateTime})
            """)
    int insertEntitlement(SkillEntitlementEntity entitlement);

    @Select("""
            SELECT id,entitlement_id,order_id,installation_id,product_version_id,target_agent_id,
                   skill_key,skill_version,permission_grant_version,approved_permissions_manifest,
                   approved_permissions_sha256,status,version,tenant_id,client_id,
                   create_time,activated_at,failed_at,update_time
            FROM economy_skill_entitlement
            WHERE """ + EXACT_SCOPE + """
              AND target_agent_id=#{targetAgentId} AND skill_key=#{skillKey} AND skill_version=#{skillVersion}
              AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{targetAgentId})
              AND OCTET_LENGTH(skill_key)=OCTET_LENGTH(#{skillKey})
              AND OCTET_LENGTH(skill_version)=OCTET_LENGTH(#{skillVersion})
            LIMIT 1 FOR UPDATE
            """)
    SkillEntitlementEntity selectEntitlementByAgentSkillForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("targetAgentId") String targetAgentId,
            @Param("skillKey") String skillKey,
            @Param("skillVersion") String skillVersion);

    @Select("""
            SELECT id,entitlement_id,order_id,installation_id,product_version_id,target_agent_id,
                   skill_key,skill_version,permission_grant_version,approved_permissions_manifest,
                   approved_permissions_sha256,status,version,tenant_id,client_id,
                   create_time,activated_at,failed_at,update_time
            FROM economy_skill_entitlement
            WHERE """ + EXACT_SCOPE + """
              AND target_agent_id=#{targetAgentId}
              AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{targetAgentId})
            ORDER BY skill_key ASC,skill_version ASC,entitlement_id ASC
            """)
    List<SkillEntitlementEntity> selectEntitlementsByAgent(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("targetAgentId") String targetAgentId);

    @Update("""
            UPDATE economy_skill_entitlement
            SET status='ACTIVE',activated_at=#{activatedAt},version=version+1,update_time=#{activatedAt}
            WHERE """ + EXACT_SCOPE + """
              AND entitlement_id=#{entitlementId} AND installation_id=#{installationId}
              AND target_agent_id=#{targetAgentId} AND status='PENDING_INSTALLATION'
              AND version=#{expectedVersion}
              AND OCTET_LENGTH(entitlement_id)=OCTET_LENGTH(#{entitlementId})
              AND OCTET_LENGTH(installation_id)=OCTET_LENGTH(#{installationId})
              AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{targetAgentId})
            """)
    int markEntitlementActive(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("targetAgentId") String targetAgentId,
            @Param("entitlementId") String entitlementId,
            @Param("installationId") String installationId,
            @Param("expectedVersion") long expectedVersion,
            @Param("activatedAt") long activatedAt);

    @Update("""
            UPDATE economy_skill_entitlement
            SET status='FAILED',failed_at=#{failedAt},version=version+1,update_time=#{failedAt}
            WHERE """ + EXACT_SCOPE + """
              AND entitlement_id=#{entitlementId} AND installation_id=#{installationId}
              AND target_agent_id=#{targetAgentId} AND status='PENDING_INSTALLATION'
              AND version=#{expectedVersion}
              AND OCTET_LENGTH(entitlement_id)=OCTET_LENGTH(#{entitlementId})
              AND OCTET_LENGTH(installation_id)=OCTET_LENGTH(#{installationId})
              AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{targetAgentId})
            """)
    int markEntitlementFailed(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("targetAgentId") String targetAgentId,
            @Param("entitlementId") String entitlementId,
            @Param("installationId") String installationId,
            @Param("expectedVersion") long expectedVersion,
            @Param("failedAt") long failedAt);
}
