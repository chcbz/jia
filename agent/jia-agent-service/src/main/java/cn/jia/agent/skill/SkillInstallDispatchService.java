package cn.jia.agent.skill;
import cn.jia.agent.dao.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
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
    private final AgentRuntimeAuthenticationService authentication;
    private final AgentCommandTransportDao commands;
    private final ObjectProvider<AgentManagedSessionLookup> sessions;
    private final TransactionTemplate tx;
    public SkillInstallDispatchService(EconomySkillApplicationMapper app,EconomySkillMarketplaceMapper market,
            SkillMarketplaceService skills,SkillAgentVersions versions,AgentRuntimeAuthenticationService authentication,
            AgentCommandTransportDao commands,ObjectProvider<AgentManagedSessionLookup> sessions,PlatformTransactionManager manager) {
        this.app=app;this.market=market;this.skills=skills;this.versions=versions;this.authentication=authentication;
        this.commands=commands;this.sessions=sessions;tx=new TransactionTemplate(manager);
    }
    public AgentRawCommandDispatchResult dispatch(String tenant,String client,String ownerJiacn,String order,String agent,String commandId,byte[] wire) {
        require("0".equals(tenant) && ownerJiacn!=null && !ownerJiacn.isBlank() && !"0".equals(ownerJiacn),403,"SKILL_DELIVERY_FENCED");
        require(!TransactionSynchronizationManager.isActualTransactionActive(),503,"SKILL_DISPATCH_TRANSACTION_OPEN");
        var proof=authentication.currentRegisteredProof(tenant,client,agent);
        authentication.withFence(proof,false,()->tx.execute(s->{
            var hint=app.order(tenant,client,order); require(hint!=null,403,"SKILL_DELIVERY_FENCED");
            var actor=new HostingRentHttp.Actor(hint.getBuyerId(),tenant,client,ownerJiacn);
            require(skills.available(actor),503,"SKILL_MARKETPLACE_DISABLED");
            skills.actorLock(actor); versions.requireOwned(actor,agent,null,true);
            var o=market.selectOrderForUpdate(tenant,client,order);
            var i=app.byCommand(tenant,client,commandId);
            require(o!=null && "INSTALLING".equals(o.getStatus()) && i!=null && agent.equals(i.getTargetAgentId())
                    && order.equals(i.getOrderId()) && "INSTALLING".equals(i.getStatus()),403,"SKILL_DELIVERY_FENCED");
            var binding=app.deliveryBinding(tenant,client,i.getInstallationId());
            skills.requireReady(actor,agent,proof);
            requireBinding(binding,agent,proof);
            var d=commands.lockDelivery(tenant,client,ownerJiacn,commandId);
            SkillInstallResultService.requireCurrentDelivery(i,d,ownerJiacn,true);
            var draft=AgentCommandCanonicalCodec.decodeBusinessBytes(d.getCommandPayload());
            require("0".equals(draft.tenantId()) && client.equals(draft.clientId())
                    && ownerJiacn.equals(draft.ownerJiacn()) && commandId.equals(draft.commandId())
                    && order.equals(draft.taskId()) && agent.equals(draft.targetAgentId())
                    && Arrays.equals(AgentCommandCanonicalCodec.sha256(d.getCommandPayload()),d.getCommandPayloadHash())
                    && Arrays.equals(wire,AgentCommandCanonicalCodec.wireBytes(draft,d.getActiveMessageId(),d.getActiveAttempt())),403,"SKILL_DELIVERY_FENCED");
            return null;
        }));
        return sessions.getObject().dispatch(tenant,client,agent,sessionFence(proof),wire);
    }
}
