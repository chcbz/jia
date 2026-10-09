package cn.jia.agent.dao.impl;
import cn.jia.agent.dao.AgentCommandInboxV2Dao;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.AgentCommandInboxV2Mapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import java.util.List;
@Named
@RequiredArgsConstructor
public class AgentCommandInboxV2DaoImpl implements AgentCommandInboxV2Dao {
    private final AgentCommandInboxV2Mapper mapper;
    public AgentCommandDeliveryEntity lockDelivery(AgentInboxMessage m){return mapper.lockDelivery(m);}
    public AgentOutboxEventEntity lockOutbox(AgentInboxMessage m){return mapper.lockOutbox(m);}
    public List<AgentOutboxEventEntity> lockPrevious(AgentInboxMessage m,int a){return mapper.lockPrevious(m,a);}
    public AgentConsumerInboxEntity lockInbox(AgentInboxMessage m){return mapper.lockInbox(m);}
    public int insert(AgentConsumerInboxEntity i){return mapper.insert(i);}
    public int transitionDelivery(AgentInboxMessage m,int a,long v,String x,String s,Long r,String e,long n){return mapper.transitionDelivery(m,a,v,x,s,r,e,n);}
    public int reclaimProcessingInbox(AgentInboxMessage m,AgentConsumerInboxEntity i,String o,long u,long n){return mapper.reclaimProcessingInbox(m,i,o,u,n);}
    public int takeoverProcessingInbox(AgentInboxMessage m,AgentConsumerInboxEntity i,String o,long u,long v,long n){return mapper.takeoverProcessingInbox(m,i,o,u,v,n);}
    public int reclaimRetryInbox(AgentInboxMessage m,AgentConsumerInboxEntity i,String o,long u,long v,long n){return mapper.reclaimRetryInbox(m,i,o,u,v,n);}
    public int takeoverRetryInbox(AgentInboxMessage m,AgentConsumerInboxEntity i,String o,long u,long v,long n){return mapper.takeoverRetryInbox(m,i,o,u,v,n);}
    public int expireProcessingInbox(AgentInboxMessage m,AgentConsumerInboxEntity i,String e,long n){return mapper.expireProcessingInbox(m,i,e,n);}
    public int expireRetryInbox(AgentInboxMessage m,AgentConsumerInboxEntity i,String e,long n){return mapper.expireRetryInbox(m,i,e,n);}
    public int settleInbox(AgentInboxClaimToken t,String s,String r,Long x,String e,long n){return mapper.settleInbox(t,s,r,x,e,n);}
    public int settleDeliveryFromClaim(AgentInboxClaimToken t,AgentConsumerInboxEntity i,String x,String s,Long r,String e,long n){return mapper.settleDeliveryFromClaim(t,i,x,s,r,e,n);}
    public int advanceAcknowledgement(AgentInboxClaimToken t,AgentConsumerInboxEntity i,long v,String x,String s,String e,long n){return mapper.advanceAcknowledgement(t,i,v,x,s,e,n);}
    public int advanceSettledAcknowledgement(AgentInboxClaimToken t,AgentConsumerInboxEntity i,long v,String x,String s,String e,long n){return mapper.advanceSettledAcknowledgement(t,i,v,x,s,e,n);}
    public int recordAcknowledgement(AgentInboxClaimToken t,AgentConsumerInboxEntity i,String m,String s,long a,String g,String r,String u,long v,long n){return mapper.recordAcknowledgement(t,i,m,s,a,g,r,u,v,n);}
    public int recordSettledAcknowledgement(AgentInboxClaimToken t,AgentConsumerInboxEntity i,String m,String s,long a,String g,String r,String u,long v,long n){return mapper.recordSettledAcknowledgement(t,i,m,s,a,g,r,u,v,n);}
    public int settleInboxAfterAcknowledgement(AgentInboxClaimToken t,AgentConsumerInboxEntity i,long n){return mapper.settleInboxAfterAcknowledgement(t,i,n);}
}
