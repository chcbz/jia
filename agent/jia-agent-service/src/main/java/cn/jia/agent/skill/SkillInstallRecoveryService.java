package cn.jia.agent.skill;
import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.mapper.AgentSkillInstallRecoveryMapper;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.AgentCommandInboxService;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;
import static cn.jia.agent.skill.SkillMarketplaceService.one;

/** Bounded recovery from the ORIGINAL committed outbox through the existing durable inbox.
 * An expired/ambiguous command holds funds; it is never presumed safe to refund. */
@Service
@ConditionalOnProperty(prefix="economy.skill.marketplace",name="enabled",havingValue="true")
public final class SkillInstallRecoveryService {
    private final AgentSkillInstallRecoveryMapper mapper;
    private final AgentCommandRecoveryDao dao;
    private final ObjectProvider<AgentCommandInboxService> inbox;
    private final ObjectProvider<AgentRabbitSafetyGate> gate;
    private final SkillInstallDispatchService dispatch;
    private final TransactionTemplate tx;
    private final String owner="skill-recovery-"+UUID.randomUUID();
    public SkillInstallRecoveryService(AgentSkillInstallRecoveryMapper mapper,AgentCommandRecoveryDao dao,
            ObjectProvider<AgentCommandInboxService> inbox,ObjectProvider<AgentRabbitSafetyGate> gate,
            SkillInstallDispatchService dispatch,PlatformTransactionManager manager) {
        this.mapper=mapper;this.dao=dao;this.inbox=inbox;this.gate=gate;this.dispatch=dispatch;tx=new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelayString="${economy.skill.marketplace.recovery-delay-ms:5000}")
    public void recover() {
        if(inbox.getIfAvailable()==null || gate.getIfAvailable()==null || !gate.getObject().rabbitDispatchEnabled()) return;
        long now=System.currentTimeMillis();
        for(var candidate:mapper.candidates(now,now-60000)) {
            if(!gate.getObject().allowsDispatch(candidate.getTenantId(),candidate.getClientId())) continue;
            try { recover(candidate,now); } catch(RuntimeException rejected) {
                // Remains durable for the next bounded scan. Never log wire/secret or infer a refund.
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Skill same-wire recovery deferred");
            }
        }
    }
    void recover(AgentCommandDeliveryEntity hint,long now) {
        AgentInboxMessage message=tx.execute(s->{
            var d=dao.lockDelivery(hint.getTenantId(),hint.getClientId(),hint.getId());
            require(d!=null && "SKILL_INSTALL".equals(d.getCommandType()) && d.getExpiresAt()>now+1,409,"SKILL_DELIVERY_FENCED");
            var rows=dao.lockActiveOutboxes(d.getTenantId(),d.getClientId(),d.getId(),d.getActiveMessageId());
            require(rows!=null && rows.size()==1,409,"SKILL_DELIVERY_FENCED");var o=rows.getFirst();
            var i=dao.lockInbox(d.getTenantId(),d.getClientId(),AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,d.getActiveMessageId());
            require(i!=null && "PUBLISHED".equals(o.getStatus()) && "ACK".equals(o.getPublisherConfirmStatus())
                    && "NOT_RETURNED".equals(o.getMandatoryReturnStatus()) && o.getConfirmedAt()!=null && o.getPublishedAt()!=null
                    && d.getId().equals(o.getDeliveryId()) && d.getId().equals(i.getDeliveryId())
                    && o.getEventId().equals(i.getEventId()) && d.getCommandId().equals(o.getCommandId())
                    && d.getCommandId().equals(i.getCommandId()) && d.getActiveMessageId().equals(i.getMessageId())
                    && d.getActiveMessageId().equals(o.getMessageId()) && d.getActiveAttempt().equals(o.getActiveAttempt())
                    && d.getExpiresAt().equals(i.getExpiresAt()) && d.getExpiresAt().equals(o.getExpiresAt())
                    && Arrays.equals(o.getWirePayload(),i.getWirePayload())
                    && Arrays.equals(AgentCommandCanonicalCodec.sha256(o.getWirePayload()),o.getWirePayloadHash())
                    && Arrays.equals(o.getWirePayloadHash(),i.getWirePayloadHash()),409,"SKILL_DELIVERY_FENCED");
            var draft=AgentCommandCanonicalCodec.decodeBusinessBytes(d.getCommandPayload());
            require(Arrays.equals(AgentCommandCanonicalCodec.sha256(d.getCommandPayload()),d.getCommandPayloadHash())
                    && Arrays.equals(o.getWirePayload(),AgentCommandCanonicalCodec.wireBytes(draft,d.getActiveMessageId(),d.getActiveAttempt())),409,"SKILL_DELIVERY_FENCED");
            if("WAITING_AGENT".equals(d.getStatus()) || "SENT".equals(d.getStatus())) {
                require(d.getUpdateTime()<=now-60000 && ("WAITING_AGENT".equals(d.getStatus())?
                        "WAITING_AGENT".equals(i.getStatus()) && "WAITING_AGENT".equals(i.getResultStatus()):
                        "PROCESSED".equals(i.getStatus()) && "SENT".equals(i.getResultStatus())),409,"SKILL_DELIVERY_FENCED");
                one(mapper.retryDelivery(d,now,now+1));one(mapper.retryInbox(i,now,now+1));
            } else require("RETRY".equals(d.getStatus()) || "CONSUMED".equals(d.getStatus()),409,"SKILL_DELIVERY_FENCED");
            return new AgentInboxMessage(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,d.getTenantId(),d.getClientId(),
                    d.getActiveMessageId(),o.getEventId(),d.getCommandId(),d.getId(),o.getWirePayload());
        });
        var claim=inbox.getObject().claim(message,owner,System.currentTimeMillis(),60000);
        if(claim.kind()!=AgentInboxClaim.Kind.ACQUIRED) return;
        AgentRawCommandDispatchResult result;
        try { result=dispatch.dispatch(hint.getTenantId(),hint.getClientId(),hint.getTaskId(),hint.getTargetAgentId(),hint.getCommandId(),message.rawWireBytes()); }
        catch(RuntimeException unknown) { result=AgentRawCommandDispatchResult.sendFailed(1); }
        long finish=System.currentTimeMillis();
        AgentInboxDisposition disposition;
        if(result.status()==AgentRawCommandDispatchResult.Status.SENT) disposition=AgentInboxDisposition.sent();
        else if(finish+30000>=claim.token().expiresAt()) disposition=new AgentInboxDisposition(AgentInboxDisposition.Type.EXPIRED,null,"MESSAGE_EXPIRED");
        else disposition=new AgentInboxDisposition(AgentInboxDisposition.Type.RETRY,finish+30000,"SKILL_SAME_WIRE_RECOVERY");
        inbox.getObject().complete(claim.token(),disposition,finish);
    }
}
