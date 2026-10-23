package cn.jia.agent.skill;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.*;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.mapper.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.function.Supplier;
import static cn.jia.agent.skill.SkillManagementCodec.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;
import static cn.jia.agent.skill.SkillMarketplaceService.one;
import static cn.jia.agent.skill.SkillMarketplaceService.id;

/** The managed skill application use cases. Orders, escrow, installation and WS outbox remain
 * the existing marketplace domains; no network IO occurs under the atomic business locks.
 * Lock order: economy actor -> persistent runtime binding/Agent -> per-skill root -> quote,
 * order/entitlement -> posting -> immutable installation/command intent. */
@Service
public final class SkillManagementService {
    private final EconomySkillManagementMapper db;
    private final EconomySkillMarketplaceMapper market;
    private final EconomySkillApplicationMapper app;
    private final SkillMarketplaceService marketplace;
    private final SkillAgentVersions versions;
    private final AgentRuntimeDao runtimes;
    private final AgentRuntimeV1InstallationDao installations;
    private final AgentRuntimeAuthenticationService authentication;
    private final ObjectProvider<SkillPackages> packages;
    private final ObjectProvider<SkillInstallCommandWriter> writer;
    private final ObjectProvider<AgentManagedSessionLookup> sessions;
    private final ObjectProvider<SkillCapabilityLookup> capabilities;
    private final TransactionTemplate tx;
    public SkillManagementService(EconomySkillManagementMapper db,EconomySkillMarketplaceMapper market,
            EconomySkillApplicationMapper app,SkillMarketplaceService marketplace,SkillAgentVersions versions,
            AgentRuntimeDao runtimes,AgentRuntimeV1InstallationDao installations,AgentRuntimeAuthenticationService authentication,
            ObjectProvider<SkillPackages> packages,ObjectProvider<SkillInstallCommandWriter> writer,
            ObjectProvider<AgentManagedSessionLookup> sessions,ObjectProvider<SkillCapabilityLookup> capabilities,
            PlatformTransactionManager manager) {
        this.db=db;this.market=market;this.app=app;this.marketplace=marketplace;this.versions=versions;this.runtimes=runtimes;
        this.installations=installations;this.authentication=authentication;this.packages=packages;this.writer=writer;
        this.sessions=sessions;this.capabilities=capabilities;this.tx=new TransactionTemplate(manager);
    }
    private void enabled(HostingRentHttp.Actor a) {require(marketplace.available(a),503,"SKILL_STATE_UNAVAILABLE");}
    private <T> T owned(HostingRentHttp.Actor a,String agent,Long expected,boolean online,Supplier<T> action) {
        enabled(a);path(agent);
        return tx.execute(s->{
            marketplace.actorLock(a);
            var hint=runtimes.findInScope(a.tenantId(),a.clientId(),agent);
            require(hint!=null&&a.ownerJiacn().equals(hint.getOwnerJiacn()),403,"AGENT_FORBIDDEN");
            require(hint.getRuntimeInstallationId()!=null&&hint.getRuntimeHostId()!=null,409,"SKILL_RUNTIME_MAPPING_REQUIRED");
            var installation=installations.lock(hint.getRuntimeInstallationId());
            require(installation!=null&&"ACTIVE".equals(installation.getStatus())
                    &&a.tenantId().equals(installation.getTenantId())&&a.clientId().equals(installation.getClientId())
                    &&agent.equals(installation.getCanonicalAgentId()),409,"SKILL_RUNTIME_MAPPING_REQUIRED");
            var row=runtimes.lockInScope(a.tenantId(),a.clientId(),agent);
            require(row!=null&&a.ownerJiacn().equals(row.getOwnerJiacn())
                    &&hint.getRuntimeInstallationId().equals(row.getRuntimeInstallationId())
                    &&hint.getRuntimeHostId().equals(row.getRuntimeHostId()),409,"SKILL_STATE_CONFLICT");
            requireVersion(a,agent,expected);
            if(online) require(online(a,agent),409,"SKILL_AGENT_OFFLINE");
            return action.get();
        });
    }
    private void requireVersion(HostingRentHttp.Actor a,String agent,Long expected) {
        try {versions.requireOwned(a,agent,expected,false);}
        catch(SkillMarketplaceException conflict) {
            if("AGENT_VERSION_CONFLICT".equals(conflict.code()))throw new SkillMarketplaceException(409,"SKILL_STATE_CONFLICT");
            throw conflict;
        }
    }
    private <T> T queryOwned(HostingRentHttp.Actor a,String agent,Supplier<T> query) {
        enabled(a);path(agent);versions.readOwned(a,agent);
        return tx.execute(s->{versions.readOwned(a,agent);return query.get();});
    }
    private boolean online(HostingRentHttp.Actor a,String agent) {
        try {var proof=authentication.currentRegisteredProof(a.tenantId(),a.clientId(),agent);
            return a.ownerJiacn().equals(proof.scope().ownerJiacn())&&sessions.getIfAvailable()!=null
                    &&sessions.getObject().isReady(a.tenantId(),a.clientId(),agent,SkillMarketplaceService.sessionFence(proof));
        } catch(IllegalArgumentException absent){return false;}
    }
    private SkillManagedStateEntity state(HostingRentHttp.Actor a,String agent,String key) {
        db.ensureState(a.tenantId(),a.clientId(),agent,key);
        var row=db.lockState(a.tenantId(),a.clientId(),agent,key);require(row!=null,503,"SKILL_STATE_UNAVAILABLE");return row;
    }
    private static Map<String,Object> decode(String json,String type) {return read(json.getBytes(java.nio.charset.StandardCharsets.UTF_8),type);}
    private static Map<String,Object> dto(SkillChangeEntity c) {return decode(c.getDtoJson(),"SkillChange");}
    private static Map<String,Object> ref(SkillInstallationAuthorizationEntity auth) {return decode(auth.getPackageJson(),"PackageRef");}
    private static Map<String,Object> money(long amount) {return map("amountMinor",Long.toString(amount),"currency","SILVER");}
    private static String permissionsHash(Object permissions) {return hash("cyf.skill.permissions.v1",permissions);}
    private static List<String> permissionList(Object value) {
        @SuppressWarnings("unchecked") var list=(List<String>)value;
        var sorted=new ArrayList<>(list);sorted.sort(UTF8);require(sorted.equals(list)&&new HashSet<>(list).size()==list.size(),400,"BAD_REQUEST");return list;
    }
    private SkillInstallationAuthorizationEntity source(HostingRentHttp.Actor a,SkillManagedStateEntity state,Object requested) {
        require(Objects.equals(state.getActiveInstallationId(),requested),409,"SKILL_STATE_CONFLICT");
        if(requested==null)return null;
        var auth=db.findAuthorization(a.tenantId(),a.clientId(),(String)requested);
        require(auth!=null,409,"SKILL_STATE_CONFLICT");
        var entitlement=db.entitlement(a.tenantId(),a.clientId(),auth.getEntitlementId());
        require(entitlement!=null&&"ACTIVE".equals(entitlement.getStatus())&&state.getAgentId().equals(entitlement.getTargetAgentId())
                &&validInstallation(new SkillCapabilityLookup.Scope(a.tenantId(),a.clientId(),a.ownerJiacn(),state.getAgentId()),auth,
                    app.installation(a.tenantId(),a.clientId(),auth.getInstallationId()),entitlement),409,"SKILL_STATE_CONFLICT");
        var runtime=runtimes.findInScope(a.tenantId(),a.clientId(),state.getAgentId());
        require(runtime!=null&&auth.getRuntimeInstallationId().equals(runtime.getRuntimeInstallationId())&&auth.getRuntimeHostId().equals(runtime.getRuntimeHostId()),409,"SKILL_RUNTIME_MAPPING_REQUIRED");
        return auth;
    }
    private void intent(HostingRentHttp.Actor a,SkillManagedStateEntity state,Map<String,Object> data) {
        require(state.getSkillRevision().toString().equals(data.get("expectedSkillRevision")),409,"SKILL_STATE_CONFLICT");
        require(state.getPendingChangeId()==null,409,"SKILL_CHANGE_PENDING");
        var source=source(a,state,data.get("sourceSkillInstallationId"));
        require("INSTALL".equals(data.get("action"))?source==null:source!=null,409,"SKILL_STATE_CONFLICT");
        if("INSTALL".equals(data.get("action"))) {
            // Unmanaged historical evidence is never silently treated as absence or fabricated into SM-C1.
            for(var e:app.lockSkillKey(a.tenantId(),a.clientId(),state.getAgentId(),state.getSkillKey())) {
                var auth=db.findAuthorization(a.tenantId(),a.clientId(),e.getInstallationId());
                var old=auth==null?null:db.findChange(a.tenantId(),a.clientId(),auth.getChangeId());
                require(old!=null&&Set.of("FAILED_RETRYABLE","FAILED_FINAL").contains(dto(old).get("status")),409,"SKILL_ALREADY_ORDERED");
            }
        }
    }
    private record Prepared(SkillProductVersionEntity version,Map<String,Object> target,Map<String,Object> details) {}
    private Prepared prepare(HostingRentHttp.Actor a,String productVersion,boolean admin) {
        var v=market.selectPurchasableProductVersion(a.tenantId(),a.clientId(),productVersion);
        require(v!=null,409,"SKILL_VERSION_NOT_PUBLISHED");
        require(admin||"NONE".equals(v.getDeploymentRestriction()),403,"AGENT_FORBIDDEN");
        var pkg=packages.getObject().prepare(v);var manifest=packages.getObject().manifest(pkg);
        var permissions=SkillMarketplaceHttp.permissions(v.getApprovedPermissionsManifest());
        var sorted=new ArrayList<>(permissions);sorted.sort(UTF8);require(sorted.equals(permissions),409,"SKILL_PERMISSIONS_MISMATCH");
        var target=map("skillInstallationId",id("si_"),"productVersionId",v.getProductVersionId(),"skillVersion",v.getSkillVersion(),
                "packageSha256",HexFormat.of().formatHex(v.getPackageSha256()),"manifestSha256",manifest==null?null:manifest.manifestSha256(),
                "byteLength",v.getPackageSize().toString(),"kind",manifest==null?"INSTRUCTION":"EXECUTABLE");
        var types=new ArrayList<String>();var requirements=new ArrayList<String>();
        if(manifest!=null) {
            manifest.document().at("/inputs/acceptedMimeTypes").forEach(n->types.add(n.asText()));
            var r=manifest.document().get("requirements");if(r!=null)r.fields().forEachRemaining(e->{if(!e.getValue().isNull())requirements.add(e.getKey()+": "+e.getValue().toString());});
        }
        var details=map("productVersionId",v.getProductVersionId(),"skillVersion",v.getSkillVersion(),"packageSha256",target.get("packageSha256"),
                "manifestSha256",target.get("manifestSha256"),"byteLength",target.get("byteLength"),"kind",target.get("kind"),"inputMimeTypes",types,
                "requiredPermissions",permissions,"requirements",requirements,"changeSummary","","executionCostDescription",null);
        validate("PackageRef",target);validate("VersionDetails",details);return new Prepared(v,target,details);
    }
    private void published(HostingRentHttp.Actor a,Prepared prepared) {
        var v=prepared.version();var product=market.selectProductForUpdate(a.tenantId(),a.clientId(),v.getProductId());
        require(product!=null&&"PUBLISHED".equals(product.getStatus())&&v.getProductVersionId().equals(product.getCurrentProductVersionId()),409,"SKILL_VERSION_NOT_PUBLISHED");
        var locked=market.selectApprovedProductVersionForUpdate(a.tenantId(),a.clientId(),v.getProductVersionId());
        require(locked!=null&&Arrays.equals(v.getPackageSha256(),locked.getPackageSha256())&&Objects.equals(v.getPriceMicro(),locked.getPriceMicro())
                &&Arrays.equals(v.getApprovedPermissionsSha256(),locked.getApprovedPermissionsSha256()),409,"SKILL_QUOTE_MISMATCH");
    }
    private SkillManagementRequestEntity replay(HostingRentHttp.Actor a,String path,String key,Map<String,Object> data) {
        var row=db.lockRequest(a.tenantId(),a.clientId(),a.actorId(),"POST",path,hash("cyf.skill.idempotency.v1",key));
        if(row!=null)require(row.getRequestSha256().equals(hash("cyf.skill.request.v1",map("method","POST","path",path,"data",data))),409,"IDEMPOTENCY_CONFLICT");return row;
    }
    private void remember(HostingRentHttp.Actor a,String path,String key,Map<String,Object> data,String resource) {
        one(db.insertRequest(new SkillManagementRequestEntity().setTenantId(a.tenantId()).setClientId(a.clientId()).setActorId(a.actorId())
                .setMethod("POST").setPath(path).setKeyHash(hash("cyf.skill.idempotency.v1",key))
                .setRequestSha256(hash("cyf.skill.request.v1",map("method","POST","path",path,"data",data))).setResourceId(resource)));
    }
    public Map<String,Object> quote(HostingRentHttp.Actor a,String key,Map<String,Object> data,boolean admin) {
        validate("QuoteRequestData",data);permissionList(data.get("approvedPermissions"));String agent=text(data,"agentId");
        // Resolve accepted replay before package access, expiry, source CAS or availability changes.
        var replay=owned(a,agent,null,false,()->{var r=replay(a,"/agent/skill-orders/quotes",key,data);
            return r==null?null:decode(db.findQuote(a.tenantId(),a.clientId(),r.getResourceId()).getDtoJson(),"Quote");});
        if(replay!=null)return replay;
        var prepared=prepare(a,text(data,"targetProductVersionId"),admin);
        return owned(a,agent,null,false,()->{
            var prior=replay(a,"/agent/skill-orders/quotes",key,data);if(prior!=null)return decode(db.findQuote(a.tenantId(),a.clientId(),prior.getResourceId()).getDtoJson(),"Quote");
            // Identity/ownership locks are already held. An accepted identical request wins
            // over stale intent preconditions, including a concurrent admission of this key.
            requireVersion(a,agent,Long.valueOf(text(data,"expectedAgentVersion")));
            if("UPGRADE".equals(data.get("action")))require(online(a,agent),409,"SKILL_AGENT_OFFLINE");
            var state=state(a,agent,text(data,"skillKey"));intent(a,state,data);published(a,prepared);
            require(state.getSkillKey().equals(prepared.version().getSkillKey()),409,"SKILL_QUOTE_MISMATCH");
            require(json(data.get("approvedPermissions")).equals(json(prepared.details().get("requiredPermissions"))),409,"SKILL_PERMISSIONS_MISMATCH");
            var src=source(a,state,data.get("sourceSkillInstallationId"));String coverage="PURCHASE",entitlement=null;long price=prepared.version().getPriceMicro();
            if(src!=null) {
                require(!ref(src).get("productVersionId").equals(data.get("targetProductVersionId")),409,"SKILL_STATE_CONFLICT");
                String policy=db.upgradePolicy(a.tenantId(),a.clientId(),text(ref(src),"productVersionId"),text(data,"targetProductVersionId"));
                require(policy!=null,409,"SKILL_UPGRADE_POLICY_UNSET");require(!"NOT_ALLOWED".equals(policy),409,"SKILL_UPGRADE_NOT_ALLOWED");
                if("COVERED".equals(policy)){coverage="ENTITLEMENT";entitlement=src.getEntitlementId();price=0;}
                else require("PAID".equals(policy),409,"SKILL_UPGRADE_POLICY_UNSET");
            }
            long now=System.currentTimeMillis();String quote=id("sq_");
            var result=map("quoteId",quote,"action",data.get("action"),"agentId",agent,"skillKey",state.getSkillKey(),
                    "sourceSkillInstallationId",data.get("sourceSkillInstallationId"),"targetProductVersionId",data.get("targetProductVersionId"),
                    "targetSkillVersion",prepared.target().get("skillVersion"),"targetPackageSha256",prepared.target().get("packageSha256"),
                    "expectedAgentVersion",data.get("expectedAgentVersion"),"expectedSkillRevision",data.get("expectedSkillRevision"),
                    "approvedPermissionsSha256",permissionsHash(data.get("approvedPermissions")),"price",money(price),"coverage",coverage,"entitlementId",entitlement,"expiresAt",now+300000L);
            validate("Quote",result);
            one(market.insertPurchaseQuote(new SkillPurchaseQuoteEntity().setQuoteId(quote).setActorType("USER").setActorId(a.actorId())
                    .setIdempotencyKey(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .setRequestHash(HexFormat.of().parseHex(hash("cyf.skill.request.v1",map("method","POST","path","/agent/skill-orders/quotes","data",data))))
                    .setProductVersionId(prepared.version().getProductVersionId()).setTargetAgentId(agent).setExpectedAgentVersion(Long.valueOf(text(data,"expectedAgentVersion")))
                    .setExpectedPriceMicro(price).setApprovedPermissionsManifest(prepared.version().getApprovedPermissionsManifest())
                    .setApprovedPermissionsSha256(prepared.version().getApprovedPermissionsSha256()).setDeploymentRestriction(prepared.version().getDeploymentRestriction())
                    .setExpiresAt(now+300000L).setTenantId(a.tenantId()).setClientId(a.clientId()).setCreateTime(now)));
            one(db.insertQuote(new SkillManagementQuoteEntity().setTenantId(a.tenantId()).setClientId(a.clientId()).setQuoteId(quote).setActorId(a.actorId())
                    .setRequestJson(json(data)).setDtoJson(json(result)).setTargetJson(json(prepared.target())).setDetailsJson(json(prepared.details()))));
            remember(a,"/agent/skill-orders/quotes",key,data,quote);return result;
        });
    }
    public Map<String,Object> create(HostingRentHttp.Actor a,String agent,String key,String requestId,Map<String,Object> data,boolean admin) {
        validate("CreateChangeData",data);String route="/agent/"+path(agent)+"/skill-changes";
        var replay=owned(a,agent,null,false,()->{var r=replay(a,route,key,data);return r==null?null:visibleChange(a,agent,r.getResourceId(),false);});
        if(replay!=null)return replay;
        boolean install=Set.of("INSTALL","UPGRADE").contains(text(data,"action"));
        var prepared=install?prepare(a,text(data,"targetProductVersionId"),admin):null;
        return owned(a,agent,null,false,()->{
            var prior=replay(a,route,key,data);if(prior!=null)return visibleChange(a,agent,prior.getResourceId(),false);
            // Recheck replay under the same actor/runtime/ownership locks BEFORE new-intent
            // CAS and readiness. The first concurrent acceptance consumes the agent version.
            requireVersion(a,agent,Long.valueOf(text(data,"expectedAgentVersion")));
            if(Set.of("UPGRADE","ENABLE").contains(text(data,"action")))require(online(a,agent),409,"SKILL_AGENT_OFFLINE");
            var state=state(a,agent,text(data,"skillKey"));intent(a,state,data);
            var src=source(a,state,data.get("sourceSkillInstallationId"));
            Map<String,Object> quote=null,target,details;String permissions;String orderId=null;Long escrowVersion=null;
            SkillManagementQuoteEntity q=null;
            if(install) {
                permissionList(data.get("approvedPermissions"));published(a,prepared);
                q=db.lockQuote(a.tenantId(),a.clientId(),text(data,"quoteId"));
                require(q!=null&&a.actorId().equals(q.getActorId()),404,"RESOURCE_NOT_FOUND");quote=decode(q.getDtoJson(),"Quote");
                require(((Number)quote.get("expiresAt")).longValue()>System.currentTimeMillis(),409,"SKILL_QUOTE_EXPIRED");
                var original=decode(q.getRequestJson(),"QuoteRequestData");var compare=new LinkedHashMap<>(data);
                compare.remove("quoteId");compare.remove("expectedPrice");compare.put("agentId",agent);
                require(json(original).equals(json(compare))&&json(data.get("expectedPrice")).equals(json(quote.get("price")))
                        &&prepared.target().get("packageSha256").equals(quote.get("targetPackageSha256")),409,"SKILL_QUOTE_MISMATCH");
                require(json(data.get("approvedPermissions")).equals(json(prepared.details().get("requiredPermissions"))),409,"SKILL_PERMISSIONS_MISMATCH");
                require(app.orderForQuote(a.tenantId(),a.clientId(),q.getQuoteId())==null,409,"SKILL_QUOTE_MISMATCH");
                if(src!=null) {
                    String policy=db.upgradePolicy(a.tenantId(),a.clientId(),text(ref(src),"productVersionId"),text(data,"targetProductVersionId"));
                    require(policy!=null,409,"SKILL_UPGRADE_POLICY_UNSET");require(!"NOT_ALLOWED".equals(policy),409,"SKILL_UPGRADE_NOT_ALLOWED");
                    require("COVERED".equals(policy)?"ENTITLEMENT".equals(quote.get("coverage")):"PAID".equals(policy)&&"PURCHASE".equals(quote.get("coverage")),409,"SKILL_QUOTE_MISMATCH");
                }
                target=decode(q.getTargetJson(),"PackageRef");details=decode(q.getDetailsJson(),"VersionDetails");permissions=json(data.get("approvedPermissions"));
            } else {target=ref(src);details=decode(src.getDetailsJson(),"VersionDetails");permissions=src.getPermissionsJson();}
            terminateSupersededFailures(a,state);
            long now=System.currentTimeMillis(),revision=Math.addExact(state.getSkillRevision(),1);
            if(install&&"PURCHASE".equals(quote.get("coverage"))) {
                orderId=id("so_");long amount=Long.parseLong(text(object(quote.get("price")),"amountMinor"));
                var reserve=marketplace.reserve(a,orderId,amount,now);escrowVersion=reserve==null?null:reserve.escrow().escrowVersion();
                var v=prepared.version();
                var order=new SkillOrderEntity().setOrderId(orderId).setQuoteId(q.getQuoteId()).setProductVersionId(v.getProductVersionId())
                        .setTargetAgentId(agent).setBuyerType("USER").setBuyerId(a.actorId()).setSellerType("SYSTEM").setSellerId("SKILL_STORE")
                        .setPriceMicro(amount).setExpectedAgentVersion(Long.valueOf(text(data,"expectedAgentVersion"))).setPermissionGrantVersion(1L)
                        .setApprovedPermissionsManifest(v.getApprovedPermissionsManifest()).setApprovedPermissionsSha256(v.getApprovedPermissionsSha256())
                        .setEscrowId(reserve==null?null:reserve.escrow().escrowId()).setReserveTransactionId(reserve==null?null:reserve.transactionId())
                        .setStatus("FUNDS_HELD").setVersion(1L).setTenantId(a.tenantId()).setClientId(a.clientId()).setHeldAt(now).setUpdateTime(now);
                one(market.insertOrder(order));one(market.markOrderInstalling(a.tenantId(),a.clientId(),orderId,1,now));
            }
            var runtime=runtimes.findInScope(a.tenantId(),a.clientId(),agent);boolean connected=online(a,agent);
            boolean desired="INSTALL".equals(data.get("action"))||"ENABLE".equals(data.get("action"))
                    ||"UPGRADE".equals(data.get("action"))&&Boolean.TRUE.equals(state.getDesiredEnabled());
            var result=map("skillChangeId",id("sc_"),"agentId",agent,"skillKey",state.getSkillKey(),"action",data.get("action"),
                    "skillRevision",Long.toString(revision),"changeRevision","1","attempt",1,"source",src==null?null:ref(src),"target",target,
                    "status","DISABLE".equals(data.get("action"))?"SUCCEEDED":connected?(install?"PREPARING":"ACTIVATING"):"WAITING_AGENT",
                    "desiredEnabled",desired,"runtimeSynced",false,"reasonCode",connected?null:"AGENT_OFFLINE","retryAllowed",false,
                    "orderId",orderId,"fundingStatus",orderId!=null&&Long.parseLong(text(object(quote.get("price")),"amountMinor"))>0?"HELD":"NONE",
                    "price",quote==null?null:quote.get("price"),"actorLabel",a.ownerJiacn(),"createdAt",now,"updatedAt",now);
            var change=new SkillChangeEntity().setTenantId(a.tenantId()).setClientId(a.clientId()).setChangeId(text(result,"skillChangeId"))
                    .setAgentId(agent).setSkillKey(state.getSkillKey()).setActorId(a.actorId()).setOwnerJiacn(a.ownerJiacn()).setVersion(1L)
                    .setDtoJson(json(result)).setPermissionsJson(permissions).setRuntimeInstallationId(runtime.getRuntimeInstallationId())
                    .setRuntimeHostId(runtime.getRuntimeHostId()).setCreatedAt(now).setUpdatedAt(now);
            if(install)change.setEntitlementId(id("se_"));else change.setEntitlementId(src.getEntitlementId());
            var command=command(change,result,requestId,install?"PREPARE":"POLICY",null);
            change.setCommandId(command.getCommandId());one(db.insertChange(change));
            if(install)candidate(a,change,result,details,escrowVersion);
            long before=state.getSkillRevision();state.setSkillRevision(revision).setDesiredEnabled(desired);
            if(!install||!desired)state.setAdmissionEnabled(false);
            if(!install)state.setActivePolicyRevision(revision);
            if(!"DISABLE".equals(data.get("action")))state.setPendingChangeId(change.getChangeId());
            one(db.saveState(state,before));
            versions.consume(a,agent,Long.parseLong(text(data,"expectedAgentVersion")));
            remember(a,route,key,data,change.getChangeId());validate("SkillChange",result);return result;
        });
    }
    private void candidate(HostingRentHttp.Actor a,SkillChangeEntity c,Map<String,Object> value,Map<String,Object> details,Long escrow) {
        var target=object(value.get("target"));String install=text(target,"skillInstallationId");long now=System.currentTimeMillis();
        var cmd=db.findCommand(a.tenantId(),a.clientId(),c.getCommandId());var data=decode(cmd.getDataJson(),"CommandData");
        var i=new SkillInstallationEntity().setInstallationId(install).setOrderId(text(value,"orderId")).setProductVersionId(text(target,"productVersionId"))
                .setTargetAgentId(c.getAgentId()).setSchemaVersion(2).setMessageType("command.dispatch").setMessageId(cmd.getMessageId()).setRequestId(cmd.getMessageId())
                .setCommandType("SKILL_INSTALL").setCommandId(cmd.getCommandId()).setAttempt(((Number)value.get("attempt")).intValue())
                .setFencingToken(Long.valueOf(text(value,"skillRevision"))).setDeliveryEpoch(1L).setSkillKey(c.getSkillKey()).setSkillVersion(text(target,"skillVersion"))
                .setPackageSize(Long.valueOf(text(target,"byteLength"))).setPackageSha256(HexFormat.of().parseHex(text(target,"packageSha256")))
                .setDownloadPath(text(data,"downloadPath")).setStatus("REQUESTED").setVersion(1L).setTenantId(a.tenantId()).setClientId(a.clientId()).setCreateTime(now).setUpdateTime(now);
        one(market.insertInstallation(i));
        one(market.markInstallationInstalling(a.tenantId(),a.clientId(),c.getAgentId(),install,cmd.getCommandId(),i.getAttempt(),i.getFencingToken(),1,1,now));
        one(app.insertDeliveryBinding(a.tenantId(),a.clientId(),install,c.getAgentId(),c.getRuntimeInstallationId(),c.getRuntimeHostId(),escrow));
        // Marketplace permission hash is the immutable existing package permission manifest hash.
        var version=market.selectApprovedProductVersion(a.tenantId(),a.clientId(),text(target,"productVersionId"));
        one(market.insertEntitlement(new SkillEntitlementEntity().setEntitlementId(c.getEntitlementId()).setOrderId(text(value,"orderId")).setInstallationId(install)
                .setProductVersionId(text(target,"productVersionId")).setTargetAgentId(c.getAgentId()).setSkillKey(c.getSkillKey()).setSkillVersion(text(target,"skillVersion"))
                .setPermissionGrantVersion(1L).setApprovedPermissionsManifest(version.getApprovedPermissionsManifest()).setApprovedPermissionsSha256(version.getApprovedPermissionsSha256())
                .setStatus("PENDING_INSTALLATION").setVersion(1L).setTenantId(a.tenantId()).setClientId(a.clientId()).setCreateTime(now).setUpdateTime(now)));
        one(db.insertAuthorization(new SkillInstallationAuthorizationEntity().setTenantId(a.tenantId()).setClientId(a.clientId()).setInstallationId(install)
                .setChangeId(c.getChangeId()).setEntitlementId(c.getEntitlementId()).setPackageJson(json(target)).setDetailsJson(json(details))
                .setPermissionsJson(c.getPermissionsJson()).setPermissionsSha256(permissionsHash(decodePermissions(c.getPermissionsJson())))
                .setRuntimeInstallationId(c.getRuntimeInstallationId()).setRuntimeHostId(c.getRuntimeHostId())));
    }
    private static List<String> decodePermissions(String value) {
        try {return new com.fasterxml.jackson.databind.ObjectMapper().readValue(value,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});}
        catch(Exception invalid){throw new SkillMarketplaceException(503,"SKILL_STATE_UNAVAILABLE");}
    }
    private SkillChangeCommandEntity command(SkillChangeEntity c,Map<String,Object> value,String requestId,String phase,SkillChangeCommandEntity prepared) {
        String id=id("cmd_"),message=id("msg_");
        var data=map("schemaVersion",2,"commandId",id,"skillChangeId",c.getChangeId(),"action",value.get("action"),"tenantId",c.getTenantId(),
                "clientId",c.getClientId(),"ownerJiacn",c.getOwnerJiacn(),"agentId",c.getAgentId(),"runtimeInstallationId",c.getRuntimeInstallationId(),
                "skillKey",c.getSkillKey(),"attempt",value.get("attempt"),"fencingToken",value.get("skillRevision"),"deliveryEpoch","1","skillRevision",value.get("skillRevision"),
                "source",value.get("source"),"target",value.get("target"),"approvedPermissionsSha256",permissionsHash(decodePermissions(c.getPermissionsJson())),
                "desiredEnabled",value.get("desiredEnabled"),"issuedAt",System.currentTimeMillis(),"phase",phase,
                "commandType","READ".equals(phase)?"SKILL_RECONCILE":"POLICY".equals(phase)?"SKILL_SET_ENABLED":"SKILL_INSTALL");
        if("PREPARE".equals(phase))data.put("downloadPath","/internal/agent/skill-installations/"+object(value.get("target")).get("skillInstallationId")+"/package");
        if("ACTIVATE".equals(phase)){data.put("preparedCommandId",prepared.getCommandId());data.put("preparedSha256",prepared.getPreparedSha256());}
        if("READ".equals(phase))data.put("observedCommandId",prepared.getCommandId());
        var hashable=new LinkedHashMap<>(data);hashable.remove("deliveryEpoch");data.put("commandSha256",hash("cyf.skill.command.v2",hashable));validate("CommandData",data);
        var envelope=map("protocolVersion",1,"requestId",requestId,"messageType","command.dispatch","messageId",message,"data",data);
        validate("Command",envelope);
        var row=new SkillChangeCommandEntity().setTenantId(c.getTenantId()).setClientId(c.getClientId()).setCommandId(id).setChangeId(c.getChangeId())
                .setRequestId(requestId).setMessageId(message).setDataJson(json(data)).setState("PENDING");
        one(db.insertCommand(row));writer.getObject().writeManagement(c,bytes(envelope),row,data);return row;
    }
    private void save(SkillChangeEntity c,Map<String,Object> value) {
        long next=Math.addExact(c.getVersion(),1);value.put("changeRevision",Long.toString(next));value.put("updatedAt",System.currentTimeMillis());validate("SkillChange",value);
        c.setDtoJson(json(value)).setUpdatedAt(((Number)value.get("updatedAt")).longValue());one(db.saveChange(c));c.setVersion(next);
    }
    private void terminateSupersededFailures(HostingRentHttp.Actor a,SkillManagedStateEntity state) {
        for(var hint:db.changes(a.tenantId(),a.clientId(),state.getAgentId())) {
            if(!state.getSkillKey().equals(hint.getSkillKey()))continue;var value=dto(hint);
            if(!"FAILED_RETRYABLE".equals(value.get("status")))continue;
            var c=db.lockChange(a.tenantId(),a.clientId(),hint.getChangeId());value=dto(c);
            if(!"FAILED_RETRYABLE".equals(value.get("status")))continue;
            if(value.get("orderId")!=null) {
                var order=market.selectOrderForUpdate(a.tenantId(),a.clientId(),text(value,"orderId"));
                require(order!=null&&Set.of("INSTALLING","REFUNDED").contains(order.getStatus()),409,"SKILL_STATE_CONFLICT");
                if(!"REFUNDED".equals(order.getStatus())) {
                    var binding=app.deliveryBinding(a.tenantId(),a.clientId(),text(object(value.get("target")),"skillInstallationId"));
                    String transaction=marketplace.settle(a,order,binding,false,System.currentTimeMillis());
                    one(market.markOrderRefunded(a.tenantId(),a.clientId(),order.getOrderId(),order.getVersion(),transaction,System.currentTimeMillis()));
                }
                value.put("fundingStatus",order.getPriceMicro()>0?"REFUNDED":"NONE");
            }
            value.put("status","FAILED_FINAL");value.put("retryAllowed",false);value.put("reasonCode","STATE_CHANGED");save(c,value);
        }
    }
    private Map<String,Object> visibleChange(HostingRentHttp.Actor a,String agent,String id,boolean lock) {
        var c=lock?db.lockChange(a.tenantId(),a.clientId(),id):db.findChange(a.tenantId(),a.clientId(),id);
        require(c!=null&&agent.equals(c.getAgentId())&&a.ownerJiacn().equals(c.getOwnerJiacn()),404,"RESOURCE_NOT_FOUND");return dto(c);
    }
    public Map<String,Object> change(HostingRentHttp.Actor a,String agent,String id) {
        path(id);return queryOwned(a,agent,()->visibleChange(a,agent,id,false));
    }
    public Map<String,Object> changes(HostingRentHttp.Actor a,String agent) {
        return queryOwned(a,agent,()->map("agentId",agent,"items",db.changes(a.tenantId(),a.clientId(),agent).stream()
                .filter(c->a.ownerJiacn().equals(c.getOwnerJiacn())).map(SkillManagementService::dto).toList()));
    }
    public Map<String,Object> retry(HostingRentHttp.Actor a,String agent,String change,String key,String requestId,Map<String,Object> data) {
        validate("ChangeActionData",data);String route="/agent/"+path(agent)+"/skill-changes/"+path(change)+"/retry";
        return owned(a,agent,null,false,()->{
            var r=replay(a,route,key,data);if(r!=null)return visibleChange(a,agent,r.getResourceId(),false);
            var c=db.lockChange(a.tenantId(),a.clientId(),change);
            require(c!=null&&agent.equals(c.getAgentId())&&a.ownerJiacn().equals(c.getOwnerJiacn()),404,"RESOURCE_NOT_FOUND");
            var value=dto(c);require(value.get("changeRevision").equals(data.get("expectedChangeRevision")),409,"SKILL_STATE_CONFLICT");
            require("FAILED_RETRYABLE".equals(value.get("status"))&&Boolean.TRUE.equals(value.get("retryAllowed"))
                    &&Set.of("NONE","HELD").contains(value.get("fundingStatus")),409,"SKILL_RETRY_NOT_ALLOWED");
            var state=state(a,agent,c.getSkillKey());
            require(state.getPendingChangeId()==null&&state.getSkillRevision().toString().equals(value.get("skillRevision"))
                    &&Objects.equals(state.getActiveInstallationId(),value.get("source")==null?null:object(value.get("source")).get("skillInstallationId")),409,"SKILL_STATE_CONFLICT");
            if(value.get("orderId")!=null) {
                var order=market.selectOrderForUpdate(a.tenantId(),a.clientId(),text(value,"orderId"));
                require(order!=null&&"INSTALLING".equals(order.getStatus())&&order.getRefundTransactionId()==null,409,"SKILL_RETRY_NOT_ALLOWED");
            }
            boolean install=Set.of("INSTALL","UPGRADE").contains(text(value,"action"));
            if(!"INSTALL".equals(value.get("action")))require(online(a,agent),409,"SKILL_AGENT_OFFLINE");
            var runtime=runtimes.findInScope(a.tenantId(),a.clientId(),agent);
            require(c.getRuntimeInstallationId().equals(runtime.getRuntimeInstallationId())&&c.getRuntimeHostId().equals(runtime.getRuntimeHostId()),409,"SKILL_RUNTIME_MAPPING_REQUIRED");
            var target=new LinkedHashMap<>(object(value.get("target")));var oldAuth=db.findAuthorization(a.tenantId(),a.clientId(),text(target,"skillInstallationId"));
            if(install)target.put("skillInstallationId",id("si_")); // Original target bytes and authorization never change.
            long before=state.getSkillRevision();long revision=Math.addExact(before,1);
            value.put("target",target);value.put("attempt",Math.addExact(((Number)value.get("attempt")).intValue(),1));value.put("skillRevision",Long.toString(revision));
            value.put("status",online(a,agent)?install?"PREPARING":"ACTIVATING":"WAITING_AGENT");value.put("reasonCode",online(a,agent)?null:"AGENT_OFFLINE");
            value.put("retryAllowed",false);value.put("runtimeSynced",false);
            if(install)c.setEntitlementId(id("se_"));
            var cmd=command(c,value,requestId,install?"PREPARE":"POLICY",null);c.setCommandId(cmd.getCommandId());
            if(install) {
                Long escrow=null;if(value.get("orderId")!=null) {
                    var original=app.deliveryBinding(a.tenantId(),a.clientId(),oldAuth.getInstallationId());escrow=original.getEscrowVersion();
                }
                candidate(a,c,value,decode(oldAuth.getDetailsJson(),"VersionDetails"),escrow);
            }
            save(c,value);state.setSkillRevision(revision).setPendingChangeId(change);if(!install)state.setActivePolicyRevision(revision);one(db.saveState(state,before));
            remember(a,route,key,data,change);return value;
        });
    }
    public Map<String,Object> reconcile(HostingRentHttp.Actor a,String agent,String change,String key,String requestId,Map<String,Object> data) {
        validate("ChangeActionData",data);String route="/agent/"+path(agent)+"/skill-changes/"+path(change)+"/reconcile";
        return owned(a,agent,null,false,()->{
            var r=replay(a,route,key,data);if(r!=null)return visibleChange(a,agent,r.getResourceId(),false);
            var c=db.lockChange(a.tenantId(),a.clientId(),change);require(c!=null&&agent.equals(c.getAgentId())&&a.ownerJiacn().equals(c.getOwnerJiacn()),404,"RESOURCE_NOT_FOUND");
            var value=dto(c);require(value.get("changeRevision").equals(data.get("expectedChangeRevision")),409,"SKILL_STATE_CONFLICT");
            var state=state(a,agent,c.getSkillKey());require(state.getSkillRevision().toString().equals(value.get("skillRevision")),409,"SKILL_STATE_CONFLICT");
            var observed=db.findCommand(a.tenantId(),a.clientId(),c.getCommandId());
            require(observed!=null,409,"SKILL_STATE_CONFLICT");
            // Reconcile is read-only at Runtime and coalesces concurrent requests for the same journal.
            boolean pending=db.commands(a.tenantId(),a.clientId(),change).stream().anyMatch(cmd->
                    "READ".equals(decode(cmd.getDataJson(),"CommandData").get("phase"))&&Set.of("PENDING","RECEIVED").contains(cmd.getState()));
            if(!pending)command(c,value,requestId,"READ",observed);
            remember(a,route,key,data,change);return value;
        });
    }
    private SkillCapabilityLookup.View catalog(HostingRentHttp.Actor a,String agent) {
        try {var proof=authentication.currentRegisteredProof(a.tenantId(),a.clientId(),agent);
            if(capabilities.getIfAvailable()==null)return null;
            var view=capabilities.getObject().lookup(new SkillCapabilityLookup.Scope(a.tenantId(),a.clientId(),a.ownerJiacn(),agent),SkillMarketplaceService.sessionFence(proof));
            return view.state()==SkillCapabilityLookup.State.AVAILABLE?view:null;
        }catch(IllegalArgumentException absent){return null;}
    }
    public Map<String,Object> skills(HostingRentHttp.Actor a,String agent,boolean admin) {
        enabled(a);path(agent);
        // Package bytes must be prepared outside all DB locks. Product pointer changes are rechecked below.
        var candidates=new LinkedHashMap<String,Prepared>();var products=new HashMap<String,SkillProductEntity>();
        for(var p:db.products(a.tenantId(),a.clientId())) {
            var prepared=prepare(a,p.getCurrentProductVersionId(),true);candidates.put(prepared.version().getSkillKey(),prepared);products.put(prepared.version().getSkillKey(),p);
        }
        return queryOwned(a,agent,()->{
            var states=new HashMap<String,SkillManagedStateEntity>();for(var s:db.states(a.tenantId(),a.clientId(),agent))states.put(s.getSkillKey(),s);
            var records=db.changes(a.tenantId(),a.clientId(),agent);var keys=new TreeSet<String>(UTF8);keys.addAll(candidates.keySet());keys.addAll(states.keySet());
            // Preserve legacy installed evidence as an explicit unknown row; never infer successful SM-C1 activation.
            for(var e:market.selectEntitlementsByAgent(a.tenantId(),a.clientId(),agent))keys.add(e.getSkillKey());
            var rows=new ArrayList<Map<String,Object>>();boolean connected=online(a,agent);var catalog=catalog(a,agent);
            var binding=runtimes.findInScope(a.tenantId(),a.clientId(),agent);boolean mapped=binding!=null&&binding.getRuntimeInstallationId()!=null&&binding.getRuntimeHostId()!=null;
            for(String skill:keys) {
                var s=states.get(skill);var candidate=candidates.get(skill);var product=products.get(skill);
                var auth=s==null||s.getActiveInstallationId()==null?null:db.findAuthorization(a.tenantId(),a.clientId(),s.getActiveInstallationId());
                var latest=records.stream().filter(c->skill.equals(c.getSkillKey())).findFirst().orElse(null);
                String eligibility="NOT_APPLICABLE";
                if(auth!=null&&candidate!=null&&!ref(auth).get("productVersionId").equals(candidate.target().get("productVersionId"))) {
                    String policy=db.upgradePolicy(a.tenantId(),a.clientId(),text(ref(auth),"productVersionId"),text(candidate.target(),"productVersionId"));
                    eligibility=policy==null?"POLICY_UNSET":"NOT_ALLOWED".equals(policy)?"NOT_ALLOWED":policy;
                }
                var runtime=runtime(a,agent,s,auth,catalog,connected);
                var allowed=new ArrayList<String>(List.of("VIEW"));var denied=new ArrayList<Map<String,Object>>();
                boolean pending=s!=null&&s.getPendingChangeId()!=null;String blocked=pending?"SKILL_CHANGE_PENDING":null;
                boolean historical=auth==null&&market.selectEntitlementsByAgent(a.tenantId(),a.clientId(),agent).stream()
                        .anyMatch(e->skill.equals(e.getSkillKey())&&db.findAuthorization(a.tenantId(),a.clientId(),e.getInstallationId())==null);
                if(auth==null) {
                    if(candidate!=null&&mapped&&!historical&&blocked==null&&(admin||"NONE".equals(candidate.version().getDeploymentRestriction())))allowed.add("INSTALL");
                    else denied.add(map("action","INSTALL","reasonCode",blocked!=null?blocked:!mapped?"RUNTIME_MAPPING_REQUIRED":historical?"INTEGRITY_UNPROVEN":"PUBLICATION_UNAVAILABLE"));
                } else {
                    if(Set.of("COVERED","PAID").contains(eligibility)&&connected&&blocked==null&&(admin||"NONE".equals(candidate.version().getDeploymentRestriction())))allowed.add("UPGRADE");
                    else if(!"NOT_APPLICABLE".equals(eligibility))denied.add(map("action","UPGRADE","reasonCode",blocked!=null?blocked:!connected?"AGENT_OFFLINE":candidate!=null&&!admin&&!"NONE".equals(candidate.version().getDeploymentRestriction())?"AGENT_FORBIDDEN":"POLICY_UNSET".equals(eligibility)?"UPGRADE_POLICY_UNSET":"UPGRADE_NOT_ALLOWED"));
                    String action=Boolean.TRUE.equals(s.getDesiredEnabled())?"DISABLE":"ENABLE";
                    if(blocked==null&&("DISABLE".equals(action)||connected))allowed.add(action);
                    else denied.add(map("action",action,"reasonCode",blocked!=null?blocked:"AGENT_OFFLINE"));
                }
                if(latest!=null) {
                    var value=dto(latest);if(Boolean.TRUE.equals(value.get("retryAllowed")))allowed.add("RETRY");
                    if(Set.of("RECOVERY_REQUIRED","VERIFYING","WAITING_AGENT","ACTIVATING","PREPARING").contains(value.get("status")))allowed.add("RECONCILE");
                }
                rows.add(map("skillKey",skill,"name",product==null?skill:product.getName(),"description",product==null?"":product.getDescription(),
                        "skillRevision",s==null?"0":s.getSkillRevision().toString(),"installed",auth==null?null:map("skillInstallationId",auth.getInstallationId(),"version",decode(auth.getDetailsJson(),"VersionDetails")),
                        "candidate",candidate==null?null:candidate.details(),"upgradeEligibility",eligibility,"desiredEnabled",s!=null&&Boolean.TRUE.equals(s.getDesiredEnabled()),
                        "admissionEnabled",s!=null&&Boolean.TRUE.equals(s.getAdmissionEnabled())&&"READY".equals(runtime.get("state")),"runtime",runtime,
                        "allowedActions",allowed,"deniedActions",denied,"latestChange",latest==null?null:dto(latest)));
            }
            var result=map("agentId",agent,"agentVersion",Long.toString(versions.readOwned(a,agent)),"items",rows);validate("SkillList",result);return result;
        });
    }
    private Map<String,Object> runtime(HostingRentHttp.Actor a,String agent,SkillManagedStateEntity state,SkillInstallationAuthorizationEntity auth,SkillCapabilityLookup.View view,boolean connected) {
        if(!connected)return map("state","OFFLINE","reasonCode","AGENT_OFFLINE","observedAt",null,"catalogRevision",null);
        if(auth==null||state==null||view==null)return map("state","UNKNOWN","reasonCode","CATALOG_UNCONFIRMED","observedAt",null,"catalogRevision",null);
        var found=view.catalog().skills().stream().filter(f->auth.getInstallationId().equals(f.skillInstallationId())).toList();
        if(found.size()!=1)return map("state","VERIFYING","reasonCode","CATALOG_UNCONFIRMED","observedAt",null,"catalogRevision",view.catalog().catalogRevision());
        var fact=found.getFirst();boolean exact=exactCatalog(a,state,auth,view,fact);
        return map("state",exact?(fact.readiness()==SkillCapabilityLookup.Readiness.UNAVAILABLE?"UNKNOWN":fact.readiness().name()):"VERSION_MISMATCH","reasonCode",exact?fact.reasonCode():"VERSION_MISMATCH",
                "observedAt",fact.observedAt(),"catalogRevision",view.catalog().catalogRevision());
    }
    private boolean exactCatalog(HostingRentHttp.Actor a,SkillManagedStateEntity state,SkillInstallationAuthorizationEntity auth,
            SkillCapabilityLookup.View view,SkillCapabilityLookup.RuntimeSkill fact) {
        var pkg=ref(auth);
        return auth.getInstallationId().equals(fact.skillInstallationId())
                &&auth.getRuntimeInstallationId().equals(view.fence().runtimeInstallationId())&&auth.getRuntimeHostId().equals(view.fence().hostId())
                &&Objects.equals(pkg.get("kind"),fact.kind())&&Objects.equals(pkg.get("productVersionId"),fact.productVersionId())&&Objects.equals(pkg.get("skillVersion"),fact.skillVersion())
                &&Objects.equals(pkg.get("packageSha256"),fact.packageSha256())&&Objects.equals(pkg.get("manifestSha256"),fact.manifestSha256())
                &&effectivePolicyRevision(a,state).equals(fact.policyRevision())&&Boolean.TRUE.equals(state.getDesiredEnabled())==fact.enabled();
    }
    private String effectivePolicyRevision(HostingRentHttp.Actor a,SkillManagedStateEntity state) {
        // The accepted management fence can advance while PREPARE (or a confirmed preparation
        // failure) leaves the old active policy untouched. Never compare that old package with
        // the candidate's policy fence or make READY depend on the current product pointer.
        return Long.toString(state.getActivePolicyRevision()==null?state.getSkillRevision():state.getActivePolicyRevision());
    }
    /** Authenticated WS result consumer. Returning a receipt happens strictly after commit. */
    public Map<String,Object> result(AgentRuntimeAuthenticationService.Proof proof,Map<String,Object> envelope) {
        validate("Result",envelope);var data=object(envelope.get("data"));
        var hashable=new LinkedHashMap<>(data);hashable.remove("resultSha256");
        require(hash("cyf.skill.result.v2",hashable).equals(data.get("resultSha256")),409,"SKILL_RESULT_CONFLICT");
        var hint=db.findChange(proof.scope().tenantId(),proof.scope().clientId(),text(data,"skillChangeId"));
        require(hint!=null&&proof.scope().agentId().equals(hint.getAgentId())&&proof.scope().ownerJiacn().equals(hint.getOwnerJiacn())
                &&proof.installationId().equals(hint.getRuntimeInstallationId())&&proof.hostId().equals(hint.getRuntimeHostId())
                &&proof.scope().agentId().equals(data.get("agentId"))&&proof.installationId().equals(data.get("runtimeInstallationId")),409,"SKILL_DELIVERY_FENCED");
        var evidence=object(data.get("evidence"));
        var approved=market.selectApprovedProductVersion(proof.scope().tenantId(),proof.scope().clientId(),text(object(dto(hint).get("target")),"productVersionId"));
        var prepared="APPLIED".equals(evidence.get("outcome"))?packages.getObject().prepare(approved):null;
        var actor=new HostingRentHttp.Actor(hint.getActorId(),hint.getTenantId(),hint.getClientId(),hint.getOwnerJiacn());
        return owned(actor,hint.getAgentId(),null,false,()->authentication.withFence(proof,false,()->{
            var state=state(actor,hint.getAgentId(),hint.getSkillKey());
            var c=db.lockChange(hint.getTenantId(),hint.getClientId(),hint.getChangeId());var value=dto(c);
            String message=text(envelope,"messageId"),wholeHash=hash("cyf.skill.result.message.v1",envelope);
            var prior=db.lockResult(c.getTenantId(),c.getClientId(),message);
            if(prior!=null){require(wholeHash.equals(prior.getResultSha256()),409,"SKILL_RESULT_CONFLICT");return decode(prior.getReceiptJson(),"ResultReceipt");}
            var cmd=db.lockCommand(c.getTenantId(),c.getClientId(),text(data,"commandId"));
            require(cmd!=null&&c.getChangeId().equals(cmd.getChangeId()),409,"SKILL_RESULT_CONFLICT");
            var command=decode(cmd.getDataJson(),"CommandData");
            for(String f:List.of("attempt","fencingToken","deliveryEpoch","skillRevision"))require(Objects.equals(command.get(f),data.get(f)),409,"SKILL_DELIVERY_FENCED");
            String phase=text(command,"phase");
            var observed="READ".equals(phase)?db.lockCommand(c.getTenantId(),c.getClientId(),text(command,"observedCommandId")):cmd;
            require(observed!=null&&observed.getCommandId().equals(evidence.get("observedCommandId")),409,"SKILL_RESULT_CONFLICT");
            var observedData=decode(observed.getDataJson(),"CommandData");
            require(observedData.get("phase").equals(evidence.get("phase")),409,"SKILL_RESULT_CONFLICT");
            boolean stale=!value.get("skillRevision").equals(data.get("skillRevision"))||!value.get("attempt").equals(data.get("attempt"))
                    ||!state.getSkillRevision().toString().equals(value.get("skillRevision"))||!observed.getCommandId().equals(c.getCommandId());
            String receiptStatus=stale?"IGNORED_STALE":"ACCEPTED";
            if(!stale) {
                // A READ never changes the original command identity, target or financial intent.
                applyEvidence(actor,state,c,value,observed,observedData,evidence,prepared);
                if("READ".equals(phase)){cmd.setState("RESULT");one(db.saveCommand(cmd));}
            }
            if(stale&&Set.of("PENDING","RECEIVED").contains(cmd.getState())) {cmd.setState("STALE");one(db.saveCommand(cmd));}
            var receipt=map("protocolVersion",1,"requestId",envelope.get("requestId"),"messageType","work.result.receipt","messageId",id("receipt_"),
                    "correlationId",message,"code","E0","msg","ok","data",map("schemaVersion",2,"skillChangeId",c.getChangeId(),
                            "commandId",data.get("commandId"),"attempt",data.get("attempt"),"receiptStatus",receiptStatus));
            validate("ResultReceipt",receipt);
            one(db.insertResult(new SkillManagementResultEntity().setTenantId(c.getTenantId()).setClientId(c.getClientId()).setMessageId(message)
                    .setResultSha256(wholeHash).setReceiptJson(json(receipt))));return receipt;
        }));
    }
    private void applyEvidence(HostingRentHttp.Actor a,SkillManagedStateEntity state,SkillChangeEntity c,Map<String,Object> value,
            SkillChangeCommandEntity cmd,Map<String,Object> command,Map<String,Object> evidence,
            cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog.PlatformSkillPackage prepared) {
        String outcome=text(evidence,"outcome"),phase=text(command,"phase");long revision=state.getSkillRevision();
        // Repeated immutable phase results are accepted, but contradictory facts never change terminal money/state.
        if("RESULT".equals(cmd.getState())) {
            require(Objects.equals(cmd.getEvidenceJson(),json(evidence)),409,"SKILL_RESULT_CONFLICT");return;
        }
        boolean install=Set.of("INSTALL","UPGRADE").contains(text(value,"action"));
        if("PREPARED".equals(outcome)) {
            require("PREPARE".equals(phase),409,"SKILL_RESULT_CONFLICT");
            String expected=hash("cyf.skill.prepared.v1",map("skillChangeId",c.getChangeId(),"attempt",value.get("attempt"),"source",value.get("source"),
                    "target",value.get("target"),"approvedPermissionsSha256",command.get("approvedPermissionsSha256")));
            require(expected.equals(evidence.get("preparedSha256")),409,"SKILL_RESULT_CONFLICT");
            require(Objects.equals(state.getActiveInstallationId(),value.get("source")==null?null:object(value.get("source")).get("skillInstallationId")),409,"SKILL_STATE_CONFLICT");
            if(value.get("source")!=null)source(a,state,object(value.get("source")).get("skillInstallationId"));
            // Durable ACTIVATE intent closes ONLY this skill's new admission before any bytes leave API.
            state.setAdmissionEnabled(false);one(db.saveState(state,revision));
            cmd.setPreparedSha256(expected);var activate=command(c,value,cmd.getRequestId(),"ACTIVATE",cmd);c.setCommandId(activate.getCommandId());
            value.put("status","ACTIVATING");value.put("reasonCode",null);value.put("runtimeSynced",false);save(c,value);
            cmd.setState("RESULT").setEvidenceJson(json(evidence));one(db.saveCommand(cmd));return;
        }
        if("APPLIED".equals(outcome)) {
            require(!"PREPARE".equals(phase)&&json(value.get("target")).equals(json(evidence.get("actual")))
                    &&value.get("skillRevision").equals(evidence.get("appliedRevision")),409,"SKILL_RESULT_CONFLICT");
            var target=object(value.get("target"));String installId=text(target,"skillInstallationId");
            var approved=market.selectApprovedProductVersionForUpdate(a.tenantId(),a.clientId(),text(target,"productVersionId"));SkillPackages.revalidate(approved,prepared);
            if(install) {
                var order=value.get("orderId")==null?null:market.selectOrderForUpdate(a.tenantId(),a.clientId(),text(value,"orderId"));
                if(order!=null)require("INSTALLING".equals(order.getStatus()),409,"SKILL_RESULT_CONFLICT");
                var ent=db.entitlement(a.tenantId(),a.clientId(),c.getEntitlementId());
                require(ent!=null&&"PENDING_INSTALLATION".equals(ent.getStatus())&&installId.equals(ent.getInstallationId()),409,"SKILL_STATE_CONFLICT");
                String transaction=order==null?null:marketplace.settle(a,order,app.deliveryBinding(a.tenantId(),a.clientId(),installId),true,System.currentTimeMillis());
                var i=market.selectInstallationForUpdate(a.tenantId(),a.clientId(),c.getAgentId(),installId);
                long observed=((Number)evidence.get("observedAt")).longValue();
                one(market.markInstallationSucceeded(a.tenantId(),a.clientId(),c.getAgentId(),installId,i.getCommandId(),i.getAttempt(),i.getFencingToken(),i.getDeliveryEpoch(),i.getVersion(),i.getPackageSha256(),observed));
                one(market.markEntitlementActive(a.tenantId(),a.clientId(),c.getAgentId(),ent.getEntitlementId(),installId,ent.getVersion(),observed));
                if(order!=null) {
                    one(market.markOrderActive(a.tenantId(),a.clientId(),order.getOrderId(),order.getVersion(),transaction,System.currentTimeMillis()));
                    value.put("fundingStatus",order.getPriceMicro()>0?"CAPTURED":"NONE");
                }
                state.setActiveInstallationId(installId).setActivePolicyRevision(revision); // Older installations/entitlements remain immutable task references.
            } else require(installId.equals(state.getActiveInstallationId()),409,"SKILL_STATE_CONFLICT");
            state.setAdmissionEnabled(false);one(db.saveState(state,revision));
            value.put("status","DISABLE".equals(value.get("action"))?"SUCCEEDED":"VERIFYING");value.put("reasonCode","CATALOG_UNCONFIRMED");
            value.put("runtimeSynced",false);value.put("retryAllowed",false);
            save(c,value);cmd.setState("RESULT").setEvidenceJson(json(evidence));one(db.saveCommand(cmd));
            confirmCatalog(a,state,c,value);return;
        }
        if(Set.of("FAILED","NOT_STARTED").contains(outcome)&&!"DISABLE".equals(value.get("action"))
                &&("PREPARE".equals(phase)||"ENABLE".equals(value.get("action"))||"NOT_STARTED".equals(outcome))) {
            // FAILED/NOT_STARTED are accepted only as determinate non-switch facts. Security and
            // entitlement denials are final; absent/unknown evidence never enters this branch.
            String reason=evidence.get("reasonCode")==null?"PREPARATION_FAILED":text(evidence,"reasonCode");
            // Frozen public Reasons only; the no-switch phase/outcome gate above remains
            // mandatory even for a terminal identity/entitlement diagnostic.
            boolean recoverable=("PREPARE".equals(phase)||"ENABLE".equals(value.get("action")))
                    && !Set.of("IDENTITY_CHANGED","ENTITLEMENT_TERMINATED").contains(reason);
            value.put("status",recoverable?"FAILED_RETRYABLE":"FAILED_FINAL");value.put("retryAllowed",recoverable);value.put("reasonCode",reason);
            value.put("runtimeSynced",false);
            var failedOrder=value.get("orderId")==null?null:market.selectOrderForUpdate(a.tenantId(),a.clientId(),text(value,"orderId"));
            var failedEnt=install?db.entitlement(a.tenantId(),a.clientId(),c.getEntitlementId()):null;
            if(!recoverable&&failedOrder!=null) {
                require("INSTALLING".equals(failedOrder.getStatus()),409,"SKILL_STATE_CONFLICT");
                String refund=marketplace.settle(a,failedOrder,app.deliveryBinding(a.tenantId(),a.clientId(),text(object(value.get("target")),"skillInstallationId")),false,System.currentTimeMillis());
                one(market.markOrderRefunded(a.tenantId(),a.clientId(),failedOrder.getOrderId(),failedOrder.getVersion(),refund,System.currentTimeMillis()));
                value.put("fundingStatus",failedOrder.getPriceMicro()>0?"REFUNDED":"NONE");
            }
            if(install) {
                var target=object(value.get("target"));String installId=text(target,"skillInstallationId");
                var ent=failedEnt;long now=System.currentTimeMillis();
                var i=market.selectInstallationForUpdate(a.tenantId(),a.clientId(),c.getAgentId(),installId);
                one(market.markInstallationFailed(a.tenantId(),a.clientId(),c.getAgentId(),installId,i.getCommandId(),i.getAttempt(),i.getFencingToken(),i.getDeliveryEpoch(),i.getVersion(),text(value,"reasonCode"),now));
                one(market.markEntitlementFailed(a.tenantId(),a.clientId(),c.getAgentId(),ent.getEntitlementId(),installId,ent.getVersion(),now));
            }
            state.setPendingChangeId(null);one(db.saveState(state,revision));save(c,value);
            cmd.setState("RESULT").setEvidenceJson(json(evidence));one(db.saveCommand(cmd));return;
        }
        if("DISABLE".equals(value.get("action"))&&Set.of("FAILED","NOT_STARTED").contains(outcome)) {
            // API success/admission closure is already durable. Only the original idempotent
            // policy intent is recovered; this must never create an installation or refund.
            value.put("runtimeSynced",false);value.put("reasonCode",evidence.get("reasonCode"));save(c,value);
            cmd.setState("PENDING").setEvidenceJson(json(evidence));one(db.saveCommand(cmd));return;
        }
        // Unknown is never failure, refund, retry or permission to execute a second installation.
        if(!"DISABLE".equals(value.get("action"))) {value.put("status","RECOVERY_REQUIRED");value.put("reasonCode","RECEIPT_UNKNOWN");value.put("retryAllowed",false);save(c,value);}
        if(!"PREPARE".equals(phase)) {state.setAdmissionEnabled(false);one(db.saveState(state,revision));}
        cmd.setState("UNKNOWN");one(db.saveCommand(cmd));
    }
    private void confirmCatalog(HostingRentHttp.Actor a,SkillManagedStateEntity state,SkillChangeEntity c,Map<String,Object> value) {
        if(!Set.of("VERIFYING","SUCCEEDED").contains(text(value,"status")))return;
        var auth=state.getActiveInstallationId()==null?null:db.findAuthorization(a.tenantId(),a.clientId(),state.getActiveInstallationId());
        var view=catalog(a,c.getAgentId());boolean connected=online(a,c.getAgentId());
        var live=runtime(a,c.getAgentId(),state,auth,view,connected);
        boolean instruction=auth!=null&&"INSTRUCTION".equals(ref(auth).get("kind"));
        // Disabling is a policy fact, not an assertion that the runner or its dependencies
        // are ready. Exact current disabled discovery confirms synchronization even when
        // execution readiness is degraded; API admission remains closed unconditionally.
        boolean disabled="DISABLE".equals(value.get("action"))&&auth!=null&&connected&&view!=null
                &&!Boolean.TRUE.equals(state.getDesiredEnabled())
                &&view.catalog().skills().stream().filter(f->auth.getInstallationId().equals(f.skillInstallationId())).count()==1
                &&view.catalog().skills().stream().anyMatch(f->exactCatalog(a,state,auth,view,f));
        if(!(disabled||"READY".equals(live.get("state"))||instruction&&"NOT_EXECUTABLE".equals(live.get("state"))))return;
        state.setAdmissionEnabled(Boolean.TRUE.equals(state.getDesiredEnabled())&&!instruction).setPendingChangeId(null);
        one(db.saveState(state,state.getSkillRevision()));
        value.put("status","SUCCEEDED");value.put("runtimeSynced",true);value.put("reasonCode",null);save(c,value);
    }
    /** Called by the existing authenticated capability registration consumer, not a new wire endpoint. */
    public void capabilitiesChanged(AgentRuntimeAuthenticationService.Proof proof) {
        if(!versions.enabled())return;
        for(var hint:db.changes(proof.scope().tenantId(),proof.scope().clientId(),proof.scope().agentId())) {
            var data=dto(hint);if(!Set.of("VERIFYING","SUCCEEDED").contains(text(data,"status"))||Boolean.TRUE.equals(data.get("runtimeSynced")))continue;
            var actor=new HostingRentHttp.Actor(hint.getActorId(),hint.getTenantId(),hint.getClientId(),hint.getOwnerJiacn());
            owned(actor,hint.getAgentId(),null,false,()->authentication.withFence(proof,false,()->{
                var state=state(actor,hint.getAgentId(),hint.getSkillKey());
                if(!state.getSkillRevision().toString().equals(data.get("skillRevision")))return null;
                var c=db.lockChange(hint.getTenantId(),hint.getClientId(),hint.getChangeId());confirmCatalog(actor,state,c,dto(c));return null;
            }));
        }
    }
    public void acknowledge(AgentRuntimeAuthenticationService.Proof proof,Map<String,Object> envelope) {
        validate("CommandAck",envelope);var data=object(envelope.get("data"));
        var hint=db.findChange(proof.scope().tenantId(),proof.scope().clientId(),text(data,"skillChangeId"));
        require(hint!=null&&hint.getAgentId().equals(proof.scope().agentId())&&hint.getOwnerJiacn().equals(proof.scope().ownerJiacn())
                &&hint.getRuntimeInstallationId().equals(proof.installationId())&&hint.getRuntimeHostId().equals(proof.hostId()),409,"SKILL_DELIVERY_FENCED");
        var a=new HostingRentHttp.Actor(hint.getActorId(),hint.getTenantId(),hint.getClientId(),hint.getOwnerJiacn());
        owned(a,hint.getAgentId(),null,false,()->authentication.withFence(proof,false,()->{
            var state=state(a,hint.getAgentId(),hint.getSkillKey());var cmd=db.lockCommand(a.tenantId(),a.clientId(),text(data,"commandId"));
            require(cmd!=null&&hint.getChangeId().equals(cmd.getChangeId())&&cmd.getMessageId().equals(envelope.get("correlationId")),409,"SKILL_RESULT_CONFLICT");
            var original=decode(cmd.getDataJson(),"CommandData");
            for(String f:List.of("attempt","fencingToken","deliveryEpoch"))require(Objects.equals(original.get(f),data.get(f)),409,"SKILL_DELIVERY_FENCED");
            if(state.getSkillRevision().toString().equals(data.get("fencingToken"))&&"PENDING".equals(cmd.getState())){
                cmd.setState("RECEIVED");one(db.saveCommand(cmd));
                var c=db.lockChange(a.tenantId(),a.clientId(),hint.getChangeId());save(c,dto(c));
            }
            return null;
        }));
    }
    public AgentRawCommandDispatchResult dispatch(String tenant,String client,String owner,String change,String agent,String commandId,byte[] wire) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),503,"SKILL_STATE_UNAVAILABLE");
        var hint=db.findChange(tenant,client,change);require(hint!=null&&owner.equals(hint.getOwnerJiacn())&&agent.equals(hint.getAgentId()),409,"SKILL_DELIVERY_FENCED");
        var a=new HostingRentHttp.Actor(hint.getActorId(),tenant,client,owner);
        var proof=authentication.currentRegisteredProof(tenant,client,agent);
        owned(a,agent,null,true,()->authentication.withFence(proof,false,()->{
            var state=state(a,agent,hint.getSkillKey());var c=db.lockChange(tenant,client,change);var value=dto(c);
            require(proof.installationId().equals(c.getRuntimeInstallationId())&&proof.hostId().equals(c.getRuntimeHostId()),409,"SKILL_DELIVERY_FENCED");
            var cmd=db.lockCommand(tenant,client,commandId);require(cmd!=null&&change.equals(cmd.getChangeId())&&Set.of("PENDING","RECEIVED").contains(cmd.getState()),409,"SKILL_DELIVERY_FENCED");
            var data=decode(cmd.getDataJson(),"CommandData");
            require(state.getSkillRevision().toString().equals(data.get("skillRevision"))&&value.get("attempt").equals(data.get("attempt")),409,"SKILL_DELIVERY_FENCED");
            require(Arrays.equals(wire,bytes(map("protocolVersion",1,"requestId",cmd.getRequestId(),"messageType","command.dispatch","messageId",cmd.getMessageId(),"data",data))),409,"SKILL_DELIVERY_FENCED");
            if("WAITING_AGENT".equals(value.get("status"))) {value.put("status","PREPARE".equals(data.get("phase"))?"PREPARING":"ACTIVATING");value.put("reasonCode",null);save(c,value);}
            return null;
        }));
        return sessions.getObject().dispatch(tenant,client,agent,SkillMarketplaceService.sessionFence(proof),wire);
    }
    public byte[] packageBytes(AgentRuntimeAuthenticationService.Proof proof,String installationId) {
        path(installationId);String t=proof.scope().tenantId(),client=proof.scope().clientId();
        var auth=db.findAuthorization(t,client,installationId);
        require(auth!=null&&proof.installationId().equals(auth.getRuntimeInstallationId())&&proof.hostId().equals(auth.getRuntimeHostId()),403,"SKILL_DOWNLOAD_FORBIDDEN");
        var hint=db.findChange(t,client,auth.getChangeId());
        require(hint!=null&&hint.getAgentId().equals(proof.scope().agentId())&&hint.getOwnerJiacn().equals(proof.scope().ownerJiacn()),403,"SKILL_DOWNLOAD_FORBIDDEN");
        var target=ref(auth);var version=market.selectApprovedProductVersion(t,client,text(target,"productVersionId"));var prepared=packages.getObject().prepare(version);
        var a=new HostingRentHttp.Actor(hint.getActorId(),t,client,hint.getOwnerJiacn());
        return owned(a,hint.getAgentId(),null,false,()->authentication.withFence(proof,false,()->{
            var state=state(a,hint.getAgentId(),hint.getSkillKey());var c=db.lockChange(t,client,hint.getChangeId());var value=dto(c);
            require(installationId.equals(object(value.get("target")).get("skillInstallationId"))&&state.getSkillRevision().toString().equals(value.get("skillRevision")),403,"SKILL_DOWNLOAD_FORBIDDEN");
            var command=db.lockCommand(t,client,c.getCommandId());var data=decode(command.getDataJson(),"CommandData");
            require("PREPARE".equals(data.get("phase"))&&Set.of("PENDING","RECEIVED").contains(command.getState()),403,"SKILL_DOWNLOAD_FORBIDDEN");
            SkillPackages.revalidate(market.selectApprovedProductVersionForUpdate(t,client,text(target,"productVersionId")),prepared);
            require(HexFormat.of().formatHex(prepared.product().packageSha256()).equals(target.get("packageSha256"))
                    &&Long.toString(prepared.product().packageSize()).equals(target.get("byteLength")),403,"SKILL_DOWNLOAD_FORBIDDEN");return prepared.bytes();
        }));
    }
    /** Keep all authorized exact installs available to in-flight tasks; only admission selects active.
     * No current product/order pointer is borrowed to authorize covered upgrades or old executions. */
    public List<InstalledSkillDescriptorLookup.Descriptor> descriptors(SkillCapabilityLookup.Scope scope) {
        var rows=new ArrayList<InstalledSkillDescriptorLookup.Descriptor>();
        for(var c:db.changes(scope.tenantId(),scope.clientId(),scope.agentId())) {
            if(!scope.ownerJiacn().equals(c.getOwnerJiacn()))continue;
            // Each retry has immutable authorization; successful installations are never overwritten.
            for(var auth:db.authorizations(scope.tenantId(),scope.clientId(),c.getChangeId())) {
                var i=app.installation(scope.tenantId(),scope.clientId(),auth.getInstallationId());
                var e=db.readEntitlement(scope.tenantId(),scope.clientId(),auth.getEntitlementId());
                if(!validInstallation(scope,auth,i,e))continue;
                var target=ref(auth);var approved=market.selectApprovedProductVersion(scope.tenantId(),scope.clientId(),text(target,"productVersionId"));
                if(approved==null||!HexFormat.of().formatHex(approved.getPackageSha256()).equals(target.get("packageSha256")))continue;
                if(i.getOrderId()!=null) {var o=app.order(scope.tenantId(),scope.clientId(),i.getOrderId());if(o==null||!"ACTIVE".equals(o.getStatus()))continue;}
                var pkg=packages.getObject().prepare(approved);var manifest=packages.getObject().manifest(pkg);if(manifest==null)continue;
                if(!manifest.manifestSha256().equals(target.get("manifestSha256")))continue;
                rows.add(new InstalledSkillDescriptorLookup.Descriptor(c.getSkillKey(),text(target,"skillVersion"),auth.getInstallationId(),
                        text(target,"packageSha256"),manifest.manifestSha256(),manifest.document().toString(),auth.getRuntimeInstallationId(),auth.getRuntimeHostId(),null));
            }
        }
        return rows;
    }
    private boolean validInstallation(SkillCapabilityLookup.Scope scope,SkillInstallationAuthorizationEntity auth,SkillInstallationEntity i,SkillEntitlementEntity e) {
        var target=ref(auth);
        if(i==null||e==null||!"SUCCEEDED".equals(i.getStatus())||!"ACTIVE".equals(e.getStatus())
                ||!scope.agentId().equals(i.getTargetAgentId())||!scope.agentId().equals(e.getTargetAgentId())
                ||!auth.getInstallationId().equals(e.getInstallationId())||!Objects.equals(e.getActivatedAt(),i.getInstalledAt())
                ||!Objects.equals(e.getOrderId(),i.getOrderId())||!Objects.equals(e.getProductVersionId(),target.get("productVersionId"))
                ||!Objects.equals(i.getProductVersionId(),target.get("productVersionId"))||!Objects.equals(i.getSkillVersion(),target.get("skillVersion"))
                ||!Objects.equals(e.getSkillKey(),i.getSkillKey())||!Objects.equals(e.getSkillVersion(),i.getSkillVersion())
                ||!HexFormat.of().formatHex(i.getPackageSha256()).equals(target.get("packageSha256"))
                ||!i.getPackageSize().toString().equals(target.get("byteLength")))return false;
        var version=market.selectApprovedProductVersion(scope.tenantId(),scope.clientId(),e.getProductVersionId());
        if(version==null||!Objects.equals(version.getApprovedPermissionsManifest(),e.getApprovedPermissionsManifest())
                ||!Arrays.equals(version.getApprovedPermissionsSha256(),e.getApprovedPermissionsSha256())
                ||!permissionsHash(decodePermissions(auth.getPermissionsJson())).equals(auth.getPermissionsSha256()))return false;
        var binding=app.deliveryBinding(scope.tenantId(),scope.clientId(),auth.getInstallationId());
        if(binding==null||!scope.agentId().equals(binding.getCanonicalAgentId())||!auth.getRuntimeInstallationId().equals(binding.getRuntimeInstallationId())
                ||!auth.getRuntimeHostId().equals(binding.getRuntimeHostId()))return false;
        return db.commands(scope.tenantId(),scope.clientId(),auth.getChangeId()).stream().filter(cmd->"RESULT".equals(cmd.getState())&&cmd.getEvidenceJson()!=null)
                .map(cmd->decode(cmd.getEvidenceJson(),"InstallEvidence")).anyMatch(ev->"APPLIED".equals(ev.get("outcome"))&&json(target).equals(json(ev.get("actual"))));
    }
    /** W05 task qualification joins its caller's task/runtime transaction and locks this skill
     * through authorization commit. Older active entitlements cannot qualify a disabled skill. */
    public boolean qualifies(SkillCapabilityLookup.Scope scope,SkillInstallationAuthorizationEntity auth) {
        require(TransactionSynchronizationManager.isActualTransactionActive(),503,"SKILL_STATE_UNAVAILABLE");
        var c=db.findChange(scope.tenantId(),scope.clientId(),auth.getChangeId());
        if(c==null||!scope.agentId().equals(c.getAgentId())||!scope.ownerJiacn().equals(c.getOwnerJiacn()))return false;
        var state=db.lockState(scope.tenantId(),scope.clientId(),scope.agentId(),c.getSkillKey());
        if(state==null||!Boolean.TRUE.equals(state.getDesiredEnabled())||!Boolean.TRUE.equals(state.getAdmissionEnabled())||!auth.getInstallationId().equals(state.getActiveInstallationId()))return false;
        if(!validInstallation(scope,auth,app.installation(scope.tenantId(),scope.clientId(),auth.getInstallationId()),db.readEntitlement(scope.tenantId(),scope.clientId(),auth.getEntitlementId())))return false;
        var a=new HostingRentHttp.Actor(c.getActorId(),scope.tenantId(),scope.clientId(),scope.ownerJiacn());
        return "READY".equals(runtime(a,scope.agentId(),state,auth,catalog(a,scope.agentId()),online(a,scope.agentId())).get("state"));
    }
    /** Outer transaction precedes task/grant locks, then retains the per-skill root while task
     * grant + execution snapshot are committed. Existing task recovery deliberately does NOT use this. */
    public <T> T admit(SkillCapabilityLookup.Scope scope,String skill,String installationId,Supplier<T> taskAdmission) {
        var auth=db.findAuthorization(scope.tenantId(),scope.clientId(),installationId);
        require(auth!=null,409,"SKILL_STATE_CONFLICT");
        var c=db.findChange(scope.tenantId(),scope.clientId(),auth.getChangeId());
        require(c!=null&&c.getAgentId().equals(scope.agentId())&&c.getOwnerJiacn().equals(scope.ownerJiacn()),403,"AGENT_FORBIDDEN");
        var a=new HostingRentHttp.Actor(c.getActorId(),scope.tenantId(),scope.clientId(),scope.ownerJiacn());
        return owned(a,scope.agentId(),null,true,()->{
            var state=state(a,scope.agentId(),skill);var e=db.entitlement(scope.tenantId(),scope.clientId(),auth.getEntitlementId());
            require(installationId.equals(state.getActiveInstallationId())&&Boolean.TRUE.equals(state.getDesiredEnabled())
                    &&Boolean.TRUE.equals(state.getAdmissionEnabled())&&e!=null&&"ACTIVE".equals(e.getStatus()),409,"SKILL_STATE_CONFLICT");
            var live=runtime(a,scope.agentId(),state,auth,catalog(a,scope.agentId()),true);require("READY".equals(live.get("state")),409,"SKILL_STATE_CONFLICT");
            return taskAdmission.get();
        });
    }
    public Map<String,Object> taskBinding(String tenant,String client,String executionId,String grantId,long grantVersion,String agent,String installationId) {
        // Internal adapter invoked only after exact installation ownership/admission was verified.
        var auth=db.findAuthorization(tenant,client,installationId);
        Map<String,Object> ref;String key,permissions;
        if(auth!=null) {
            ref=ref(auth);var c=db.findChange(tenant,client,auth.getChangeId());
            require(c!=null&&agent.equals(c.getAgentId()),409,"SKILL_STATE_CONFLICT");key=c.getSkillKey();permissions=auth.getPermissionsSha256();
        } else {
            // Explicit adapter for ALREADY admitted snapshots. Resolve ONLY their exact immutable
            // installation/order/receipt, never current state/product or a guessed replacement.
            var i=app.installation(tenant,client,installationId);
            var entitlements=market.selectEntitlementsByAgent(tenant,client,agent).stream().filter(e->installationId.equals(e.getInstallationId())).toList();
            require(i!=null&&"SUCCEEDED".equals(i.getStatus())&&agent.equals(i.getTargetAgentId())&&entitlements.size()==1,409,"SKILL_STATE_CONFLICT");
            var e=entitlements.getFirst();var order=app.order(tenant,client,i.getOrderId());
            var binding=app.deliveryBinding(tenant,client,installationId);var v=market.selectApprovedProductVersion(tenant,client,i.getProductVersionId());
            require(order!=null&&"ACTIVE".equals(order.getStatus())&&agent.equals(order.getTargetAgentId())
                    &&"ACTIVE".equals(e.getStatus())&&Objects.equals(i.getOrderId(),e.getOrderId())&&Objects.equals(i.getProductVersionId(),e.getProductVersionId())
                    &&Objects.equals(i.getInstalledAt(),e.getActivatedAt())&&Objects.equals(v==null?null:v.getApprovedPermissionsManifest(),e.getApprovedPermissionsManifest())
                    &&app.successfulReceipts(tenant,client,installationId)>0&&binding!=null&&agent.equals(binding.getCanonicalAgentId()),409,"SKILL_STATE_CONFLICT");
            var prepared=packages.getObject().prepare(v);var manifest=packages.getObject().manifest(prepared);
            require(manifest!=null&&Arrays.equals(i.getPackageSha256(),v.getPackageSha256())&&Objects.equals(i.getPackageSize(),v.getPackageSize()),409,"SKILL_STATE_CONFLICT");
            ref=map("skillInstallationId",installationId,"productVersionId",i.getProductVersionId(),"skillVersion",i.getSkillVersion(),
                    "packageSha256",HexFormat.of().formatHex(i.getPackageSha256()),"manifestSha256",manifest.manifestSha256());
            key=i.getSkillKey();var approved=new ArrayList<>(SkillMarketplaceHttp.permissions(e.getApprovedPermissionsManifest()));approved.sort(UTF8);permissions=permissionsHash(approved);
        }
        var result=map("executionId",executionId,"taskGrantId",grantId,"taskGrantVersion",Long.toString(grantVersion),"agentId",agent,"skillKey",key,
                "skillInstallationId",installationId,"productVersionId",ref.get("productVersionId"),"skillVersion",ref.get("skillVersion"),
                "packageSha256",ref.get("packageSha256"),"manifestSha256",ref.get("manifestSha256"),"approvedPermissionsSha256",permissions);
        validate("TaskSkillBinding",result);return result;
    }
}
