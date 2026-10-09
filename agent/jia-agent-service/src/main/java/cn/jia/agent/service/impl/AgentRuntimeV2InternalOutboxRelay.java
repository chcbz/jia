package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentRuntimeV2InternalOutboxDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeV2CompositeAuthority;
import cn.jia.agent.service.AgentRuntimeV2SocketGateway;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.time.Clock;
import java.util.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row;
import static cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.check;
import static cn.jia.agent.service.impl.AgentRuntimeV2DispatchServiceImpl.outside;

/** Shared-DB owner-targeted at-least-once relay. Transport never retargets a dispatch. */
public final class AgentRuntimeV2InternalOutboxRelay {
    private final AgentRuntimeV2InternalOutboxDao dao;
    private final AgentRuntimeV2CompositeAuthority authority;
    private final AgentRuntimeV2DispatchSource sources;
    private final AgentRuntimeV2SocketGateway gateway;
    private final Clock clock;
    private final TransactionTemplate tx;
    private record Claim(AgentRuntimeV2InternalOutboxRow row,long deadline) {}
    public AgentRuntimeV2InternalOutboxRelay(AgentRuntimeV2InternalOutboxDao dao,AgentRuntimeV2CompositeAuthority authority,AgentRuntimeV2SocketGateway gateway,PlatformTransactionManager manager,Clock clock){this(dao,authority,gateway,manager,clock,new AgentRuntimeV2DispatchSource(dao));}
    public AgentRuntimeV2InternalOutboxRelay(AgentRuntimeV2InternalOutboxDao dao,AgentRuntimeV2CompositeAuthority authority,AgentRuntimeV2SocketGateway gateway,PlatformTransactionManager manager,Clock clock,AgentRuntimeV2DispatchSource sources){this.dao=dao;this.authority=authority;this.gateway=gateway;this.clock=clock;this.sources=Objects.requireNonNull(sources);tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(7);}
    public int poll(int limit){outside();int visited=0;for(String id:dao.due(gateway.ownerInstanceId(),clock.millis(),limit)){deliver(id);visited++;}for(String id:dao.expired(clock.millis(),limit)){expire(id);visited++;}for(String id:dao.stale(clock.millis(),limit)){maintain(id);visited++;}outside();return visited;}
    public void deliver(String id){
        outside();var hint=dao.hint(id);if(hint==null||!gateway.ownerInstanceId().equals(hint.text("socket_owner_instance_id")))return;
        Claim claim=tx.execute(status->{
            var current=authority.lock(AgentRuntimeV2DispatchSource.scope(hint),hint.text("runtime_binding_id"),null);authority.original(hint);sources.lock(hint);var r=dao.lock(id);if(r==null)return null;check(hint.sameImmutableAs(r));if(terminal(r))return null;
            long now=clock.millis();
            if(now>=r.number("expires_at")){finish(r,"EXPIRED","MESSAGE_EXPIRED",now);return null;}
            if(!current.admitted()||!"ACTIVE".equals(current.binding().get("status"))){finish(r,"FENCED","AUTHORITY_REVOKED",now);return null;}
            if(!current.tuple().equals(r.tuple())||now>=cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.number(current.binding(),"socket_owner_lease_until")){finish(r,"FENCED","TARGET_FENCED",now);return null;}
            current.fresh(now);
            if("CLAIMED".equals(r.text("status"))?now<r.number("claim_until"):now<r.number("next_attempt_at"))return null;
            if(!gateway.current(r.tuple()))return null; // no claim, no fabricated attempt/outcome
            long until=Math.min(Math.addExact(now,10000),Math.min(r.number("expires_at"),cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.number(current.binding(),"socket_owner_lease_until")));
            if(current.credentialExpiresAt()!=null)until=Math.min(until,current.credentialExpiresAt());
            if(until<=now)return null;
            var changes=row("status","CLAIMED","attempt_count",Math.addExact(Math.toIntExact(r.number("attempt_count")),1),"next_attempt_at",null,"claim_id",UUID.randomUUID().toString(),"claim_until",until,"last_attempt_at",now,"last_error_code",null,"update_time",now);
            if(!dao.transition(r,changes))return null;
            var v=r.values();v.putAll(changes);v.put("version",Math.addExact(r.number("version"),1));var claimed=new AgentRuntimeV2InternalOutboxRow(v);
            long deadline=Math.min(Math.addExact(now,5000),until);long finalUntil=until;
            Runnable fresh=()->{long sample=clock.millis();current.fresh(sample);check(sample<finalUntil&&sample<r.number("expires_at"));};fresh.run();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){fresh.run();}});
            return new Claim(claimed,deadline);
        });
        outside();if(claim==null)return;
        boolean sent=false;try{if(clock.millis()<claim.deadline())sent=gateway.send(claim.row().tuple(),claim.row().binary("dispatch_wire_bytes"),claim.deadline());}catch(RuntimeException failure){sent=false;}
        outside();outcome(claim,sent);outside();
    }
    private void outcome(Claim claim,boolean sent){
        tx.executeWithoutResult(status->{var r=dao.lock(claim.row().text("internal_outbox_id"));if(r==null||!"CLAIMED".equals(r.text("status"))||r.number("version")!=claim.row().number("version")||!Objects.equals(r.get("claim_id"),claim.row().get("claim_id"))||!r.sameImmutableAs(claim.row()))return;
            long now=clock.millis();if(now>=r.number("expires_at")){finish(r,"EXPIRED","MESSAGE_EXPIRED",now);return;}
            // Expired claim holders have no outcome authority, even when their network call succeeded.
            if(now>=r.number("claim_until"))return;
            if(sent){dao.transition(r,row("status","SENT","claim_id",null,"claim_until",null,"sent_at",now,"finished_at",now,"update_time",now));}
            else {long next=Math.min(Math.addExact(now,5000),r.number("expires_at"));dao.transition(r,row("status","RETRY","claim_id",null,"claim_until",null,"next_attempt_at",next,"last_error_code","SEND_FAILED","update_time",now));}
        });
    }
    public void expire(String id){outside();var hint=dao.hint(id);if(hint==null||terminal(hint))return;
        tx.executeWithoutResult(status->{authority.maintenance(hint);sources.lock(hint);var r=dao.lock(id);long now=clock.millis();if(r!=null){check(hint.sameImmutableAs(r));if(!terminal(r)&&now>=r.number("expires_at"))finish(r,"EXPIRED","MESSAGE_EXPIRED",now);}});outside();}
    public void maintain(String id){outside();var hint=dao.hint(id);if(hint==null||terminal(hint))return;tx.executeWithoutResult(status->{var binding=authority.maintenance(hint);sources.lock(hint);var r=dao.lock(id);if(r==null)return;check(hint.sameImmutableAs(r));if(terminal(r))return;long now=clock.millis();if(now>=r.number("expires_at")){finish(r,"EXPIRED","MESSAGE_EXPIRED",now);return;}boolean same="ACTIVE".equals(binding.get("status"));for(String[] pair:new String[][]{{"binding_generation","dispatch_binding_generation"},{"runtime_instance_id","runtime_instance_id"},{"current_session_fence","session_fence"},{"socket_owner_instance_id","socket_owner_instance_id"},{"socket_owner_epoch","socket_owner_epoch"}})same&=cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.same(binding.get(pair[0]),r.get(pair[1]));if(!same||now>=cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.number(binding,"socket_owner_lease_until"))finish(r,"FENCED","TARGET_FENCED",now);});outside();}
    private void finish(AgentRuntimeV2InternalOutboxRow r,String status,String reason,long now){check(!status.equals("EXPIRED")||now>=r.number("expires_at"));dao.transition(r,row("status",status,"claim_id",null,"claim_until",null,"next_attempt_at",null,"sent_at",null,"finished_at",now,"last_error_code",reason,"update_time",now));}
    private static boolean terminal(AgentRuntimeV2InternalOutboxRow r){return Set.of("SENT","FENCED","EXPIRED").contains(r.text("status"));}
}
