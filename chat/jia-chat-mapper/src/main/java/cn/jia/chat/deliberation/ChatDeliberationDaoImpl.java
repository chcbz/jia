package cn.jia.chat.deliberation;

import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import java.util.List;

@Named
@RequiredArgsConstructor
public class ChatDeliberationDaoImpl implements ChatDeliberationDao {
    private final ChatDeliberationMapper mapper;
    public int insertSnapshot(ChatContextSnapshotEntity e) { return mapper.insertSnapshot(e); }
    public int insertRequest(ChatRequestEntity e) { return mapper.insertRequest(e); }
    public int insertTurn(ChatTurnEntity e) { return mapper.insertTurn(e); }
    public int insertOutbox(ChatDispatchOutboxEntity e) { return mapper.insertOutbox(e); }
    public int insertEvent(ChatConversationEventEntity e) { return mapper.insertEvent(e); }
    public int assignEventVersion(long s) { return mapper.assignEventVersion(s); }
    public ChatRequestEntity lockRequest(String t,String o,String c,String r,long v){return mapper.lockRequest(t,o,c,r,v);}
    public ChatRequestEntity findRequest(String t,String o,String c,String r){return mapper.findRequest(t,o,c,r);}
    public List<ChatTurnEntity> findTurnsByRequest(String t,String o,String c,String r){return mapper.findTurnsByRequest(t,o,c,r);}
    public ChatTurnEntity findTurn(String t,String o,String c,String r){return mapper.findTurn(t,o,c,r);}
    public ChatTurnEntity lockTurn(String t,String o,String c,String r){return mapper.lockTurn(t,o,c,r);}
    public ChatContextSnapshotEntity findSnapshot(String t,String o,String c,String s){return mapper.findSnapshot(t,o,c,s);}
    public ChatDispatchOutboxEntity lockOutbox(String t,String o,String c,String turn,String event){return mapper.lockOutbox(t,o,c,turn,event);}
    public ChatDispatchOutboxEntity lockOutboxById(String t,String o,String c,String e){return mapper.lockOutboxById(t,o,c,e);}
    public ChatDispatchOutboxEntity lockOutboxByDispatch(String t,String o,String c,String d){return mapper.lockOutboxByDispatch(t,o,c,d);}
    public List<ChatDispatchOutboxEntity> findDueOutbox(long n,int l){return mapper.findDueOutbox(n,l);}
    public List<ChatConversationEventEntity> replayEvents(String t,String o,String c,String id,long g,long a,long through,int l){return mapper.replayEvents(t,o,c,id,g,a,through,l);}
    public long eventHighWatermark(String t,String o,String c,String id,long g){Long value=mapper.eventHighWatermark(t,o,c,id,g);return value==null?0L:value;}
    public int acceptDelta(ChatTurnEntity t,long s,String d,long n){return mapper.acceptDelta(t.getTenantId(),t.getOwnerJiacn(),t.getClientId(),t.getTurnId(),t.getStateVersion(),ChatDeliberationStates.STREAMING,s,d,n);}
    public int persistFinal(ChatTurnEntity t,String d,long m,long n){return mapper.persistFinal(t.getTenantId(),t.getOwnerJiacn(),t.getClientId(),t.getTurnId(),t.getStateVersion(),d,m,n);}
    public int updateTurnState(ChatTurnEntity t,String s,String r,long n){return mapper.updateTurnState(t.getTenantId(),t.getOwnerJiacn(),t.getClientId(),t.getTurnId(),t.getStateVersion(),s,r,n);}
    public int publishFinal(ChatTurnEntity t,long n){return mapper.publishFinal(t.getTenantId(),t.getOwnerJiacn(),t.getClientId(),t.getTurnId(),t.getStateVersion(),n);}
    public int updateOutbox(ChatDispatchOutboxEntity o,String s,long n){return mapper.updateOutbox(o.getTenantId(),o.getOwnerJiacn(),o.getClientId(),o.getTurnId(),o.getEventType(),o.getVersion(),s,n);}
    public int claimOutbox(ChatDispatchOutboxEntity o,String owner,long until,long fence,long now){return mapper.claimOutbox(o.getTenantId(),o.getOwnerJiacn(),o.getClientId(),o.getEventId(),o.getVersion(),owner,until,fence,now);}
    public int renewOutbox(ChatDispatchOutboxEntity o,long until,long now){return mapper.renewOutbox(o.getTenantId(),o.getOwnerJiacn(),o.getClientId(),o.getEventId(),o.getVersion(),o.getLeaseOwner(),o.getFencingToken(),until,now);}
    public int settleOutbox(ChatDispatchOutboxEntity o,String status,Long available,String error,Long sent,long now){return mapper.settleOutbox(o.getTenantId(),o.getOwnerJiacn(),o.getClientId(),o.getEventId(),o.getVersion(),o.getLeaseOwner(),o.getFencingToken(),status,available,error,sent,now);}
    public int acknowledgeOutbox(ChatDispatchOutboxEntity o,long now){return mapper.acknowledgeOutbox(o.getTenantId(),o.getOwnerJiacn(),o.getClientId(),o.getEventId(),o.getVersion(),now);}
    public int updateRequestState(ChatRequestEntity r,String s,long n){return mapper.updateRequestState(r.getTenantId(),r.getOwnerJiacn(),r.getClientId(),r.getId(),r.getStateVersion(),s,n);}
}
