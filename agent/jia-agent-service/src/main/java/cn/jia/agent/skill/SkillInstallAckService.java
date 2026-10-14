package cn.jia.agent.skill;

import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.AgentSkillInstallRecoveryMapper;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.mapper.EconomySkillApplicationMapper;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Set;

/** Native skill-order ACKs authenticate the original WS command, not a Rabbit publication.
 * ACK only advances delivery state. Package/result verification and settlement remain separate. */
@Service
public final class SkillInstallAckService {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "REJECTED");
    private final EconomySkillApplicationMapper app;
    private final EconomySkillMarketplaceMapper market;
    private final AgentCommandRecoveryDao commands;
    private final AgentSkillInstallRecoveryMapper recovery;
    private final AgentRuntimeAuthenticationService authentication;
    private final EconomyPreviewGate preview;
    private final SkillMarketplaceService skills;
    private final TransactionTemplate tx;

    public SkillInstallAckService(EconomySkillApplicationMapper app, EconomySkillMarketplaceMapper market,
            AgentCommandRecoveryDao commands, AgentSkillInstallRecoveryMapper recovery,
            AgentRuntimeAuthenticationService authentication, EconomyPreviewGate preview, SkillMarketplaceService skills,
            PlatformTransactionManager manager) {
        this.app=app; this.market=market; this.commands=commands; this.recovery=recovery;
        this.authentication=authentication; this.preview=preview; this.skills=skills; tx=new TransactionTemplate(manager);
    }

    /** Null means no skill installation exists for this command; malformed skill ACKs never fall back. */
    public AgentCommandAckResult acknowledgeIfSkill(AgentRuntimeAuthenticationService.Proof proof,
            AgentCommandAck ack, long now) {
        var hint=app.byCommand(ack.tenantId(),ack.clientId(),ack.commandId());
        if(hint==null) return null;
        check(proof!=null && "0".equals(ack.tenantId()) && preview.allows(ack.tenantId(),ack.clientId())
                && ack.tenantId().equals(proof.scope().tenantId()) && ack.clientId().equals(proof.scope().clientId())
                && ack.registeredAgentId().equals(proof.scope().agentId()) && now>0
                && Set.of("RECEIVED","STARTED","SUCCEEDED","FAILED","REJECTED").contains(ack.ackStatus()));
        var visible=app.order(ack.tenantId(),ack.clientId(),hint.getOrderId());
        check(visible!=null);
        return authentication.withFence(proof,false,()->tx.execute(s->{
            var actor=new cn.jia.agent.hosting.HostingRentHttp.Actor(visible.getBuyerId(),ack.tenantId(),ack.clientId(),proof.scope().ownerJiacn());
            check(skills.available(actor)); skills.actorLock(actor);
            var order=market.selectOrderForUpdate(ack.tenantId(),ack.clientId(),hint.getOrderId());
            var install=market.selectInstallationForUpdate(ack.tenantId(),ack.clientId(),ack.registeredAgentId(),hint.getInstallationId());
            check(order!=null && install!=null && ack.tenantId().equals(order.getTenantId()) && ack.clientId().equals(order.getClientId())
                    && ack.tenantId().equals(install.getTenantId()) && ack.clientId().equals(install.getClientId())
                    && ack.registeredAgentId().equals(order.getTargetAgentId()) && order.getOrderId().equals(install.getOrderId())
                    && ack.taskId().equals(order.getOrderId()) && ack.commandId().equals(install.getCommandId())
                    && ack.registeredAgentId().equals(install.getTargetAgentId())
                    && ack.correlationId().equals(install.getMessageId()) && ack.workItemId()==null);
            try {
                SkillMarketplaceService.requireBinding(app.deliveryBinding(ack.tenantId(),ack.clientId(),install.getInstallationId()),
                        ack.registeredAgentId(),proof);
            } catch(SkillMarketplaceException rejected) { throw new AgentCommandAckRejectedException("SKILL_NATIVE_ACK_REJECTED"); }
            var d=commands.lockDeliveryByCommand(ack.tenantId(),ack.clientId(),ack.commandId());
            check(d!=null && d.getId()!=null && d.getId()>0 && d.getVersion()!=null && d.getVersion()>=0
                    && ack.tenantId().equals(d.getTenantId()) && ack.clientId().equals(d.getClientId())
                    && proof.scope().ownerJiacn().equals(d.getOwnerJiacn()) && ack.commandId().equals(d.getCommandId())
                    && ack.taskId().equals(d.getTaskId()) && ack.registeredAgentId().equals(d.getTargetAgentId())
                    && d.getWorkItemId()==null && "SKILL_INSTALL".equals(d.getCommandType())
                    && ack.correlationId().equals(d.getActiveMessageId()) && install.getAttempt().equals(d.getActiveAttempt())
                    && d.getActiveAttempt().equals(d.getAttemptCount()) && d.getExpiresAt()!=null);
            var rows=commands.lockActiveOutboxes(ack.tenantId(),ack.clientId(),d.getId(),d.getActiveMessageId());
            check(rows!=null && rows.size()==1); var o=rows.getFirst();
            SkillInstallRecoveryService.validate(d,o);
            check(o.getId()!=null && o.getVersion()!=null && o.getVersion()>=0 && o.getConfirmError()==null && o.getReturnedAt()==null);
            boolean inFlight="CONSUMED".equals(d.getStatus()) && "CLAIMED".equals(o.getStatus())
                    && d.getLeaseOwner()!=null && d.getLeaseOwner().equals(o.getLeaseOwner())
                    && d.getLeaseUntil()!=null && d.getLeaseUntil()>now && Objects.equals(d.getLeaseUntil(),o.getLeaseUntil());
            boolean settled="SENT".equals(o.getStatus()) && d.getLeaseOwner()==null && d.getLeaseUntil()==null
                    && o.getLeaseOwner()==null && o.getLeaseUntil()==null
                    && Set.of("SENT","RECEIVED","STARTED","SUCCEEDED","FAILED","REJECTED").contains(d.getStatus());
            check(inFlight || settled);
            boolean prior=ack.ackStatus().equals(d.getStatus());
            check(now<d.getExpiresAt() || "STARTED".equals(d.getStatus()) && TERMINAL.contains(ack.ackStatus())
                    || prior && TERMINAL.contains(d.getStatus()));
            if(prior) return new AgentCommandAckResult(AgentCommandAckResult.Kind.PRIOR,d.getStatus(),d.getVersion());
            boolean allowed=switch(d.getStatus()) {
                case "CONSUMED","SENT" -> Set.of("RECEIVED","REJECTED").contains(ack.ackStatus());
                case "RECEIVED" -> Set.of("STARTED","REJECTED").contains(ack.ackStatus());
                case "STARTED" -> TERMINAL.contains(ack.ackStatus());
                default -> false;
            };
            long maximum=switch(d.getStatus()) {
                case "CONSUMED","SENT" -> Long.MAX_VALUE-3;
                case "RECEIVED" -> Long.MAX_VALUE-2;
                case "STARTED" -> Long.MAX_VALUE-1;
                default -> -1;
            };
            check(allowed && d.getVersion()<=maximum);
            // An authenticated RECEIVED may race the WS sender's completion. Settle only its
            // exact native outbox lease, so the sender cannot overwrite a committed ACK.
            if(inFlight) check(recovery.finishOutbox(o,d.getLeaseOwner(),"SENT",null,"SKILL_WS_SENT",now)==1);
            String error=switch(ack.ackStatus()) {
                case "FAILED" -> "AGENT_REPORTED_FAILED";
                case "REJECTED" -> "AGENT_REPORTED_REJECTED";
                default -> null;
            };
            check(commands.advanceAck(d,ack.ackStatus(),error,now)==1);
            return new AgentCommandAckResult(AgentCommandAckResult.Kind.ADVANCED,ack.ackStatus(),d.getVersion()+1);
        }));
    }
    private static void check(boolean valid) {
        if(!valid) throw new AgentCommandAckRejectedException("SKILL_NATIVE_ACK_REJECTED");
    }
}
