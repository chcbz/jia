package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import cn.jia.economy.entity.EconomyHostingRentPlanEntity;
import cn.jia.economy.entity.EconomyWalletLedgerRow;
import cn.jia.economy.entity.EconomyWalletSnapshotRow;
import cn.jia.economy.entity.skill.SkillEntitlementEntity;
import cn.jia.economy.entity.skill.SkillInstallationEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * V1.7 read-only persistence surface. Every statement is a plain SELECT without locks, seeds,
 * upserts, stored routines, or legacy gate calls. BINARY plus OCTET_LENGTH makes every ACL key
 * byte-exact even when an installed table retained a case-insensitive collation.
 */
public interface EconomyReadOnlyPreviewMapper {
    String WALLET_SCOPE = " wallet.tenant_id=#{tenantId} AND wallet.client_id=#{clientId}"
            + " AND CAST(wallet.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)"
            + " AND OCTET_LENGTH(wallet.tenant_id)=OCTET_LENGTH(#{tenantId})"
            + " AND CAST(wallet.client_id AS BINARY)=CAST(#{clientId} AS BINARY)"
            + " AND OCTET_LENGTH(wallet.client_id)=OCTET_LENGTH(#{clientId}) ";

    @Select("""
            SELECT COALESCE(wallet.balance_micro,0) AS available_micro,
                   COALESCE(held.held_micro,0) AS held_micro,
                   COALESCE(held.minimum_held_component_micro,0) AS minimum_held_component_micro,
                   COALESCE(wallet.version,0)+COALESCE(held.hosting_version,0) AS version
            FROM (SELECT 1 AS singleton) snapshot_anchor
            LEFT JOIN economy_account wallet ON
            """ + WALLET_SCOPE + """
             AND wallet.currency='SILVER' AND wallet.owner_type='USER'
             AND wallet.owner_id=#{actorId} AND wallet.purpose='AVAILABLE'
             AND CAST(wallet.owner_id AS BINARY)=CAST(#{actorId} AS BINARY)
             AND OCTET_LENGTH(wallet.owner_id)=OCTET_LENGTH(#{actorId})
            LEFT JOIN (
                SELECT SUM(e.gross_micro-e.captured_micro-e.refunded_micro) AS held_micro,
                       SUM(CASE WHEN e.business_type='HOSTING_RENT' THEN e.version ELSE 0 END) AS hosting_version,
                       MIN(e.gross_micro-e.captured_micro-e.refunded_micro) AS minimum_held_component_micro
                FROM economy_escrow e
                JOIN economy_account payer
                  ON payer.tenant_id=e.tenant_id AND payer.client_id=e.client_id
                 AND payer.account_id=e.payer_account_id
                 AND CAST(payer.tenant_id AS BINARY)=CAST(e.tenant_id AS BINARY)
                 AND OCTET_LENGTH(payer.tenant_id)=OCTET_LENGTH(e.tenant_id)
                 AND CAST(payer.client_id AS BINARY)=CAST(e.client_id AS BINARY)
                 AND OCTET_LENGTH(payer.client_id)=OCTET_LENGTH(e.client_id)
                 AND CAST(payer.account_id AS BINARY)=CAST(e.payer_account_id AS BINARY)
                 AND OCTET_LENGTH(payer.account_id)=OCTET_LENGTH(e.payer_account_id)
                WHERE e.tenant_id=#{tenantId} AND e.client_id=#{clientId}
                  AND CAST(e.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
                  AND OCTET_LENGTH(e.tenant_id)=OCTET_LENGTH(#{tenantId})
                  AND CAST(e.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
                  AND OCTET_LENGTH(e.client_id)=OCTET_LENGTH(#{clientId})
                  AND payer.owner_type='USER' AND payer.owner_id=#{actorId} AND payer.purpose='AVAILABLE'
                  AND CAST(payer.owner_id AS BINARY)=CAST(#{actorId} AS BINARY)
                  AND OCTET_LENGTH(payer.owner_id)=OCTET_LENGTH(#{actorId})
            ) held ON snapshot_anchor.singleton=1
            LIMIT 1
            """)
    EconomyWalletSnapshotRow selectWallet(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("actorId") String actorId);

