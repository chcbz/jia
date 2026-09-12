package cn.jia.agent.skill;
import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.AgentManagedSessionLookup;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import cn.jia.economy.mapper.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;
import static cn.jia.agent.skill.SkillMarketplaceService.*;

/** Replaces task-member ACL only for an exact durable skill order/installation. All sends after commit. */
@Service
public final class SkillInstallDispatchService {
    private final EconomySkillApplicationMapper app;
    private final EconomySkillMarketplaceMapper market;
    private final SkillMarketplaceService skills;
    private final SkillAgentVersions versions;
    private final AgentRuntimeDao runtimes;
    private final AgentCommandTransportDao commands;
    private final ObjectProvider<AgentManagedSessionLookup> sessions;
    private final TransactionTemplate tx;
    public SkillInstallDispatchService(EconomySkillApplicationMapper app,EconomySkillMarketplaceMapper market,
            SkillMarketplaceService skills,SkillAgentVersions versions,AgentRuntimeDao runtimes,
            AgentCommandTransportDao commands,ObjectProvider<AgentManagedSessionLookup> sessions,PlatformTransactionManager manager) {
        this.app=app;this.market=market;this.skills=skills;this.versions=versions;this.runtimes=runtimes;
        this.commands=commands;this.sessions=sessions;tx=new TransactionTemplate(manager);
    }
    public AgentRawCommandDispatchResult dispatch(String tenant,String client,String order,String agent,String commandId,byte[] wire) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),503,"SKILL_DISPATCH_TRANSACTION_OPEN");
        var proof=tx.execute(s->{
            var hint=app.order(tenant,client,order); require(hint!=null,403,"SKILL_DELIVERY_FENCED");
            var actor=new HostingRentHttp.Actor(hint.getBuyerId(),tenant,client);
            require(skills.available(actor),503,"SKILL_MARKETPLACE_DISABLED");
            skills.actorLock(actor); versions.requireOwned(actor,agent,null,true);
            var o=market.selectOrderForUpdate(tenant,client,order);
            var i=app.byCommand(tenant,client,commandId);
            require(o!=null && "INSTALLING".equals(o.getStatus()) && i!=null && agent.equals(i.getTargetAgentId())
                    && order.equals(i.getOrderId()) && "INSTALLING".equals(i.getStatus()),403,"SKILL_DELIVERY_FENCED");
            var binding=app.deliveryBinding(tenant,client,i.getInstallationId());
            String key=skills.requireManagedKey(actor,agent);
            require(binding!=null && key.equals(binding.getApiKeyId()),403,"SKILL_DELIVERY_FENCED");
            var d=commands.lockDelivery(tenant,client,commandId);
            SkillInstallResultService.requireCurrentDelivery(i,d,true);
            var draft=AgentCommandCanonicalCodec.decodeBusinessBytes(d.getCommandPayload());
            require(Arrays.equals(AgentCommandCanonicalCodec.sha256(d.getCommandPayload()),d.getCommandPayloadHash())
                    && Arrays.equals(wire,AgentCommandCanonicalCodec.wireBytes(draft,d.getActiveMessageId(),d.getActiveAttempt())),403,"SKILL_DELIVERY_FENCED");
            return new Proof(key,registrationHash(runtimes.findByAgentIdForUpdate(agent)));
        });
        return sessions.getObject().dispatch(tenant,client,agent,proof.key(),proof.registration(),wire);
    }
    private record Proof(String key,byte[] registration) {}
}
