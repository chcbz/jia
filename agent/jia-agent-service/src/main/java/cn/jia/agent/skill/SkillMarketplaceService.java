package cn.jia.agent.skill;

import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.agent.service.AgentManagedSessionLookup;
import cn.jia.economy.common.*;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.entity.EconomyAccountEntity;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.mapper.*;
import cn.jia.economy.service.*;
import cn.jia.oauth.service.ApiKeyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** Money admission, command intent and pending entitlement share one REQUIRED transaction.
 * The existing committed outbox publisher owns all network I/O and crash recovery. */
@Service
public final class SkillMarketplaceService {
    private final EconomySkillMarketplaceMapper market;
    private final EconomySkillApplicationMapper application;
    private final EconomyLedgerMapper ledger;
    private final EconomyPostingService posting;
    private final EconomyPreviewGate preview;
    private final SkillAgentVersions versions;
    private final AgentRuntimeDao runtimes;
    private final ObjectProvider<AgentCommandTransportWriter> writer;
    private final ObjectProvider<AgentRabbitSafetyGate> transportGate;
    private final ObjectProvider<SkillPackages> packages;
    private final ObjectProvider<ApiKeyService> keys;
    private final TransactionTemplate tx;
    private final SkillManagedCredentials credentials;
    private final ObjectProvider<AgentManagedSessionLookup> sessions;
    public SkillMarketplaceService(EconomySkillMarketplaceMapper market,EconomySkillApplicationMapper application,
            EconomyLedgerMapper ledger,EconomyPostingService posting,EconomyPreviewGate preview,
            SkillAgentVersions versions,AgentRuntimeDao runtimes,ObjectProvider<AgentCommandTransportWriter> writer,
            ObjectProvider<AgentRabbitSafetyGate> transportGate,ObjectProvider<SkillPackages> packages,
            ObjectProvider<ApiKeyService> keys,SkillManagedCredentials credentials,ObjectProvider<AgentManagedSessionLookup> sessions,PlatformTransactionManager manager) {
        this.market=market; this.application=application; this.ledger=ledger; this.posting=posting;
        this.preview=preview; this.versions=versions; this.runtimes=runtimes; this.writer=writer;
        this.transportGate=transportGate; this.packages=packages; this.keys=keys; this.tx=new TransactionTemplate(manager);this.credentials=credentials;this.sessions=sessions;
    }
    public boolean available(HostingRentHttp.Actor a) {
        var gate=transportGate.getIfAvailable();
        return versions.enabled() && preview.allows(a.tenantId(),a.clientId()) && packages.getIfAvailable()!=null
                && writer.getIfAvailable()!=null && keys.getIfAvailable()!=null && sessions.getIfAvailable()!=null && gate!=null
                && gate.rabbitDispatchEnabled() && gate.allowsDispatch(a.tenantId(),a.clientId());
    }
    public Map<String,Object> capabilities(HostingRentHttp.Actor a) {
        return Map.of("economyPreviewEnabled",preview.allows(a.tenantId(),a.clientId()),
                "skillMarketplaceEnabled",available(a),"principalScopeFingerprint",HexFormat.of().formatHex(
                        HostingRentHttp.hash("economy-principal-scope-v1",Map.of("actor",a.actorId(),"tenant",a.tenantId(),"client",a.clientId()))));
    }
    private void enabled(HostingRentHttp.Actor a) { require(available(a),503,"SKILL_MARKETPLACE_DISABLED"); }
    void actorLock(HostingRentHttp.Actor a) {
        application.ensureActor(a.tenantId(),a.clientId(),a.actorId());
        require(application.lockActor(a.tenantId(),a.clientId(),a.actorId())!=null,503,"SKILL_STATE_UNAVAILABLE");
    }
    public List<Map<String,Object>> catalog(HostingRentHttp.Actor a,boolean admin) {
        enabled(a);
        return market.selectPublishedProducts(a.tenantId(),a.clientId(),0,100).stream().map(p->detail(a,p,admin)).toList();
    }
    public Map<String,Object> detail(HostingRentHttp.Actor a,String id,boolean admin) {
        enabled(a); var p=application.product(a.tenantId(),a.clientId(),id);
        require(p!=null,404,"SKILL_PRODUCT_NOT_FOUND"); return detail(a,p,admin);
    }
    private Map<String,Object> detail(HostingRentHttp.Actor a,SkillProductEntity p,boolean admin) {
        var v=market.selectPurchasableProductVersion(a.tenantId(),a.clientId(),p.getCurrentProductVersionId());
        require(v!=null,404,"SKILL_PRODUCT_NOT_FOUND"); verifyPackage(v);
        Map<String,Object> dto=new LinkedHashMap<>();
        dto.put("productId",p.getProductId()); dto.put("name",p.getName()); dto.put("description",p.getDescription());
        dto.put("productVersionId",v.getProductVersionId()); dto.put("skillKey",v.getSkillKey()); dto.put("skillVersion",v.getSkillVersion());
        dto.put("priceMicro",v.getPriceMicro().toString()); dto.put("permissions",SkillMarketplaceHttp.permissions(v.getApprovedPermissionsManifest()));
        boolean allowed=admin || "NONE".equals(v.getDeploymentRestriction());
        dto.put("canPurchase",allowed); dto.put("denialReason",allowed?null:"SKILL_ADMIN_REQUIRED");
        dto.put("deploymentRestriction",v.getDeploymentRestriction()); return dto;
    }
    public Map<String,Object> quote(HostingRentHttp.Actor a,String key,Map<String,String> body,boolean admin) {
        enabled(a); byte[] hash=HostingRentHttp.hash("skill-quote-v1",body);
        return tx.execute(s->{
            actorLock(a);
            var old=market.selectPurchaseQuoteByActorKeyForUpdate(a.tenantId(),a.clientId(),"USER",a.actorId(),bytes(key));
            if(old!=null) { same(old.getRequestHash(),hash); return quoteDto(old); }
            String agent=body.get("targetAgentId"), productVersion=body.get("productVersionId");
            long version=HostingRentHttp.positive(body.get("expectedAgentVersion"));
            versions.requireOwned(a,agent,version,true); requireManagedKey(a,agent);
            var v=productLocked(a,productVersion,admin);
            require(application.lockSkillKey(a.tenantId(),a.clientId(),agent,v.getSkillKey()).isEmpty(),409,"SKILL_ALREADY_ORDERED");
            long now=System.currentTimeMillis();
            var q=new SkillPurchaseQuoteEntity().setQuoteId(id("sq_")).setActorType("USER").setActorId(a.actorId())
                    .setIdempotencyKey(bytes(key)).setRequestHash(hash).setProductVersionId(productVersion).setTargetAgentId(agent)
                    .setExpectedAgentVersion(version).setExpectedPriceMicro(v.getPriceMicro())
                    .setApprovedPermissionsManifest(v.getApprovedPermissionsManifest()).setApprovedPermissionsSha256(v.getApprovedPermissionsSha256())
                    .setDeploymentRestriction(v.getDeploymentRestriction()).setExpiresAt(Math.addExact(now,300000L))
                    .setTenantId(a.tenantId()).setClientId(a.clientId()).setCreateTime(now);
            one(market.insertPurchaseQuote(q)); return quoteDto(q);
        });
    }
    public Map<String,Object> purchase(HostingRentHttp.Actor a,String key,Map<String,String> body,boolean admin) {
        enabled(a); byte[] hash=HostingRentHttp.hash("skill-purchase-v1",body);
        return tx.execute(s->{
            actorLock(a);
            var old=market.selectOrderReceiptByActorKeyForUpdate(a.tenantId(),a.clientId(),"USER",a.actorId(),bytes(key));
            if(old!=null) {
                same(old.getRequestHash(),hash);
                var order=market.selectOrderByBuyer(a.tenantId(),a.clientId(),"USER",a.actorId(),old.getOrderId());
                require(order!=null,503,"SKILL_STATE_UNAVAILABLE"); return orderDto(order,old.getOrderStatus());
            }
            var q=market.selectPurchaseQuoteForUpdate(a.tenantId(),a.clientId(),body.get("quoteId"),"USER",a.actorId());
            require(q!=null,404,"SKILL_QUOTE_NOT_FOUND");
            require(application.orderForQuote(a.tenantId(),a.clientId(),q.getQuoteId())==null,409,"SKILL_QUOTE_USED");
            require(q.getExpiresAt()>System.currentTimeMillis(),409,"SKILL_QUOTE_EXPIRED");
            require(q.getProductVersionId().equals(body.get("productVersionId")) && q.getTargetAgentId().equals(body.get("targetAgentId"))
                    && q.getExpectedAgentVersion().toString().equals(body.get("expectedAgentVersion"))
                    && q.getExpectedPriceMicro().toString().equals(body.get("expectedPriceMicro")),409,"SKILL_QUOTE_MISMATCH");
            versions.requireOwned(a,q.getTargetAgentId(),q.getExpectedAgentVersion(),true);
            String keyId=requireManagedKey(a,q.getTargetAgentId());
            var v=productLocked(a,q.getProductVersionId(),admin);
            require(v.getPriceMicro().equals(q.getExpectedPriceMicro()) && Arrays.equals(v.getApprovedPermissionsSha256(),q.getApprovedPermissionsSha256())
                    && v.getApprovedPermissionsManifest().equals(body.get("approvedPermissions")),409,"SKILL_PERMISSIONS_MISMATCH");
            require(application.lockSkillKey(a.tenantId(),a.clientId(),q.getTargetAgentId(),v.getSkillKey()).isEmpty(),409,"SKILL_ALREADY_ORDERED");
            long now=System.currentTimeMillis(); String orderId=id("so_"),installId=id("si_");
            var reserve=reserve(a,orderId,v.getPriceMicro(),now);
            var order=new SkillOrderEntity().setOrderId(orderId).setQuoteId(q.getQuoteId()).setProductVersionId(v.getProductVersionId())
                    .setTargetAgentId(q.getTargetAgentId()).setBuyerType("USER").setBuyerId(a.actorId()).setSellerType("SYSTEM").setSellerId("SKILL_STORE")
                    .setPriceMicro(v.getPriceMicro()).setExpectedAgentVersion(q.getExpectedAgentVersion()).setPermissionGrantVersion(1L)
                    .setApprovedPermissionsManifest(v.getApprovedPermissionsManifest()).setApprovedPermissionsSha256(v.getApprovedPermissionsSha256())
                    .setEscrowId(reserve==null?null:reserve.escrow().escrowId()).setReserveTransactionId(reserve==null?null:reserve.transactionId())
                    .setStatus("FUNDS_HELD").setVersion(1L).setTenantId(a.tenantId()).setClientId(a.clientId()).setHeldAt(now).setUpdateTime(now);
            one(market.insertOrder(order));
            var payload=new AgentSkillInstallPayload(orderId,installId,v.getProductVersionId(),v.getSkillKey(),v.getSkillVersion(),
                    v.getPackageSize().toString(),digest(v.getPackageSha256()),"/internal/agent/skill-installations/"+installId+"/package");
            // Existing outbox is the after-commit dispatcher. The order root fills its legacy aggregate slot;
            // no task is fabricated, admitted, or completed by this command.
            var draft=new AgentCommandDraft(1,"cmd_skill_"+installId,orderId,installId,a.tenantId(),a.clientId(),orderId,null,
                    q.getTargetAgentId(),"SKILL_INSTALL",now,Math.addExact(now,3600000L),payload);
            var command=writer.getObject().write(draft);
            var install=new SkillInstallationEntity().setInstallationId(installId).setOrderId(orderId).setProductVersionId(v.getProductVersionId())
                    .setTargetAgentId(q.getTargetAgentId()).setSchemaVersion(1).setMessageType("command.dispatch").setMessageId(command.messageId())
                    .setRequestId(installId).setCommandType("SKILL_INSTALL").setCommandId(draft.commandId()).setAttempt(1).setFencingToken(1L).setDeliveryEpoch(1L)
                    .setSkillKey(v.getSkillKey()).setSkillVersion(v.getSkillVersion()).setPackageSize(v.getPackageSize()).setPackageSha256(v.getPackageSha256())
                    .setDownloadPath(payload.downloadPath()).setStatus("REQUESTED").setVersion(1L).setTenantId(a.tenantId()).setClientId(a.clientId()).setCreateTime(now).setUpdateTime(now);
            one(market.insertInstallation(install));
            one(application.insertDeliveryBinding(a.tenantId(),a.clientId(),installId,keyId,
                    registrationHash(runtimes.findByAgentIdForUpdate(q.getTargetAgentId())),reserve==null?null:reserve.escrow().escrowVersion()));
            var entitlement=new SkillEntitlementEntity().setEntitlementId(id("se_")).setOrderId(orderId).setInstallationId(installId)
                    .setProductVersionId(v.getProductVersionId()).setTargetAgentId(q.getTargetAgentId()).setSkillKey(v.getSkillKey()).setSkillVersion(v.getSkillVersion())
                    .setPermissionGrantVersion(1L).setApprovedPermissionsManifest(v.getApprovedPermissionsManifest()).setApprovedPermissionsSha256(v.getApprovedPermissionsSha256())
                    .setStatus("PENDING_INSTALLATION").setVersion(1L).setTenantId(a.tenantId()).setClientId(a.clientId()).setCreateTime(now).setUpdateTime(now);
            one(market.insertEntitlement(entitlement));
            one(market.insertOrderReceipt(new SkillOrderReceiptEntity().setOrderId(orderId).setActorType("USER").setActorId(a.actorId())
                    .setIdempotencyKey(bytes(key)).setRequestHash(hash).setOrderVersion(1L).setOrderStatus("FUNDS_HELD").setPriceMicro(v.getPriceMicro())
                    .setPermissionGrantVersion(1L).setApprovedPermissionsSha256(v.getApprovedPermissionsSha256()).setEscrowId(order.getEscrowId())
                    .setTenantId(a.tenantId()).setClientId(a.clientId()).setCreateTime(now)));
            versions.consume(a,q.getTargetAgentId(),q.getExpectedAgentVersion());
            // State represents a durable install scheduled for delivery, not fabricated successful delivery.
            one(market.markOrderInstalling(a.tenantId(),a.clientId(),orderId,1,now));
            one(market.markInstallationInstalling(a.tenantId(),a.clientId(),q.getTargetAgentId(),installId,draft.commandId(),1,1,1,1,now));
            return orderDto(order,"FUNDS_HELD");
        });
    }
    private SkillProductVersionEntity productLocked(HostingRentHttp.Actor a,String id,boolean admin) {
        var published=market.selectPurchasableProductVersion(a.tenantId(),a.clientId(),id);
        require(published!=null,404,"SKILL_PRODUCT_NOT_FOUND");
        var p=market.selectProductForUpdate(a.tenantId(),a.clientId(),published.getProductId());
        require(p!=null && "PUBLISHED".equals(p.getStatus()) && id.equals(p.getCurrentProductVersionId()),409,"SKILL_PRODUCT_CHANGED");
        var v=market.selectApprovedProductVersionForUpdate(a.tenantId(),a.clientId(),id);
        require(v!=null && ("NONE".equals(v.getDeploymentRestriction()) || admin),403,"SKILL_ADMIN_REQUIRED");
        verifyPackage(v); return v;
    }
    private void verifyPackage(SkillProductVersionEntity v) {
        var p=packages.getObject().get(v.getProductVersionId()).product();
        require(p.skillKey().equals(v.getSkillKey()) && p.skillVersion().equals(v.getSkillVersion()) && p.packageSize()==v.getPackageSize()
                && Arrays.equals(p.packageSha256(),v.getPackageSha256()) && p.priceMicro()==v.getPriceMicro()
                && p.approvedPermissionsManifest().equals(v.getApprovedPermissionsManifest())
                && Arrays.equals(p.approvedPermissionsSha256(),v.getApprovedPermissionsSha256())
                && p.deploymentRestriction().equals(v.getDeploymentRestriction()),503,"SKILL_PACKAGE_UNAVAILABLE");
    }
    String requireManagedKey(HostingRentHttp.Actor a,String agentId) {
        String key=credentials.requireCurrent(a,agentId);
        require(sessions.getIfAvailable()!=null && sessions.getObject().isReady(a.tenantId(),a.clientId(),agentId,key,
                registrationHash(runtimes.findByAgentIdForUpdate(agentId))),409,"SKILL_AGENT_REGISTRATION_REQUIRED");
        return key;
    }
    public Map<String,Object> order(HostingRentHttp.Actor a,String id) {
        enabled(a); var o=market.selectOrderByBuyer(a.tenantId(),a.clientId(),"USER",a.actorId(),id);
        require(o!=null,404,"SKILL_ORDER_NOT_FOUND"); return orderDto(o,o.getStatus());
    }
    public List<Map<String,Object>> entitlements(HostingRentHttp.Actor a,String agentId) {
        enabled(a); return tx.execute(s->{
            versions.requireOwned(a,agentId,null,false);
            return market.selectEntitlementsByAgent(a.tenantId(),a.clientId(),agentId).stream().map(e->{
                Map<String,Object> d=new LinkedHashMap<>();
                d.put("entitlementId",e.getEntitlementId()); d.put("targetAgentId",e.getTargetAgentId()); d.put("orderId",e.getOrderId());
                d.put("installationId",e.getInstallationId()); d.put("productVersionId",e.getProductVersionId());
                d.put("skillKey",e.getSkillKey()); d.put("skillVersion",e.getSkillVersion()); d.put("status",e.getStatus());
                d.put("permissionGrantVersion",e.getPermissionGrantVersion().toString()); return d;
            }).toList();
        });
    }
    private EconomyPostingResult reserve(HostingRentHttp.Actor a,String order,long amount,long now) {
        if(amount==0) return null;
        var escrow=escrow(order); ensureAccount(a,escrow,now);
        return posting.post(new EconomyPostingCommand(a.scope(),a.principal(),UUID.randomUUID().toString(),
                HostingRentHttp.hash("skill-reserve",Map.of("order",order)),EconomyJournalType.RESERVE_SKILL,order,
                List.of(new EconomyPostingLine(availableAccount(a.actorId()),-amount),new EconomyPostingLine(escrow,amount)),
                new EconomyEscrowFunding(EconomyEscrowType.SKILL_ORDER,availableAccount(a.actorId()),escrow,amount,null)));
    }
    String settle(HostingRentHttp.Actor a,SkillOrderEntity o,SkillDeliveryBindingEntity binding,boolean capture,long now) {
        if(o.getPriceMicro()==0) return null;
        var dest=capture?new EconomyAccountKey("SILVER",EconomyAccountOwnerType.SYSTEM,"SKILL_STORE",EconomyAccountPurpose.SKILL_STORE):availableAccount(a.actorId());
        if(capture) ensureAccount(a,dest,now);
        require(binding.getEscrowVersion()!=null,503,"SKILL_STATE_UNAVAILABLE");
        var result=posting.post(new EconomyPostingCommand(a.scope(),a.principal(),UUID.randomUUID().toString(),
                HostingRentHttp.hash(capture?"skill-capture":"skill-refund",Map.of("order",o.getOrderId())),
                capture?EconomyJournalType.CAPTURE_SKILL:EconomyJournalType.REFUND_SKILL,o.getOrderId(),
                List.of(new EconomyPostingLine(escrow(o.getOrderId()),-o.getPriceMicro()),new EconomyPostingLine(dest,o.getPriceMicro())),null,
                new EconomyEscrowSettlement(EconomyEscrowType.SKILL_ORDER,escrow(o.getOrderId()),dest,o.getPriceMicro(),binding.getEscrowVersion(),o.getReserveTransactionId())));
        return result.transactionId();
    }
    private void ensureAccount(HostingRentHttp.Actor a,EconomyAccountKey k,long now) {
        ledger.insertAccountIfAbsent(new EconomyAccountEntity().setAccountId(id("skill_ac_")).setOwnerType(k.ownerType().name()).setOwnerId(k.ownerId())
                .setPurpose(k.purpose().name()).setCurrency("SILVER").setBalanceMicro(0L).setAllowNegative(0).setStatus("ACTIVE").setVersion(0L)
                .setTenantId(a.tenantId()).setClientId(a.clientId()).setCreateTime(now).setUpdateTime(now));
    }
    static EconomyAccountKey escrow(String order) { return new EconomyAccountKey("SILVER",EconomyAccountOwnerType.ORDER,order,EconomyAccountPurpose.ESCROW); }
    static EconomyAccountKey availableAccount(String actor) { return new EconomyAccountKey("SILVER",EconomyAccountOwnerType.USER,actor,EconomyAccountPurpose.AVAILABLE); }
    static byte[] registrationHash(AgentRuntimeEntity r) {
        require(r!=null && r.getTokenHash()!=null && !r.getTokenHash().isBlank(),409,"AGENT_NOT_READY");
        return sessionRegistrationHash(r.getAgentId(),r.getTokenHash());
    }
    public static byte[] sessionRegistrationHash(String agent,String token) {
        HostingRentHttp.exact(agent,100);HostingRentHttp.exact(token,100);
        return HostingRentHttp.hash("skill-registration",Map.of("agent",agent,"token",token));
    }
    static Map<String,Object> quoteDto(SkillPurchaseQuoteEntity q) {
        return Map.of("quoteId",q.getQuoteId(),"productVersionId",q.getProductVersionId(),"targetAgentId",q.getTargetAgentId(),
                "expectedAgentVersion",q.getExpectedAgentVersion().toString(),"priceMicro",q.getExpectedPriceMicro().toString(),"expiresAt",q.getExpiresAt().toString());
    }
    static Map<String,Object> orderDto(SkillOrderEntity o,String status) {
        return Map.of("orderId",o.getOrderId(),"productVersionId",o.getProductVersionId(),"targetAgentId",o.getTargetAgentId(),
                "expectedAgentVersion",o.getExpectedAgentVersion().toString(),"status",status);
    }
    static String id(String prefix) { return prefix+UUID.randomUUID().toString().replace("-",""); }
    static String digest(byte[] hash) { return "sha256:"+HexFormat.of().formatHex(hash); }
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static void same(byte[] a,byte[] b) { require(Arrays.equals(a,b),409,"IDEMPOTENCY_CONFLICT"); }
    static void one(int n) { require(n==1,409,"SKILL_STATE_CONFLICT"); }
}