    @Select("""
            SELECT e.id AS row_id,e.transaction_id,e.entry_id,t.business_type,t.business_id AS business_ref,
                   e.signed_amount_micro,e.status,e.posted_at
            FROM economy_entry e
            JOIN economy_account a
              ON a.tenant_id=e.tenant_id AND a.client_id=e.client_id AND a.account_id=e.account_id
             AND CAST(a.tenant_id AS BINARY)=CAST(e.tenant_id AS BINARY)
             AND OCTET_LENGTH(a.tenant_id)=OCTET_LENGTH(e.tenant_id)
             AND CAST(a.client_id AS BINARY)=CAST(e.client_id AS BINARY)
             AND OCTET_LENGTH(a.client_id)=OCTET_LENGTH(e.client_id)
             AND CAST(a.account_id AS BINARY)=CAST(e.account_id AS BINARY)
             AND OCTET_LENGTH(a.account_id)=OCTET_LENGTH(e.account_id)
            JOIN economy_transaction t
              ON t.tenant_id=e.tenant_id AND t.client_id=e.client_id AND t.transaction_id=e.transaction_id
             AND CAST(t.tenant_id AS BINARY)=CAST(e.tenant_id AS BINARY)
             AND OCTET_LENGTH(t.tenant_id)=OCTET_LENGTH(e.tenant_id)
             AND CAST(t.client_id AS BINARY)=CAST(e.client_id AS BINARY)
             AND OCTET_LENGTH(t.client_id)=OCTET_LENGTH(e.client_id)
             AND CAST(t.transaction_id AS BINARY)=CAST(e.transaction_id AS BINARY)
             AND OCTET_LENGTH(t.transaction_id)=OCTET_LENGTH(e.transaction_id)
            WHERE e.tenant_id=#{tenantId} AND e.client_id=#{clientId}
              AND CAST(e.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(e.tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(e.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(e.client_id)=OCTET_LENGTH(#{clientId})
              AND a.owner_type='USER' AND a.owner_id=#{actorId} AND a.purpose='AVAILABLE'
              AND CAST(a.owner_id AS BINARY)=CAST(#{actorId} AS BINARY)
              AND OCTET_LENGTH(a.owner_id)=OCTET_LENGTH(#{actorId})
              AND e.status='POSTED' AND t.status='POSTED'
              AND (#{cursorPostedAt} IS NULL OR e.posted_at < #{cursorPostedAt}
                   OR (e.posted_at=#{cursorPostedAt} AND e.id < #{cursorRowId}))
            ORDER BY e.posted_at DESC,e.id DESC
            LIMIT #{limit}
            """)
    List<EconomyWalletLedgerRow> selectLedger(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("actorId") String actorId,
            @Param("cursorPostedAt") Long cursorPostedAt, @Param("cursorRowId") Long cursorRowId,
            @Param("limit") int limit);

