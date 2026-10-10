package cn.jia.agent.skill;
import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentRuntimeV2InternalOutboxRow;
import cn.jia.agent.entity.AgentRuntimeV2EffectCodec;
import cn.jia.agent.entity.AgentSkillInstallPayload;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.mapper.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.datasource.ConnectionHolder;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;
import static cn.jia.agent.skill.SkillMarketplaceService.*;

/** Only called with a trusted registered WebSocket Agent identity, never a public success callback. */
@Service
public final class SkillInstallResultService {
    // Installer codes proven to occur before activation. IO/CONFLICT/unknown are deliberately absent.
    static final Set<String> SAFE_FAILURES=Set.of("SKILL_INSTALL_DISABLED","SKILL_DOWNLOAD_FORBIDDEN",
            "SKILL_PACKAGE_TOO_LARGE","SKILL_PACKAGE_DIGEST_MISMATCH","SKILL_ARCHIVE_INVALID","SKILL_IDENTITY_MISMATCH");
    private final EconomySkillMarketplaceMapper market;
    private final EconomySkillApplicationMapper app;
    private final AgentCommandTransportDao commands;
    private final SkillMarketplaceService skills;
    private final SkillAgentVersions versions;
    private final AgentRuntimeAuthenticationService authentication;
    private final ObjectProvider<SkillPackages> packages;
    private final TransactionTemplate tx;
    public SkillInstallResultService(EconomySkillMarketplaceMapper market,EconomySkillApplicationMapper app,
            AgentCommandTransportDao commands,SkillMarketplaceService skills,SkillAgentVersions versions,
            AgentRuntimeAuthenticationService authentication,ObjectProvider<SkillPackages> packages,PlatformTransactionManager manager) {
        this.market=market;this.app=app;this.commands=commands;this.skills=skills;this.versions=versions;
        this.authentication=authentication;this.packages=packages;this.tx=new TransactionTemplate(manager);
    }
    public Map<String,Object> accept(AgentRuntimeAuthenticationService.Proof proof,Map<String,Object> body) {
        require(proof!=null && "0".equals(proof.scope().tenantId()),403,"SKILL_RESULT_REJECTED");
        String tenant=proof.scope().tenantId(),client=proof.scope().clientId(),registeredAgent=proof.scope().agentId();
        String installId=text(body,"installationId"),messageId=text(body,"messageId");
        var hint=app.installation(tenant,client,installId);
        require(hint!=null && registeredAgent.equals(hint.getTargetAgentId()),403,"SKILL_RESULT_REJECTED");
        var visible=app.order(tenant,client,hint.getOrderId());
        require(visible!=null,403,"SKILL_RESULT_REJECTED");
        byte[] hash=resultHash(body);
        return authentication.withFence(proof,false,()->tx.execute(s->{
            String ownerJiacn=proof.scope().ownerJiacn();
            var actor=new HostingRentHttp.Actor(visible.getBuyerId(),tenant,client,ownerJiacn);
            require(skills.available(actor),503,"SKILL_MARKETPLACE_DISABLED");
            skills.actorLock(actor);
            var authenticatedBinding=app.deliveryBinding(tenant,client,installId);
            requireBinding(authenticatedBinding,registeredAgent,proof);
            var prior=app.lockResult(tenant,client,messageId);
            if(prior!=null) {
                same(prior.getRequestHash(),hash); require(installId.equals(prior.getInstallationId()),409,"SKILL_RESULT_CONFLICT");
                return receipt(body);
            }
            // Immutable installation/host binding survives an ordinary authorized reconnect.
            versions.requireOwned(actor,registeredAgent,null,false);
            var order=market.selectOrderForUpdate(tenant,client,hint.getOrderId());
            var i=market.selectInstallationForUpdate(tenant,client,registeredAgent,installId);
            require(order!=null && i!=null && order.getOrderId().equals(i.getOrderId()),403,"SKILL_RESULT_REJECTED");
            validateEnvelope(body,i);
            var binding=app.deliveryBinding(tenant,client,installId);
            requireBinding(binding,registeredAgent,proof);
            var delivery=commands.lockDelivery(tenant,client,ownerJiacn,i.getCommandId());
            requireCurrentDelivery(i,delivery,ownerJiacn,false);
            boolean success="SUCCEEDED".equals(body.get("status"));
            String failure=body.get("failureCode")==null?null:text(body,"failureCode");
            boolean refund=!success && failure!=null && SAFE_FAILURES.contains(failure);
            long now=System.currentTimeMillis(); long installedAt=0;
            if(success) {
                require(failure==null,409,"SKILL_RESULT_CONFLICT");
                installedAt=HostingRentHttp.positive(text(body,"installedAt"));
                require(installedAt<=Math.addExact(now,300000L) && installedAt>=Math.max(1,order.getHeldAt()-300000L),409,"SKILL_RESULT_CONFLICT");
            } else require(body.get("installedAt")==null && failure!=null,409,"SKILL_RESULT_CONFLICT");
            String outcome=success?"ACTIVE":refund?"REFUNDED":"UNKNOWN";
            if(Set.of("ACTIVE","REFUNDED").contains(order.getStatus())) {
                require(order.getStatus().equals(outcome),409,"SKILL_RESULT_CONFLICT");
                require(success?Objects.equals(i.getInstalledAt(),installedAt):Objects.equals(i.getFailureCode(),failure),409,"SKILL_RESULT_CONFLICT");
            } else if(success || refund) {
                var entitlement=market.selectEntitlementByAgentSkillForUpdate(tenant,client,registeredAgent,i.getSkillKey(),i.getSkillVersion());
                require(entitlement!=null && installId.equals(entitlement.getInstallationId()) && order.getOrderId().equals(entitlement.getOrderId())
                        && "PENDING_INSTALLATION".equals(entitlement.getStatus()),409,"SKILL_STATE_CONFLICT");
                String transactionId=skills.settle(actor,order,binding,success,now);
                if(success) {
                    one(market.markInstallationSucceeded(tenant,client,registeredAgent,installId,i.getCommandId(),i.getAttempt(),i.getFencingToken(),i.getDeliveryEpoch(),i.getVersion(),i.getPackageSha256(),installedAt));
                    one(market.markEntitlementActive(tenant,client,registeredAgent,entitlement.getEntitlementId(),installId,entitlement.getVersion(),installedAt));
                    one(market.markOrderActive(tenant,client,order.getOrderId(),order.getVersion(),transactionId,now));
                } else {
                    one(market.markInstallationFailed(tenant,client,registeredAgent,installId,i.getCommandId(),i.getAttempt(),i.getFencingToken(),i.getDeliveryEpoch(),i.getVersion(),failure,now));
                    one(market.markEntitlementFailed(tenant,client,registeredAgent,entitlement.getEntitlementId(),installId,entitlement.getVersion(),now));
                    one(market.markOrderRefunded(tenant,client,order.getOrderId(),order.getVersion(),transactionId,now));
                }
            }
            one(app.insertResult(tenant,client,messageId,installId,hash,outcome,now));
            // UNKNOWN receipt records observation only; escrow remains held for trusted reconciliation.
            return receipt(body);
        }));
    }
    public byte[] packageBytes(AgentRuntimeAuthenticationService.Proof proof,String installationId) {
        require(proof!=null && "0".equals(proof.scope().tenantId()),403,"SKILL_DOWNLOAD_FORBIDDEN");
        String t=proof.scope().tenantId(),c=proof.scope().clientId(),ownerJiacn=proof.scope().ownerJiacn();
        require(ownerJiacn!=null && !ownerJiacn.isBlank() && !"0".equals(ownerJiacn),403,"SKILL_DOWNLOAD_FORBIDDEN");
        var hint=app.installation(t,c,installationId);
        require(hint!=null && proof.scope().agentId().equals(hint.getTargetAgentId()),403,"SKILL_DOWNLOAD_FORBIDDEN");
        return authentication.withFence(proof,false,()->tx.execute(s->{
            var visible=app.order(t,c,hint.getOrderId());
            require(visible!=null,403,"SKILL_DOWNLOAD_FORBIDDEN");
            var actor=new HostingRentHttp.Actor(visible.getBuyerId(),t,c,ownerJiacn);
            require(skills.available(actor),503,"SKILL_MARKETPLACE_DISABLED");
            skills.actorLock(actor); versions.requireOwned(actor,hint.getTargetAgentId(),null,false);
            var o=market.selectOrderForUpdate(t,c,hint.getOrderId());
            var i=market.selectInstallationForUpdate(t,c,hint.getTargetAgentId(),installationId);
            require(o!=null && "INSTALLING".equals(o.getStatus()) && i!=null && "INSTALLING".equals(i.getStatus()),403,"SKILL_DOWNLOAD_FORBIDDEN");
            var binding=app.deliveryBinding(t,c,installationId);
            requireBinding(binding,i.getTargetAgentId(),proof);
            requireCurrentDelivery(i,commands.lockDelivery(t,c,ownerJiacn,i.getCommandId()),ownerJiacn,true);
            var pkg=packages.getObject().get(i.getProductVersionId());
            require(pkg.product().packageSize()==i.getPackageSize() && Arrays.equals(pkg.product().packageSha256(),i.getPackageSha256()),503,"SKILL_PACKAGE_UNAVAILABLE");
            return pkg.bytes(); // already cached at startup; no filesystem/network I/O under transaction
        }));
    }
    /** Same-transaction V2 resource operations. The original composite reducer
     * authenticates/locks the current credential and origin first. C remains
     * the resource client; no managed legacy key or second delivery/start lane
     * is execution authority here. Actor root precedes order/install/result.
     */
    private record V2Locked(HostingRentHttp.Actor actor,SkillOrderEntity order,
            SkillInstallationEntity installation,SkillDeliveryBindingEntity binding,V2Transaction transaction) { }
    private record V2Transaction(Object datasource,ConnectionHolder holder,java.sql.Connection connection) {
        @Override public boolean equals(Object other){return other instanceof V2Transaction value&&datasource==value.datasource&&holder==value.holder&&connection==value.connection;}
        @Override public int hashCode(){return System.identityHashCode(datasource)^System.identityHashCode(holder)^System.identityHashCode(connection);}
    }
    private V2Locked v2Lock(AgentRuntimeV2InternalOutboxRow origin,AgentSkillInstallPayload payload,
            AgentRuntimeV2EffectCodec.SkillIdentity identity) {
        var transaction=v2Transaction();String tenant=origin.text("tenant_id"),client=origin.text("command_caller_client_id"),owner=origin.text("owner_jiacn"),agent=origin.text("agent_id");
        require("0".equals(tenant)&&payload!=null&&identity!=null,403,"SKILL_RESULT_REJECTED");
        require(identity.installationId().equals(payload.installationId())&&identity.productVersionId().equals(payload.productVersionId())&&identity.packageDigest().equals(payload.packageDigest())&&identity.skillKey().equals(payload.skillKey())&&identity.skillVersion().equals(payload.skillVersion()),409,"SKILL_RESULT_CONFLICT");
        var visible=app.order(tenant,client,payload.orderId());require(visible!=null,403,"SKILL_RESULT_REJECTED");
        var actor=new HostingRentHttp.Actor(visible.getBuyerId(),tenant,client,owner);
        require(skills.available(actor),503,"SKILL_MARKETPLACE_DISABLED");skills.actorLock(actor);
        var order=market.selectOrderForUpdate(tenant,client,payload.orderId());
        var installation=market.selectInstallationForUpdate(tenant,client,agent,payload.installationId());
        require(order!=null&&installation!=null&&Objects.equals(order.getBuyerId(),actor.actorId())&&payload.orderId().equals(installation.getOrderId())&&agent.equals(installation.getTargetAgentId())&&origin.text("command_id").equals(installation.getCommandId())&&origin.text("source_message_id").equals(installation.getMessageId())&&payload.productVersionId().equals(installation.getProductVersionId())&&payload.skillKey().equals(installation.getSkillKey())&&payload.skillVersion().equals(installation.getSkillVersion())&&payload.packageDigest().equals(digest(installation.getPackageSha256()))&&payload.packageSize().equals(Long.toString(installation.getPackageSize())),403,"SKILL_DELIVERY_FENCED");
        var binding=app.deliveryBinding(tenant,client,payload.installationId());require(binding!=null,409,"SKILL_STATE_CONFLICT");
        require(transaction.equals(v2Transaction()),503,"SKILL_STATE_UNAVAILABLE");return new V2Locked(actor,order,installation,binding,transaction);
    }
    public AgentRuntimeV2EffectCodec.ReadResult v2Package(AgentRuntimeV2InternalOutboxRow origin,
            AgentSkillInstallPayload payload,AgentRuntimeV2EffectCodec.SkillRead request) {
        var locked=v2Lock(origin,payload,request.identity());
        require("INSTALLING".equals(locked.order().getStatus())&&"INSTALLING".equals(locked.installation().getStatus()),403,"SKILL_DOWNLOAD_FORBIDDEN");
        var pkg=packages.getObject().get(payload.productVersionId());byte[] bytes=pkg.bytes();
        require(pkg.product().packageSize()==locked.installation().getPackageSize()&&Arrays.equals(pkg.product().packageSha256(),locked.installation().getPackageSha256())&&bytes.length==locked.installation().getPackageSize()&&payload.packageDigest().equals(digest(cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256(bytes))),503,"SKILL_PACKAGE_UNAVAILABLE");
        require(request.offset()>=0&&request.length()>=0&&request.length()<=AgentRuntimeV2EffectCodec.CHUNK_CAP&&request.offset()<=bytes.length&&request.length()<=bytes.length-request.offset(),400,"BAD_REQUEST");
        byte[] chunk=Arrays.copyOfRange(bytes,Math.toIntExact(request.offset()),Math.toIntExact(request.offset()+request.length()));
        require(locked.transaction().equals(v2Transaction()),503,"SKILL_STATE_UNAVAILABLE");
        return new AgentRuntimeV2EffectCodec.ReadResult(request.offset(),chunk.length,bytes.length,HexFormat.of().formatHex(cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256(chunk)),chunk);
    }
    /** The verifier, not wire status/failureCode, chooses a known disposition.
     * UNKNOWN never captures/refunds escrow or changes entitlement/install.
     * Stable result identity is the original effect, not a new transport ID.
     */
    public enum V2Disposition { ACTIVE, NOT_RUN_PROVEN, UNKNOWN }
    public String v2Result(AgentRuntimeV2InternalOutboxRow origin,AgentSkillInstallPayload payload,
            AgentRuntimeV2EffectCodec.SkillIdentity identity,String effectId,byte[] exactProof,
            V2Disposition disposition,Long installedAt,long now) {
        require(effectId!=null&&effectId.matches("eff_[0-9a-f]{64}")&&exactProof!=null&&exactProof.length>0&&disposition!=null&&now>0,400,"BAD_REQUEST");
        var locked=v2Lock(origin,payload,identity);var actor=locked.actor();var order=locked.order();var installation=locked.installation();String tenant=actor.tenantId(),client=actor.clientId(),agent=installation.getTargetAgentId();
        var prior=app.lockResult(tenant,client,effectId);
        // Uncertainty belongs in the R4 append-only observation/event graph,
        // never this unique TERMINAL business result slot. A later true proof
        // must still be able to capture/refund exactly once with this effectId.
        // Conversely an old UNKNOWN observation cannot downgrade a known row.
        if(disposition==V2Disposition.UNKNOWN){
            if(prior!=null){require(identity.installationId().equals(prior.getInstallationId())&&Set.of("ACTIVE","REFUNDED").contains(prior.getOutcome()),409,"SKILL_RESULT_CONFLICT");v2Known(locked,identity,prior.getOutcome(),installation.getInstalledAt());return prior.getOutcome();}
            if(Set.of("ACTIVE","REFUNDED").contains(order.getStatus())){v2Known(locked,identity,order.getStatus(),installation.getInstalledAt());return order.getStatus();}
            require("INSTALLING".equals(order.getStatus())&&"INSTALLING".equals(installation.getStatus()),409,"SKILL_STATE_CONFLICT");
            require(locked.transaction().equals(v2Transaction()),503,"SKILL_STATE_UNAVAILABLE");return "UNKNOWN";
        }
        byte[] hash=cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256(exactProof);
        String outcome=disposition==V2Disposition.ACTIVE?"ACTIVE":disposition==V2Disposition.NOT_RUN_PROVEN?"REFUNDED":"UNKNOWN";
        if(prior!=null){same(prior.getRequestHash(),hash);require(identity.installationId().equals(prior.getInstallationId())&&outcome.equals(prior.getOutcome()),409,"SKILL_RESULT_CONFLICT");v2Known(locked,identity,outcome,installedAt);return outcome;}
        require(disposition==V2Disposition.ACTIVE?installedAt!=null&&installedAt>0&&installedAt<=now&&installedAt>=Math.max(1,order.getHeldAt()-300000L):installedAt==null,409,"SKILL_RESULT_CONFLICT");
        if(Set.of("ACTIVE","REFUNDED").contains(order.getStatus())) {
            v2Known(locked,identity,outcome,installedAt);
        }else if(disposition!=V2Disposition.UNKNOWN){
            require("INSTALLING".equals(order.getStatus())&&"INSTALLING".equals(installation.getStatus()),409,"SKILL_STATE_CONFLICT");
            var entitlement=market.selectEntitlementByAgentSkillForUpdate(tenant,client,agent,identity.skillKey(),identity.skillVersion());
            require(entitlement!=null&&identity.installationId().equals(entitlement.getInstallationId())&&order.getOrderId().equals(entitlement.getOrderId())&&"PENDING_INSTALLATION".equals(entitlement.getStatus()),409,"SKILL_STATE_CONFLICT");
            boolean success=disposition==V2Disposition.ACTIVE;String transactionId=skills.settle(actor,order,locked.binding(),success,now);
            if(success){
                one(market.markInstallationSucceeded(tenant,client,agent,identity.installationId(),installation.getCommandId(),installation.getAttempt(),installation.getFencingToken(),installation.getDeliveryEpoch(),installation.getVersion(),installation.getPackageSha256(),installedAt));
                one(market.markEntitlementActive(tenant,client,agent,entitlement.getEntitlementId(),identity.installationId(),entitlement.getVersion(),installedAt));
                one(market.markOrderActive(tenant,client,order.getOrderId(),order.getVersion(),transactionId,now));
            }else{
                one(market.markInstallationFailed(tenant,client,agent,identity.installationId(),installation.getCommandId(),installation.getAttempt(),installation.getFencingToken(),installation.getDeliveryEpoch(),installation.getVersion(),"V2_NOT_RUN_PROVEN",now));
                one(market.markEntitlementFailed(tenant,client,agent,entitlement.getEntitlementId(),identity.installationId(),entitlement.getVersion(),now));
                one(market.markOrderRefunded(tenant,client,order.getOrderId(),order.getVersion(),transactionId,now));
            }
        }
        one(app.insertResult(tenant,client,effectId,identity.installationId(),hash,outcome,now));
        require(locked.transaction().equals(v2Transaction()),503,"SKILL_STATE_UNAVAILABLE");return outcome;
    }
    private void v2Known(V2Locked locked,AgentRuntimeV2EffectCodec.SkillIdentity identity,String outcome,Long installedAt){
        var actor=locked.actor();var order=locked.order();var installation=locked.installation();boolean active="ACTIVE".equals(outcome);
        require(Set.of("ACTIVE","REFUNDED").contains(outcome)&&outcome.equals(order.getStatus())&&(active?"SUCCEEDED":"FAILED").equals(installation.getStatus())
                &&(active?installedAt!=null&&Objects.equals(installedAt,installation.getInstalledAt()):installedAt==null&&"V2_NOT_RUN_PROVEN".equals(installation.getFailureCode())),409,"SKILL_RESULT_CONFLICT");
        var entitlement=market.selectEntitlementByAgentSkillForUpdate(actor.tenantId(),actor.clientId(),installation.getTargetAgentId(),identity.skillKey(),identity.skillVersion());
        require(entitlement!=null&&identity.installationId().equals(entitlement.getInstallationId())&&order.getOrderId().equals(entitlement.getOrderId())&&(active?"ACTIVE":"FAILED").equals(entitlement.getStatus()),409,"SKILL_STATE_CONFLICT");
        require(locked.transaction().equals(v2Transaction()),503,"SKILL_STATE_UNAVAILABLE");
    }
    private static V2Transaction v2Transaction(){
        require(TransactionSynchronizationManager.isActualTransactionActive()&&TransactionSynchronizationManager.isSynchronizationActive()&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly(),503,"SKILL_STATE_UNAVAILABLE");
        // MyBatis legitimately binds its SqlSessionHolder lazily. That does
        // not replace the original JDBC transaction; extra/replaced JDBC
        // bindings or a changed connection still fail closed.
        var bindings=TransactionSynchronizationManager.getResourceMap().entrySet().stream().filter(entry->entry.getValue() instanceof ConnectionHolder).toList();
        require(bindings.size()==1,503,"SKILL_STATE_UNAVAILABLE");var binding=bindings.getFirst();var holder=(ConnectionHolder)binding.getValue();
        return new V2Transaction(binding.getKey(),holder,holder.getConnection());
    }
    static void requireCurrentDelivery(SkillInstallationEntity i,AgentCommandDeliveryEntity d,String ownerJiacn,boolean download) {
        require(d!=null && "0".equals(i.getTenantId()) && "0".equals(d.getTenantId())
                && ownerJiacn!=null && ownerJiacn.equals(d.getOwnerJiacn())
                && i.getTenantId().equals(d.getTenantId()) && i.getClientId().equals(d.getClientId())
                && i.getCommandId().equals(d.getCommandId()) && "SKILL_INSTALL".equals(d.getCommandType())
                && i.getOrderId().equals(d.getTaskId()) && i.getTargetAgentId().equals(d.getTargetAgentId())
                && i.getMessageId().equals(d.getActiveMessageId()) && i.getAttempt().equals(d.getActiveAttempt())
                && d.getStatus()!=null && Set.of("CONSUMED","SENT","RECEIVED","STARTED","SUCCEEDED","FAILED","WAITING_AGENT","RETRY","EXPIRED").contains(d.getStatus())
                && (!download || d.getExpiresAt()!=null && d.getExpiresAt()>System.currentTimeMillis()
                    && Set.of("CONSUMED","SENT","RECEIVED","STARTED").contains(d.getStatus())),403,"SKILL_DELIVERY_FENCED");
    }
    static void validateEnvelope(Map<String,Object> b,SkillInstallationEntity i) {
        require(Integer.valueOf(1).equals(b.get("schemaVersion")) && "work.result".equals(b.get("messageType"))
                && "SKILL_INSTALL_RESULT".equals(b.get("resultType")) && i.getAttempt().equals(b.get("attempt"))
                && i.getFencingToken().toString().equals(b.get("fencingToken")) && i.getDeliveryEpoch().toString().equals(b.get("deliveryEpoch"))
                && i.getOrderId().equals(b.get("orderId")) && i.getInstallationId().equals(b.get("installationId"))
                && i.getCommandId().equals(b.get("commandId")) && i.getTargetAgentId().equals(b.get("targetAgentId"))
                && i.getProductVersionId().equals(b.get("productVersionId")) && digest(i.getPackageSha256()).equals(b.get("packageDigest"))
                && i.getSkillKey().equals(b.get("skillKey")) && i.getSkillVersion().equals(b.get("skillVersion"))
                && ("SUCCEEDED".equals(b.get("status")) || "FAILED".equals(b.get("status"))),409,"SKILL_RESULT_CONFLICT");
        for(String field:List.of("agentId","sourceAgentId"))
            require(!b.containsKey(field) || i.getTargetAgentId().equals(b.get(field)),403,"SKILL_RESULT_REJECTED");
    }
    static byte[] resultHash(Map<String,Object> body) {
        Map<String,String> values=new TreeMap<>();
        for(var e:body.entrySet()) {
            Object v=e.getValue();
            require(v==null || v instanceof String || (Set.of("schemaVersion","attempt").contains(e.getKey()) && v instanceof Integer),400,"BAD_REQUEST");
            values.put(e.getKey(),v==null?null:v.getClass().getName()+":"+v);
        }
        return HostingRentHttp.hash("skill-result-v1",values);
    }
    static String text(Map<String,Object> body,String field) {
        require(body.get(field) instanceof String,400,"BAD_REQUEST");
        String text=(String)body.get(field); HostingRentHttp.exact(text,100); return text;
    }
    static Map<String,Object> receipt(Map<String,Object> b) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("schemaVersion",1); out.put("messageType","work.result.receipt"); out.put("messageId","sr_"+HexFormat.of().formatHex(HostingRentHttp.hash("skill-result-receipt",Map.of("message",text(b,"messageId")))));
        out.put("correlationId",b.get("messageId"));out.put("resultType","SKILL_INSTALL_RESULT");out.put("receiptStatus","ACCEPTED");
        for(String f:List.of("commandId","attempt","fencingToken","deliveryEpoch","installationId","targetAgentId")) out.put(f,b.get(f));
        return out;
    }
}
