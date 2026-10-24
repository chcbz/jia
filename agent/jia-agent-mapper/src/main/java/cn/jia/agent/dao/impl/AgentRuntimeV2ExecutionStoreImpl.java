package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentRuntimeV2ExecutionStore;
import cn.jia.agent.mapper.AgentRuntimeV2ExecutionMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

public final class AgentRuntimeV2ExecutionStoreImpl implements AgentRuntimeV2ExecutionStore {
    private final AgentRuntimeV2ExecutionMapper mapper;
    public AgentRuntimeV2ExecutionStoreImpl(AgentRuntimeV2ExecutionMapper mapper){this.mapper=Objects.requireNonNull(mapper);}
    public JdbcTemplate jdbc(){return mapper.jdbc();}
    public Map<String,Object> hint(String t,Map<String,Object> k){return mapper.select(t,k,false);}
    public Map<String,Object> lock(String t,Map<String,Object> k){return mapper.select(t,k,true);}
    public List<Map<String,Object>> rows(String t,String dispatch,boolean lock){return mapper.rows(t,dispatch,lock);}
    public void append(String t,Map<String,Object> v){mapper.append(t,v);}
    public void cas(String t,Map<String,Object> before,Map<String,Object> changes){mapper.cas(t,before,changes);}
}