    @Select("""
            SELECT p.product_id,p.name,p.description,v.product_version_id,v.skill_key,v.skill_version,
                   v.price_micro,v.approved_permissions_manifest,v.deployment_restriction
            FROM economy_skill_product p
            JOIN economy_skill_product_version v
              ON v.tenant_id=p.tenant_id AND v.client_id=p.client_id AND v.product_id=p.product_id
             AND CAST(v.tenant_id AS BINARY)=CAST(p.tenant_id AS BINARY)
             AND OCTET_LENGTH(v.tenant_id)=OCTET_LENGTH(p.tenant_id)
             AND CAST(v.client_id AS BINARY)=CAST(p.client_id AS BINARY)
             AND OCTET_LENGTH(v.client_id)=OCTET_LENGTH(p.client_id)
             AND CAST(v.product_id AS BINARY)=CAST(p.product_id AS BINARY)
             AND OCTET_LENGTH(v.product_id)=OCTET_LENGTH(p.product_id)
             AND v.product_version_id=p.current_product_version_id
             AND CAST(v.product_version_id AS BINARY)=CAST(p.current_product_version_id AS BINARY)
             AND OCTET_LENGTH(v.product_version_id)=OCTET_LENGTH(p.current_product_version_id)
            WHERE p.tenant_id=#{tenantId} AND p.client_id=#{clientId}
              AND CAST(p.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(p.tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(p.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(p.client_id)=OCTET_LENGTH(#{clientId})
              AND p.status='PUBLISHED' AND v.review_status='APPROVED'
            ORDER BY p.name ASC,p.product_id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<EconomyReadOnlyPreviewRows.ProductRow> selectProducts(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            SELECT p.product_id,p.name,p.description,v.product_version_id,v.skill_key,v.skill_version,
                   v.price_micro,v.approved_permissions_manifest,v.deployment_restriction
            FROM economy_skill_product p
            JOIN economy_skill_product_version v
              ON v.tenant_id=p.tenant_id AND v.client_id=p.client_id AND v.product_id=p.product_id
             AND CAST(v.tenant_id AS BINARY)=CAST(p.tenant_id AS BINARY)
             AND OCTET_LENGTH(v.tenant_id)=OCTET_LENGTH(p.tenant_id)
             AND CAST(v.client_id AS BINARY)=CAST(p.client_id AS BINARY)
             AND OCTET_LENGTH(v.client_id)=OCTET_LENGTH(p.client_id)
             AND CAST(v.product_id AS BINARY)=CAST(p.product_id AS BINARY)
             AND OCTET_LENGTH(v.product_id)=OCTET_LENGTH(p.product_id)
             AND v.product_version_id=p.current_product_version_id
             AND CAST(v.product_version_id AS BINARY)=CAST(p.current_product_version_id AS BINARY)
             AND OCTET_LENGTH(v.product_version_id)=OCTET_LENGTH(p.current_product_version_id)
            WHERE p.tenant_id=#{tenantId} AND p.client_id=#{clientId} AND p.product_id=#{productId}
              AND CAST(p.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(p.tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(p.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(p.client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(p.product_id AS BINARY)=CAST(#{productId} AS BINARY)
              AND OCTET_LENGTH(p.product_id)=OCTET_LENGTH(#{productId})
              AND p.status='PUBLISHED' AND v.review_status='APPROVED'
            LIMIT 2
            """)
    List<EconomyReadOnlyPreviewRows.ProductRow> selectProduct(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("productId") String productId);

    @Select("""
            SELECT i.binding_id,i.tenant_id,i.client_id,i.owner_jiacn,
                   i.canonical_agent_id AS agent_id,b.persona_code
            FROM agent_identity_registry i
            JOIN agent_persona_binding b
              ON b.id=i.binding_id AND b.tenant_id=i.tenant_id AND b.client_id=i.client_id
             AND b.owner_jiacn=i.owner_jiacn AND b.agent_id=i.canonical_agent_id
             AND CAST(b.tenant_id AS BINARY)=CAST(i.tenant_id AS BINARY)
             AND OCTET_LENGTH(b.tenant_id)=OCTET_LENGTH(i.tenant_id)
             AND CAST(b.client_id AS BINARY)=CAST(i.client_id AS BINARY)
             AND OCTET_LENGTH(b.client_id)=OCTET_LENGTH(i.client_id)
             AND CAST(b.owner_jiacn AS BINARY)=CAST(i.owner_jiacn AS BINARY)
             AND OCTET_LENGTH(b.owner_jiacn)=OCTET_LENGTH(i.owner_jiacn)
             AND CAST(b.agent_id AS BINARY)=CAST(i.canonical_agent_id AS BINARY)
             AND OCTET_LENGTH(b.agent_id)=OCTET_LENGTH(i.canonical_agent_id)
            WHERE i.tenant_id=#{tenantId} AND i.client_id=#{clientId} AND i.owner_jiacn=#{ownerJiacn}
              AND i.canonical_agent_id=#{agentId}
              AND CAST(i.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(i.tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(i.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(i.client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(i.owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(i.owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
              AND CAST(i.canonical_agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
              AND OCTET_LENGTH(i.canonical_agent_id)=OCTET_LENGTH(#{agentId})
              AND i.lifecycle_status IN ('PROVISIONED','ACTIVE') AND b.status=1
            LIMIT 2
            """)
    List<EconomyReadOnlyPreviewRows.AgentOwnershipRow> selectOwnedAgent(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("agentId") String agentId);

