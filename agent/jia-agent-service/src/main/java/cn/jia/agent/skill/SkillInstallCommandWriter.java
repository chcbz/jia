package cn.jia.agent.skill;

import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import cn.jia.economy.config.EconomyPreviewGate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;
import static cn.jia.agent.skill.SkillMarketplaceService.one;

/** Narrow transactional skill-install producer. Reuses the command delivery/outbox tables,
 * without enabling the unrelated Rabbit/task-command transport. The caller owns order ACL. */
@Service
@ConditionalOnProperty(prefix="economy.skill.marketplace",name="enabled",havingValue="true")
public final class SkillInstallCommandWriter {
    public static final String DESTINATION="agent.skill-install.ws.v1";
    private final AgentCommandTransportDao commands;
    private final EconomyPreviewGate preview;
    public SkillInstallCommandWriter(AgentCommandTransportDao commands,EconomyPreviewGate preview) {
        this.commands=commands;this.preview=preview;
    }
    public boolean allows(String tenant,String client) { return "0".equals(tenant) && preview.allows(tenant,client); }
    public AgentCommandTransportWriteResult write(AgentCommandDraft draft) {
        require(TransactionSynchronizationManager.isActualTransactionActive(),503,"SKILL_COMMAND_TRANSACTION_REQUIRED");
        require("SKILL_INSTALL".equals(draft.commandType()) && allows(draft.tenantId(),draft.clientId()),403,"SKILL_DELIVERY_FENCED");
        byte[] business=AgentCommandCanonicalCodec.businessBytes(draft),hash=AgentCommandCanonicalCodec.sha256(business);
        // An order replay is resolved by the caller's actor/idempotency lock before reaching here.
        // A second command identity is a conflict, not permission to mutate an existing delivery.
        require(commands.lockDelivery(draft.tenantId(),draft.clientId(),draft.ownerJiacn(),draft.commandId())==null,409,"SKILL_COMMAND_CONFLICT");
        String message=UUID.randomUUID().toString(),event=UUID.randomUUID().toString();
        var d=new AgentCommandDeliveryEntity().setCommandId(draft.commandId()).setOwnerJiacn(draft.ownerJiacn())
                .setTaskId(draft.taskId()).setWorkItemId(draft.workItemId()).setTargetAgentId(draft.targetAgentId())
                .setCommandType("SKILL_INSTALL").setCommandPayload(business).setCommandPayloadHash(hash).setStatus("PENDING")
                .setAttemptCount(1).setActiveMessageId(message).setActiveAttempt(1).setExpiresAt(draft.expiresAt())
                .setLastError("SKILL_WS_PENDING").setVersion(0L);
        d.setTenantId(draft.tenantId());d.setClientId(draft.clientId());d.setCreateTime(draft.issuedAt());d.setUpdateTime(draft.issuedAt());
        one(commands.insertDelivery(d));require(d.getId()!=null && d.getId()>0,503,"SKILL_STATE_UNAVAILABLE");
        byte[] wire=AgentCommandCanonicalCodec.wireBytes(draft,message);
        var o=new AgentOutboxEventEntity().setEventId(event).setMessageId(message).setCommandId(draft.commandId()).setDeliveryId(d.getId())
                .setAggregateType("skill-order").setAggregateId(draft.taskId()).setDestination(DESTINATION).setRoutingKey(draft.targetAgentId())
                .setWirePayload(wire).setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire)).setStatus("PENDING")
                .setAttemptCount(0).setActiveAttempt(1).setExpiresAt(draft.expiresAt()).setPublisherConfirmStatus("NONE")
                .setMandatoryReturnStatus("NONE").setLastError("SKILL_WS_PENDING").setVersion(0L);
        o.setTenantId(draft.tenantId());o.setClientId(draft.clientId());o.setCreateTime(draft.issuedAt());o.setUpdateTime(draft.issuedAt());
        one(commands.insertOutbox(o));return new AgentCommandTransportWriteResult(d.getId(),draft.commandId(),message,event,false);
    }
}
