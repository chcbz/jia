package cn.jia.agent.skill;
import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.mapper.*;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final AgentRuntimeDao runtimes;
    private final ObjectProvider<SkillPackages> packages;
    private final TransactionTemplate tx;
    public SkillInstallResultService(EconomySkillMarketplaceMapper market,EconomySkillApplicationMapper app,
            AgentCommandTransportDao commands,SkillMarketplaceService skills,SkillAgentVersions versions,
            AgentRuntimeDao runtimes,ObjectProvider<SkillPackages> packages,PlatformTransactionManager manager) {
        this.market=market;this.app=app;this.commands=commands;this.skills=skills;this.versions=versions;
        this.runtimes=runtimes;this.packages=packages;this.tx=new TransactionTemplate(manager);
    }
    public Map<String,Object> accept(String tenant,String client,String registeredAgent,String authenticatedKeyId,Map<String,Object> body) {
        require(authenticatedKeyId!=null && !authenticatedKeyId.isBlank(),403,"SKILL_RESULT_REJECTED");
        String installId=text(body,"installationId"),messageId=text(body,"messageId");
        var hint=app.installation(tenant,client,installId);
        require(hint!=null && registeredAgent.equals(hint.getTargetAgentId()),403,"SKILL_RESULT_REJECTED");
        var visible=app.order(tenant,client,hint.getOrderId());
        require(visible!=null,403,"SKILL_RESULT_REJECTED");
        var actor=new HostingRentHttp.Actor(visible.getBuyerId(),tenant,client);
        require(skills.available(actor),503,"SKILL_MARKETPLACE_DISABLED");
        byte[] hash=resultHash(body);
        return tx.execute(s->{
            skills.actorLock(actor);
            var authenticatedBinding=app.deliveryBinding(tenant,client,installId);
            require(authenticatedBinding!=null && authenticatedKeyId.equals(authenticatedBinding.getApiKeyId()),403,"SKILL_RESULT_REJECTED");
            var prior=app.lockResult(tenant,client,messageId);
            if(prior!=null) {
                same(prior.getRequestHash(),hash); require(installId.equals(prior.getInstallationId()),409,"SKILL_RESULT_CONFLICT");
                return receipt(body);
            }
            // Persistent binding/key and command fences survive an ordinary same-key reconnect.
            versions.requireOwned(actor,registeredAgent,null,false);
            var order=market.selectOrderForUpdate(tenant,client,hint.getOrderId());
            var i=market.selectInstallationForUpdate(tenant,client,registeredAgent,installId);
            require(order!=null && i!=null && order.getOrderId().equals(i.getOrderId()),403,"SKILL_RESULT_REJECTED");
            validateEnvelope(body,i);
            var binding=app.deliveryBinding(tenant,client,installId);
            require(binding!=null,409,"SKILL_REGISTRATION_CHANGED");
            require(binding.getApiKeyId().equals(skills.requireManagedKey(actor,registeredAgent)),403,"SKILL_AGENT_CREDENTIAL_UNPROVEN");
            var delivery=commands.lockDelivery(tenant,client,i.getCommandId());
            requireCurrentDelivery(i,delivery,false);
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
        });
    }
    public byte[] packageBytes(OauthApiKeyEntity key,String installationId) {
        require(key!=null && key.getId()!=null && key.getJiacn()!=null && key.getJiacn().equals(key.getTenantId())
                && Integer.valueOf(1).equals(key.getStatus()) && (key.getExpireTime()==null || key.getExpireTime()>System.currentTimeMillis()),403,"SKILL_DOWNLOAD_FORBIDDEN");
        String t=key.getJiacn(),c=key.getClientId();
        var hint=app.installation(t,c,installationId);
        require(hint!=null,403,"SKILL_DOWNLOAD_FORBIDDEN");
        return tx.execute(s->{
            var visible=app.order(t,c,hint.getOrderId());
            require(visible!=null,403,"SKILL_DOWNLOAD_FORBIDDEN");
            var actor=new HostingRentHttp.Actor(visible.getBuyerId(),t,c);
            require(skills.available(actor),503,"SKILL_MARKETPLACE_DISABLED");
            skills.actorLock(actor); versions.requireOwned(actor,hint.getTargetAgentId(),null,false);
            var o=market.selectOrderForUpdate(t,c,hint.getOrderId());
            var i=market.selectInstallationForUpdate(t,c,hint.getTargetAgentId(),installationId);
            require(o!=null && "INSTALLING".equals(o.getStatus()) && i!=null && "INSTALLING".equals(i.getStatus()),403,"SKILL_DOWNLOAD_FORBIDDEN");
            var binding=app.deliveryBinding(t,c,installationId);
            require(binding!=null && key.getId().equals(binding.getApiKeyId()) && key.getId().equals(skills.requireManagedKey(actor,i.getTargetAgentId())),403,"SKILL_DOWNLOAD_FORBIDDEN");
            requireCurrentDelivery(i,commands.lockDelivery(t,c,i.getCommandId()),true);
            var pkg=packages.getObject().get(i.getProductVersionId());
            require(pkg.product().packageSize()==i.getPackageSize() && Arrays.equals(pkg.product().packageSha256(),i.getPackageSha256()),503,"SKILL_PACKAGE_UNAVAILABLE");
            return pkg.bytes(); // already cached at startup; no filesystem/network I/O under transaction
        });
    }
    static void requireCurrentDelivery(SkillInstallationEntity i,AgentCommandDeliveryEntity d,boolean download) {
        require(d!=null && i.getTenantId().equals(d.getTenantId()) && i.getClientId().equals(d.getClientId())
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
