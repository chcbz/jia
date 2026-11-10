package cn.jia.agent.skill;
import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.agent.mapper.AgentSkillInstallRecoveryMapper;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import cn.jia.task.scheduling.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;
import static cn.jia.agent.skill.SkillMarketplaceService.one;

/** Unified durable callbacks drain ORIGINAL committed outbox bytes over WS.
 * No broker ACK is fabricated. Same-message replay is safe only through the real Runtime
 * installation idempotence. Original live orders remain recoverable without an artificial task-invitation deadline. */
@Service
public final class SkillInstallRecoveryService implements ScheduledJobHandler {
    public static final String HANDLER = "skill.native-delivery";
    private final ObjectProvider<ScheduleService> schedules;
    private final AgentSkillInstallRecoveryMapper mapper;
    private final AgentCommandRecoveryDao dao;
    private final SkillInstallDispatchService dispatch;
    private final TransactionTemplate tx;
    private final FixedTenantAdmissionPolicy processScope;
    public SkillInstallRecoveryService(AgentSkillInstallRecoveryMapper mapper,AgentCommandRecoveryDao dao,
            SkillInstallDispatchService dispatch,PlatformTransactionManager manager,
            FixedTenantAdmissionPolicy processScope, ObjectProvider<ScheduleService> schedules) {
        this.mapper=mapper;this.dao=dao;this.dispatch=dispatch;tx=new TransactionTemplate(manager);
        this.processScope=processScope;this.schedules=schedules;
    }
    @Override public String getName() { return HANDLER; }
    @EventListener(ApplicationReadyEvent.class)
    public void schedulePending() {
        long cursor=0;
        while(true) {
            var page=mapper.pending(processScope.businessTenantId(),processScope.clientId(),cursor);
            for(var row:page) schedules.getObject().signal(key(row),nextAttempt(row));
            if(page.size()<100)return;
            cursor=page.getLast().getId();
        }
    }
    @Override public void executeScheduled(ScheduledInvocation invocation) {
        processScope.requireAdmitted(invocation.tenantId(),invocation.key().clientId());
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Skill transport I/O must be outside the scheduler transaction");
        long id=Long.parseLong(invocation.key().businessKey());
        var row=mapper.findPending(invocation.tenantId(),invocation.key().clientId(),id);
        if(row==null)return; // terminal/replaced command; no recurring empty scan
        long now=System.currentTimeMillis();
        long deferredUntil=nextAttempt(row)<=now?recover(row,now):0;
        row=mapper.findPending(invocation.tenantId(),invocation.key().clientId(),id);
        if(row!=null)schedules.getObject().reschedule(key(row),Math.max(nextAttempt(row),deferredUntil));
    }
    static ScheduleKey key(AgentCommandDeliveryEntity row) {
        return new ScheduleKey(row.getClientId(),HANDLER,Long.toString(row.getId()),"");
    }
    static long nextAttempt(AgentCommandDeliveryEntity row) {
        long due="PENDING".equals(row.getStatus())?0:Math.addExact(row.getUpdateTime(),60000L);
        if(row.getNextRetryAt()!=null)due=Math.max(due,row.getNextRetryAt());
        if(row.getLeaseUntil()!=null)due=Math.max(due,row.getLeaseUntil());
        return due;
    }
    long recover(AgentCommandDeliveryEntity hint,long now) {
        if(!processScope.businessTenantId().equals(hint.getTenantId())
                || processScope.clientId()!=null&&!processScope.clientId().equals(hint.getClientId())) return 0;
        String owner="skill-ws-"+UUID.randomUUID();
        Claim claim=tx.execute(s->{
            var d=dao.lockDelivery(hint.getTenantId(),hint.getClientId(),hint.getId());
            require(d!=null && Objects.equals(hint.getTenantId(),d.getTenantId()) && Objects.equals(hint.getClientId(),d.getClientId())
                    && hint.getOwnerJiacn()!=null && hint.getOwnerJiacn().equals(d.getOwnerJiacn())
                    && hint.getCommandId().equals(d.getCommandId()) && hint.getTargetAgentId().equals(d.getTargetAgentId())
                    && hint.getTaskId().equals(d.getTaskId()) && Set.of("SKILL_INSTALL","SKILL_SET_ENABLED","SKILL_RECONCILE").contains(d.getCommandType())
                    && d.getExpiresAt()!=null,409,"SKILL_DELIVERY_FENCED");
            if(d.getLeaseUntil()!=null && d.getLeaseUntil()>now) return Claim.deferred(d.getLeaseUntil());
            if(d.getNextRetryAt()!=null && d.getNextRetryAt()>now) return Claim.deferred(d.getNextRetryAt());
            require(Set.of("PENDING","SENT","WAITING_AGENT","RETRY","CONSUMED").contains(d.getStatus()),409,"SKILL_DELIVERY_FENCED");
            if(!"PENDING".equals(d.getStatus()) && d.getUpdateTime()>now-60000) return Claim.deferred(d.getUpdateTime()+60000);
            var rows=dao.lockActiveOutboxes(d.getTenantId(),d.getClientId(),d.getId(),d.getActiveMessageId());
            require(rows!=null && rows.size()==1,409,"SKILL_DELIVERY_FENCED");var o=rows.getFirst();
            validate(d,o);
            if(o.getLeaseUntil()!=null && o.getLeaseUntil()>now) return Claim.deferred(o.getLeaseUntil());
            require(Set.of("PENDING","SENT","RETRY","CLAIMED").contains(o.getStatus()),409,"SKILL_DELIVERY_FENCED");
            long until=now+60000;
            one(mapper.claimDelivery(d,owner,until,now));one(mapper.claimOutbox(o,owner,until,now));
            return new Claim(d,o.getId(),owner,o.getWirePayload().clone(),0);
        });
        if(claim.delivery()==null) return claim.retryAt();
        AgentRawCommandDispatchResult result;
        var d=claim.delivery();
        try { result=dispatch.dispatch(d.getTenantId(),d.getClientId(),d.getOwnerJiacn(),d.getTaskId(),d.getTargetAgentId(),d.getCommandId(),claim.wire()); }
        catch(RuntimeException unknown) { result=AgentRawCommandDispatchResult.sendFailed(1); }
        boolean sent=result.status()==AgentRawCommandDispatchResult.Status.SENT;
        long finish=System.currentTimeMillis();
        tx.execute(s->{
            var current=dao.lockDelivery(d.getTenantId(),d.getClientId(),d.getId());
            if(current==null || !claim.owner().equals(current.getLeaseOwner())) return null; // superseded lease owns completion
            var rows=dao.lockActiveOutboxes(d.getTenantId(),d.getClientId(),d.getId(),d.getActiveMessageId());
            require(rows!=null && rows.size()==1,409,"SKILL_DELIVERY_FENCED");var o=rows.getFirst();validate(current,o);
            require(claim.outboxId().equals(o.getId()) && claim.owner().equals(o.getLeaseOwner()),409,"SKILL_DELIVERY_FENCED");
            String status=sent?"SENT":"RETRY";
            Long retry=finish+(sent?60000:30000);
            String error=sent?"SKILL_WS_SENT":"SKILL_WS_SEND_UNCERTAIN";
            one(mapper.finishDelivery(current,claim.owner(),status,retry,error,finish));
            one(mapper.finishOutbox(o,claim.owner(),status,retry,error,finish));return null;
        });
        return 0;
    }
    static void validate(AgentCommandDeliveryEntity d,AgentOutboxEventEntity o) {
        require(SkillInstallCommandWriter.DESTINATION.equals(o.getDestination())
                && d.getTenantId().equals(o.getTenantId()) && d.getClientId().equals(o.getClientId())
                && "skill-change".equals(o.getAggregateType()) && d.getTaskId().equals(o.getAggregateId())
                && d.getTargetAgentId().equals(o.getRoutingKey()) && d.getId().equals(o.getDeliveryId())
                && d.getCommandId().equals(o.getCommandId()) && d.getActiveMessageId().equals(o.getMessageId())
                && d.getActiveAttempt().equals(o.getActiveAttempt()) && d.getExpiresAt().equals(o.getExpiresAt())
                && "NONE".equals(o.getPublisherConfirmStatus()) && "NONE".equals(o.getMandatoryReturnStatus())
                && o.getConfirmedAt()==null && o.getPublishedAt()==null
                && Arrays.equals(AgentCommandCanonicalCodec.sha256(o.getWirePayload()),o.getWirePayloadHash())
                && Arrays.equals(AgentCommandCanonicalCodec.sha256(d.getCommandPayload()),d.getCommandPayloadHash()),409,"SKILL_DELIVERY_FENCED");
        var data=SkillManagementCodec.read(d.getCommandPayload(),"CommandData");
        var wire=SkillManagementCodec.read(o.getWirePayload(),"Command");
        require(d.getCommandType().equals(data.get("commandType")) && d.getTenantId().equals(data.get("tenantId")) && d.getClientId().equals(data.get("clientId"))
                && d.getOwnerJiacn().equals(data.get("ownerJiacn")) && d.getCommandId().equals(data.get("commandId"))
                && d.getTaskId().equals(data.get("skillChangeId")) && d.getTargetAgentId().equals(data.get("agentId"))
                && d.getActiveMessageId().equals(wire.get("messageId")) && d.getActiveAttempt().equals(data.get("attempt"))
                && SkillManagementCodec.json(data).equals(SkillManagementCodec.json(wire.get("data"))),409,"SKILL_DELIVERY_FENCED");
    }
    private record Claim(AgentCommandDeliveryEntity delivery,Long outboxId,String owner,byte[] wire,long retryAt) {
        static Claim deferred(long at) { return new Claim(null,null,null,null,at); }
    }
}
