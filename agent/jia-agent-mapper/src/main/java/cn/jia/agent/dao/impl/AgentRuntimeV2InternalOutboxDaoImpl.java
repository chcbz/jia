package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentRuntimeV2InternalOutboxDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.AgentRuntimeV2InternalOutboxMapper;
import java.util.*;

public final class AgentRuntimeV2InternalOutboxDaoImpl implements AgentRuntimeV2InternalOutboxDao {
    private final AgentRuntimeV2InternalOutboxMapper mapper;
    public AgentRuntimeV2InternalOutboxDaoImpl(AgentRuntimeV2InternalOutboxMapper mapper){this.mapper=Objects.requireNonNull(mapper);}
    public Map<String,Object> delivery(long id){return mapper.delivery(id);}
    public Map<String,Object> source(long id){return mapper.source(id);}
    public List<Map<String,Object>> sources(long id){return mapper.sources(id);}
    public Map<String,Object> inbox(long id){return mapper.inbox(id,true);}
    public Map<String,Object> inboxHint(long id){return mapper.inbox(id,false);}
    public AgentRuntimeV2InternalOutboxRow hint(String id){return mapper.internal("internal_outbox_id",id,false);}
    public AgentRuntimeV2InternalOutboxRow lock(String id){return mapper.internal("internal_outbox_id",id,true);}
    public AgentRuntimeV2InternalOutboxRow forInbox(long id){return mapper.internal("inbox_id",id,true);}
    public AgentRuntimeV2InternalOutboxRow forInboxHint(long id){return mapper.internal("inbox_id",id,false);}
    public List<String> due(String owner,long now,int limit){return mapper.due(owner,now,limit);}
    public List<String> expired(long now,int limit){return mapper.expired(now,limit);}
    public List<String> stale(long now,int limit){return mapper.stale(now,limit);}
    public void insert(AgentRuntimeV2InternalOutboxRow row){mapper.insert(row);}
    public boolean transition(AgentRuntimeV2InternalOutboxRow row,Map<String,Object> changes){return mapper.transition(row,changes);}
    public void handoff(AgentInboxClaimToken token,AgentRuntimeV2InternalOutboxRow row,long now){mapper.handoff(token,row,now);}
}