    @Select("""
            SELECT id,entitlement_id,order_id,installation_id,product_version_id,target_agent_id,
                   skill_key,skill_version,permission_grant_version,approved_permissions_manifest,
                   approved_permissions_sha256,status,version,tenant_id,client_id,
                   create_time,activated_at,failed_at,update_time
            FROM economy_skill_entitlement
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND target_agent_id=#{agentId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(target_agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
              AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{agentId})
            ORDER BY skill_key ASC,skill_version ASC,entitlement_id ASC
            """)
    List<SkillEntitlementEntity> selectEntitlements(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("agentId") String agentId);

    @Select("""
            SELECT id,installation_id,order_id,product_version_id,target_agent_id,schema_version,message_type,
                   message_id,request_id,command_type,command_id,attempt,fencing_token,delivery_epoch,
                   skill_key,skill_version,package_size,package_sha256,download_path,status,failure_code,
                   version,tenant_id,client_id,create_time,started_at,installed_at,failed_at,update_time
            FROM economy_skill_installation
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND target_agent_id=#{agentId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(target_agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
              AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{agentId})
            ORDER BY installation_id ASC
            """)
    List<SkillInstallationEntity> selectInstallations(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("agentId") String agentId);

    @Select("""
            SELECT id,binding_id,owner_jiacn,canonical_agent_id,persona_code,profile_key,api_key_id,
                   lifecycle_state,resume_state,generation,desired_enabled,last_error,
                   tenant_id,client_id,create_time,update_time
            FROM agent_hosted_profile
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
              AND binding_id=#{bindingId} AND canonical_agent_id=#{agentId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
              AND CAST(canonical_agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
              AND OCTET_LENGTH(canonical_agent_id)=OCTET_LENGTH(#{agentId})
            LIMIT 2
            """)
    List<AgentHostedProfileEntity> selectHostedProfile(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("bindingId") long bindingId,
            @Param("agentId") String agentId);

    @Select("""
            SELECT id,plan_id,plan_version,amount_micro,period_seconds,quote_ttl_seconds,
                   currency,status,tenant_id,client_id,create_time
            FROM economy_hosting_rent_plan
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND status='ACTIVE'
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
            ORDER BY plan_version DESC,id DESC
            LIMIT 1
            """)
    EconomyHostingRentPlanEntity selectLatestPlan(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId);

    @Select("""
            SELECT id,lease_id,principal_type,principal_id,persona_code,agent_id,binding_id,
                   plan_id,plan_version,amount_micro,period_seconds,status,paid_from,paid_through,
                   latest_intent_id,version,tenant_id,client_id,create_time,update_time
            FROM economy_hosting_lease
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND agent_id=#{agentId}
              AND principal_type='USER' AND principal_id=#{actorId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
              AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId})
              AND CAST(principal_id AS BINARY)=CAST(#{actorId} AS BINARY)
              AND OCTET_LENGTH(principal_id)=OCTET_LENGTH(#{actorId})
            ORDER BY id DESC
            LIMIT 1
            """)
    EconomyHostingLeaseEntity selectLatestLease(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("actorId") String actorId,
            @Param("agentId") String agentId);
}
